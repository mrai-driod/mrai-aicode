package com.aicode.feature.workspace.presentation

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.workspace.domain.model.Workspace
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class WorkspaceViewModel @Inject constructor(
    private val repository: WorkspaceRepository,
    private val executionModeHolder: ExecutionModeHolder
) : ViewModel() {

    val workspaces: StateFlow<List<Workspace>> = repository.workspaces
    val current: StateFlow<Workspace?> = repository.current

    /** Local execution mode (not remote SSH): external local directory workspaces can only be added in local mode. */
    val isLocalMode: StateFlow<Boolean> = executionModeHolder.mode
        .map { it != ExecutionMode.REMOTE_SSH }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /** Remote workspace initialization failure hint (root path / default workspace creation failed). */
    val initError: StateFlow<String?> = repository.initError

    /** Failure hint for adding an external local workspace (directory not writable / cannot be resolved, etc.). */
    val addError: StateFlow<String?> = repository.addError
    val externalWarningDismissed: StateFlow<Boolean> = repository.externalWarningDismissed
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Whether removing an external local workspace also deletes its chat history (used in the delete confirmation text). */
    val deleteExternalWorkspaceSessions: StateFlow<Boolean> = repository.deleteExternalWorkspaceSessionsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Consumes the initialization error hint, to avoid showing the Toast repeatedly. */
    fun consumeInitError() {
        repository.consumeInitError()
    }

    /** Consumes the add-external-workspace failure hint, to avoid showing the Toast repeatedly. */
    fun consumeAddError() {
        repository.consumeAddError()
    }

    init {
        viewModelScope.launch { runCatching { repository.initialize() } }
        // Reload the workspace list after a mode switch (local File.listFiles ↔ remote SFTP ls).
        // drop(1) skips the first emission (init already called initialize); only respond to later switches.
        viewModelScope.launch {
            executionModeHolder.mode.drop(1).distinctUntilChanged().collect {
                runCatching { repository.initialize() }
            }
        }
    }

    fun selectWorkspace(name: String) = viewModelScope.launch {
        runCatching { repository.selectWorkspace(name) }
    }

    fun createWorkspace(name: String, onResult: (Workspace?) -> Unit = {}) = viewModelScope.launch {
        val ws = runCatching { repository.createWorkspace(name) }.getOrNull()
        onResult(ws)
    }

    /** Refreshes external workspace availability before opening the panel (so the list and current selection update promptly after storage is inserted/removed). */
    fun refreshAvailability() = viewModelScope.launch {
        runCatching { repository.refreshAvailability() }
    }

    fun setExternalWarningDismissed(dismissed: Boolean) = viewModelScope.launch {
        runCatching { repository.setExternalWarningDismissed(dismissed) }
    }

    /** Registers a device directory chosen by the user as an external local workspace, without switching automatically; switching goes through the caller's confirmation flow. */
    fun addExternalWorkspace(uri: Uri, onResult: (Workspace?) -> Unit = {}) = viewModelScope.launch {
        val ws = runCatching { repository.addExternalWorkspace(uri) }.getOrNull()
        onResult(ws)
    }

    fun deleteWorkspace(name: String) = viewModelScope.launch {
        runCatching { repository.deleteWorkspace(name) }
    }
}
