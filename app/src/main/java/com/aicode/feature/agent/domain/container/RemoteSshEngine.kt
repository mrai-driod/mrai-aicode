package com.aicode.feature.agent.domain.container

import com.aicode.core.util.BoundedLineReader
import com.aicode.core.util.FileLogger
import com.aicode.core.util.LINE_TRUNCATED_NOTE
import com.aicode.feature.agent.domain.container.CommandEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

private const val TAG = "RemoteSshEngine"

/**
 * The remote SSH implementation of [CommandEngine]: runs commands on a remote server over an sshj exec channel.
 *
 * Commands run through a shared [RemoteSshConnection] (which holds the sshj [SSHClient]); file reading and writing
 * go through [RemoteSftpFileAccess] over a separate, independent SFTP transport (see [RemoteSshConnection.sftp]),
 * so the two are isolated and do not affect each other.
 *
 * Correspondence with the semantics of [LinuxContainerEngine]:
 * - [ensureInstalled]: establishes/maintains the SSH connection (corresponding to locally extracting the rootfs);
 * - [isContainerInstalled]: whether the SSH connection is alive (corresponding to whether the local rootfs is ready);
 * - [isProvisioned]: always true (remote tools are the user's own responsibility, corresponding to local apk package installation being complete);
 * - [defaultShell]: /bin/bash (remote servers usually have bash).
 */
