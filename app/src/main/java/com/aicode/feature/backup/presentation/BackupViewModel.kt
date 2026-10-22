package com.aicode.feature.backup.presentation

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.feature.backup.domain.BackupDecryptionException
import com.aicode.feature.backup.domain.BackupManager
import com.aicode.feature.backup.domain.BackupOptions
import com.aicode.feature.backup.domain.RestoreStats
import com.aicode.feature.backup.domain.WorkspaceBackupMeta
import com.aicode.core.util.FileLogger
import dagger.hilt.android.lifecycle.HiltViewModel
import com.aicode.R
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream
import javax.inject.Inject

sealed class BackupState {
    data object Idle : BackupState()
    data object Working : BackupState()
    data object ExportDone : BackupState()
    data class ImportSuccess(val stats: RestoreStats) : BackupState()
    /** Import preview complete and the backup contains workspace data: waiting for the user to select the workspaces to restore (uri and password are held temporarily). */
    data class WorkspaceSelection(
        val workspaces: List<WorkspaceBackupMeta>,
        val uri: Uri,
        val password: String
    ) : BackupState()
    data class Error(val message: String) : BackupState()
}

@HiltViewModel
class BackupViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val backupManager: BackupManager
) : ViewModel() {

    private val _state = MutableStateFlow<BackupState>(BackupState.Idle)
    val state: StateFlow<BackupState> = _state.asStateFlow()

    private val prefs = context.getSharedPreferences("backup_options", Context.MODE_PRIVATE)

    private val _exportOptions = MutableStateFlow(loadExportOptions())
    val exportOptions: StateFlow<BackupOptions> = _exportOptions.asStateFlow()

    /** Export data scope: selections are persisted immediately and reused the next time the page is opened. */
    fun updateExportOptions(options: BackupOptions) {
        _exportOptions.value = options
        prefs.edit()
            .putBoolean(KEY_PROVIDERS, options.providers)
            .putBoolean(KEY_REMOTE_CONNECTIONS, options.remoteConnections)
            .putBoolean(KEY_CHAT_HISTORY, options.chatHistory)
            .putBoolean(KEY_MCP_SERVERS, options.mcpServers)
            .putBoolean(KEY_PERMISSION_RULES, options.permissionRules)
            .putBoolean(KEY_APP_SETTINGS, options.appSettings)
            .putBoolean(KEY_WORKSPACE_FILES, options.workspaceFiles)
            .apply()
    }

    private fun loadExportOptions(): BackupOptions = BackupOptions(
        providers = prefs.getBoolean(KEY_PROVIDERS, true),
        remoteConnections = prefs.getBoolean(KEY_REMOTE_CONNECTIONS, true),
        chatHistory = prefs.getBoolean(KEY_CHAT_HISTORY, true),
        mcpServers = prefs.getBoolean(KEY_MCP_SERVERS, true),
        permissionRules = prefs.getBoolean(KEY_PERMISSION_RULES, true),
        appSettings = prefs.getBoolean(KEY_APP_SETTINGS, true),
        workspaceFiles = prefs.getBoolean(KEY_WORKSPACE_FILES, false)
    )

    /** Streams the export to [output] (opened by the caller, closed by this method). */
    fun export(password: String, options: BackupOptions, output: OutputStream) {
        _state.value = BackupState.Working
        viewModelScope.launch {
            val pw = password.toCharArray().takeIf { it.isNotEmpty() }
            try {
                backupManager.export(pw, options, output)
                _state.value = BackupState.ExportDone
            } catch (e: Exception) {
                _state.value = BackupState.Error(e.message ?: context.getString(R.string.backup_export_failed))
            } finally {
                runCatching { output.close() }
            }
        }
    }

    /** Streams an import from a SAF Uri: previews first; if the backup contains workspace data, goes to selection confirmation, otherwise restores in full. */
    fun import(uri: Uri, password: String) {
        _state.value = BackupState.Working
        viewModelScope.launch {
            val pw = password.toCharArray().takeIf { it.isNotEmpty() }
            FileLogger.i(TAG, "Import request: ${describeUri(uri)} (${if (pw != null) "encrypted" else "plaintext"})")
            try {
                val input = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)
                        ?: throw IllegalArgumentException(context.getString(R.string.backup_read_failed))
                }
                input.use { backupManager.previewImport(it, pw) }
                    .onSuccess { preview ->
                        if (preview.hasWorkspaceData) {
                            _state.value = BackupState.WorkspaceSelection(preview.workspaces, uri, password)
                        } else {
                            restoreFromUri(uri, pw, null)
                        }
                    }
                    .onFailure { _state.value = BackupState.Error(describeImportError(it)) }
            } catch (e: Exception) {
                _state.value = BackupState.Error(describeImportError(e))
            }
        }
    }

    /** After selection confirmation, restores using the selected workspaces (reopens the input stream to perform the actual restore). */
    fun confirmImportSelection(selected: Set<String>) {
        val current = _state.value as? BackupState.WorkspaceSelection ?: return
        _state.value = BackupState.Working
        viewModelScope.launch {
            val pw = current.password.toCharArray().takeIf { it.isNotEmpty() }
            restoreFromUri(current.uri, pw, selected)
        }
    }

    /** Cancels the workspace selection and aborts the import. */
    fun cancelImportSelection() {
        if (_state.value is BackupState.WorkspaceSelection) {
            _state.value = BackupState.Idle
        }
    }

    private suspend fun restoreFromUri(uri: Uri, pw: CharArray?, selected: Set<String>?) {
        try {
            FileLogger.i(TAG, "Starting restore: ${describeUri(uri)}${if (selected != null) ", selected workspaces=${selected.size}" else ", full"}")
            val input = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)
                    ?: throw IllegalArgumentException(context.getString(R.string.backup_read_failed))
            }
            input.use { backupManager.import(it, pw, selected) }
                .onSuccess {
                    FileLogger.i(TAG, "Restore succeeded: $it")
                    _state.value = BackupState.ImportSuccess(it)
                }
                .onFailure {
                    FileLogger.e(TAG, "Restore failed", it)
                    _state.value = BackupState.Error(describeImportError(it))
                }
        } catch (e: Exception) {
            FileLogger.e(TAG, "Restore error", e)
            _state.value = BackupState.Error(describeImportError(e))
        }
    }

    /** URI summary for logging: keeps only scheme://authority/last segment, to avoid logging the full path. */
    private fun describeUri(uri: Uri): String {
        val last = uri.lastPathSegment ?: ""
        return "${uri.scheme}://${uri.authority}/…/$last"
    }

    private fun describeImportError(e: Throwable): String = when (e) {
        is BackupDecryptionException -> e.message ?: context.getString(R.string.backup_wrong_password)
        else -> buildString {
            append(e.message ?: e::class.simpleName ?: context.getString(R.string.backup_import_failed))
            // During diagnosis, appends the cause chain (at most two levels) to help locate the real exception
            var cause = e.cause
            var depth = 0
            while (cause != null && depth < 2) {
                append("\nCaused by: ")
                append(cause::class.simpleName ?: "Unknown exception")
                append(": ")
                append(cause.message ?: "")
                cause = cause.cause
                depth++
            }
        }
    }

    fun reset() {
        _state.value = BackupState.Idle
    }

    companion object {
        private const val TAG = "Backup"
        private const val KEY_PROVIDERS = "providers"
        private const val KEY_REMOTE_CONNECTIONS = "remote_connections"
        private const val KEY_CHAT_HISTORY = "chat_history"
        private const val KEY_MCP_SERVERS = "mcp_servers"
        private const val KEY_PERMISSION_RULES = "permission_rules"
        private const val KEY_APP_SETTINGS = "app_settings"
        private const val KEY_WORKSPACE_FILES = "workspace_files"
    }
}
