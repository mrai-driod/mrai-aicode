package com.aicode.feature.workspace.domain

import android.content.Context
import android.util.Base64
import com.aicode.core.util.FileLogger
import com.aicode.core.util.boundedLines
import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.agent.domain.device.DeviceRootEngine
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.StringReader
import java.nio.charset.Charset
import java.nio.file.FileAlreadyExistsException
import java.nio.file.NoSuchFileException
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Device ROOT implementation of [FileAccessProvider]: all file operations are executed on the device as root via `su -c`.
 *
 * Does not read directly via java.io.File, to avoid uid mismatch -- files created by root shell commands belong to root and the app uid may not be able to read them;
 * once everything goes through the root shell, the file tools and `execute_command` see and operate on exactly the same files.
 *
 * Path mapping (aligned with the container layout of [WorkspacePathMapper]):
 * - `~/workspace[/...]` (including the expanded `$HOME/workspace` alias) -> the current workspace's real device absolute path;
 * - `~/.aicode[/...]` (including the `/root/.aicode` form) -> the global config directory [ContainerInstaller.aicodeDir];
 * - Other absolute paths (`/etc/...`, `/data/...`) -> used as-is as device paths (the point of the device channel);
 * - Relative paths -> mounted under the current workspace root.
 *
 * Implementation notes:
 * - One `su` fork per operation (seamless once Magisk/KernelSU remembers the grant); content transfer uses base64 --
 *   reads return via stdout, writes are fed via a stdin pipe to `base64 -d` (not constrained by the argv length limit);
 * - Script exit-code convention: 0 success / 2 does not exist ([NoSuchFileException]) / 3 already exists ([FileAlreadyExistsException]) /
 *   4 exceeds the read limit ([RemoteOutputTooLargeException]) / 5 post-write length check failed / other [IOException];
 * - Known limitation: the old implementation split directory-listing output by line / by tab, so file names containing newlines or tabs parsed incorrectly;
 *   this is now `find -printf` + NUL separation (see [DeviceRootListing]) and the limitation no longer applies;
 *   read/write limits [MAX_READ_BYTES]/[MAX_WRITE_BYTES] (content must pass through memory as a whole).
 */