class RemoteSshEngine @Inject constructor(
    private val connection: RemoteSshConnection
) : CommandEngine {

    private val _initProgress = MutableStateFlow<ContainerInitState>(ContainerInitState.Idle)
    override val initProgress: StateFlow<ContainerInitState> = _initProgress.asStateFlow()

    private val connectMutex = Mutex()

    override fun runCommandStream(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): Flow<CommandEvent> = flow {
        ensureInstalled()
        emitAll(streamExec(command, projectPath, timeoutMs))
    }.flowOn(Dispatchers.IO)

    private fun streamExec(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): Flow<CommandEvent> = flow {
        val effectiveTimeout = timeoutMs.coerceIn(1L, CommandEngine.MAX_TIMEOUT_MS)
        FileLogger.d(TAG, "Running command (remote, streaming) cwd=$projectPath timeout=${effectiveTimeout}ms: ${sanitizeCommandForLog(command)}")
        val session = connection.startExecSession(buildCdCommand(command, projectPath))
        val timedOut = AtomicBoolean(false)
        val watchScope = CoroutineScope(Dispatchers.IO + Job())
        val watchdog = watchScope.launch {
            delay(effectiveTimeout)
            if (session.isOpen) {
                timedOut.set(true)
                FileLogger.w(TAG, "Command timed out (${effectiveTimeout}ms), terminated: ${sanitizeCommandForLog(command)}")
                runCatching { session.close() }
            }
        }
        val cancellationHook = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
            if (cause is CancellationException && session.isOpen) {
                FileLogger.i(TAG, "Command cancelled, closing session: ${sanitizeCommandForLog(command)}")
                runCatching { session.close() }
            }
        }
        val reader = BoundedLineReader(InputStreamReader(session.inputStream))
        try {
            while (true) {
                val line = reader.readLine() ?: break
                emit(CommandEvent.Line(if (line.truncated) "${line.text}\n$LINE_TRUNCATED_NOTE" else line.text))
            }
            val exitCode = session.exitStatus
            watchdog.cancel()
            if (timedOut.get()) {
                emit(CommandEvent.Line("[Command execution timed out: exceeded ${effectiveTimeout}ms and was forcibly terminated]"))
                emit(CommandEvent.Exit(null))
            } else {
                if (exitCode != 0) FileLogger.w(TAG, "Command exit code=$exitCode: ${sanitizeCommandForLog(command)}")
                else FileLogger.v(TAG, "Command completed (exit code 0): ${sanitizeCommandForLog(command)}")
                emit(CommandEvent.Exit(exitCode))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            watchdog.cancel()
            if (timedOut.get()) {
                emit(CommandEvent.Line("[Command execution timed out: exceeded ${effectiveTimeout}ms and was forcibly terminated]"))
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
            runCatching { session.close() }
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun runCommandSync(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): String = withContext(Dispatchers.IO) {
        ensureInstalled()
        execCaptured(command, projectPath, timeoutMs).output
    }

    override suspend fun runCommandSyncWithExit(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): CommandResult = withContext(Dispatchers.IO) {
        ensureInstalled()
        execCaptured(command, projectPath, timeoutMs)
    }

    override suspend fun runCommandSyncIfReady(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): CommandResult? {
        if (!isContainerInstalled()) return null
        return runCatching { execCaptured(command, projectPath, timeoutMs) }
            .getOrElse {
                FileLogger.w(TAG, "Remote command execution failed (connection may have dropped): ${sanitizeCommandForLog(command)}", it)
                null
            }
    }

    override suspend fun runCommandSyncUnbounded(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): CommandResult = withContext(Dispatchers.IO) {
        ensureInstalled()
        execCaptured(command, projectPath, timeoutMs, unbounded = true)
    }

    private suspend fun execCaptured(
        command: String,
        projectPath: String?,
        timeoutMs: Long,
        unbounded: Boolean = false
    ): CommandResult = withContext(Dispatchers.IO) {
        val effectiveTimeout = timeoutMs.coerceIn(1L, CommandEngine.MAX_TIMEOUT_MS)
        FileLogger.d(TAG, "Running command (remote, synchronous) cwd=$projectPath timeout=${effectiveTimeout}ms: ${sanitizeCommandForLog(command)}")
        val session = connection.startExecSession(buildCdCommand(command, projectPath))
        val output = if (unbounded) BoundedOutput.hardCapped(MAX_UNBOUNDED_CHARS) else BoundedOutput()
        var exitCode: Int? = null
        try {
            coroutineScope {
                val watchdog = launch {
                    delay(effectiveTimeout)
                    if (session.isOpen) {
                        FileLogger.w(TAG, "Command timed out (${effectiveTimeout}ms), terminated: ${sanitizeCommandForLog(command)}")
                        runCatching { session.close() }
                    }
                }
                val reader = BoundedLineReader(InputStreamReader(session.inputStream))
                try {
                    while (true) {
                        val line = reader.readLine() ?: break
                        output.append(if (line.truncated) "${line.text}\n$LINE_TRUNCATED_NOTE" else line.text)
                        output.append("\n")
                    }
                } finally {
                    watchdog.cancel()
                    runCatching { reader.close() }
                }
                // Read stderr concurrently and merge it into output: the local engine's redirectErrorStream(true) has merge semantics;
                // if the remote side did not merge, command errors written only to stderr (such as command not found when rg is not installed) would be silently discarded.
                val stderrJob = launch {
                    val errReader = BoundedLineReader(InputStreamReader(session.errorStream))
                    try {
                        while (true) {
                            val errLine = errReader.readLine() ?: break
                            output.append(if (errLine.truncated) "${errLine.text}\n$LINE_TRUNCATED_NOTE" else errLine.text)
                            output.append("\n")
                        }
                    } finally {
                        runCatching { errReader.close() }
                    }
                }
                stderrJob.join()
                // sshj's exitStatus is not necessarily ready after stream EOF; it is only guaranteed to have a value after close
                runCatching { session.close() }
                exitCode = session.exitStatus
            }
        } finally {
            runCatching { session.close() }
        }
        FileLogger.v(TAG, "Command completed (remote, exit code $exitCode, output ${output.totalChars} characters): ${sanitizeCommandForLog(command)}")
        CommandResult(output.build(), exitCode, output.truncated)
    }

    override fun isContainerInstalled(): Boolean = connection.isConnected()

    override fun isProvisioned(): Boolean = true

    override fun defaultShell(): String = "/bin/bash"

    override suspend fun ensureInstalled() = connectMutex.withLock {
        if (connection.isConnected()) {
            _initProgress.value = ContainerInitState.Ready
            return@withLock
        }
        _initProgress.value = ContainerInitState.InstallingPackages(line = "Connecting to the SSH server…")
        try {
            connection.connect()
            _initProgress.value = ContainerInitState.Ready
        } catch (e: Exception) {
            FileLogger.e(TAG, "SSH connection failed", e)
            val friendly = friendlySshError(e)
            _initProgress.value = ContainerInitState.Failed(friendly)
            throw RuntimeException(friendly, e)
        }
    }

    /** Builds the full command by prepending a cd to projectPath before running command; if projectPath is null, runs it directly.
     *  Prefers cd-ing to ~/workspace (a symlink), so the AI sees ~/workspace rather than the real path when it runs pwd.
     *  If ~/workspace does not exist (the symlink was not created), falls back to projectPath.
     *  Injects GIT_CONFIG_GLOBAL pointing at the App-managed ~/.aicode/gitconfig:
     *  that file only exists when the user has enabled "auto-inject" (it contains an include of the user's global config plus an includeIf scoped to the workspace root);
     *  when the file does not exist git silently skips it, so it does not affect the user running git manually on the server. */
    private fun buildCdCommand(command: String, projectPath: String?): String {
        val prefix = "export GIT_CONFIG_GLOBAL=\"\$HOME/.aicode/gitconfig\"; "
        if (projectPath == null) return prefix + command
        return prefix + "cd ~/workspace 2>/dev/null || cd '$projectPath' 2>/dev/null; $command"
    }
}
