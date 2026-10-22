package com.aicode.feature.agent.domain.container

import com.aicode.core.util.BoundedLineReader
import com.aicode.core.util.FileLogger
import com.aicode.core.util.LINE_TRUNCATED_NOTE
import com.aicode.R
import com.aicode.feature.settings.data.repository.ExecutionMode
import dagger.hilt.android.qualifiers.ApplicationContext
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local PRoot container command execution backend, implementing [CommandEngine].
 *
 * The original logic is unchanged - only `: CommandEngine` was added and `override` added to public methods.
 * [CommandEvent] and [CommandResult] have been promoted to top-level types (see [CommandEngine.kt]),
 * and this class no longer defines them itself. PRoot-specific methods ([startStdioProcess]/[buildProotInvocation]/
 * [incPromptInFlight], etc.) are not part of the interface and are only for local MCP stdio / credential helper / terminal PTY use.
 */
/**
 * The complete description of one PRoot invocation: executable + argument list + environment variables.
 *
 * Element 0 of [argv] is the proot binary path, the rest are arguments.
 *
 * Both consumers need the "complete argv":
 *  - [ProcessBuilder] receives the argv list directly;
 *  - Termux TerminalSession's cmd is only used for execvp to look up the executable; the real argv is formed as-is from its
 *    args parameter (native execvp(cmd, argv), which does not automatically add argv[0]), so args must
 *    also be the complete argv "including argv[0]=proot binary", otherwise every option shifts by one place and proot will
 *    mistake the rootfs path for the guest program ("is not a regular file"). [executable] is only used for the former's cmd slot.
 */
data class ProotInvocation(
    val argv: List<String>,
    val env: Map<String, String>
) {
    val executable: String get() = argv.first()

    /**
     * The complete environment array ("KEY=VALUE") for use by Termux [com.termux.terminal.TerminalSession].
     *
     * [ProcessBuilder]'s environment() initially is a copy of the parent process (App) environment, with [env] overlaid,
     * so proot can get system variables such as ANDROID_ROOT/ANDROID_DATA/LD_LIBRARY_PATH; but
     * TerminalSession receives a "complete environment array that does not inherit the parent process", so feeding only [env] would make proot,
     * a dynamically linked Android executable, fail to exec due to the missing system environment and exit instantly (the terminal shows
     * "session ended" with no other error in the log). So the parent process environment is explicitly merged here, replicating ProcessBuilder semantics.
     */
    val ptyEnvArray: Array<String>
        get() = (System.getenv() + env).map { "${it.key}=${it.value}" }.toTypedArray()
}

