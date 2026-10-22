package com.aicode.feature.terminal.domain

import android.content.Context
import android.content.Intent
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.LinuxContainerEngine
import com.aicode.feature.agent.domain.device.DeviceRootEngine
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.terminal.presentation.component.AppTerminalSessionClient
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.workspace.domain.PathHomeResolver
import com.termux.terminal.TerminalSession
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-process resident terminal session pool: the sole owner of all [TerminalSession]s.
 *
 * It lives in a [Singleton] rather than a ViewModel: the terminal must stay resident in the background, and leaving the terminal page / switching to the chat page must not kill sessions.
 * A ViewModel is bound to a navigation route and its onCleared fires when popped, which would also kill proot via finishIfRunning -- so session
 * ownership is lifted to this manager, and the ViewModel degrades to a read-only observation layer. As long as the app process is alive, sessions keep running.
 *
 * Each tab has a stable, AI-friendly unique id (`term-N`). With an id, the AI can:
 *  - [startBackgroundCommand] run something like `npm run dev` in the background and get an id;
 *  - [sendInput] keep sending commands by id;
 *  - [writeBytesToTab] send control characters by id (e.g. Ctrl-C=0x03);
 *  - [closeTab] close and destroy a session by id;
 *  - [getTabOutput] read terminal content by id (emulator screen buffer).
 *
 * All mutable state is read and written on the main thread (UI events, AI workflow calls dispatched to the main thread), with no extra locking.
 */
