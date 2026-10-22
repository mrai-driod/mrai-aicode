package com.aicode.feature.terminal.domain

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.RemoteSshConnection
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "RemoteTerminalSessionManager"
private const val TRANSCRIPT_ROWS = 2000
private const val DEFAULT_COLUMNS = 80
private const val DEFAULT_ROWS = 24

/**
 * Remote SSH terminal session manager: drives [TerminalSession] (backed by [SshShellBackend]) using an sshj shell channel,
 * sharing the same UI/tool interface as the local [TerminalSessionManager] (which forks a PTY process).
 *
 * Lifecycle, tab management, and event flows are aligned with the local version; the only difference is the backend.
 */
@Singleton
class RemoteTerminalSessionManager @Inject constructor(
    private val connection: RemoteSshConnection,
    private val modeHolder: ExecutionModeHolder,
    private val workspaceRepository: WorkspaceRepository
) : TerminalSessionProvider {

    private val _tabs = MutableStateFlow<List<TerminalTab>>(emptyList())
    val tabs: StateFlow<List<TerminalTab>> = _tabs.asStateFlow()

    private val _activeTabId = MutableStateFlow<String?>(null)
    val activeTabId: StateFlow<String?> = _activeTabId.asStateFlow()

    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    private val _tabFinishedEvents = MutableSharedFlow<TabFinishedEvent>(extraBufferCapacity = 16)
    override val tabFinishedEvents: SharedFlow<TabFinishedEvent> = _tabFinishedEvents.asSharedFlow()

    private val idCounter = AtomicInteger(0)

    val activeTab: TerminalTab? get() = _tabs.value.firstOrNull { it.id == _activeTabId.value }

    fun tab(id: String): TerminalTab? = _tabs.value.firstOrNull { it.id == id }

    /** Only usable when the current mode is REMOTE_SSH and connected. */
    private fun ensureRemote(): Boolean =
        modeHolder.currentMode() == ExecutionMode.REMOTE_SSH && connection.isConnected()

    /** Called when the terminal page is entered: creates an interactive shell if there are no tabs. Idempotent. */
    suspend fun ensureInitialTab() {
        if (_tabs.value.isEmpty()) {
            createInteractiveTab()
        } else if (_activeTabId.value == null) {
            _activeTabId.value = _tabs.value.first().id
        }
    }

    /** Creates a new interactive shell tab and makes it current. Returns the new tab id. */
    suspend fun createInteractiveTab(): String {
        if (!ensureRemote()) throw IllegalStateException("Not in remote mode or SSH not connected")
        return openShellTab(command = null, isBackground = false, notify = false, title = null, sourceSessionId = null).also { id ->
            _activeTabId.value = id
            FileLogger.i(TAG, "Created new interactive remote terminal tab $id")
        }
    }

    override suspend fun startBackgroundCommand(
        command: String,
        title: String?,
        notify: Boolean,
        sourceSessionId: String?
    ): String {
        if (!ensureRemote()) throw IllegalStateException("Not in remote mode or SSH not connected")
        val id = openShellTab(command, isBackground = true, notify = notify, title = title, sourceSessionId = sourceSessionId)
        FileLogger.i(TAG, "Background command tab $id: $command")
        return id
    }

    /**
     * Opens an SSH shell channel, allocates a PTY, constructs [TerminalSession] (backed by [SshShellBackend]),
     * and adds it to the tab list. Interactive tabs and background commands share this path, differing only in metadata.
     */
    private suspend fun openShellTab(
        command: String?,
        isBackground: Boolean,
        notify: Boolean,
        title: String?,
        sourceSessionId: String?
    ): String {
        val id = nextId()
        // sshj startSession/startShell performs network I/O and must be off the main thread, otherwise NetworkOnMainThreadException.
        // But TerminalSession's constructor calls new Handler() (bound to the current thread's Looper) and must be constructed on a thread with a Looper (the main thread),
        // so only the sshj channel setup is moved to IO; after obtaining the shell handle, return to the main thread to construct the session.
        val shell = withContext(Dispatchers.IO) {
            connection.startShellSession().also { it.allocateDefaultPTY() }.startShell()
        }
        val backend = SshShellBackend(shell)
        val termSession = TerminalSession(TRANSCRIPT_ROWS, AppRemoteSessionClient(), backend)
        termSession.updateSize(DEFAULT_COLUMNS, DEFAULT_ROWS)
        // After login the shell defaults to home, so first cd to the current workspace, consistent with the command execution path (RemoteSshEngine.buildCdCommand):
        // Prefer the ~/workspace symlink, falling back to the real workspace path on failure.
        val wsPath = workspaceRepository.currentPath()
        if (wsPath.isNotBlank() && wsPath != "/") {
            termSession.write("cd ~/workspace 2>/dev/null || cd '${wsPath.trimEnd('/')}' 2>/dev/null\n")
        }
        if (command != null) {
            val init = command + (if (notify) "" else "; exec /bin/sh")
            termSession.write(init + "\n")
        }
        val tab = TerminalTab(
            id = id,
            title = title ?: id,
            session = termSession,
            isBackground = isBackground,
            command = command,
            notifyOnExit = notify,
            sourceSessionId = sourceSessionId,
            runState = RunState.Running
        )
        addTab(tab)
        if (_activeTabId.value == null) _activeTabId.value = id
        return id
    }

    override fun sendInput(id: String, input: String, appendNewline: Boolean): Boolean {
        val tab = tab(id) ?: return false
        if (tab.runState !is RunState.Running) return false
        val text = if (appendNewline && !input.endsWith("\n")) input + "\n" else input
        val bytes = text.toByteArray(Charsets.UTF_8)
        tab.session.write(bytes, 0, bytes.size)
        return true
    }

    override fun writeToTab(id: String, text: String): Boolean {
        val tab = tab(id) ?: return false
        if (tab.runState !is RunState.Running) return false
        val bytes = text.toByteArray(Charsets.UTF_8)
        tab.session.write(bytes, 0, bytes.size)
        return true
    }

    override fun writeBytesToTab(id: String, vararg bytes: Int): Boolean {
        val tab = tab(id) ?: return false
        if (tab.runState !is RunState.Running) return false
        val arr = ByteArray(bytes.size) { bytes[it].toByte() }
        tab.session.write(arr, 0, arr.size)
        return true
    }

    override fun getTabOutput(id: String): String? {
        val tab = tab(id) ?: return null
        return runCatching {
            tab.session.emulator?.screen?.transcriptText?.trimEnd('\n')
        }.getOrNull() ?: ""
    }

    override fun listTabs(): List<TabInfo> = _tabs.value.map {
        TabInfo(
            id = it.id,
            title = it.title,
            isBackground = it.isBackground,
            running = it.runState is RunState.Running,
            command = it.command
        )
    }

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
        FileLogger.i(TAG, "Closed remote terminal tab $id")
        return true
    }

    fun activate(id: String) {
        if (_tabs.value.any { it.id == id }) _activeTabId.value = id
    }

    fun rename(id: String, title: String) {
        tab(id)?.let {
            it.title = title
            bumpRevision()
        }
    }

    /** Writes text to the current active tab (extra keys row: arrow keys/Tab, etc.). */
    fun writeToActive(text: String) {
        activeTab?.let { tab ->
            if (tab.runState !is RunState.Running) return
            val bytes = text.toByteArray(Charsets.UTF_8)
            tab.session.write(bytes, 0, bytes.size)
        }
    }

    /** Writes raw bytes to the current active tab (control characters, e.g. Ctrl-C=0x03). */
    fun writeBytesToActive(vararg bytes: Int) {
        val tab = activeTab ?: return
        if (tab.runState !is RunState.Running) return
        val arr = ByteArray(bytes.size) { bytes[it].toByte() }
        tab.session.write(arr, 0, arr.size)
    }

    private fun nextId(): String = "term-${idCounter.incrementAndGet()}"

    private fun addTab(tab: TerminalTab) {
        _tabs.value = _tabs.value + tab
        bumpRevision()
    }

    private fun bumpRevision() {
        _revision.value = _revision.value + 1
    }

    /** Implementation of [TerminalSessionClient] for remote mode, with callbacks consistent with the local one. */
    private inner class AppRemoteSessionClient : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) {
            _tabs.value.firstOrNull { it.session === changedSession }?.view?.onScreenUpdated()
        }

        override fun onTitleChanged(changedSession: TerminalSession) {}
        override fun onSessionFinished(finishedSession: TerminalSession) {
            _tabs.value.firstOrNull { it.session === finishedSession }?.let { target ->
                target.runState = RunState.Finished(0)
                bumpRevision()
                if (target.notifyOnExit) {
                    _tabFinishedEvents.tryEmit(
                        TabFinishedEvent(
                            target.id, target.title, target.command, 0, target.sourceSessionId,
                            tailOutput = getTabOutput(target.id)?.takeTailLines(TAIL_LINES)
                        )
                    )
                }
            }
        }

        override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {}
        override fun onPasteTextFromClipboard(session: TerminalSession?) {}
        override fun onBell(session: TerminalSession) {}
        override fun onColorsChanged(session: TerminalSession) {}
        override fun onTerminalCursorStateChange(state: Boolean) {}
        override fun getTerminalCursorStyle(): Int? = null
        override fun logError(tag: String?, message: String?) { FileLogger.e(tag ?: TAG, message ?: "") }
        override fun logWarn(tag: String?, message: String?) { FileLogger.w(tag ?: TAG, message ?: "") }
        override fun logInfo(tag: String?, message: String?) { FileLogger.i(tag ?: TAG, message ?: "") }
        override fun logDebug(tag: String?, message: String?) { FileLogger.d(tag ?: TAG, message ?: "") }
        override fun logVerbose(tag: String?, message: String?) { FileLogger.d(tag ?: TAG, message ?: "") }
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) { FileLogger.e(tag ?: TAG, message ?: "", e) }
        override fun logStackTrace(tag: String?, e: Exception?) { FileLogger.e(tag ?: TAG, "", e) }
    }
}
