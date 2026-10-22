package com.aicode.feature.workspace.domain

import com.aicode.core.util.BoundedLineReader
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.RemoteSshConnection
import com.aicode.feature.agent.domain.container.friendlySshError
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.workspace.domain.WorkspacePathMapper.Companion.CONTAINER_ROOT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.RemoteFile
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.sftp.SFTPException
import net.schmizz.sshj.xfer.FilePermission
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.Charset
import java.nio.file.Paths
import java.nio.file.FileAlreadyExistsException
import java.nio.file.NoSuchFileException
import java.util.EnumSet
import javax.inject.Inject

private const val TAG = "RemoteSftpFileAccess"

/** Buffer size for a single SFTP read/write. */
private const val IO_CHUNK = 32 * 1024

/** Size limit (bytes) for remote files read via `readFile` / `readBytes`: exceeding it throws [RemoteOutputTooLargeException] to avoid loading the whole file into memory. */
private const val MAX_REMOTE_READ_BYTES = 8L * 1024 * 1024

/** Thrown when a remote file exceeds the limit: callers use this to show a "content too large" hint instead of misreporting the file as missing. */
class RemoteOutputTooLargeException(message: String) : IOException(message)

/**
 * Remote implementation of [FileAccessProvider]: reads and writes remote files over SFTP.
 *
 * **Separate channel**: SFTP runs on a second SSH transport established by [RemoteSshConnection.sftp], isolated from the Bash/command
 * exec channel — sshj SFTP has an intermittent buffer overflow (hierynomus/sshj#461); sharing a transport would let a crash
 * take down the command channel as well; separated, it only affects file I/O, and a failed channel is discarded and rebuilt on the next call.
 *
 * Path mapping: AI-provided `~/workspace/...` maps to the remote absolute path of the currently selected workspace; other absolute paths (e.g. `/etc/...`)
 * are used directly as remote absolute paths. sshj's [SFTPClient] is not thread-safe, so all operations are serialized via [sftpMutex].
 */