@Singleton
class LinuxContainerEngine @Inject constructor(
    @param:ApplicationContext private val context: android.content.Context,
    private val containerInstaller: ContainerInstaller,
    private val containerOsDetector: ContainerOsDetector,
    private val containerSettingsRepository: com.aicode.feature.settings.data.repository.ContainerSettingsRepository,
    private val pathHomeResolver: com.aicode.feature.workspace.domain.PathHomeResolver
) : CommandEngine {
    /** Real-time progress of container initialization, sharing one state across all entry points (terminal page/AI/background terminal/MCP). */
    private val _initProgress = MutableStateFlow<ContainerInitState>(ContainerInitState.Idle)
    override val initProgress: StateFlow<ContainerInitState> = _initProgress.asStateFlow()

    /** Serializes container initialization (including task creation and execution inside the background initScope), avoiding duplicate extraction/configuration from concurrent entry points. */
    private val initMutex = Mutex()

    /**
     * Independent coroutine scope for container initialization: not interrupted by cancellation of any page/caller,
     * guaranteeing that initialization continues in the background after leaving the terminal page, and can be reused or awaited on the next entry.
     */
    private val initScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The currently in-flight initialization job (protected by [initMutex]), set to null on completion. */
    private var initJob: Job? = null

    /**
     * The currently selected profile (cached from [containerSettingsRepository.activeProfileIdFlow], avoiding synchronous DataStore reads).
     * The first frame at startup is the built-in Alpine (same as before the change); after a profile switch it is updated by the flow collector. Falling back to the built-in keeps it safe.
     */
    @Volatile
    private var currentProfile: ContainerProfile = ContainerProfile.BUILTIN_ALPINE

    init {
        CoroutineScope(Dispatchers.IO).launch {
            containerSettingsRepository.activeProfileIdFlow.collect { id ->
                currentProfile = resolveProfile(id)
            }
        }
    }

    /**
     * Resolves a profile by id: looks it up in the persisted list, falls back to the first in the list if not found, then to the built-in Alpine (guaranteeing the engine is always usable).
     * A persisted Alpine override in the list takes precedence over the [ContainerProfile.BUILTIN_ALPINE] constant (so user-edited configuration takes effect).
     */
    private suspend fun resolveProfile(id: String): ContainerProfile {
        val profiles = containerSettingsRepository.customProfilesFlow.first()
        return profiles.firstOrNull { it.id == id }
            ?: profiles.firstOrNull()
            ?: ContainerProfile.BUILTIN_ALPINE
    }

    /**
     * Count of credential requests currently in flight (the number of pairs where the custom credential helper is blocked waiting for the app dialog to fill in).
     *
     * Incremented by [com.aicode.feature.credentials.data.CredentialRequestBridge] when it receives the helper's cred-req, and
     * decremented when it writes back cred-resp. [launchKillWatchdog] relaxes the timeout based on this - while a helper is in flight, the user's git command is "waiting
     * for the user to enter credentials" rather than "stuck", so the watchdog should not force-kill it on the normal timeout; see its change notes.
     */
    private val credentialPromptInFlight = java.util.concurrent.atomic.AtomicInteger(0)

    /** A credential request enters flight (called when the bridge receives the helper's cred-req). */
    fun incPromptInFlight() { credentialPromptInFlight.incrementAndGet() }

    /** A credential request leaves flight (called when the bridge writes back cred-resp). */
    fun decPromptInFlight() { credentialPromptInFlight.decrementAndGet() }

    companion object {
        private const val TAG = "LinuxContainerEngine"

        /**
         * The redirect target for the child process's stdin. The command execution path never writes input to the child process; if Java's default empty pipe were left,
         * programs that decide their behaviour by "whether stdin is readable" (rg with no path argument, package managers waiting for confirmation, etc.) would treat it as
         * input to be read and block until the command times out; attaching the null device lets them get EOF immediately and take the normal branch.
         */
        private val STDIN_FROM_DEV_NULL: ProcessBuilder.Redirect =
            ProcessBuilder.Redirect.from(java.io.File("/dev/null"))

        /** Default command timeout (ms): applied when not explicitly specified, avoiding a stuck command occupying the session forever. */
        const val DEFAULT_TIMEOUT_MS = 120_000L

        /** Maximum command timeout (ms): even larger requests are clamped to this value, preventing a de facto "infinite wait". */
        const val MAX_TIMEOUT_MS = 1_800_000L

        /** Grace period (ms) for the process to exit gracefully after a timeout, after which it is force-killed. */
        private const val TIMEOUT_KILL_GRACE_MS = 200L

        /**
         * Base package provisioning version. Corresponds to the version of assets/aicode/provision.sh: when changing the script (package list/install logic/mirror sources), increment it in sync,
         * triggering re-execution of the script on the device (apk add is idempotent, already-installed packages are skipped).
         * Independent of [ContainerInstaller]'s rootfs INSTALL_VERSION: a rootfs version upgrade deletes the rootfs
         * (taking this marker with it), so a new rootfs always reruns provisioning; changing the script under the same rootfs is triggered by this version number.
         * The marker file is written by the script itself (its content is the version number below, or the skip marker for when a custom-image user chooses manual installation,
         * provision-script-skipped); the App side only reads it to judge completion.
         */
        private const val PROVISION_VERSION = "provision-script-v10"
    }

    /** Marks that the base packages have been provisioned per [PROVISION_VERSION] (content is the version number, or the skip marker for manual installation by the user), stored per the current profile's rootfs directory (built-in/custom are independent). */
    private fun provisionMarker(profile: ContainerProfile): java.io.File =
        java.io.File(containerInstaller.rootfsDirFor(profile), ".provisioned")

    /**
     * Executes a command in the container as a stream: emits one [CommandEvent.Line] per line read, and emits
     * [CommandEvent.Exit] when the command ends. The first call triggers rootfs installation (idempotent).
     *
     * Shares the same process-building logic as [runCommandSync]; the only difference is that output is delivered line by line in real time,
     * letting the terminal see the execution "process" rather than only the final result.
     *
     * [timeoutMs] is the maximum command execution time (ms), default [DEFAULT_TIMEOUT_MS], maximum [MAX_TIMEOUT_MS].
     * On timeout, the child process is forcibly terminated and a timeout notice line is appended at the end; the [CommandEvent.Exit] exit code is recorded as null.
     * Since readLine is a blocking read, a coroutine timeout alone cannot interrupt it, so an independent watchdog destroys the process to unblock it here.
     */
    override fun runCommandStream(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): Flow<CommandEvent> = flow {
        // When not ready (rootfs not extracted or base packages not provisioned), do not initialize automatically; prompt the user to complete initialization on the terminal page.
        notReadyHint()?.let {
            emit(CommandEvent.Line(it))
            emit(CommandEvent.Exit(null))
            return@flow
        }
        emitAll(streamExecNoInstall(command, projectPath, timeoutMs))
    }.flowOn(Dispatchers.IO)
    /**
     * The "raw" implementation of streaming command execution in the container: emits [CommandEvent.Line] per line read, and emits
     * [CommandEvent.Exit] when the command ends. **Does not trigger lazy installation** (does not call [ensureInstalled]); assumes the rootfs is ready.
     *
     * This method is extracted for shared use by [runCommandStream] (which calls [ensureInstalled] first) and initialization script execution (which calls
     * [ContainerInstaller.installRootfsIfNeed] first and must not trigger ensureInstalled again or it would recurse),
     * so initialization can also receive output line by line to update progress.
     *
     * [timeoutMs] is the maximum command execution time (ms), default [DEFAULT_TIMEOUT_MS], maximum [MAX_TIMEOUT_MS].
     * On timeout, the child process is forcibly terminated and a timeout notice line is appended at the end; the [CommandEvent.Exit] exit code is recorded as null.
     * Since readLine is a blocking read, a coroutine timeout alone cannot interrupt it, so an independent watchdog destroys the process to unblock it here.
     */
    private fun streamExecNoInstall(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): Flow<CommandEvent> = flow {
        val effectiveTimeout = timeoutMs.coerceIn(1L, MAX_TIMEOUT_MS)
        FileLogger.d(TAG, "Executing command (streaming) cwd=$projectPath timeout=${effectiveTimeout}ms: ${sanitizeCommandForLog(command)}")
        val process = startContainerProcess(command, projectPath)
        val timedOut = AtomicBoolean(false)
        // The watchdog runs on an independent scope (independent Job): if placed inside the coroutineScope wrapping emit, emit's
        // Job would not match the flow collector's and would trigger "Flow invariant is violated". It is only used here to kill the process on timeout.
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
                FileLogger.w(TAG, "Command timed out (${effectiveTimeout}ms) and was terminated: ${sanitizeCommandForLog(command)}")
                emit(CommandEvent.Line(timeoutNotice(effectiveTimeout)))
                emit(CommandEvent.Exit(null))
            } else {
                if (exitCode != 0) FileLogger.w(TAG, "Command exit code=$exitCode: ${sanitizeCommandForLog(command)}")
                else FileLogger.v(TAG, "Command completed (exit code 0): ${sanitizeCommandForLog(command)}")
                emit(CommandEvent.Exit(exitCode))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // When the watchdog destroys the process on timeout it closes the stdout pipe, making the blocked readLine throw an IOException
            // (rather than returning null). If not absorbed here, the exception would terminate the flow abnormally, CommandEvent.Exit would no longer be emitted,
            // the upper executeStream collect would then abort, and output already shown to the user line by line would be lost from the final ToolResult.
            // So this branches on whether it timed out: on timeout, emit the timeout notice + Exit(null), preserving the lines already emitted;
            // non-IO exceptions are also converted to a one-line notice + Exit, avoiding the flow terminating abnormally and losing already-output content.
            watchdog.cancel()
            if (timedOut.get()) {
                FileLogger.w(TAG, "Command timed out (${effectiveTimeout}ms) and was terminated (readLine exception): ${sanitizeCommandForLog(command)}", e)
                emit(CommandEvent.Line(timeoutNotice(effectiveTimeout)))
                emit(CommandEvent.Exit(null))
            } else {
                FileLogger.e(TAG, "Command output read error (previous output preserved): ${sanitizeCommandForLog(command)}", e)
                emit(CommandEvent.Line("[Command execution error: ${e.message}]"))
                emit(CommandEvent.Exit(null))
            }
        } finally {
            // On coroutine cancellation (user leaves the page, etc.), ensure the child process is reclaimed to avoid leaks
            cancellationHook?.dispose()
            watchdog.cancel()
            watchScope.cancel()
            runCatching { reader.close() }
            runCatching { process.destroy() }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Executes a command synchronously in the container and returns the output. The first call triggers rootfs installation (idempotent).
     *
     * [timeoutMs] is the maximum command execution time (ms), default [DEFAULT_TIMEOUT_MS], maximum [MAX_TIMEOUT_MS].
     * On timeout, the child process is forcibly terminated, the partial output collected so far is returned, and a timeout notice is appended at the end.
     */
    override suspend fun runCommandSync(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): String = withContext(Dispatchers.IO) {
        // When not ready, do not initialize automatically; return the guidance text directly and let the user complete initialization on the terminal page.
        notReadyHint()?.let { return@withContext it }
        execCaptured(command, projectPath, timeoutMs).output
    }

    /**
     * Same as [runCommandSync], but also returns the exit code (null on timeout/error). For callers that need to judge success or failure by the exit code
     * - e.g. git write operations: a non-zero git exit code is not a process crash, and [runCommandSync] returning only text would make the upper layer falsely report success.
     */
    override suspend fun runCommandSyncWithExit(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): CommandResult = withContext(Dispatchers.IO) {
        // When not ready, do not initialize automatically; return the guidance text directly and let the user complete initialization on the terminal page.
        notReadyHint()?.let { return@withContext CommandResult(it, null) }
        val r = execCaptured(command, projectPath, timeoutMs)
        CommandResult(r.output, r.exitCode, r.truncated)
    }

    /** Internal result of one in-container execution: the complete capped output + exit code (null on timeout/error). */
    private data class ExecResult(val output: String, val exitCode: Int?, val truncated: Boolean = false)

    /**
     * Executes a command only when the container is ready (rootfs installed); does not trigger rootfs extraction.
     *
     * Used by read-only tools as a performance enhancement: e.g. search can prefer rg, but a single auto-approved search must not
     * implicitly extract the container or install an environment over the network. Returns null when not installed.
     */
    override suspend fun runCommandSyncIfReady(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): CommandResult? {
        if (!containerInstaller.isInstalledFor(currentProfile)) return null
        val result = execCaptured(command, projectPath, timeoutMs)
        return CommandResult(result.output, result.exitCode, result.truncated)
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

    /**
     * Executes a command synchronously in the container and captures the output. **Assumes the rootfs is installed** (no lazy install/provisioning),
     * shared by [runCommandSync] (which calls [ensureInstalled] first) and initialization script execution (which calls [installRootfsIfNeed] first),
     * avoiding the provisioning flow triggering [ensureInstalled] in reverse and causing recursion.
     *
     * [timeoutMs] is the maximum command execution time (ms), clamped to [MAX_TIMEOUT_MS]. On timeout the process is force-killed,
     * the partial output collected so far is returned with a timeout notice appended at the end, and [ExecResult.exitCode] is recorded as null.
     */
    private suspend fun execCaptured(
        command: String,
        projectPath: String?,
        timeoutMs: Long,
        unbounded: Boolean = false
    ): ExecResult = withContext(Dispatchers.IO) {
        try {
            val effectiveTimeout = timeoutMs.coerceIn(1L, MAX_TIMEOUT_MS)
            FileLogger.d(TAG, "Executing command (sync) cwd=$projectPath timeout=${effectiveTimeout}ms: ${sanitizeCommandForLog(command)}")
            val process = startContainerProcess(command, projectPath)
            val timedOut = AtomicBoolean(false)
            val cancellationHook = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
                if (cause is CancellationException && process.isAlive) {
                    FileLogger.i(TAG, "Command cancelled, terminating process: ${sanitizeCommandForLog(command)}")
                    runCatching { process.destroy() }
                    runCatching { process.destroyForcibly() }
                }
            }

            // Capped accumulation: for huge output only the beginning + end are kept, avoiding blowing up memory and model context.
            // Unbounded mode keeps the continuous beginning, up to MAX_UNBOUNDED_CHARS,
            // and stops and sets truncated once over the limit (callers such as git use this to prompt "output too large").
            val output = if (unbounded) BoundedOutput.hardCapped(MAX_UNBOUNDED_CHARS) else BoundedOutput()
            // The watchdog runs concurrently with the read loop: on timeout it destroys the process, making the blocked readLine return null immediately and exit the loop.
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
                FileLogger.w(TAG, "Command timed out (${effectiveTimeout}ms) and was terminated: ${sanitizeCommandForLog(command)}")
                output.append(timeoutNotice(effectiveTimeout))
                output.append("\n")
                ExecResult(output.build(), null, output.truncated)
            } else {
                FileLogger.v(TAG, "Command completed (exit code $exitCode, output ${output.totalChars} chars): ${sanitizeCommandForLog(command)}")
                ExecResult(output.build(), exitCode, output.truncated)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.e(TAG, "Command execution error: ${sanitizeCommandForLog(command)}", e)
            ExecResult("Error: ${e.message}", null)
        }
    }

    /**
     * Starts the watchdog: after waiting [timeoutMs], if the process is still alive, marks it timed out and terminates it gracefully then forcibly, thereby unblocking
     * the caller's blocked readLine. The returned [Job] is cancelled by the caller on normal completion.
     *
     * **Pauses while a credential dialog is in flight**: at the deadline, if [credentialPromptInFlight] > 0 (the custom git credential helper
     * is blocked waiting for the app dialog to fill in the user's credential request), the watchdog does not kill - this git command is "waiting for the user to enter credentials"
     * rather than "stuck"; force-killing it on the normal timeout would let a user who stepped away for a few minutes come back to find the push failed and have to redo it. Instead it rechecks each round:
     * if in flight, it grants a grace period (1min) and checks again, killing only when no longer in flight; the absolute limit [MAX_TIMEOUT_MS] (30min) kills even while in flight
     * as a backstop, avoiding a de facto infinite wait. The terminal PTY path does not go through the watchdog ([TerminalSessionManager] raw tty) and is naturally the most patient.
     */
    private fun launchKillWatchdog(
        scope: CoroutineScope,
        process: Process,
        timeoutMs: Long,
        timedOut: AtomicBoolean,
        command: String
    ): Job = scope.launch {
        var remaining = timeoutMs
        var totalWaited = 0L
        while (true) {
            delay(remaining)
            if (!process.isAlive) return@launch
            totalWaited += remaining
            val inflight = credentialPromptInFlight.get()
            if (inflight > 0 && totalWaited < MAX_TIMEOUT_MS) {
                // Credential dialog in flight: grant 1min grace (not exceeding the absolute limit), then recheck.
                FileLogger.i(TAG, "Credential dialog in flight (${inflight}), watchdog deferred, waiting another 60000ms: ${sanitizeCommandForLog(command)}")
                remaining = minOf(60_000L, MAX_TIMEOUT_MS - totalWaited)
                continue
            }
            // Not in flight, or the 30min absolute limit has been reached: terminate on normal timeout.
            timedOut.set(true)
            FileLogger.w(TAG, "Command execution exceeded ${timeoutMs}ms (total waited ${totalWaited}ms, inflight=${inflight}), terminating process: ${sanitizeCommandForLog(command)}")
            runCatching { process.destroy() }
            delay(TIMEOUT_KILL_GRACE_MS)
            if (process.isAlive) runCatching { process.destroyForcibly() }
            return@launch
        }
    }

    /** Timeout notice line (spliced into the output, fed back to the model / shown to the user). */
    private fun timeoutNotice(timeoutMs: Long): String =
        "[Command execution timed out: exceeded ${timeoutMs}ms and was forcibly terminated]"

    /**
     * Starts a process: if rootfs/proot are installed and ready, enters the container with PRoot; **if not ready, refuses to execute**.
     *
     * There used to be a "fall back to the Android native shell" backstop here ([buildNativeProcess],
     * `/system/bin/sh -c` + working directory set to the workspace). That was a security hazard: users chose the local container channel
     * for the "sandbox"; once the rootfs was not extracted, commands would **silently** land in the real device shell and execute,
     * with only one warning log line the whole time - appearing as "clearly using the container channel, yet the command took effect on the device".
     * The sandbox promise must not be silently downgraded, so this is now a hard failure: if not ready it throws, and the upper layer converts it into a clear error output.
     */
    private fun startContainerProcess(command: String, projectPath: String?): Process {
        val profile = currentProfile
        if (!containerInstaller.isInstalledFor(profile)) {
            val hint = notReadyHintFor(profile) ?: "Container not yet initialized"
            FileLogger.e(
                TAG,
                "Container not ready, refusing to execute (native shell fallback is disabled): ${sanitizeCommandForLog(command)}"
            )
            throw IllegalStateException(hint)
        }

        val processBuilder = buildProcessBuilder(buildProotInvocation(command, projectPath))

        // Redirect stderr to stdout so we capture everything in one stream
        processBuilder.redirectErrorStream(true)
        processBuilder.redirectInput(STDIN_FROM_DEV_NULL)
        return processBuilder.start()
    }

    /** Whether the container is installed and ready (for the current profile). */
    override fun isContainerInstalled(): Boolean = containerInstaller.isInstalledFor(currentProfile)

    /**
     * Whether the base packages have been provisioned (by the current profile's marker). On first entering the terminal, the initialization menu
     * (assets/aicode/provision.sh) guides installation, and the script writes the marker on completion; if not completed, entering the shell is not affected.
     */
    override fun isProvisioned(): Boolean = isProvisionedFor(currentProfile)

    /** Judges for the specified profile whether base package provisioning is complete ([defaultShell] chooses bash/sh based on this). */
    fun isProvisionedFor(profile: ContainerProfile): Boolean {
        val marker = provisionMarker(profile)
        return marker.exists() && marker.readText().trim() == PROVISION_VERSION
    }

    /**
     * Returns guidance text when the container is not ready (rootfs not extracted), and null when ready.
     * Command execution entry points use this to not initialize automatically, fail directly, and guide the user to the terminal page to complete initialization.
     */
    override fun notReadyHint(): String? = notReadyHintFor(currentProfile)

    /** Judges for the specified profile whether the container is ready (used by MCP to check before starting a runtime container). */
    fun notReadyHintFor(profile: ContainerProfile): String? {
        if (containerInstaller.isInstalledFor(profile)) return null
        return context.getString(R.string.container_not_ready_hint)
    }

    /**
     * The container's default command shell: prefers the profile-specified [ContainerProfile.shellPath]; when unspecified,
     * chooses `/bin/bash` (the script has installed bash) or `/bin/sh` according to the provisioning state.
     */
    override fun defaultShell(): String {
        val profile = currentProfile
        profile.shellPath?.takeIf { it.isNotBlank() }?.let { return it }
        return if (isProvisioned()) "/bin/bash" else "/bin/sh"
    }

    /**
     * Idempotently ensures the container is usable: extracts rootfs/proot (time-consuming the first time). Consistent for all containers (built-in/custom) -
     * base tool installation is not performed automatically at this stage; the initialization menu on entering the terminal (assets/aicode/provision.sh)
     * guides the user's choice, and a package installation failure does not block entering the shell.
     * Called only by the terminal page ([TerminalSessionManager]) as the sole initialization entry point; command execution entry points no longer trigger it automatically.
     *
     * Time-consuming initialization (extraction) runs in the engine-level [initScope] and is not interrupted by caller cancellation (terminal page viewModelScope)
     * - after leaving the terminal page, initialization continues in the background, and the next entry can reuse the same job or wait for its completion.
     * Stage progress is reported throughout via [initProgress]. On rootfs extraction failure, sets [ContainerInitState.Failed]
     * and throws (does not silently set Ready), putting the terminal page into a failed state that can be retried.
     */
    override suspend fun ensureInstalled() {
        val profile = currentProfile
        val startedAt = System.currentTimeMillis()
        FileLogger.i(
            TAG,
            "ensureInstalled starting: profile=${profile.id}(${profile.name}) mode=${profile.mode} " +
                "installed=${containerInstaller.isInstalledFor(profile)}"
        )
        // Before each entry to the terminal page, ensure the latest built-in docs are extracted
        containerInstaller.extractDocs()
        if (containerInstaller.isInstalledFor(profile)) {
            // An old rootfs (e.g. an imported Ubuntu 26.04) will not be re-extracted, so run a backstop inspection here to repair known compatibility issues
            containerInstaller.repairRootfsCompatibility(containerInstaller.rootfsDirFor(profile))
            _initProgress.value = ContainerInitState.Ready
            refreshContainerHome()
            detectAndCacheOsIfNeeded(profile)
            FileLogger.i(TAG, "ensureInstalled completed (fast path, took ${System.currentTimeMillis() - startedAt}ms)")
            logContainerDiagnostics("ensureInstalled fast path")
            return
        }
        // Start or reuse the background initialization job; initMutex only protects job creation/reuse, the real time-consuming work runs in initScope.
        val job = initMutex.withLock {
            val existing = initJob
            if (existing == null || !existing.isActive) {
                FileLogger.i(TAG, "Creating background initialization task: profile=${profile.id}")
                initJob = initScope.launch { doInit(profile) }
            } else {
                FileLogger.i(TAG, "Reusing in-progress background initialization task: profile=${profile.id}")
            }
            initJob!!
        }
        // Exceptions thrown inside the background job are not rethrown by join, so a completion callback is attached here to record them,
        // avoiding the "log stops at installation start" with no follow-up.
        job.invokeOnCompletion { cause ->
            if (cause != null) FileLogger.e(TAG, "Background initialization task ended abnormally: profile=${profile.id}", cause)
        }
        // Wait for completion; if the caller (terminal page) is cancelled, join throws CancellationException, but the background job keeps running.
        job.join()
        if (containerInstaller.isInstalledFor(profile)) {
            _initProgress.value = ContainerInitState.Ready
            refreshContainerHome()
            detectAndCacheOsIfNeeded(profile)
            FileLogger.i(TAG, "ensureInstalled completed (took ${System.currentTimeMillis() - startedAt}ms)")
            logContainerDiagnostics("after ensureInstalled completed")
        } else {
            val reason = "Container not installed (missing rootfs/proot)"
            FileLogger.e(TAG, "ensureInstalled failed: $reason (profile=${profile.id})")
            logContainerDiagnostics("ensureInstalled failed")
            _initProgress.value = ContainerInitState.Failed(reason)
            throw IllegalStateException(reason)
        }
    }

    /**
     * Dumps the container runtime environment (rootfs / proot binary / initialization script / disk space) to the log once.
     *
     * These states are not normally printed; once a user reports "the container won't start / the terminal is blank", there would be only guesswork. Dump once proactively before and after initialization and when a terminal
     * session is silent, so the log contains locatable evidence. Also include the tail of provision.sh's own execution log
     * (written by the script to /root/.aicode/provision.log), to judge whether the initialization menu actually ran.
     */
    fun logContainerDiagnostics(reason: String) {
        runCatching {
            val profile = currentProfile
            val rootfs = containerInstaller.rootfsDirFor(profile)
            val marker = provisionMarker(profile)
            val provision = java.io.File(containerInstaller.aicodeDir, "provision.sh")
            fun desc(f: java.io.File?): String = when {
                f == null -> "null"
                !f.exists() -> "missing(${f.absolutePath})"
                f.isDirectory -> "directory(${f.absolutePath})"
                else -> "${f.absolutePath}[${f.length()}B, executable=${f.canExecute()}]"
            }
            val space = runCatching { rootfs.usableSpace / (1024 * 1024) }.getOrDefault(-1L)
            val ptmx = java.io.File("/dev/ptmx")
            FileLogger.i(
                TAG,
                "Container diagnostics ($reason): profile=${profile.id} mode=${profile.mode} rootfs=${desc(rootfs)} " +
                    "marker=${if (marker.isFile) marker.readText().trim() else "none"} " +
                    "proot=${desc(containerInstaller.prootBin)} loader=${desc(containerInstaller.prootLoader)} " +
                    "loader32=${desc(containerInstaller.prootLoader32)} provision.sh=${desc(provision)} " +
                    "nativeLibDir=${containerInstaller.prootBin.parentFile?.absolutePath} available space=${space}MB " +
                    "/dev/ptmx(readable=${ptmx.canRead()}, writable=${ptmx.canWrite()})"
            )
            val provisionLog = java.io.File(containerInstaller.aicodeDir, "provision.log")
            if (provisionLog.isFile) {
                val tail = runCatching { provisionLog.readLines().takeLast(20).joinToString(" | ") }.getOrDefault("")
                FileLogger.i(TAG, "provision log tail ($reason): $tail")
            } else {
                FileLogger.i(TAG, "provision.log does not exist (initialization script never ran): ${provisionLog.absolutePath}")
            }
        }.onFailure { FileLogger.w(TAG, "Failed to output container diagnostics", it) }
    }

    /** Actually performs the one-time initialization in [initScope]: extracts the rootfs (base tools are installed via the initialization menu guide on entering the terminal). */
    private suspend fun doInit(profile: ContainerProfile) {
        // installRootfsIfNeed calls back to update progress when actually extracting/deploying (the fast path when already installed does not call back)
        containerInstaller.installRootfsIfNeed(profile) { _initProgress.value = it }
    }

    /** Looks up $HOME inside the container and caches it in [com.aicode.feature.workspace.domain.PathHomeResolver], for tools to expand ~. */
    private suspend fun refreshContainerHome() {
        runCatching {
            val result = execCaptured("echo \$HOME", projectPath = null, timeoutMs = 3000)
            val home = result.output.trim().ifEmpty { null }
            if (home != null) pathHomeResolver.containerHome = home
        }.onFailure { FileLogger.w(TAG, "Failed to look up container \$HOME", it) }
    }

    /**
     * After the container's first run, identifies the OS type (reads the ID field of /etc/os-release) and writes it to the cache;
     * skips if a cache already exists or identification fails, and retries on the next run. Remote SSH has no local rootfs, so it is not detected.
     */
    private suspend fun detectAndCacheOsIfNeeded(profile: ContainerProfile) {
        if (profile.mode == ExecutionMode.REMOTE_SSH) return
        if (containerOsDetector.cachedOs(profile.id) != null) return
        runCatching {
            val result = execCaptured(
                "if [ -f /etc/os-release ]; then . /etc/os-release 2>/dev/null; echo \"\$ID\"; fi",
                projectPath = null,
                timeoutMs = 3000
            )
            val osId = result.output.trim().lowercase().ifBlank { null }
            if (osId != null && result.exitCode == 0) {
                containerOsDetector.cacheOs(profile.id, osId)
                FileLogger.i(TAG, "Container OS identified: ${profile.id} -> $osId")
            }
        }.onFailure { FileLogger.w(TAG, "Container OS identification failed: ${profile.id}", it) }
    }

    /**
     * Executes a command in the container as a long-lived process (e.g. `sshd -D`). The container must be installed before calling.
     * Unlike [runCommandStream], this does not read/consume the output stream; the caller decides how to handle it (usually discards it).
     */
    fun startProotProcess(command: String, projectPath: String?): Process {
        val pb = buildProcessBuilder(buildProotInvocation(command, projectPath))
        pb.redirectErrorStream(true)
        pb.redirectInput(STDIN_FROM_DEV_NULL)
        return pb.start()
    }

    /**
     * Starts a program in the container as a long-lived process while keeping **separate** stdin/stdout/stderr, for the caller to
     * communicate with bidirectional streaming (e.g. an MCP stdio server: writes JSON-RPC to stdin, reads JSON-RPC from stdout).
     *
     * The key difference from [startProotProcess]: does **not** redirectErrorStream; stderr is kept separate,
     * guaranteeing stdout is a clean protocol stream (the server's logs go to stderr and will not pollute JSON-RPC).
     *
     * [program] is the in-container executable name/path (e.g. `npx`), [programArgs] are its arguments; both are passed through item by item via
     * `/bin/sh -c 'exec "$0" "$@"'`, avoiding shell quoting/escaping problems. [extraEnv] is overlaid onto
     * the container's default environment (overriding same-name entries). The container must be installed before calling ([ensureInstalled]).
     */
    fun startStdioProcess(
        program: String,
        programArgs: List<String>,
        projectPath: String?,
        extraEnv: Map<String, String> = emptyMap(),
        profile: ContainerProfile = currentProfile
    ): Process {
        val invocation = buildStdioInvocation(program, programArgs, projectPath, extraEnv, profile)
        val pb = buildProcessBuilder(invocation)
        // Deliberately does not redirectErrorStream: stdout is left for JSON-RPC, stderr is consumed separately by the caller.
        // Likewise deliberately does not redirect stdin: stdin here is the JSON-RPC request channel; attaching /dev/null would make the server receive EOF immediately and exit.
        return pb.start()
    }

    /**
     * Builds the PRoot invocation for "directly exec a program in the container (keeping separate streams)".
     * Uses `sh -c 'exec "$0" "$@"' program arg1 arg2 ...` to hand the arguments as-is to execvp, avoiding quoting problems.
     */
    private fun buildStdioInvocation(
        program: String,
        programArgs: List<String>,
        projectPath: String?,
        extraEnv: Map<String, String>,
        profile: ContainerProfile
    ): ProotInvocation {
        val argv = buildBaseProotArgv(projectPath, profile)
        argv.add("/bin/sh")
        argv.add("-c")
        argv.add("exec \"\$0\" \"\$@\"")
        argv.add(program)
        argv.addAll(programArgs)
        return ProotInvocation(argv, buildContainerEnv(profile) + profile.env + extraEnv)
    }

    /**
     * Builds the complete PRoot invocation (argv + env) for entering the container to execute [command].
     * Exposed to terminal sessions: Termux TerminalSession needs the executable and arguments passed separately.
     */
    fun buildProotInvocation(command: String, projectPath: String?): ProotInvocation {
        val argv = buildBaseProotArgv(projectPath, currentProfile)
        // Uses [defaultShell]: once bash is installed, AI commands and terminal sessions both go through bash; during installation / on failure it falls back to /bin/sh.
        argv.add(defaultShell())
        argv.add("-c")
        argv.add(command)
        return ProotInvocation(argv, buildContainerEnv(currentProfile) + currentProfile.env)
    }

    /**
     * Builds the common prefix argv of a PRoot invocation: proot binary + rootfs + standard bindings + fake root + workspace binding,
     * but **without** the final guest command (each caller appends `/bin/sh -c ...` or the `exec` form itself).
     */
    private fun buildBaseProotArgv(projectPath: String?, profile: ContainerProfile): MutableList<String> {
        val rootfs = containerInstaller.rootfsDirFor(profile).absolutePath
        val prootBin = containerInstaller.prootBin.absolutePath
        if (!containerInstaller.prootBin.exists()) {
            // It cannot be missing unless the packaging configuration regressed: the jniLibs sourceSet or packaging.jniLibs.useLegacyPackaging
            // was lost; then nativeLibraryDir is empty and proot reports ENOENT without revealing the real cause.
            FileLogger.e(TAG, "proot is not in nativeLibraryDir: $prootBin (check jniLibs and useLegacyPackaging)")
        }
        val argv = mutableListOf(
            prootBin,
            "-r", rootfs,
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "/system",  // Bind /system so host dynamic libraries are available
            "-0"              // Fake root, needed by apk etc.
        )

        // Bind the current workspace directory to ~/workspace inside the container (i.e. /root/workspace), so commands and file tools act on the same directory
        if (projectPath != null) {
            argv.add("-b")
            argv.add("$projectPath:/root/workspace")
            argv.add("-w")
            argv.add("/root/workspace")
        }

        // Bind the AI configuration directory to /root/.aicode inside the container (read/write): it contains skills/ (instructions read by load_skill often reference
        // scripts in the skill directory, which the AI executes via execute_command as `python /root/.aicode/skills/<name>/x.py`, etc.) and
        // mcp.json (MCP configuration). The host physical directory is independent of the rootfs, so container upgrades/reinstalls do not lose user data.
        // The base interpreter python3 (3.12) and git are installed by the initialization menu on entering the terminal (provision.sh);
        // other runtimes such as node are still the responsibility of the skill / user. proot's -b requires the source path to exist, so ensure the directory is created first.
        val aicodeDir = containerInstaller.aicodeDir.apply { mkdirs() }
        argv.add("-b")
        argv.add("${aicodeDir.absolutePath}:/root/.aicode")

        // The profile's extra bindings and arguments: built-in and imported containers also inject their defaults here (see ContainerProfile.DEFAULT_PROOT_ARGS),
        // via the same path as user manual additions, guaranteeing the arguments land at the end of argv.
        for (b in profile.extraBindings) {
            argv.add("-b")
            argv.add(b)
        }
        argv.addAll(profile.extraArgs)

        return argv
    }

    /** Standard environment variables for in-container processes (proot loader / dynamic libraries / PATH / HOME, etc.). */
    private fun buildContainerEnv(profile: ContainerProfile): Map<String, String> {
        // Each container uses the /tmp under its own rootfs: the image ships that directory, but resetting/deleting another container should not affect this one,
        // and proot requires PROOT_TMP_DIR to already exist, so ensure it is created before startup (same handling as -b source paths).
        val tmpDir = containerInstaller.prootTmpDirFor(profile).apply { mkdirs() }
        return mapOf(
            // Environment variables required by Android proot
            "PROOT_TMP_DIR" to tmpDir.absolutePath, // Android has no /tmp
            // Termux proot has a separate loader, which must be pointed to with PROOT_LOADER/_32, otherwise it cannot inject into child processes and will not start.
            "PROOT_LOADER" to containerInstaller.prootLoader.absolutePath,
            "PROOT_LOADER_32" to containerInstaller.prootLoader32.absolutePath,
            // Termux proot dynamically links libtalloc.so / libandroid-shmem.so (living with proot in nativeLibraryDir),
            // so linker64 must be able to find them; libc.so/liblog.so use the system default paths (/system/lib64).
            "LD_LIBRARY_PATH" to "${containerInstaller.prootLibDir.absolutePath}:/system/lib64:/system/lib",
            // Note (statx / seccomp): old proot 5.1.0's seccomp filter table has no statx, so Node resolving module paths with statx
            // would get the untranslated ~/workspace/xxx -> ENOENT "Cannot find module". Termux proot
            // (5.1.107.x)'s seccomp filter table already includes statx, and the default seccomp mode translates correctly, so here
            // **PROOT_NO_SECCOMP is deliberately not set** - this is exactly how Termux itself uses proot; forcing full ptrace
            // (PROOT_NO_SECCOMP=1) has instead triggered a ptrace(PEEKDATA) I/O error on this device.
            // Prefix /root/.aicode/bin: the in-container aicode environment tool (see ContainerInstaller.extractEnvTool).
            "PATH" to "/root/.aicode/bin:/usr/bin:/bin:/usr/sbin:/sbin",
            "HOME" to "/root",
            // The host process environment's TMPDIR points to the App cache directory (/data/user/0/<pkg>/cache); /data is not mounted inside the container,
            // so that path does not exist - mktemp/dpkg etc. would fail for not finding a temp directory, hence it is explicitly overridden to the in-container /tmp.
            "TMPDIR" to "/tmp",
            // The git global config points to the .gitconfig in the persistent mount (/root/.aicode is bound to the host filesDir/aicode,
            // not lost across rootfs upgrades). git-credentials is in the same directory, read via credential.helper=store;
            // credential.helper is loaded by provision.sh via includeIf limited to /root/workspace/ (minimized injection).
            "GIT_CONFIG_GLOBAL" to "/root/.aicode/.gitconfig",
            // safe.directory=* disables git's dubious ownership check. A user-chosen external workspace sits on
            // sdcardfs/FUSE storage, whose synthesized file owner uid does not match the euid(0) inside proot, so git judges it a dubious owner
            // and then fatally refuses operations - appearing as `git init` succeeding (init does not check ownership) but subsequent `git rev-parse` all failing,
            // so the Git page keeps showing "not initialized". The container is a single-user sandbox with no cross-user repositories, so allowing all is safe.
            // Injected via GIT_CONFIG_COUNT rather than written into .gitconfig: it takes effect immediately for already-initialized old containers too, and does not modify user config files.
            // The Git page / terminal / agent all start via this environment, so one place covers all local git entry points.
            "GIT_CONFIG_COUNT" to "1",
            "GIT_CONFIG_KEY_0" to "safe.directory",
            "GIT_CONFIG_VALUE_0" to "*",
            "TERM" to "xterm-256color",
            "LANG" to "C.UTF-8",
            // Time zone follows the phone: the container image defaults to hardcoded UTC (/etc/localtime -> Etc/UTC); after injecting TZ,
            // date/Python/Node/Go/JVM etc. display in the phone's current time zone; newly started commands take effect after the phone's time zone changes.
            "TZ" to containerTimeZone(profile)
            // Global HTTP proxy: when enabled, injects HTTP_PROXY/HTTPS_PROXY/ALL_PROXY/NO_PROXY,
            // so in-container curl/git/npm/pip etc. all go through the proxy; when disabled or incompletely configured it is an empty map and does not affect direct connections.
        ) + com.aicode.core.net.AppProxy.proxyEnv(context)
    }

    /**
     * In-container time zone (TZ environment variable value), following the phone's current system time zone.
     *
     * glibc honours TZ and it takes precedence over /etc/localtime; after injection, all local commands in the container display in the phone's time zone.
     * Prefer the IANA name (including DST rules), but the rootfs's /usr/share/zoneinfo must actually contain that file - Alpine
     * and other slim images do not ship tzdata by default, or it is missing when the system returns a non-IANA name such as "GMT+08:00"; then it degrades to
     * a POSIX form generated from the current UTC offset (e.g. `<UTC+8>-8`), which does not handle DST but always works.
     */
    private fun containerTimeZone(profile: ContainerProfile): String {
        val timeZone = java.util.TimeZone.getDefault()
        val zoneInfoFile = java.io.File(
            containerInstaller.rootfsDirFor(profile),
            "usr/share/zoneinfo/${timeZone.id}"
        )
        if (zoneInfoFile.isFile) return timeZone.id

        // localMinutes is the local offset relative to UTC (UTC+8 zone = +480). The name is written in the intuitive direction as UTC+8,
        // but POSIX's offset sign is the opposite (the UTC+8 zone is written -8); if both had the same sign it would display as "UTC-8" and mislead people into thinking it is the UTC-8 zone.
        // The name takes only whole hours (musl's std name does not accept ':'), and the offset uses the full hh:mm so minute-level time zones are also precise.
        val localMinutes = timeZone.getOffset(System.currentTimeMillis()) / 60_000
        if (localMinutes == 0) return "UTC0"
        val absMinutes = Math.abs(localMinutes)
        val hours = absMinutes / 60
        val minutes = absMinutes % 60
        val localSign = if (localMinutes > 0) "+" else "-"
        val posixSign = if (localMinutes > 0) "-" else "+"
        val posixOffset = if (minutes == 0) "$hours" else "%d:%02d".format(hours, minutes)
        return "<UTC$localSign$hours>$posixSign$posixOffset"
    }

    private fun buildProcessBuilder(invocation: ProotInvocation): ProcessBuilder {
        val processBuilder = ProcessBuilder(invocation.argv)
        processBuilder.environment().putAll(invocation.env)
        return processBuilder
    }

    // The original buildNativeProcess (/system/bin/sh native fallback) has been deleted: the container channel must not silently degrade to the real device shell;
    // see the comment on startContainerProcess. If real-device execution is needed, explicitly go through DeviceRootEngine (DEVICE_ROOT channel).
}
