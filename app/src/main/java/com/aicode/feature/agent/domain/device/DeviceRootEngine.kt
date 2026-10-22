package com.aicode.feature.agent.domain.device

import android.content.Context
import android.system.Os
import com.aicode.R
import com.aicode.core.util.BoundedLineReader
import com.aicode.core.util.FileLogger
import com.aicode.core.util.LINE_TRUNCATED_NOTE
import com.aicode.feature.agent.domain.container.BoundedOutput
import com.aicode.feature.agent.domain.container.CommandEngine
import com.aicode.feature.agent.domain.container.CommandEvent
import com.aicode.feature.agent.domain.container.CommandResult
import com.aicode.feature.agent.domain.container.ContainerInitState
import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.agent.domain.container.MAX_UNBOUNDED_CHARS
import com.aicode.feature.agent.domain.container.sanitizeCommandForLog
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.workspace.domain.PathHomeResolver
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Device ROOT command execution backend: the AI's commands do not go through the PRoot sandbox container, but run
 * directly as root on the real device.
 *
 * Implementation (decided by the user): every command runs via `su -c` (`ProcessBuilder(listOf("su", "-c", command))`,
 * with argv passed directly so no shell escaping is needed). Magisk/KernelSU shows an authorization dialog on the
 * first run; once the user checks "remember", subsequent commands are seamless.
 *
 * Differences from [com.aicode.feature.agent.domain.container.LinuxContainerEngine]:
 * - No rootfs/proot installation concept: readiness is determined by a single `su` probe (`id -u` returning 0);
 * - [projectPath] is a real-device absolute path, entered via `cd '<path>' && <command>`;
 * - The child process environment is su's default root environment (PATH usually contains /system/bin:/system/xbin:/sbin, depending on the su implementation).
 *
 * Security note: commands on this channel run as uid 0; the authorization level ([com.aicode.feature.settings.data.repository.CommandAuthLevel])
 * and the catastrophic-rm protection still apply as usual, and the UI shows a separate countdown confirmation when switching to this channel.
 */