@Singleton
class TerminalSessionManager @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val containerEngine: LinuxContainerEngine,
    private val workspaceRepository: WorkspaceRepository,
    private val modeHolder: ExecutionModeHolder,
    private val deviceRootEngine: DeviceRootEngine,
    private val pathHomeResolver: PathHomeResolver
) : TerminalSessionProvider {
    private companion object {
        const val TAG = "TerminalSessionManager"
        const val TRANSCRIPT_ROWS = 2000
        // Default terminal size used to start a session in place when no view is attached; resized to the real size once a view attaches.
        const val DEFAULT_COLUMNS = 80
        const val DEFAULT_ROWS = 24
        /** Grace period (ms) to wait for the normal onFinished callback after a command prints `[command exited: N]`. */
        const val EXIT_MARKER_GRACE_MS = 1_500L
        /** Polling interval (ms) for the fallback completion monitor scanning the screen buffer. The old 200ms value built a 2000-row transcript string every round,
         *  which kept consuming CPU during long commands (builds/installs); widening to 1s plus skipping the scan when output has not grown makes the overhead negligible. */
        const val EXIT_MARKER_POLL_MS = 1_000L
        /** Locator prefix for matching the command exit marker `[command exited: N]` (used with manual exit-code parsing to avoid a full regex scan). */
        const val EXIT_MARKER_PREFIX = "[command exited: "
        /** How long after an interactive tab is created with still no output before dumping container diagnostics (proactive forensics for when proot fails to start / gets stuck). */
        const val SILENT_DIAGNOSTIC_DELAY_MS = 10_000L
        /** Candidate paths for the su binary in real-device ROOT mode (following common Magisk/KernelSU/SuperSU layouts). */
        val SU_CANDIDATES = listOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su",
            "/su/bin/su", "/data/adb/ksu/bin/su", "/system/bin/failsafe/su"
        )
    }

    /** Whether the current channel is real-device ROOT (PTY started via `su` rather than proot). */
    private fun isDevice(): Boolean = modeHolder.currentMode() == ExecutionMode.DEVICE_ROOT

    private val _tabs = MutableStateFlow<List<TerminalTab>>(emptyList())
    val tabs: StateFlow<List<TerminalTab>> = _tabs.asStateFlow()

    private val _activeTabId = MutableStateFlow<String?>(null)
    val activeTabId: StateFlow<String?> = _activeTabId.asStateFlow()

    /** Incremented whenever any tab's output/state changes, so Compose recomposition can pull the latest screen content. */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    private val _tabFinishedEvents = MutableSharedFlow<TabFinishedEvent>(extraBufferCapacity = 16)
    override val tabFinishedEvents: SharedFlow<TabFinishedEvent> = _tabFinishedEvents.asSharedFlow()

    private val idCounter = AtomicInteger(0)

    private val monitorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val activeTab: TerminalTab? get() = _tabs.value.firstOrNull { it.id == _activeTabId.value }

    fun tab(id: String): TerminalTab? = _tabs.value.firstOrNull { it.id == id }

    /** Whether any background terminal tab is still running (used to decide whether KeepaliveService can stop). */
    fun hasBackgroundTabs(): Boolean =
        _tabs.value.any { it.isBackground && it.runState is RunState.Running }

    /** Called when the terminal page is entered: creates an interactive shell if there are no tabs. Idempotent. */
    suspend fun ensureInitialTab() {
        if (_tabs.value.isEmpty()) {
            createInteractiveTab()
        } else if (_activeTabId.value == null) {
            _activeTabId.value = _tabs.value.first().id
        }
    }

    /**
     * Creates a new interactive shell tab and makes it current. Returns the new tab id.
     *
     * The first call triggers rootfs/proot extraction (idempotent); failures throw and are handled by the caller.
     */
    suspend fun createInteractiveTab(): String = createTab()

    private suspend fun createTab(): String {
        FileLogger.i(TAG, "Creating interactive terminal tab: preparing execution environment (device=${isDevice()})")
        ensureContainer()
        val id = nextId()
        val shellCommand = buildInteractiveCommand()
        FileLogger.i(TAG, "Interactive shell command ($id): $shellCommand")
        val (session, client) = try {
            buildSession(shellCommand)
        } catch (e: Exception) {
            FileLogger.e(TAG, "Failed to create interactive terminal session ($id)", e)
            if (isDevice()) {
                FileLogger.e(TAG, "Device mode su probe=${deviceRootEngine.reprobeRoot()}, if ROOT is unavailable please check in Settings")
            } else {
                containerEngine.logContainerDiagnostics("Failed to create interactive terminal session")
            }
            throw e
        }
        addTab(
            TerminalTab(
                id = id,
                title = id,
                session = session,
                isBackground = false,
                command = null,
                client = client,
                runState = RunState.Running
            )
        )
        _activeTabId.value = id
        FileLogger.i(TAG, "Created interactive terminal tab $id: pid=${session.pid} running=${session.isRunning}")
        scheduleSilentSessionDiagnostic(id)
        return id
    }

    /**
     * Interactive shell startup command. -w already sets cwd to /root/workspace, so cd is only a fallback; a bare sh/bash on a tty enters interactive mode automatically,
     * loads the login environment via ENV=/etc/profile; exec lets the shell replace the outer sh -c as the foreground interactive shell.
     * On startup it runs the first-time setup menu (the script exits instantly if already completed/skipped); `;` separators ensure any script failure does not block entering the shell.
     *
     * Real-device ROOT mode: cwd is set by buildSession to the real workspace; HOME points to the app-private filesDir
     * (where the `workspace`/`.aicode` symlinks are maintained by [DeviceRootEngine.refreshWorkspaceLinks]);
     * PS1 uses the mksh parameter-expansion form (`$PWD`), and exec /system/bin/sh finishes by entering interactive mode.
     */
    private fun buildInteractiveCommand(): String {
        if (isDevice()) {
            val ws = workspaceRepository.currentPath()
            val home = pathHomeResolver.home()
            return "cd ${DeviceRootEngine.shellQuote(ws)} 2>/dev/null; " +
                "export HOME=${DeviceRootEngine.shellQuote(home)}; " +
                "export PS1='\$PWD # '; " +
                "alias ls='ls --color=auto' 2>/dev/null; alias ll='ls -la' 2>/dev/null; " +
                "exec ${deviceRootEngine.defaultShell()}"
        }
        val startup = "[ -f /root/.aicode/provision.sh ] && sh /root/.aicode/provision.sh; "
        return "cd ~/workspace 2>/dev/null; export ENV=/etc/profile; " +
            startup +
            "export PS1='\\[\\033[01;32m\\]\\u@\\h\\[\\033[00m\\]:\\[\\033[01;34m\\]\\w\\[\\033[00m\\]\\$ '; " +
            "alias ls='ls --color=auto' 2>/dev/null; alias grep='grep --color=auto' 2>/dev/null; alias ll='ls -la --color=auto' 2>/dev/null; " +
            "exec ${containerEngine.defaultShell()}"
    }

    /**
     * Silent session diagnostics: if an interactive tab still has no output long after creation, proot may have failed to start or be stuck in kernel state
     * (the user report of a blank, stuck terminal after extraction completes is exactly this shape). Proactively dump container state once,
     * so the logs retain locatable evidence instead of showing nothing after tab creation.
     */
    private fun scheduleSilentSessionDiagnostic(tabId: String) {
        monitorScope.launch {
            delay(SILENT_DIAGNOSTIC_DELAY_MS)
            val tab = tab(tabId) ?: return@launch
            if (tab.runState !is RunState.Running) return@launch
            if (!getTabOutput(tabId).isNullOrBlank()) return@launch
            if (isDevice()) {
                FileLogger.w(TAG, "Terminal tab $tabId still has no output ${SILENT_DIAGNOSTIC_DELAY_MS}ms after startup (device mode), su probe=${deviceRootEngine.reprobeRoot()}")
            } else {
                FileLogger.w(TAG, "Terminal tab $tabId still has no output ${SILENT_DIAGNOSTIC_DELAY_MS}ms after startup, dumping container state")
                containerEngine.logContainerDiagnostics("Terminal tab $tabId silent")
            }
        }
    }

    /**
     * Reserved interface for the AI: run a command in the background (e.g. `npm run dev`) and return a unique tabId.
     *
     * After the command finishes, `exec /bin/sh` keeps it alive so the tab remains a session that can keep accepting input (reusable after a dev server exits),
     * and output stays in the emulator buffer the whole time, so the user switching over or the AI using [getTabOutput] can both see the accumulated output.
     */
    override suspend fun startBackgroundCommand(
        command: String,
        title: String?,
        notify: Boolean,
        sourceSessionId: String?
    ): String {
        ensureContainer()
        val id = nextId()
        // Capture the command's real exit code with `ec=$?` before echo, otherwise echo itself is always 0 and would overwrite the process exit code, causing
        // the exitStatus in the onFinished callback to always be 0 and not match the code echoed on screen.
        // notify=true: after echo, `exit $ec` ends the shell with the command's real exit code -> proot passes it through -> the callback exitStatus
        //   is correct, and the shell exiting naturally reliably triggers MSG_PROCESS_EXITED; otherwise the script ends with echo, the process exit code is
        //   polluted to 0, and in some cases an unclean process exit can also prevent the callback from firing.
        // notify=false: after echo, `exec ${shell}` keeps it alive so the tab is reusable (can keep accepting input after a dev server exits);
        //   the process does not exit and does not call back, so the exit code is meaningless, which matches the dev-server scenario design.
        val afterCommand = if (notify) "; exit \$ec" else "; exec ${if (isDevice()) deviceRootEngine.defaultShell() else containerEngine.defaultShell()}"
        val shellCommand = if (isDevice()) {
            "cd ${DeviceRootEngine.shellQuote(workspaceRepository.currentPath())} 2>/dev/null; " +
                "export HOME=${DeviceRootEngine.shellQuote(pathHomeResolver.home())}; " +
                "$command; ec=\$?; echo \"[command exited: \$ec]\"$afterCommand"
        } else {
            "cd ~/workspace 2>/dev/null; export ENV=/etc/profile; " +
                "$command; ec=\$?; echo \"[command exited: \$ec]\"$afterCommand"
        }
        val (session, client) = buildSession(shellCommand)
        addTab(
            TerminalTab(
                id = id,
                title = title ?: id,
                session = session,
                isBackground = true,
                command = command,
                notifyOnExit = notify,
                sourceSessionId = sourceSessionId,
                client = client,
                runState = RunState.Running
            )
        )
        // A background command does not steal focus from the current tab: it only becomes current when there is no active tab.
        if (_activeTabId.value == null) _activeTabId.value = id

        if (notify) monitorBackgroundExit(id)

        startKeepaliveService()
        FileLogger.i(TAG, "Background command tab $id: $command")
        return id
    }

    private fun runningTab(id: String): TerminalTab? {
        val t = tab(id) ?: return null
        return if (t.runState is RunState.Running) t else null
    }

    /** Sends input to a tab by id followed by Enter to execute it (entry point for the AI to keep sending commands). Returns whether the tab was hit and is still active. */
    override fun sendInput(id: String, input: String, appendNewline: Boolean): Boolean {
        val tab = runningTab(id) ?: return false
        val text = if (appendNewline && !input.endsWith("\n")) input + "\n" else input
        writeToSession(tab.session, text)
        return true
    }

    /** Writes raw text to a tab by id, without automatically appending a newline. */
    override fun writeToTab(id: String, text: String): Boolean {
        val tab = runningTab(id) ?: return false
        writeToSession(tab.session, text)
        return true
    }

    /** Writes raw bytes to a tab by id (control characters, e.g. Ctrl-C=0x03). */
    override fun writeBytesToTab(id: String, vararg bytes: Int): Boolean {
        val tab = runningTab(id) ?: return false
        val arr = ByteArray(bytes.size) { bytes[it].toByte() }
        tab.session.write(arr, 0, arr.size)
        return true
    }

    /** Writes text to the current active tab (extra key row: arrow keys/Tab, etc.). */
    fun writeToActive(text: String) {
        activeTab?.let { writeToSession(it.session, text) }
    }

    /** Writes raw bytes to the current active tab (control characters, e.g. Ctrl-C=0x03). */
    fun writeBytesToActive(vararg bytes: Int) {
        val tab = activeTab ?: return
        val arr = ByteArray(bytes.size) { bytes[it].toByte() }
        tab.session.write(arr, 0, arr.size)
    }

    /**
     * Reads terminal content by id (the full transcript of the emulator screen buffer) for the AI to pull.
     * Returns null if there is no such tab.
     */
    override fun getTabOutput(id: String): String? {
        val tab = tab(id) ?: return null
        return runCatching {
            tab.session.emulator?.screen?.transcriptText?.trimEnd('\n')
        }.getOrNull() ?: ""
    }

    /** Lists a summary of all tabs (id/title/is-background/run state/command) for the AI to pick a target. */
    override fun listTabs(): List<TabInfo> = _tabs.value.map {
        TabInfo(
            id = it.id,
            title = it.title,
            isBackground = it.isBackground,
            running = it.runState is RunState.Running,
            command = it.command
        )
    }

    /** Switches the current tab. */
    fun activate(id: String) {
        if (_tabs.value.any { it.id == id }) _activeTabId.value = id
    }

    /** Closes and destroys a tab (user-initiated close / AI close). Removes it from the list and kills the session. */
    override fun closeTab(id: String): Boolean {
        val tab = tab(id) ?: return false
        runCatching { tab.session.finishIfRunning() }
        tab.view = null
        val remaining = _tabs.value.filterNot { it.id == id }
        _tabs.value = remaining
        if (_activeTabId.value == id) {
            _activeTabId.value = remaining.lastOrNull()?.id
        }
        bumpRevision()

        if (tab.isBackground) maybeStopKeepalive(tab)
        FileLogger.i(TAG, "Closed terminal tab $id")
        return true
    }

    fun rename(id: String, title: String) {
        tab(id)?.let {
            it.title = title
            bumpRevision()
        }
    }

    private suspend fun ensureContainer() {
        val startedAt = System.currentTimeMillis()
        if (isDevice()) {
            // The device channel has no container concept: readiness is a single su probe (ensureInstalled throws if it fails)
            deviceRootEngine.ensureInstalled()
            deviceRootEngine.refreshWorkspaceLinks()
            FileLogger.i(TAG, "Device ROOT ready (took ${System.currentTimeMillis() - startedAt}ms)")
            return
        }
        containerEngine.ensureInstalled()
        if (!containerEngine.isContainerInstalled()) {
            throw IllegalStateException("Container not installed (missing rootfs/proot)")
        }
        FileLogger.i(TAG, "Container ready (took ${System.currentTimeMillis() - startedAt}ms)")
    }

    private fun nextId(): String = "term-${idCounter.incrementAndGet()}"

    /**
     * Fallback completion monitor: a termux session ends when the PTY reads EOF, but the proot host process on Android may
     * intermittently fail to exit (even after its bash has run exit, proot still holds /dev/pts open), so onFinished
     * never fires and notify=true tasks never trigger a notification. bash always prints `[command exited: N]` before truly exiting,
     * so use that as the reliable signal that a command ended: once detected, wait a short grace period (to give the normal callback time), and force completion if still Running.
     */
    /** Locates the exit marker `[command exited: N]` in the trailing lines of the output text and parses the exit code; returns null if not found.
     *  The exit marker must appear as its own line (preceded by a newline or at the start of the text) and is located from the end backwards, to avoid false positives from PTY command-line echo. */
    private fun extractExitCode(output: String): Int? {
        val tail = output.takeLast(1000)
        val idx = tail.lastIndexOf(EXIT_MARKER_PREFIX)
        if (idx < 0) return null
        if (idx > 0 && tail[idx - 1] != '\n' && tail[idx - 1] != '\r') return null
        var end = idx + EXIT_MARKER_PREFIX.length
        if (end >= tail.length || !tail[end].isDigit()) return null
        var code = 0
        while (end < tail.length && tail[end].isDigit()) {
            code = code * 10 + (tail[end] - '0')
            end++
        }
        return if (end < tail.length && tail[end] == ']') code else null
    }

    private fun monitorBackgroundExit(tabId: String) {
        monitorScope.launch {
            var seenMarker = false
            var lastOutputLen = -1
            while (true) {
                val tab = tab(tabId) ?: return@launch
                if (tab.runState !is RunState.Running) return@launch
                val output = getTabOutput(tabId) ?: return@launch
                if (!seenMarker) {
                    // Skip the scan when output has not grown: only compare lengths, avoiding a full exit-marker search every round.
                    if (output.length != lastOutputLen) {
                        lastOutputLen = output.length
                        if (extractExitCode(output) != null) seenMarker = true
                    }
                } else {
                    delay(EXIT_MARKER_GRACE_MS)
                    // Check again after the grace period: if the normal onFinished callback already fired, the state is no longer Running, so exit here.
                    val current = tab(tabId) ?: return@launch
                    if (current.runState is RunState.Running) {
                        val exitCode = extractExitCode(getTabOutput(tabId) ?: "") ?: 0
                        current.runState = RunState.Finished(exitCode)
                        bumpRevision()
                        FileLogger.i(TAG, "Fallback: tab $tabId exit marker detected, forcing completion exit=$exitCode")
                        if (current.isBackground) maybeStopKeepalive(current)
                        if (current.notifyOnExit && !current.finishedNotified) {
                            current.finishedNotified = true
                            _tabFinishedEvents.tryEmit(
                                TabFinishedEvent(
                                    current.id, current.title, current.command, exitCode, current.sourceSessionId,
                                    tailOutput = getTabOutput(current.id)?.takeTailLines(TAIL_LINES)
                                )
                            )
                        }
                    }
                    return@launch
                }
                delay(EXIT_MARKER_POLL_MS)
            }
        }
    }

    private fun addTab(tab: TerminalTab) {
        _tabs.value = _tabs.value + tab
        bumpRevision()
    }

    private fun writeToSession(session: TerminalSession, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        session.write(bytes, 0, bytes.size)
    }

    private fun bumpRevision() {
        _revision.value = _revision.value + 1
    }

    /**
     * Builds a PTY session (container proot / device su) and wires up the output/finish callbacks.
     *
     * The client's viewProvider/onFinished both look up [_tabs] keyed by session: sessions and tabs correspond one-to-one,
     * so there is no need to inject the tab reference into the client up front (avoiding the chicken-and-egg problem of the tab not existing yet when the client is constructed).
     */
    private fun buildSession(shellCommand: String): Pair<TerminalSession, AppTerminalSessionClient> {
        val workspace = workspaceRepository.currentPath()
        // Executable / argv / environment / cwd branch by channel; argv[0] must match executable
        // (TerminalSession's cmd is only used for the execvp lookup; the real argv is formed verbatim from args).
        val executable: String
        val argv: Array<String>
        val env: Array<String>
        val cwd: String
        if (isDevice()) {
            executable = resolveSuExecutable()
            argv = arrayOf(executable, "-c", shellCommand)
            // The TerminalSession environment array does not inherit the parent process, so merge explicitly (same semantics as proot's ptyEnvArray);
            // su resets HOME; shellCommand already exports a fix, so the HOME here is only a fallback.
            env = (System.getenv() + mapOf(
                "HOME" to pathHomeResolver.home(),
                "TERM" to "xterm-256color"
            )).map { "${it.key}=${it.value}" }.toTypedArray()
            cwd = if (java.io.File(workspace).isDirectory) workspace else appContext.filesDir.absolutePath
            FileLogger.i(TAG, "PTY started (device): workspace=$workspace su=$executable")
        } else {
            val invocation = containerEngine.buildProotInvocation(shellCommand, workspace)
            executable = invocation.executable
            argv = invocation.argv.toTypedArray()
            env = invocation.ptyEnvArray
            cwd = appContext.filesDir.absolutePath
            FileLogger.i(
                TAG,
                "PTY started: workspace=$workspace executable=$executable" +
                    " (exists=${java.io.File(executable).exists()}) argv=${argv.joinToString(" ")}"
            )
            FileLogger.d(TAG, "PTY environment variable keys: ${invocation.env.keys.joinToString(",")}")
        }
        lateinit var session: TerminalSession
        val client = AppTerminalSessionClient(
            context = appContext,
            viewProvider = { _tabs.value.firstOrNull { it.session === session }?.view },
            onFinished = { finished ->
                _tabs.value.firstOrNull { it.session === finished }?.let { target ->
                    target.runState = RunState.Finished(finished.exitStatus)
                    bumpRevision()
                    FileLogger.i(TAG, "Terminal tab ${target.id} session finished exit=${finished.exitStatus}")
                    if (target.isBackground) maybeStopKeepalive(target)
                    if (target.notifyOnExit && !target.finishedNotified) {
                        target.finishedNotified = true
                        _tabFinishedEvents.tryEmit(
                            TabFinishedEvent(
                                target.id, target.title, target.command, finished.exitStatus, target.sourceSessionId,
                                tailOutput = getTabOutput(target.id)?.takeTailLines(TAIL_LINES)
                            )
                        )
                    }
                }
            }
        )
        session = TerminalSession(executable, cwd, argv, env, TRANSCRIPT_ROWS, client)
        session.updateSize(DEFAULT_COLUMNS, DEFAULT_ROWS)
        FileLogger.i(TAG, "PTY session created: pid=${session.pid} running=${session.isRunning}")
        return session to client
    }

    /** Resolves the su executable path: probes common layouts, falling back to a bare "su" (relying on execvp to search PATH) if not found. */
    private fun resolveSuExecutable(): String =
        SU_CANDIDATES.firstOrNull { java.io.File(it).exists() } ?: "su"

    private fun startKeepaliveService() {
        TerminalKeepaliveService.startSession(appContext)
        FileLogger.i(TAG, "Background keepalive Service started")
    }

    private fun stopKeepaliveService() {
        TerminalKeepaliveService.stopSession(appContext)
        FileLogger.i(TAG, "Background keepalive Service stopped")
    }

    private fun maybeStopKeepalive(closingTab: TerminalTab) {
        if (closingTab.isBackground && _tabs.value.none { it.isBackground && it.runState is RunState.Running }) {
            stopKeepaliveService()
        }
    }
}