@Singleton
class DeviceRootFileAccess @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val workspaceRepository: WorkspaceRepository,
    private val pathHomeResolver: PathHomeResolver,
    private val containerInstaller: ContainerInstaller
) : FileAccessProvider {

    private companion object {
        const val TAG = "DeviceRootFileAccess"

        /** Per-file read limit (bytes); throws [RemoteOutputTooLargeException] when exceeded. */
        const val MAX_READ_BYTES = 8L * 1024 * 1024

        /** Per-write limit (bytes): the content must pass through stdin as a whole after base64 encoding. */
        const val MAX_WRITE_BYTES = 8L * 1024 * 1024

        /** Per-shell-output accumulation limit (bytes): the base64 of an 8MB file is about 11.2MB, leaving headroom up to 12MB. */
        const val MAX_OUTPUT_BYTES = 12 * 1024 * 1024

        /**
         * Per-batch budget for batch file-head reads (bytes): heads are only a few thousand bytes, so one su round trip can fetch the heads of hundreds of skill files;
         * constrained by [MAX_OUTPUT_BYTES] (base64 inflates size by about 1/3, so take half to leave headroom).
         */
        const val HEAD_READ_BUDGET_BYTES = 4L * 1024 * 1024

        /** Timeout for regular operations (milliseconds). */
        const val DEFAULT_TIMEOUT_MS = 60_000L

        /** Directory-listing timeout (milliseconds): per-item stat on large directories is slower, so this is relaxed. */
        const val LIST_TIMEOUT_MS = 120_000L

        /** Files per batch read: chosen conservatively small against the 8MB per-file limit, so a single batch's output does not hit [MAX_OUTPUT_BYTES]. */
        const val BATCH_READ_CHUNK = 32

        val STDIN_FROM_DEV_NULL: ProcessBuilder.Redirect =
            ProcessBuilder.Redirect.from(File("/dev/null"))

        fun q(s: String): String = DeviceRootEngine.shellQuote(s)
    }

    // ---------- Path mapping ----------

    /** Resolve a path provided by AI to a real device absolute path. */
    private fun resolve(path: String): String {
        val ws = workspaceRepository.currentPath().trim().trimEnd('/')
        val home = pathHomeResolver.home().trimEnd('/')
        val wsAlias = "$home/workspace"
        val aicodeAlias = "$home/.aicode"
        val aicodeReal = containerInstaller.aicodeDir.absolutePath
        val p0 = path.trim()
        val p = pathHomeResolver.expandHome(p0)
        return when {
            p0 == "~/workspace" || p0 == "~/workspace/" || p == wsAlias || p == ws -> ws
            p0.startsWith("~/workspace/") -> joinPath(ws, p0.removePrefix("~/workspace/"))
            p.startsWith("$wsAlias/") -> joinPath(ws, p.removePrefix("$wsAlias/"))
            p0 == "~/.aicode" || p == aicodeAlias ||
                p == WorkspacePathMapper.AICODE_ROOT -> aicodeReal
            p0.startsWith("~/.aicode/") -> File(aicodeReal, p0.removePrefix("~/.aicode/")).path
            p.startsWith("$aicodeAlias/") -> File(aicodeReal, p.removePrefix("$aicodeAlias/")).path
            p.startsWith("${WorkspacePathMapper.AICODE_ROOT}/") ->
                File(aicodeReal, p.removePrefix("${WorkspacePathMapper.AICODE_ROOT}/")).path
            p.startsWith("/") -> p
            ws.isNotEmpty() -> File(ws, p).path
            else -> p
        }
    }

    private fun joinPath(root: String, suffix: String): String =
        if (root.isEmpty()) "/$suffix" else "$root/$suffix"

    private fun parentDir(real: String): String {
        val idx = real.trimEnd('/').lastIndexOf('/')
        return if (idx <= 0) "/" else real.substring(0, idx)
    }

    // ---------- Shell execution ----------

    private data class ShResult(val output: String, val exitCode: Int, val truncated: Boolean)

    /**
     * Execute a script synchronously via `su -c` and capture all output. Order: write stdin (optional) -> read stdout to EOF -> waitFor,
     * one-way flow with no pipe deadlock; a watchdog timeout uses destroy/forcibly to unblock the read.
     */
    private fun sh(script: String, stdin: ByteArray? = null, timeoutMs: Long = DEFAULT_TIMEOUT_MS): ShResult {
        val pb = ProcessBuilder(listOf("su", "-c", script))
        pb.redirectErrorStream(true)
        if (stdin == null) pb.redirectInput(STDIN_FROM_DEV_NULL)
        val process = pb.start()
        val timedOut = AtomicBoolean(false)
        val watchdog = Thread({ runShWatchdog(process, timeoutMs, timedOut) }, WATCHDOG_THREAD_NAME)
        watchdog.isDaemon = true
        watchdog.start()
        try {
            if (stdin != null) {
                // The child process may exit early (e.g. exit 3 when overwrite=false) -> a broken pipe is normal; ignore it
                try {
                    process.outputStream.use { it.write(stdin); it.flush() }
                } catch (e: IOException) {
                    FileLogger.d(TAG, "stdin write interrupted (child process already exited): ${e.message}")
                }
            }
            val output = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            var truncated = false
            while (true) {
                val n = try {
                    process.inputStream.read(buf)
                } catch (e: IOException) {
                    break // Watchdog destroy closes the pipe
                }
                if (n < 0) break
                if (output.size() < MAX_OUTPUT_BYTES) output.write(buf, 0, n) else truncated = true
            }
            val exit = try {
                process.waitFor()
            } catch (e: InterruptedException) {
                -1
            }
            if (timedOut.get()) throw IOException("root shell execution timed out (>${timeoutMs}ms)")
            return ShResult(output.toString(Charsets.UTF_8.name()), exit, truncated)
        } finally {
            watchdog.interrupt()
            runCatching { process.destroy() }
        }
    }

    /** Throw the corresponding exception per the script exit-code convention; return directly on 0. */
    private fun ShResult.throwForError(displayPath: String) {
        when (exitCode) {
            0 -> return
            2 -> throw NoSuchFileException(displayPath)
            3 -> throw FileAlreadyExistsException(displayPath)
            4 -> throw RemoteOutputTooLargeException(
                "File exceeds the device channel read limit (${MAX_READ_BYTES / 1024 / 1024}MB): $displayPath"
            )
            5 -> throw IOException("Write verification failed (written length mismatch): $displayPath")
            else -> throw IOException("root shell failed (exit=$exitCode): ${output.take(300)}")
        }
    }

    /** Single-condition test: exit 0 -> true. */
    private fun testFlag(real: String, flag: String): Boolean =
        sh("test $flag ${q(real)}").exitCode == 0

    // ---------- Read ----------

    override fun readFile(path: String): String = String(readBytes(path), Charsets.UTF_8)

    /**
     * Read line by line. Unlike the local/remote implementations: the device channel re-reads the whole file via `su` on each iteration (non-streaming),
     * constrained by the [MAX_READ_BYTES] limit; the sequence is still repeatedly iterable, and single-line truncation is guaranteed by [boundedLines].
     */
    override fun readLines(path: String): Sequence<String> =
        boundedLines { StringReader(readFile(path)) }

    override fun readBytes(path: String): ByteArray {
        val real = resolve(path)
        val r = sh(
            "sz=\$(stat -c %s ${q(real)} 2>/dev/null) || exit 2; " +
                "[ \"\$sz\" -gt $MAX_READ_BYTES ] && exit 4; " +
                "base64 < ${q(real)}"
        )
        r.throwForError(path)
        val clean = r.output.filter { !it.isWhitespace() }
        return runCatching { Base64.decode(clean, Base64.DEFAULT) }
            .getOrElse { throw IOException("base64 decode failed: $path (${it.message})") }
    }

    /**
     * Batch read: one `su -c` child process fetches the whole batch's contents (`path` lines and base64 lines alternating).
     *
     * Every [sh] call on the device channel forks an su process (tens to hundreds of milliseconds); calling [readFile] one by one on hundreds of
     * skill files degrades into hundreds of round trips -- that, not the files themselves being large, is the root cause of skill scans dragging the main thread into an ANR.
     * Batching is only to avoid the per-call output limit [MAX_OUTPUT_BYTES] (base64 inflates size by about 1/3).
     */
    override fun readFiles(paths: List<String>): Map<String, String> {
        if (paths.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>(paths.size)
        paths.chunked(BATCH_READ_CHUNK).forEach { chunk -> out.putAll(readFilesChunk(chunk)) }
        return out
    }

    private fun readFilesChunk(paths: List<String>): Map<String, String> {
        val realPaths = paths.map { resolve(it) }
        val r = sh(
            "while IFS= read -r p; do " +
                "printf '%s\\n' \"\$p\"; " +
                "sz=\$(stat -c %s \"\$p\" 2>/dev/null) || { printf '\\n'; continue; }; " +
                "[ \"\$sz\" -gt $MAX_READ_BYTES ] && { printf '\\n'; continue; }; " +
                "base64 < \"\$p\" 2>/dev/null | tr -d '\\n'; printf '\\n'; " +
                "done",
            stdin = realPaths.joinToString("\n").toByteArray(Charsets.UTF_8),
            timeoutMs = LIST_TIMEOUT_MS
        )
        return parsePathContentPairs(r.output, realPaths, paths)
    }

    /**
     * Batch file-head read: one `su -c` child process fetches the first [maxBytes] bytes of the whole batch (`path` lines and base64 lines alternating).
     *
     * Same protocol as [readFilesChunk], except only the head is taken via `head -c` -- when locating a skill by the name in its frontmatter,
     * the hundreds of SKILL.md files only need their heads to match, instead of pulling all body text through the shell pipe. Batches are split by cumulative head budget
     * ([HEAD_READ_BUDGET_BYTES]); small files are done in one batch, still a single su round trip.
     */
    override fun readFileHeads(paths: List<String>, maxBytes: Int): Map<String, String> {
        if (paths.isEmpty() || maxBytes <= 0) return emptyMap()
        val out = LinkedHashMap<String, String>(paths.size)
        val chunk = mutableListOf<String>()
        var budget = 0L
        for (p in paths) {
            chunk.add(p)
            budget += maxBytes
            if (budget >= HEAD_READ_BUDGET_BYTES) {
                out.putAll(readFileHeadsChunk(chunk, maxBytes))
                chunk.clear()
                budget = 0
            }
        }
        if (chunk.isNotEmpty()) out.putAll(readFileHeadsChunk(chunk, maxBytes))
        return out
    }

    private fun readFileHeadsChunk(paths: List<String>, maxBytes: Int): Map<String, String> {
        val realPaths = paths.map { resolve(it) }
        val r = sh(
            "while IFS= read -r p; do " +
                "printf '%s\\n' \"\$p\"; " +
                "head -c $maxBytes \"\$p\" 2>/dev/null | base64 2>/dev/null | tr -d '\\n'; printf '\\n'; " +
                "done",
            stdin = realPaths.joinToString("\n").toByteArray(Charsets.UTF_8),
            timeoutMs = LIST_TIMEOUT_MS
        )
        return parsePathContentPairs(r.output, realPaths, paths)
    }

    /**
     * Parse batch-read output: "real path line + base64 line" pairs, in the same order as the input;
     * entries that fail to read (empty line / corrupt base64 block / skipped for exceeding the limit) are absent.
     */
    private fun parsePathContentPairs(
        output: String,
        realPaths: List<String>,
        paths: List<String>
    ): Map<String, String> {
        val lines = output.lineSequence().toList()
        val byRealPath = LinkedHashMap<String, String>(lines.size / 2)
        var i = 0
        while (i + 1 < lines.size) {
            val key = lines[i]
            val encoded = lines[i + 1].filter { !it.isWhitespace() }
            i += 2
            if (encoded.isEmpty()) continue
            val bytes = runCatching { Base64.decode(encoded, Base64.DEFAULT) }.getOrNull() ?: continue
            byRealPath[key] = String(bytes, Charsets.UTF_8)
        }
        val out = LinkedHashMap<String, String>(paths.size)
        paths.forEachIndexed { index, path ->
            byRealPath[realPaths[index]]?.let { out[path] = it }
        }
        return out
    }

    override fun exists(path: String): Boolean = testFlag(resolve(path), "-e")

    override fun isDirectory(path: String): Boolean = testFlag(resolve(path), "-d")

    override fun isFile(path: String): Boolean = testFlag(resolve(path), "-f")

    override fun fileSize(path: String): Long =
        sh("stat -c %s ${q(resolve(path))} 2>/dev/null").output.trim().toLongOrNull() ?: 0L

    override fun lastModified(path: String): Long =
        (sh("stat -c %Y ${q(resolve(path))} 2>/dev/null").output.trim().toLongOrNull() ?: 0L) * 1000

    override fun permissions(path: String): String {
        val a = sh("stat -c %A ${q(resolve(path))} 2>/dev/null").output.trim()
        // %A looks like "-rw-r--r--": take the owner triplet; when unavailable, default to readable/writable/executable from root's perspective
        return if (a.length >= 4) a.substring(1, 4) else "rwx"
    }

    override fun listFiles(path: String): List<FileEntry> {
        val real = resolve(path)
        // Fast path: one find -printf fetches all attributes (single su fork, see DeviceRootListing).
        val r = sh(DeviceRootListing.findScript(real), timeoutMs = LIST_TIMEOUT_MS)
        if (r.exitCode == 0) return DeviceRootListing.parseFindOutput(r.output, real)
        // Fallback: when find -printf is unavailable (old toybox), fall back to the legacy per-item stat script; behaviour unchanged, just slower.
        FileLogger.d(TAG, "find -printf listing unavailable (exit=${r.exitCode}), falling back to legacy script: $path")
        val legacy = sh(DeviceRootListing.legacyScript(real), timeoutMs = LIST_TIMEOUT_MS)
        legacy.throwForError(path)
        return DeviceRootListing.parseLegacyOutput(legacy.output, real)
    }

    override fun listFilesRecursive(path: String, maxDepth: Int): List<String> {
        val real = resolve(path)
        val r = sh(
            "[ -d ${q(real)} ] || exit 0; " +
                "cd ${q(real)} && find . -maxdepth $maxDepth -type f 2>/dev/null",
            timeoutMs = LIST_TIMEOUT_MS
        )
        if (r.exitCode != 0) return emptyList()
        return r.output.lineSequence()
            .filter { it.isNotBlank() }
            .map { it.removePrefix("./") }
            .toList()
    }

    // ---------- Write ----------

    /** Write a file body: base64 is fed via a stdin pipe to `base64 -d`, then stat verifies the length after writing (aligned with Local's post-write check). */
    private fun writeBytesInternal(path: String, bytes: ByteArray, overwrite: Boolean) {
        if (bytes.size > MAX_WRITE_BYTES) {
            throw IOException("Write content exceeds the device channel limit (${MAX_WRITE_BYTES / 1024 / 1024}MB): $path")
        }
        val real = resolve(path)
        val script = buildString {
            if (!overwrite) append("[ -e ").append(q(real)).append(" ] && exit 3; ")
            append("mkdir -p ").append(q(parentDir(real))).append(" || exit 1; ")
            append("base64 -d > ").append(q(real)).append(" || exit 1; ")
            append("sz=\$(stat -c %s ").append(q(real)).append(" 2>/dev/null || echo -1); ")
            append("[ \"\$sz\" = \"").append(bytes.size).append("\" ] || exit 5")
        }
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        sh(script, stdin = b64.toByteArray(Charsets.US_ASCII)).throwForError(path)
        FileLogger.i(TAG, "Write (device): '$path' -> $real (${bytes.size} bytes)")
    }

    override fun writeFile(path: String, content: String, overwrite: Boolean, encoding: Charset) =
        writeBytesInternal(path, content.toByteArray(encoding), overwrite)

    override fun writeBytes(path: String, bytes: ByteArray, overwrite: Boolean) =
        writeBytesInternal(path, bytes, overwrite)

    override fun writeStream(path: String, input: InputStream, overwrite: Boolean): Long {
        // Like readBytes, this must pass through memory as a whole (base64 via stdin); the cap matches the write limit.
        val acc = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > MAX_WRITE_BYTES) {
                throw IOException("Write content exceeds the device channel limit (${MAX_WRITE_BYTES / 1024 / 1024}MB): $path")
            }
            acc.write(buf, 0, n)
        }
        writeBytesInternal(path, acc.toByteArray(), overwrite)
        return total
    }

    // ---------- Directory/file operations ----------

    override fun mkdirs(path: String) {
        // Aligned with Local (mkdirs return value ignored): failures are silent
        sh("mkdir -p ${q(resolve(path))} 2>/dev/null; exit 0")
    }

    override fun delete(path: String) {
        // Aligned with Local (file.delete() return value ignored): missing/failure is silent
        val real = resolve(path)
        sh(
            "if [ -d ${q(real)} ] && [ ! -L ${q(real)} ]; then " +
                "rmdir ${q(real)} 2>/dev/null; " +
                "else rm -f ${q(real)} 2>/dev/null; fi; exit 0"
        )
    }

    override fun deleteRecursively(path: String) {
        val real = resolve(path)
        val r = sh("[ -e ${q(real)} ] || [ -L ${q(real)} ] || exit 0; rm -rf ${q(real)}")
        if (r.exitCode != 0) throw IOException("delete failed: $real (${r.output.take(200)})")
    }

    override fun rename(path: String, newPath: String) {
        val src = resolve(path)
        val dst = resolve(newPath)
        val script = "[ -e ${q(src)} ] || [ -L ${q(src)} ] || exit 2; " +
            "if [ -e ${q(dst)} ] || [ -L ${q(dst)} ]; then exit 3; fi; " +
            "mkdir -p ${q(parentDir(dst))} || exit 1; " +
            "mv ${q(src)} ${q(dst)} || exit 1"
        sh(script).throwForError(path)
    }

    override fun copy(path: String, newPath: String, overwrite: Boolean) {
        val src = resolve(path)
        val dst = resolve(newPath)
        if (dst == src || dst.startsWith("$src/")) {
            throw IOException("destination is the source or its descendant: $newPath")
        }
        val script = buildString {
            append("[ -e ").append(q(src)).append(" ] || [ -L ").append(q(src)).append(" ] || exit 2; ")
            append("if [ -e ").append(q(dst)).append(" ] || [ -L ").append(q(dst)).append(" ]; then ")
            if (overwrite) append("rm -rf ").append(q(dst)).append(" || exit 1; ") else append("exit 3; ")
            append("fi; ")
            append("mkdir -p ").append(q(parentDir(dst))).append(" || exit 1; ")
            append("cp -r ").append(q(src)).append(" ").append(q(dst)).append(" || exit 1")
        }
        sh(script).throwForError(path)
    }

    override fun move(path: String, newPath: String, overwrite: Boolean) {
        val src = resolve(path)
        val dst = resolve(newPath)
        if (dst == src || dst.startsWith("$src/")) {
            throw IOException("destination is the source or its descendant: $newPath")
        }
        // mv automatically degrades to copy+delete across filesystems
        val script = buildString {
            append("[ -e ").append(q(src)).append(" ] || [ -L ").append(q(src)).append(" ] || exit 2; ")
            append("if [ -e ").append(q(dst)).append(" ] || [ -L ").append(q(dst)).append(" ]; then ")
            if (overwrite) append("rm -rf ").append(q(dst)).append(" || exit 1; ") else append("exit 3; ")
            append("fi; ")
            append("mkdir -p ").append(q(parentDir(dst))).append(" || exit 1; ")
            append("mv ").append(q(src)).append(" ").append(q(dst)).append(" || exit 1")
        }
        sh(script).throwForError(path)
    }

    // ---------- Local conversion ----------

    override fun copyToLocal(path: String): File {
        val real = resolve(path)
        // Fast path with no copy for paths the app can read directly, such as the workspace / sdcard (same semantics as Local);
        // paths readable only by root are read via su and written to the cache directory.
        val direct = File(real)
        if (direct.isFile && direct.canRead()) return direct
        val bytes = readBytes(path)
        val dir = File(context.cacheDir, "device_copy").apply { mkdirs() }
        val name = File(real).name.ifBlank { "file" }
        val tmp = File(dir, "${real.hashCode().toString(16)}-$name")
        tmp.writeBytes(bytes)
        return tmp
    }

    override fun parentPath(path: String): String? {
        val real = resolve(path).trimEnd('/')
        val idx = real.lastIndexOf('/')
        if (idx < 0) return null
        val parent = if (idx == 0) "/" else real.substring(0, idx)
        if (parent == real) return null
        return toDisplayPath(parent)
    }

    override fun toDisplayPath(path: String): String {
        val ws = workspaceRepository.currentPath().trim().trimEnd('/')
        val aicodeReal = containerInstaller.aicodeDir.absolutePath
        val p = path.trim().trimEnd('/').ifEmpty { "/" }
        return when {
            ws.isNotEmpty() && p == ws -> "~/workspace"
            ws.isNotEmpty() && p.startsWith("$ws/") -> "~/workspace/" + p.removePrefix("$ws/")
            p == aicodeReal -> "~/.aicode"
            p.startsWith("$aicodeReal/") -> "~/.aicode/" + p.removePrefix("$aicodeReal/")
            // On the device channel, all other paths are real paths and are displayed as-is
            else -> path
        }
    }
}