class RemoteSftpFileAccess @Inject constructor(
    private val connection: RemoteSshConnection,
    private val workspaceRepository: WorkspaceRepository
) : FileAccessProvider {

    private val sftpMutex = Mutex()

    /** The real path of the currently selected workspace on the remote server (e.g. /data/.../test/111). */
    private fun currentWorkspaceRoot(): String {
        val cfg = connection.config ?: throw IllegalStateException("SSH not connected")
        // currentPath() in remote mode returns the remote absolute path of the selected workspace; falls back to remoteWorkspacePath when none is selected
        val path = workspaceRepository.currentPath()
        return if (path.isNotBlank() && path != "/") path else cfg.remoteWorkspacePath.trimEnd('/')
    }

    /** Maps an AI path to the real path on the remote server. */
    private fun toRemotePath(path: String): String =
        remotePathFor(path, currentWorkspaceRoot(), connection.remoteHome)

    /** Converts a remote path back to the AI-facing container path (for display). */
    private fun toDisplayPathFromRemote(remotePath: String): String =
        displayPathFor(remotePath, currentWorkspaceRoot())

    /**
     * Runs [block] serially on the dedicated SFTP channel. On a transport error, discards the current channel (rebuilt automatically on the next call) and rethrows as-is;
     * business errors (file not found / already exists, SFTP status errors) do not trigger a rebuild. No automatic retry — retrying a write could duplicate data on disk.
     */
    private fun <T> withSftp(block: (SFTPClient) -> T): T = runBlocking {
        withContext(Dispatchers.IO) {
            sftpMutex.withLock {
                val sftp = try {
                    connection.sftp()
                } catch (e: Exception) {
                    throw IOException(friendlySshError(e), e)
                }
                guarded { block(sftp) }
            }
        }
    }

    /**
     * Runs one operation reusing the already-open SFTP channel (without fetching a new client). Used by [readLines] for lazy iteration: each lock covers
     * a single read, `yield` happens outside the lock, so abandoning iteration midway does not hold [sftpMutex] forever.
     */
    private fun <T> onSftp(block: () -> T): T = runBlocking {
        withContext(Dispatchers.IO) { sftpMutex.withLock { guarded(block) } }
    }

    /** On a transport error, discards the SFTP channel (rebuilt automatically on the next call) and rethrows as-is; business errors do not trigger a rebuild. */
    private suspend fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (e: Exception) {
        if (e !is SFTPException && e !is NoSuchFileException && e !is FileAlreadyExistsException) {
            runCatching { connection.invalidateSftp() }
        }
        throw e
    }

    override fun readFile(path: String): String = String(readAll(toRemotePath(path)), Charsets.UTF_8)

    /**
     * Lazy line-by-line reading: only the current line is materialized at a time (capped at 64K characters per line), read on demand, without loading the whole file into memory.
     * The sequence is re-iterable (each iteration reopens the remote file); callers must iterate on an IO thread.
     */
    override fun readLines(path: String): Sequence<String> {
        val remote = toRemotePath(path)
        return sequence {
            val reader = withSftp { sftp ->
                val attrs = sftp.statExistence(remote) ?: throw NoSuchFileException(File(remote))
                if (attrs.type == FileMode.Type.DIRECTORY) throw IOException("Is a directory, cannot read as a file: $remote")
                BoundedLineReader(InputStreamReader(RemoteFileInputStream(sftp.open(remote))))
            }
            try {
                while (true) {
                    val line = onSftp { reader.readLine() } ?: break
                    yield(line.text)
                }
            } finally {
                runCatching { onSftp { reader.close() } }
            }
        }
    }

    override fun writeFile(path: String, content: String, overwrite: Boolean, encoding: Charset) =
        writeBytes(path, content.toByteArray(encoding), overwrite)

    override fun exists(path: String): Boolean = withSftp { it.statExistence(toRemotePath(path)) != null }

    override fun isDirectory(path: String): Boolean =
        withSftp { it.statExistence(toRemotePath(path))?.type == FileMode.Type.DIRECTORY }

    override fun isFile(path: String): Boolean =
        withSftp { it.statExistence(toRemotePath(path))?.type == FileMode.Type.REGULAR }

    override fun fileSize(path: String): Long =
        withSftp { it.statExistence(toRemotePath(path))?.size ?: 0L }

    override fun lastModified(path: String): Long =
        withSftp { (it.statExistence(toRemotePath(path))?.mtime ?: 0L) * 1000L }

    override fun permissions(path: String): String =
        withSftp { it.statExistence(toRemotePath(path))?.permissions?.let(::formatPermissions) ?: "---" }

    override fun listFiles(path: String): List<FileEntry> {
        val remote = toRemotePath(path)
        return withSftp { sftp ->
            sftp.ls(remote).mapNotNull { info ->
                val name = info.name
                if (name == "." || name == "..") return@mapNotNull null
                FileEntry(
                    name = name,
                    isDirectory = info.isDirectory,
                    size = info.attributes.size,
                    lastModified = info.attributes.mtime * 1000L,
                    localFile = null,
                    permissions = formatPermissions(info.attributes.permissions)
                )
            }
        }
    }

    override fun listFilesRecursive(path: String, maxDepth: Int): List<String> {
        val remote = toRemotePath(path)
        return withSftp { sftp ->
            val result = mutableListOf<String>()
            fun walk(dir: String, rel: String, depth: Int) {
                val entries = runCatching { sftp.ls(dir) }.getOrNull() ?: return
                for (info in entries) {
                    val name = info.name
                    if (name == "." || name == "..") continue
                    val childRel = if (rel.isEmpty()) name else "$rel/$name"
                    if (info.isDirectory) {
                        if (depth < maxDepth) walk(info.path, childRel, depth + 1)
                    } else {
                        result += childRel
                    }
                }
            }
            walk(remote, "", 1)
            result
        }
    }

    override fun readBytes(path: String): ByteArray = readAll(toRemotePath(path))

    override fun writeBytes(path: String, bytes: ByteArray, overwrite: Boolean) {
        val remote = toRemotePath(path)
        withSftp { sftp ->
            if (sftp.statExistence(remote) != null && !overwrite) throw FileAlreadyExistsException(File(remote))
            ensureParent(sftp, remote)
            sftp.open(remote, EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)).use { rf ->
                writeAll(rf, bytes)
            }
        }
    }

    override fun writeStream(path: String, input: InputStream, overwrite: Boolean): Long {
        val remote = toRemotePath(path)
        // Write to .aicode-part first, then rename: a disconnect mid-transfer will not leave a partial file at the target
        val tmp = "$remote.aicode-part"
        return withSftp { sftp ->
            if (sftp.statExistence(remote) != null && !overwrite) throw FileAlreadyExistsException(File(remote))
            ensureParent(sftp, remote)
            val written = try {
                sftp.open(tmp, EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)).use { rf ->
                    val buf = ByteArray(IO_CHUNK)
                    var offset = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        if (n == 0) continue
                        rf.write(offset, buf, 0, n)
                        offset += n
                    }
                    offset
                }
            } catch (e: Exception) {
                runCatching { sftp.rm(tmp) }
                throw e
            }
            // If the target already exists, delete it before renaming (SFTP v3 has no reliable cross-platform overwrite-rename)
            if (sftp.statExistence(remote) != null) runCatching { sftp.rm(remote) }
            sftp.rename(tmp, remote)
            written
        }
    }

    override fun copyToLocal(path: String): File {
        val remote = toRemotePath(path)
        val tempFile = File.createTempFile("aicode_remote_", ".copy").apply { deleteOnExit() }
        return try {
            withSftp { sftp ->
                if (sftp.statExistence(remote) == null) throw NoSuchFileException(File(remote))
                sftp.get(remote, tempFile.absolutePath)
            }
            tempFile
        } catch (e: Exception) {
            // Delete after use: temp files do not rely on deleteOnExit (only cleaned up at process exit); the failure path also cleans up to avoid accumulation over long sessions.
            runCatching { tempFile.delete() }
            if (e is NoSuchFileException) throw e
            FileLogger.e(TAG, "copyToLocal failed: $remote", e)
            throw NoSuchFileException(File(remote))
        }
    }

    override fun delete(path: String) {
        val remote = toRemotePath(path)
        // Interface contract: delete only removes files or empty directories: rmdir fails on a non-empty directory → throws (aligned with local File.delete()).
        withSftp { sftp ->
            val attrs = sftp.statExistence(remote) ?: return@withSftp
            if (attrs.type == FileMode.Type.DIRECTORY) sftp.rmdir(remote) else sftp.rm(remote)
        }
    }

    override fun deleteRecursively(path: String) {
        val remote = toRemotePath(path)
        withSftp { sftp -> deleteRecursive(sftp, remote) }
    }

    override fun rename(path: String, newPath: String) {
        val from = toRemotePath(path)
        val to = toRemotePath(newPath)
        withSftp { sftp ->
            if (sftp.statExistence(from) == null) throw NoSuchFileException(File(from))
            if (sftp.statExistence(to) != null) throw FileAlreadyExistsException(File(to))
            sftp.rename(from, to)
        }
    }

    override fun copy(path: String, newPath: String, overwrite: Boolean) {
        val from = Paths.get(toRemotePath(path)).normalize().toString()
        val to = Paths.get(toRemotePath(newPath)).normalize().toString()
        withSftp { sftp ->
            val source = sftp.statExistence(from) ?: throw NoSuchFileException(File(from))
            if (to == from || (source.type == FileMode.Type.DIRECTORY && Paths.get(to).startsWith(Paths.get(from)))) {
                throw IOException("destination is the source or its descendant: $newPath")
            }
            if (sftp.statExistence(to) != null) {
                if (!overwrite) throw FileAlreadyExistsException(File(to))
                deleteRecursive(sftp, to)
            }
            copyRecursive(sftp, from, to)
        }
    }

    override fun move(path: String, newPath: String, overwrite: Boolean) {
        val from = Paths.get(toRemotePath(path)).normalize().toString()
        val to = Paths.get(toRemotePath(newPath)).normalize().toString()
        withSftp { sftp ->
            val source = sftp.statExistence(from) ?: throw NoSuchFileException(File(from))
            if (to == from || (source.type == FileMode.Type.DIRECTORY && Paths.get(to).startsWith(Paths.get(from)))) {
                throw IOException("destination is the source or its descendant: $newPath")
            }
            if (sftp.statExistence(to) != null) {
                if (!overwrite) throw FileAlreadyExistsException(File(to))
                deleteRecursive(sftp, to)
            }
            sftp.rename(from, to)
        }
    }

    override fun mkdirs(path: String) {
        val remote = toRemotePath(path)
        withSftp { it.mkdirs(remote) }
    }

    override fun parentPath(path: String): String? {
        val remote = toRemotePath(path)
        val parent = remote.substringBeforeLast('/', "")
        if (parent.isEmpty()) return null
        return toDisplayPathFromRemote(parent)
    }

    override fun toDisplayPath(path: String): String = toDisplayPathFromRemote(toRemotePath(path))

    /** Reads all bytes of a remote file; throws [NoSuchFileException] if missing, [RemoteOutputTooLargeException] if it exceeds [MAX_REMOTE_READ_BYTES]. */
    private fun readAll(remote: String): ByteArray = withSftp { sftp ->
        val attrs = sftp.statExistence(remote) ?: throw NoSuchFileException(File(remote))
        if (attrs.type == FileMode.Type.DIRECTORY) throw IOException("Is a directory, cannot read as a file: $remote")
        if (attrs.size > MAX_REMOTE_READ_BYTES) {
            throw RemoteOutputTooLargeException(
                "Remote file exceeds ${MAX_REMOTE_READ_BYTES / 1024 / 1024}MB, please read it in sections using start_line/end_line"
            )
        }
        sftp.open(remote).use { rf -> readFully(rf) }
    }

    private fun readFully(rf: RemoteFile): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(IO_CHUNK)
        var offset = 0L
        while (true) {
            val n = rf.read(offset, buf, 0, buf.size)
            if (n <= 0) break
            out.write(buf, 0, n)
            offset += n
        }
        return out.toByteArray()
    }

    private fun writeAll(rf: RemoteFile, bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            val n = minOf(IO_CHUNK, bytes.size - offset)
            rf.write(offset.toLong(), bytes, offset, n)
            offset += n
        }
    }

    /** Recursive delete (SFTP has no recursive-delete primitive): post-order traversal, rmdir a directory after its children are deleted. */
    private fun deleteRecursive(sftp: SFTPClient, remote: String) {
        val attrs = sftp.statExistence(remote) ?: return
        if (attrs.type == FileMode.Type.DIRECTORY) {
            for (info in sftp.ls(remote)) {
                val name = info.name
                if (name == "." || name == "..") continue
                deleteRecursive(sftp, info.path)
            }
            sftp.rmdir(remote)
        } else {
            sftp.rm(remote)
        }
    }

    /** Recursive copy (SFTP has no server-side copy primitive): creates directories, then copies item by item, streaming file reads/writes. */
    private fun copyRecursive(sftp: SFTPClient, from: String, to: String) {
        val attrs = sftp.statExistence(from) ?: throw NoSuchFileException(File(from))
        if (attrs.type == FileMode.Type.DIRECTORY) {
            sftp.mkdirs(to)
            for (info in sftp.ls(from)) {
                val name = info.name
                if (name == "." || name == "..") continue
                copyRecursive(sftp, info.path, "$to/$name")
            }
        } else {
            sftp.open(from).use { src ->
                sftp.open(to, EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)).use { dst ->
                    val buf = ByteArray(IO_CHUNK)
                    var offset = 0L
                    while (true) {
                        val n = src.read(offset, buf, 0, buf.size)
                        if (n <= 0) break
                        dst.write(offset, buf, 0, n)
                        offset += n
                    }
                }
            }
        }
    }

    private fun ensureParent(sftp: SFTPClient, remote: String) {
        val parent = remote.substringBeforeLast('/', "")
        if (parent.isNotEmpty()) sftp.mkdirs(parent)
    }
}

