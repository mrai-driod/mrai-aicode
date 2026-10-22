package com.aicode.feature.credentials.presentation

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.R
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.RemoteSshConnection
import com.aicode.feature.credentials.data.CredentialInjectSettingsRepository
import com.aicode.feature.credentials.domain.model.GitCredential
import com.aicode.feature.credentials.domain.repository.CredentialRepository
import com.aicode.feature.git.domain.GitRepository
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Credential page UI orchestration: credential CRUD + commit identity (user.name/email) configuration.
 *
 * Commit identity reads and writes go through `git config` ([GitRepository.setUserIdentity] / [getUserName] / [getUserEmail]):
 * **project level first** (current workspace ~/workspace/.git/config), falling back to global (persistently mounted /root/.aicode/.gitconfig) when there is none.
 * The UI and typing `git config user.name` in the terminal read the same identity——project level first, global as fallback, with no two competing sources.
 */
@HiltViewModel
class CredentialViewModel @Inject constructor(
    private val credentialRepository: CredentialRepository,
    private val gitRepository: GitRepository,
    private val injectSettings: CredentialInjectSettingsRepository,
    private val remoteSshConnection: RemoteSshConnection,
    private val workspaceRepository: WorkspaceRepository,
    @param:ApplicationContext private val context: Context
) : ViewModel() {

    private companion object { const val TAG = "CredentialViewModel" }

    data class UiState(
        val credentials: List<GitCredential> = emptyList(),
        /** The container git config's current user.name, used as the initial value for the edit field. */
        val userName: String = "",
        /** The container git config's current user.email, used as the initial value for the edit field. */
        val userEmail: String = "",
        /** The container `git config --global` actual user.name, echoed as the "actual git value" (same source as userName). */
        val globalUserName: String = "",
        /** The container git config's current remote.origin.url, used as the initial value for the repository URL edit field. */
        val repoUrl: String = "",
        /** In remote mode, the state of the "auto-inject credentials to remote server" switch (remembered independently per server). */
        val autoInjectEnabled: Boolean = false,
        /** In remote mode, whether this server is entered for the first time (not yet asked); the UI shows a confirmation dialog based on this. */
        val showAutoInjectPrompt: Boolean = false,
        /** Whether currently in remote mode (determines whether the switch is shown). */
        val isRemote: Boolean = false,
        val toast: String? = null
    )

    // State from non-reactive sources: identity read from git config, repository URL, injection switch, toast. credentials go through the file repository Flow.
    private val _extra = MutableStateFlow(Extra())
    private data class Extra(
        val userName: String = "",
        val userEmail: String = "",
        val globalUserName: String = "",
        val repoUrl: String = "",
        val autoInjectEnabled: Boolean = false,
        val showAutoInjectPrompt: Boolean = false,
        val isRemote: Boolean = false,
        val toast: String? = null
    )

    val state: StateFlow<UiState> = combine(credentialRepository.getAll(), _extra) { creds, extra ->
        UiState(
            credentials = creds,
            userName = extra.userName,
            userEmail = extra.userEmail,
            globalUserName = extra.globalUserName,
            repoUrl = extra.repoUrl,
            autoInjectEnabled = extra.autoInjectEnabled,
            showAutoInjectPrompt = extra.showAutoInjectPrompt,
            isRemote = extra.isRemote,
            toast = extra.toast
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, UiState())

    init {
        refreshIdentity()
        refreshAutoInjectState()
    }

    /** Read the current identity and repository URL from the container git config to refresh the UI (edit-field initial values + actual-value echo). Re-entrant; called once when entering the credentials page to pick up terminal changes. */
    fun refreshIdentity() {
        viewModelScope.launch {
            runCatching {
                _extra.update {
                    it.copy(
                        userName = gitRepository.getUserName(),
                        userEmail = gitRepository.getUserEmail(),
                        globalUserName = gitRepository.getUserName(),
                        repoUrl = gitRepository.getRepoUrl()
                    )
                }
            }.onFailure { FileLogger.w(TAG, "Failed to read git global identity: ${it.message}") }
        }
    }

    fun saveCredential(credential: GitCredential) {
        viewModelScope.launch {
            try {
                credentialRepository.save(credential)
                syncInjectionIfEnabled() // After credentials change, re-sync to remote servers where injection is enabled
                toast(context.getString(R.string.credential_toast_saved))
            } catch (e: Exception) {
                FileLogger.e(TAG, "Failed to save credential", e)
                toast(context.getString(R.string.credential_toast_save_failed, e.message))
            }
        }
    }

    fun deleteCredential(id: String) {
        viewModelScope.launch {
            credentialRepository.delete(id)
            syncInjectionIfEnabled() // After deleting a credential, remove it from the remote injection file, otherwise the remote could still use the old credential
            toast(context.getString(R.string.credential_toast_deleted))
        }
    }

    // ---------- Remote auto-injection switch ----------

    /** Identifier of the current remote server (host:port hash); null in local mode / when not connected. */
    private fun currentServerKey(): String? {
        val cfg = remoteSshConnection.config ?: return null
        return injectSettings.serverKey(cfg.host, cfg.port)
    }

    /** Refresh the switch state and "first-time ask" flag for the current server. Called when entering the credentials page / when the connection changes. */
    fun refreshAutoInjectState() {
        val key = currentServerKey()
        _extra.update {
            it.copy(
                isRemote = key != null,
                autoInjectEnabled = key?.let { k -> injectSettings.isAutoInjectEnabled(k) } ?: false,
                showAutoInjectPrompt = key?.let { k -> !injectSettings.hasAsked(k) } ?: false
            )
        }
    }

    /** User toggles the switch: persist + immediately inject / revoke. */
    fun setAutoInject(enabled: Boolean) {
        viewModelScope.launch {
            val key = currentServerKey() ?: return@launch
            injectSettings.setAutoInject(key, enabled)
            applyInjection(key, enabled)
            refreshAutoInjectState()
        }
    }

    /** Called after the user chooses in the first-time prompt dialog: mark as asked + apply the choice. */
    fun dismissAutoInjectPrompt(enabled: Boolean) {
        viewModelScope.launch {
            val key = currentServerKey() ?: return@launch
            injectSettings.markAsked(key)
            injectSettings.setAutoInject(key, enabled)
            applyInjection(key, enabled)
            refreshAutoInjectState()
        }
    }

    /** When enabled, upload the remote injection config (credentials + the gitconfig scoped by includeIf); when disabled, revoke it. */
    private suspend fun applyInjection(key: String, enabled: Boolean) {
        if (!enabled) {
            remoteSshConnection.removeGitCredentialConfig()
            return
        }
        val creds = credentialRepository.getAll().first()
        val workspaceRoot = workspaceRepository.currentPath()
            .takeIf { it.isNotBlank() && it != "/" }
            ?: remoteSshConnection.config?.remoteWorkspacePath.orEmpty()
        remoteSshConnection.uploadGitCredentialConfig(creds, workspaceRoot)
    }

    /** After credentials are added or removed, if injection is enabled for the current server, re-sync to the remote. */
    private suspend fun syncInjectionIfEnabled() {
        val key = currentServerKey() ?: return
        if (injectSettings.isAutoInjectEnabled(key)) applyInjection(key, true)
    }

    /** Save the commit identity and repository URL: run `git config` to write the source of truth for the corresponding keys + refresh the echo. The UI and command line use the same file. */
    fun saveUserIdentity(name: String, email: String, repoUrl: String) {
        viewModelScope.launch {
            try {
                gitRepository.setUserIdentity(name, email)
                gitRepository.setRepoUrl(repoUrl)
                refreshIdentity()
                toast(context.getString(R.string.credential_toast_identity_saved))
            } catch (e: Exception) {
                FileLogger.e(TAG, "Failed to save identity", e)
                toast(context.getString(R.string.credential_toast_save_failed, e.message))
            }
        }
    }

    fun consumeToast() = _extra.update { it.copy(toast = null) }

    private fun toast(msg: String) = _extra.update { it.copy(toast = msg) }
}
