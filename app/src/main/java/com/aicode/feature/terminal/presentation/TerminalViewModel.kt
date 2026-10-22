package com.aicode.feature.terminal.presentation

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.R
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.ContainerInitState
import com.aicode.feature.agent.domain.container.LinuxContainerEngine
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.terminal.domain.RemoteTerminalSessionManager
import com.aicode.feature.terminal.domain.TerminalSessionManager
import com.aicode.feature.terminal.presentation.component.TerminalKeyModifiers
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import com.aicode.feature.terminal.data.repository.TerminalSettings
import com.aicode.feature.terminal.data.repository.TerminalSettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Thin observation layer for the terminal page.
 *
 * Session ownership lies with [TerminalSessionManager] (Singleton); this ViewModel only forwards UI operations and exposes its state flows,
 * **holds and destroys no sessions** — this is the key to "always resident in the background": when leaving the terminal page causes this VM's onCleared,
 * sessions are still held by the manager and keep running in the background, and are reused directly next time the terminal page is opened.
 */
@HiltViewModel
class TerminalViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val localManager: TerminalSessionManager,
    private val remoteManager: RemoteTerminalSessionManager,
    private val modeHolder: ExecutionModeHolder,
    private val containerEngine: LinuxContainerEngine,
    private val terminalSettingsRepository: TerminalSettingsRepository
) : ViewModel() {

    private companion object { const val TAG = "TerminalViewModel" }

    private fun isRemote() = modeHolder.currentMode() == ExecutionMode.REMOTE_SSH

    /** Whether currently in remote SSH mode (the UI uses this to hide local-container-only entry points, such as environment tools). */
    val isRemoteMode: Boolean get() = isRemote()

    /** Overall state of the container preparation phase: only used for Loading/Error prompts before the first tab is created. */
    sealed interface PrepareState {
        data object Loading : PrepareState
        data object Ready : PrepareState
        data class Error(val message: String) : PrepareState
    }

    private val _prepareState = MutableStateFlow<PrepareState>(PrepareState.Loading)
    val prepareState: StateFlow<PrepareState> = _prepareState.asStateFlow()

    /** A new tab is being created: in SSH mode establishing a shell channel must wait for the server response, so the UI uses this to show a waiting animation and block duplicate taps. */
    private val _creatingTab = MutableStateFlow(false)
    val creatingTab: StateFlow<Boolean> = _creatingTab.asStateFlow()

    /** Real-time container initialization progress (extracting/deploying/installing packages); the Loading phase uses it to show fine-grained text. */
    val containerInit: StateFlow<ContainerInitState> = containerEngine.initProgress

    val tabs get() = if (isRemote()) remoteManager.tabs else localManager.tabs
    val activeTabId get() = if (isRemote()) remoteManager.activeTabId else localManager.activeTabId
    val revision get() = if (isRemote()) remoteManager.revision else localManager.revision

    /** Virtual modifier keys driven by the extra keys row, for TerminalView to read. */
    val modifiers = TerminalKeyModifiers()

    val terminalSettings: StateFlow<TerminalSettings> = terminalSettingsRepository.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TerminalSettings())

    fun setTheme(themeId: String) {
        viewModelScope.launch { terminalSettingsRepository.setThemeId(themeId) }
    }

    fun setFontSize(sizeSp: Int) {
        viewModelScope.launch { terminalSettingsRepository.setFontSizeSp(sizeSp) }
    }

    fun setCursorStyle(style: Int) {
        viewModelScope.launch { terminalSettingsRepository.setCursorStyle(style) }
    }

    fun setFontPath(path: String) {
        viewModelScope.launch { terminalSettingsRepository.setFontPath(path) }
    }

    init {
        prepare()
    }

    /** Enter the terminal page: ensure at least one tab exists (on first entry this extracts the container or connects SSH). */
    fun prepare() {
        viewModelScope.launch {
            _prepareState.value = PrepareState.Loading
            try {
                if (isRemote()) remoteManager.ensureInitialTab() else localManager.ensureInitialTab()
                _prepareState.value = PrepareState.Ready
            } catch (e: Exception) {
                FileLogger.e(TAG, "Terminal preparation failed", e)
                _prepareState.value = PrepareState.Error(e.message ?: context.getString(R.string.terminal_prepare_error_unknown))
            }
        }
    }

    fun newTab() {
        if (_creatingTab.value) return
        _creatingTab.value = true
        viewModelScope.launch {
            try {
                if (isRemote()) remoteManager.createInteractiveTab() else localManager.createInteractiveTab()
            } catch (e: Exception) {
                FileLogger.e(TAG, "Failed to create new tab", e)
            } finally {
                _creatingTab.value = false
            }
        }
    }

    fun activate(id: String) = if (isRemote()) remoteManager.activate(id) else localManager.activate(id)

    fun closeTab(id: String) = if (isRemote()) remoteManager.closeTab(id) else localManager.closeTab(id)

    /** Close all terminal tabs (called before switching workspaces). */
    fun closeAllTabs() {
        (if (isRemote()) remoteManager.tabs else localManager.tabs).value.map { it.id }.forEach { closeTab(it) }
    }

    /** Writes text to the current active tab (extra keys row: arrow keys/Tab, etc.). */
    fun write(text: String) = if (isRemote()) remoteManager.writeToActive(text) else localManager.writeToActive(text)

    /** Writes raw bytes to the current active tab (sends control characters, e.g. Ctrl-C=0x03, Ctrl-D=0x04). */
    fun writeBytes(vararg bytes: Int) = if (isRemote()) remoteManager.writeBytesToActive(*bytes) else localManager.writeBytesToActive(*bytes)

    // Note: deliberately do not destroy sessions in onCleared — sessions are owned by the Singleton manager and must stay resident in the background.
}