/** Grace period to wait for the process to exit on its own after the watchdog destroys it. */
private const val WATCHDOG_GRACE_MS = 200L

/** Watchdog thread name: readable in crash reports, no longer a string of Thread-N. */
private const val WATCHDOG_THREAD_NAME = "RootShWatchdog"

/**
 * `su -c` watchdog thread body (extracted from [DeviceRootFileAccess.sh] to a file-level function, so unit tests can reproduce the interrupt race).
 *
 * Timing: sleep for the full [timeoutMs] -> if the process is still alive, declare a timeout and `destroy` -> after the grace period [WATCHDOG_GRACE_MS], `destroyForcibly`.
 *
 * Interrupt safety (root cause of a v1.0.4 real-device crash): when the main flow finishes normally it `interrupt()`s this thread in its finally block,
 * and the interrupt may well land "after the timeout decision, before/during the grace sleep" -- **both sleeps must catch
 * InterruptedException**. The old code protected only the first; the second ran unprotected, and the exception escaping `run()` was turned by the default
 * uncaught-exception handler into a whole-app crash (reproducible in DEVICE_ROOT mode by opening the drawer and listing a directory).
 */
internal fun runShWatchdog(process: Process, timeoutMs: Long, timedOut: AtomicBoolean) {
    try {
        Thread.sleep(timeoutMs)
    } catch (_: InterruptedException) {
        return
    }
    if (!process.isAlive) return
    timedOut.set(true)
    runCatching { process.destroy() }
    runCatching { Thread.sleep(WATCHDOG_GRACE_MS) }
    if (process.isAlive) runCatching { process.destroyForcibly() }
}