@Singleton
class DeviceRootEngine @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val pathHomeResolver: PathHomeResolver,
    private val workspaceRepository: WorkspaceRepository,
    private val containerInstaller: ContainerInstaller
) : CommandEngine {

    // Not private: peer implementations such as terminal/file access need to reuse [shellQuote] and the timeout constants.
    companion object {
        private const val TAG = "DeviceRootEngine"

        /** Child process stdin connected to the null device: prevents "waiting for input" programs from blocking until timeout (same as LinuxContainerEngine). */
        private val STDIN_FROM_DEV_NULL: ProcessBuilder.Redirect =
            ProcessBuilder.Redirect.from(java.io.File("/dev/null"))

        /** Command timeout upper limit (milliseconds), aligned with [CommandEngine.MAX_TIMEOUT_MS]. */
        private const val MAX_TIMEOUT_MS = CommandEngine.MAX_TIMEOUT_MS

        /** Grace period (milliseconds) for the process to exit gracefully after a timeout, after which it is force-killed. */
        private const val TIMEOUT_KILL_GRACE_MS = 200L

        /** How long an su probe result is cached (milliseconds): avoids forking a probe process for every command. */
        private const val PROBE_CACHE_MS = 30_000L

        /** Wraps a shell string in single quotes (`'` -> `'\''`). */
        fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
    }

    /** The device channel needs no initialization: progress is always Ready. */
    private val _initProgress = MutableStateFlow<ContainerInitState>(ContainerInitState.Ready)
    override val initProgress: StateFlow<ContainerInitState> = _initProgress.asStateFlow()

    /** su probe cache: result + probe time. */
    @Volatile
    private var probeCache: Pair<Boolean, Long>? = null

    /**
     * Probes whether ROOT is available: `su -c id -u` exits with code 0 and its first output line is "0".
     * The result is cached for [PROBE_CACHE_MS] milliseconds; the first probe may trigger the su authorization dialog (depending on the su implementation).
     */
    private fun probeRoot(): Boolean {
        val now = System.currentTimeMillis()
        probeCache?.let { (ok, ts) -> if (now - ts < PROBE_CACHE_MS) return ok }
        val ok = runCatching {
            val process = ProcessBuilder(listOf("su", "-c", "id -u"))
                .redirectErrorStream(true)
                .redirectInput(STDIN_FROM_DEV_NULL)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            exitCode == 0 && output.lineSequence().firstOrNull()?.trim() == "0"
        }.getOrElse {
            FileLogger.w(TAG, "su probe failed: ${it.message}")
            false
        }
        probeCache = ok to now
        return ok
    }

    /** Forces a re-probe (used by the "Check ROOT" button on the settings page, bypassing the cache). */
    fun reprobeRoot(): Boolean {
        probeCache = null
        return probeRoot()
    }

    override fun isContainerInstalled(): Boolean = probeRoot()

    override fun isProvisioned(): Boolean = probeRoot()

    override fun defaultShell(): String = "/system/bin/sh"

    override fun notReadyHint(): String? =
        if (probeRoot()) null else context.getString(R.string.device_root_not_ready_hint)

    override suspend fun ensureInstalled() {
        if (!probeRoot()) {
            throw IllegalStateException(context.getString(R.string.device_root_not_ready_hint))
        }
    }

    override fun runCommandStream(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): Flow<CommandEvent> = flow {
        notReadyHint()?.let {
            emit(CommandEvent.Line(it))
            emit(CommandEvent.Exit(null))
            return@flow
        }
        emitAll(streamExec(command, projectPath, timeoutMs))
    }.flowOn(Dispatchers.IO)

    /**
     * Streaming execution (isomorphic with [com.aicode.feature.agent.domain.container.LinuxContainerEngine]):
     * emits [CommandEvent.Line] line by line and [CommandEvent.Exit] at the end; on timeout the watchdog destroys
     * the process to unblock readLine, appends a timeout notice line, and emits Exit(null).
     */
    private fun streamExec(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): Flow<CommandEvent> = flow {
        val effectiveTimeout = timeoutMs.coerceIn(1L, MAX_TIMEOUT_MS)
        FileLogger.d(TAG, "Running command (device, streaming) cwd=$projectPath timeout=${effectiveTimeout}ms: ${sanitizeCommandForLog(command)}")
        val process = startRootProcess(command, projectPath)
        val timedOut = AtomicBoolean(false)
        val watchScope = CoroutineScope(Dispatchers.IO + Job())
        val watchdog = launchKillWatchdog(watchScope, process, effectiveTimeout, timedOut, command)
        val cancellationHook = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
            if (cause is CancellationException && process.isAlive) {
                FileLogger.i(TAG, "Command cancelled, terminating process: ${sanitizeCommandForLog(command)}")
                runCatching { process.destroy() }
                runCatching { process.destroyForcibly() }
            }
        }
        val reader = BoundedLineReader(InputStreamReader(process.inputStream))
        try {
            while (true) {
                val line = reader.readLine() ?: break
                emit(CommandEvent.Line(if (line.truncated) "${line.text}\n$LINE_TRUNCATED_NOTE" else line.text))
            }
            val exitCode = process.waitFor()
            watchdog.cancel()
            if (timedOut.get()) {
                FileLogger.w(TAG, "Command timed out (${effectiveTimeout}ms), terminated: ${sanitizeCommandForLog(command)}")
                emit(CommandEvent.Line(timeoutNotice(effectiveTimeout)))
                emit(CommandEvent.Exit(null))
            } else {
                if (exitCode != 0) FileLogger.w(TAG, "Command exit code=$exitCode: ${sanitizeCommandForLog(command)}")
                emit(CommandEvent.Exit(exitCode))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Same reasoning as the container engine: the watchdog's destroy closes the pipe and makes readLine throw an IOException, so branch on timeout vs. exception
            // to guarantee that Exit is always emitted and already-produced output is not lost.
            watchdog.cancel()
            if (timedOut.get()) {
                emit(CommandEvent.Line(timeoutNotice(effectiveTimeout)))
                emit(CommandEvent.Exit(null))
            } else {
                FileLogger.e(TAG, "Exception reading command output (previous output preserved): ${sanitizeCommandForLog(command)}", e)
                emit(CommandEvent.Line("[Command execution exception: ${e.message}]"))
                emit(CommandEvent.Exit(null))
            }
        } finally {
            cancellationHook?.dispose()
            watchdog.cancel()
            watchScope.cancel()
            runCatching { reader.close() }
            runCatching { process.destroy() }
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun runCommandSync(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): String = withContext(Dispatchers.IO) {
        notReadyHint()?.let { return@withContext it }
        execCaptured(command, projectPath, timeoutMs).output
    }

    override suspend fun runCommandSyncWithExit(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): CommandResult = withContext(Dispatchers.IO) {
        notReadyHint()?.let { return@withContext CommandResult(it, null) }
        val r = execCaptured(command, projectPath, timeoutMs)
        CommandResult(r.output, r.exitCode, r.truncated)
    }

    override suspend fun runCommandSyncUnbounded(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): CommandResult = withContext(Dispatchers.IO) {
        notReadyHint()?.let { return@withContext CommandResult(it, null) }
        val r = execCaptured(command, projectPath, timeoutMs, unbounded = true)
        CommandResult(r.output, r.exitCode, r.truncated)
    }

    override suspend fun runCommandSyncIfReady(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): CommandResult? {
        if (!probeRoot()) return null
        val r = execCaptured(command, projectPath, timeoutMs)
        return CommandResult(r.output, r.exitCode, r.truncated)
    }

    /** Internal result of one device execution: the capped full output + exit code (null on timeout/exception). */
    private data class ExecResult(val output: String, val exitCode: Int?, val truncated: Boolean = false)

    /** Runs synchronously and captures output (isomorphic with the container engine: capped accumulation + watchdog + cancellation cleanup). */
    private suspend fun execCaptured(
        command: String,
        projectPath: String?,
        timeoutMs: Long,
        unbounded: Boolean = false
    ): ExecResult = withContext(Dispatchers.IO) {
        try {
            val effectiveTimeout = timeoutMs.coerceIn(1L, MAX_TIMEOUT_MS)
            FileLogger.d(TAG, "Running command (device, synchronous) cwd=$projectPath timeout=${effectiveTimeout}ms: ${sanitizeCommandForLog(command)}")
            val process = startRootProcess(command, projectPath)
            val timedOut = AtomicBoolean(false)
            val cancellationHook = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
                if (cause is CancellationException && process.isAlive) {
                    FileLogger.i(TAG, "Command cancelled, terminating process: ${sanitizeCommandForLog(command)}")
                    runCatching { process.destroy() }
                    runCatching { process.destroyForcibly() }
                }
            }

            val output = if (unbounded) BoundedOutput.hardCapped(MAX_UNBOUNDED_CHARS) else BoundedOutput()
            var exitCode: Int? = null
            try {
                coroutineScope {
                    val watchdog = launchKillWatchdog(this, process, effectiveTimeout, timedOut, command)
                    val reader = BoundedLineReader(InputStreamReader(process.inputStream))
                    try {
                        while (true) {
                            val line = reader.readLine() ?: break
                            output.append(if (line.truncated) "${line.text}\n$LINE_TRUNCATED_NOTE" else line.text)
                            output.append("\n")
                        }
                        exitCode = process.waitFor()
                    } finally {
                        watchdog.cancel()
                        runCatching { reader.close() }
                    }
                }
            } finally {
                cancellationHook?.dispose()
                runCatching { process.destroy() }
            }

            if (timedOut.get()) {
                FileLogger.w(TAG, "Command timed out (${effectiveTimeout}ms), terminated: ${sanitizeCommandForLog(command)}")
                output.append(timeoutNotice(effectiveTimeout))
                output.append("\n")
                ExecResult(output.build(), null, output.truncated)
            } else {
                ExecResult(output.build(), exitCode, output.truncated)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.e(TAG, "Command execution exception: ${sanitizeCommandForLog(command)}", e)
            ExecResult("Error: ${e.message}", null)
        }
    }

    /** Watchdog: when the deadline arrives and the process is still alive, marks it timed out and terminates it gracefully, then forcibly (the device channel has no credential-dialog grace scenario). */
    private fun launchKillWatchdog(
        scope: CoroutineScope,
        process: Process,
        timeoutMs: Long,
        timedOut: AtomicBoolean,
        command: String
    ): Job = scope.launch {
        delay(timeoutMs)
        if (!process.isAlive) return@launch
        timedOut.set(true)
        FileLogger.w(TAG, "Command execution exceeded ${timeoutMs}ms, terminating process: ${sanitizeCommandForLog(command)}")
        runCatching { process.destroy() }
        delay(TIMEOUT_KILL_GRACE_MS)
        if (process.isAlive) runCatching { process.destroyForcibly() }
    }

    /** Timeout notice line (appended to the output, fed back to the model / shown to the user). */
    private fun timeoutNotice(timeoutMs: Long): String =
        "[Command execution timed out: exceeded ${timeoutMs}ms and was forcibly terminated]"

    /**
     * Starts a command as root: `su -c '<command>'`; when [projectPath] is non-empty, `cd`s into it first.
     * argv is passed item by item (not concatenated through a shell); the command text itself is still parsed by the root shell (consistent with the container engine's semantics).
     *
     * The prefix first does `export HOME=<filesDir>`: su resets HOME to /root (which does not exist on the device),
     * while the prompt convention names the workspace alias `~/workspace` - with HOME pointing at filesDir, `~/workspace`
     * inside the shell and the file tools' path alias resolve to the same place (via the symlink created by [refreshWorkspaceLinks]).
     */
    private fun startRootProcess(command: String, projectPath: String?): Process {
        refreshWorkspaceLinks()
        val sb = StringBuilder()
        sb.append("export HOME=").append(shellQuote(pathHomeResolver.home())).append("; ")
        if (!projectPath.isNullOrBlank()) {
            sb.append("cd ").append(shellQuote(projectPath)).append(" && ")
        }
        sb.append(command)
        val pb = ProcessBuilder(listOf("su", "-c", sb.toString()))
        pb.redirectErrorStream(true)
        pb.redirectInput(STDIN_FROM_DEV_NULL)
        return pb.start()
    }

    /** The workspace path for which links were last successfully created: skipped when the workspace is unchanged, avoiding a repeated stat/symlink for every command. */
    @Volatile
    private var linkedWorkspace: String? = null

    /**
     * Creates `workspace` / `.aicode` symlinks under `$HOME` (the App's private filesDir), pointing at the current
     * workspace and the global config directory respectively, mirroring the layout of the `~/workspace` bind mount
     * and the `/root/.aicode` binding in container mode, so that manually running `cd ~/workspace` or
     * `ls ~/.aicode` in the device root shell agrees with the file tools' path aliases.
     *
     * Best-effort: the links are created inside filesDir on ext4 (the app can create them itself, no su needed), and
     * pointing at external targets such as /sdcard is fine too; an existing real directory/file at the target is
     * never overwritten. Any failure is silently ignored: the file tools' alias mapping does not depend on the links actually existing.
     */
    fun refreshWorkspaceLinks() {
        val ws = workspaceRepository.currentPath().trim().trimEnd('/')
        if (ws.isEmpty() || ws == linkedWorkspace) return
        val home = context.filesDir.absolutePath
        linkIfPossible(File(home, "workspace"), ws)
        linkIfPossible(File(home, ".aicode"), containerInstaller.aicodeDir.absolutePath)
        linkedWorkspace = ws
    }

    /** Creates a single symlink; skipped when [link] is already a link to the same target, and never overwrites an existing real file/directory. */
    private fun linkIfPossible(link: File, target: String) {
        runCatching {
            val path = link.toPath()
            if (java.nio.file.Files.isSymbolicLink(path)) {
                val existing = runCatching { Os.readlink(link.absolutePath) }.getOrNull()
                if (existing == target) return
                if (!link.delete()) return
            } else if (link.exists()) {
                return
            }
            Os.symlink(target, link.absolutePath)
        }.onFailure { FileLogger.w(TAG, "Failed to create symlink ${link.absolutePath} -> $target: ${it.message}") }
    }
}