/** Adapts random reads on an SFTP [RemoteFile] to a sequential [InputStream] for lazy line-by-line reading by [BoundedLineReader]. */
private class RemoteFileInputStream(private val file: RemoteFile) : InputStream() {
    private var offset = 0L
    private val single = ByteArray(1)

    override fun read(): Int {
        val n = file.read(offset, single, 0, 1)
        if (n <= 0) return -1
        offset += 1
        return single[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        val n = file.read(offset, b, off, len)
        if (n <= 0) return -1
        offset += n
        return n
    }

    override fun close() = file.close()
}

/** SFTP permission set → 9-character rwx string (e.g. `rw-r--r--`). */
internal fun formatPermissions(perms: Set<FilePermission>): String {
    fun bit(p: FilePermission, c: Char) = if (p in perms) c else '-'
    return buildString {
        append(bit(FilePermission.USR_R, 'r'))
        append(bit(FilePermission.USR_W, 'w'))
        append(bit(FilePermission.USR_X, 'x'))
        append(bit(FilePermission.GRP_R, 'r'))
        append(bit(FilePermission.GRP_W, 'w'))
        append(bit(FilePermission.GRP_X, 'x'))
        append(bit(FilePermission.OTH_R, 'r'))
        append(bit(FilePermission.OTH_W, 'w'))
        append(bit(FilePermission.OTH_X, 'x'))
    }
}

/** AI path → remote real path: the `~/workspace` prefix maps to [workspaceRoot], other absolute paths are used as-is. */
internal fun remotePathFor(path: String, workspaceRoot: String, remoteHome: String?): String {
    val root = workspaceRoot.trimEnd('/')
    // CONTAINER_ROOT is ~/workspace; expand ~ before prefix matching
    val wsRoot = (remoteHome ?: "~").trimEnd('/') + "/workspace"
    val p = path.trim().let {
        if (it.startsWith("~/")) {
            val home = remoteHome
            if (home != null) home.trimEnd('/') + "/" + it.removePrefix("~/") else it
        } else it
    }
    return when {
        p == wsRoot || p == "$wsRoot/" || p == CONTAINER_ROOT || p == "$CONTAINER_ROOT/" -> root
        p.startsWith("$wsRoot/") ->
            root + "/" + p.removePrefix("$wsRoot/")
        p.startsWith("/") -> p
        else -> root + "/" + p
    }
}

/** Remote real path → AI-facing container path (for display). */
internal fun displayPathFor(remotePath: String, workspaceRoot: String): String {
    val root = workspaceRoot.trimEnd('/')
    return when {
        remotePath == root -> CONTAINER_ROOT
        remotePath.startsWith("$root/") -> CONTAINER_ROOT + "/" + remotePath.removePrefix("$root/")
        else -> remotePath
    }
}
