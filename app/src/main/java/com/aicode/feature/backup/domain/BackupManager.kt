package com.aicode.feature.backup.domain

import java.io.InputStream
import java.io.OutputStream

/**
 * Backup orchestrator: collects a snapshot from each data source and packs it, or unpacks and restores it in reverse.
 *
 * Export: collects per [BackupOptions] -> streaming serialization (metadata.json + individual *.jsonl) -> tar.gz compression -> chunked AES-GCM encryption if the password is non-empty.
 * Import: streaming decryption first if the password is non-empty -> untar.gz -> validates schemaVersion -> merges and writes to each data source in batches.
 * Fully streaming throughout, so peak memory is decoupled from the total data size (only the current page batch is held).
 */
interface BackupManager {
    /**
     * Streams a generated backup into [output] (the caller is responsible for opening and closing the output stream).
     * When password is null or empty, no encryption is applied and a plaintext tar.gz is output.
     */
    suspend fun export(password: CharArray?, options: BackupOptions, output: OutputStream)

    /**
     * Exports a single session (passwordless tar.gz): contains only that session + its related messages + todos, and can be restored directly by [import].
     * @param output the caller is responsible for opening and closing the output stream
     */
    suspend fun exportSession(sessionId: String, output: OutputStream)

    /**
     * Streams, unpacks and restores a backup file.
     * @param input the caller is responsible for opening and closing the input stream
     * @param password pass null or empty when the backup is not encrypted; an encrypted file requires the correct password.
     * @param selectedWorkspaces when non-null, only files for these workspaces are restored (selected via [previewImport] before import);
     *   null means a full restore (the backup has no workspace data, or a legacy call).
     * @return restore statistics (entry counts per data section); returns failure on a wrong password / mismatched format / too-new version.
     */
    suspend fun import(
        input: InputStream,
        password: CharArray?,
        selectedWorkspaces: Set<String>? = null
    ): Result<RestoreStats>

    /**
     * Pre-import preview: validates the password/format/version and reads the list of workspaces contained in the backup (name + file count).
     * When workspace data is present the UI should show a selection confirmation (with an overwrite warning); when there is no such data, import needs no dialog.
     * @param input the caller is responsible for opening and closing the input stream
     */
    suspend fun previewImport(input: InputStream, password: CharArray?): Result<ImportPreview>
}

/** Import preview result: the list of workspaces in the backup (for selection). */
data class ImportPreview(
    val workspaces: List<WorkspaceBackupMeta>
) {
    val hasWorkspaceData: Boolean get() = workspaces.isNotEmpty()
}

/** Export data scope options; unselected sections stay empty in the snapshot and are skipped on import. */
data class BackupOptions(
    val providers: Boolean = true,
    val remoteConnections: Boolean = true,
    val chatHistory: Boolean = true,
    val mcpServers: Boolean = true,
    val permissionRules: Boolean = true,
    val appSettings: Boolean = true,
    val workspaceFiles: Boolean = false
)

data class RestoreStats(
    val providers: Int = 0,
    val remoteConnections: Int = 0,
    val remoteMounts: Int = 0,
    val chatSessions: Int = 0,
    val agentMessages: Int = 0,
    val todoItems: Int = 0,
    val mcpServers: Int = 0,
    val globalPermissionRules: Int = 0,
    val workspaceFiles: Int = 0
) {
    operator fun plus(other: RestoreStats) = RestoreStats(
        providers = providers + other.providers,
        remoteConnections = remoteConnections + other.remoteConnections,
        remoteMounts = remoteMounts + other.remoteMounts,
        chatSessions = chatSessions + other.chatSessions,
        agentMessages = agentMessages + other.agentMessages,
        todoItems = todoItems + other.todoItems,
        mcpServers = mcpServers + other.mcpServers,
        globalPermissionRules = globalPermissionRules + other.globalPermissionRules,
        workspaceFiles = workspaceFiles + other.workspaceFiles
    )
}
