package com.aicode.feature.backup.data

import android.content.Context
import com.aicode.core.security.KeystoreCipher
import com.aicode.core.util.FileLogger
import com.aicode.core.util.GitIgnoreMatcher
import com.aicode.feature.agent.data.local.dao.AgentMessageDao
import com.aicode.feature.agent.data.local.dao.ChatSessionDao
import com.aicode.feature.agent.data.local.dao.TodoItemDao
import com.aicode.feature.agent.data.local.database.AgentDatabase
import com.aicode.feature.agent.data.local.entity.AgentMessageEntity
import com.aicode.feature.agent.data.local.entity.ChatSessionEntity
import com.aicode.feature.agent.data.local.entity.TodoItemEntity
import com.aicode.feature.agent.domain.mcp.McpConfigRepository
import com.aicode.feature.agent.domain.mcp.McpManager
import com.aicode.feature.agent.domain.permission.PermissionRulesRepository
import com.aicode.feature.backup.domain.AgentMessageDto
import com.aicode.feature.backup.domain.BackupCrypto
import com.aicode.feature.backup.domain.BackupDecryptionException
import com.aicode.feature.backup.domain.BackupManager
import com.aicode.feature.backup.domain.BackupMetadata
import com.aicode.feature.backup.domain.BackupOptions
import com.aicode.feature.backup.domain.BackupSnapshot
import com.aicode.feature.backup.domain.ChatSessionDto
import com.aicode.feature.backup.domain.ImportPreview
import com.aicode.feature.backup.domain.ProviderDto
import com.aicode.feature.backup.domain.RemoteConnectionDto
import com.aicode.feature.backup.domain.RemoteMountDto
import com.aicode.feature.backup.domain.RestoreStats
import com.aicode.feature.backup.domain.TodoItemDto
import com.aicode.feature.backup.domain.WorkspaceBackupMeta
import com.aicode.feature.backup.domain.toMetadata
import com.aicode.feature.settings.data.local.dao.AIProviderDao
import com.aicode.feature.settings.data.local.entity.AIProviderEntity
import com.aicode.feature.settings.data.repository.CompactionModelSettingsRepository
import com.aicode.feature.settings.data.repository.AgentSoundSettingsRepository
import com.aicode.feature.settings.data.repository.GeneralSettingsRepository
import com.aicode.feature.settings.data.repository.KeepaliveSettingsRepository
import com.aicode.feature.settings.data.repository.ScreenOnSettingsRepository
import com.aicode.feature.settings.data.repository.LogSettingsRepository
import com.aicode.feature.settings.data.repository.SyncSettingsRepository
import com.aicode.feature.settings.data.repository.ThemeSettingsRepository
import com.aicode.feature.settings.data.repository.VisionModelSettingsRepository
import com.aicode.feature.workspace.data.local.dao.RemoteConnectionDao
import com.aicode.feature.workspace.data.local.entity.RemoteConnectionEntity
import com.aicode.feature.workspace.data.local.entity.RemoteMountEntity
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.workspace.domain.model.RemoteProtocol
import com.aicode.feature.workspace.domain.model.Workspace
import com.aicode.feature.workspace.domain.model.WorkspaceType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BackupManagerImpl @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val aiProviderDao: AIProviderDao,
    private val remoteConnectionDao: RemoteConnectionDao,
    private val chatSessionDao: ChatSessionDao,
    private val agentMessageDao: AgentMessageDao,
    private val todoItemDao: TodoItemDao,
    private val mcpConfigRepository: McpConfigRepository,
    private val mcpManager: McpManager,
    private val permissionRulesRepository: PermissionRulesRepository,
    private val themeSettingsRepository: ThemeSettingsRepository,
    private val keepaliveSettingsRepository: KeepaliveSettingsRepository,
    private val screenOnSettingsRepository: ScreenOnSettingsRepository,
    private val agentSoundSettingsRepository: AgentSoundSettingsRepository,
    private val generalSettingsRepository: GeneralSettingsRepository,
    private val logSettingsRepository: LogSettingsRepository,
    private val visionModelSettingsRepository: VisionModelSettingsRepository,
    private val compactionModelSettingsRepository: CompactionModelSettingsRepository,
    private val syncSettingsRepository: SyncSettingsRepository,
    private val workspaceRepository: WorkspaceRepository
) : BackupManager {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    private fun currentSchemaVersion(): Int = AgentDatabase.SCHEMA_VERSION

    private fun appVersionName(): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
    }.getOrDefault("")

    override suspend fun export(password: CharArray?, options: BackupOptions, output: OutputStream) {
        withContext(Dispatchers.IO) {
            val temp = createTempFile()
            try {
                writeTarGz(temp, options)
                val pw = password?.takeIf { it.isNotEmpty() }
                FileInputStream(temp).use { input ->
                    if (pw != null) {
                        BackupCrypto.encryptStream(input, output, pw)
                    } else {
                        input.copyTo(output)
                    }
                }
            } finally {
                temp.delete()
            }
        }
    }

    override suspend fun exportSession(sessionId: String, output: OutputStream) {
        withContext(Dispatchers.IO) {
            val session = chatSessionDao.getById(sessionId) ?: error("Session not found: $sessionId")
            val temp = createTempFile()
            try {
                FileOutputStream(temp).use { fos ->
                    GzipCompressorOutputStream(fos).use { gz ->
                        TarArchiveOutputStream(gz).use { tar ->
                            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU)
                            writeMetadataEntry(tar, BackupMetadata(
                                schemaVersion = currentSchemaVersion(),
                                appVersion = appVersionName(),
                                createdAt = System.currentTimeMillis()
                            ))
                            writeJsonlFileEntry(tar, FILE_SESSIONS) { writer ->
                                writer.writeLine(json.encodeToString(ChatSessionDto.serializer(), session.toDto()))
                            }
                            writeJsonlFileEntry(tar, FILE_MESSAGES) { writer ->
                                var lastTs = 0L
                                var lastId = ""
                                while (true) {
                                    val batch = agentMessageDao.getPageBySessionAfter(sessionId, lastTs, lastId, PAGE_SIZE)
                                    if (batch.isEmpty()) break
                                    batch.forEach { writer.writeLine(json.encodeToString(AgentMessageDto.serializer(), it.toDto())) }
                                    lastTs = batch.last().timestamp
                                    lastId = batch.last().id
                                }
                            }
                            writeJsonlFileEntry(tar, FILE_TODOS) { writer ->
                                var lastTs = 0L
                                var lastId = ""
                                while (true) {
                                    val batch = todoItemDao.getBySessionPageAfter(sessionId, lastTs, lastId, PAGE_SIZE)
                                    if (batch.isEmpty()) break
                                    batch.forEach { writer.writeLine(json.encodeToString(TodoItemDto.serializer(), it.toDto())) }
                                    lastTs = batch.last().createdAt
                                    lastId = batch.last().id
                                }
                            }
                        }
                    }
                }
                FileInputStream(temp).use { it.copyTo(output) }
            } finally {
                temp.delete()
            }
        }
    }

    override suspend fun import(
        input: InputStream,
        password: CharArray?,
        selectedWorkspaces: Set<String>?
    ): Result<RestoreStats> {
        val pw = password?.takeIf { it.isNotEmpty() }
        return withContext(Dispatchers.IO) {
            FileLogger.i(TAG, "Backup import started (${if (pw != null) "encrypted" else "plaintext"}${if (selectedWorkspaces != null) ", selected workspaces=${selectedWorkspaces.size}" else ", full"})")
            runCatching {
                openTar(input, pw).use { source ->
                    restoreFromTar(source.tar, selectedWorkspaces)
                }
            }
            .onSuccess { FileLogger.i(TAG, "Backup import completed: $it") }
            .onFailure { FileLogger.e(TAG, "Backup import failed", it) }
            .recoverCatching { e -> mapImportError(e, pw) }
        }
    }

    override suspend fun previewImport(input: InputStream, password: CharArray?): Result<ImportPreview> {
        val pw = password?.takeIf { it.isNotEmpty() }
        return withContext(Dispatchers.IO) {
            FileLogger.i(TAG, "Import preview started (${if (pw != null) "encrypted" else "plaintext"})")
            runCatching {
                openTar(input, pw).use { source ->
                    val tar = source.tar
                    var workspaces: List<WorkspaceBackupMeta> = emptyList()
                    var entry = tar.nextEntry
                    while (entry != null) {
                        if (entry.name == FILE_METADATA) {
                            val plain = tar.readBytes()
                            val metadata = json.decodeFromString(BackupMetadata.serializer(), String(plain, Charsets.UTF_8))
                            checkVersion(metadata.schemaVersion)
                            workspaces = metadata.workspaces
                            // On export metadata.json is the first entry; stop as soon as it is read to avoid scanning large jsonl sections
                            break
                        }
                        entry = tar.nextEntry
                    }
                    ImportPreview(workspaces)
                }
            }
            .onSuccess { FileLogger.i(TAG, "Import preview completed: ${it.workspaces.size} workspaces") }
            .onFailure { FileLogger.e(TAG, "Import preview failed", it) }
            .recoverCatching { e -> mapImportError(e, pw) }
        }
    }

    /** Maps an import exception to a user-readable IllegalArgumentException (decryption exceptions are rethrown as-is). */
    private fun mapImportError(e: Throwable, pw: CharArray?): Nothing {
        when (e) {
            is BackupDecryptionException -> throw e
            is IllegalStateException -> throw e
            else -> throw IllegalArgumentException(
                if (pw != null) {
                    "The backup file is corrupted, or the password does not match the backup file"
                } else {
                    "Not a valid AiCode backup file; if this is an encrypted backup, please enter the export password"
                },
                e
            )
        }
    }

    /** Opens the tar stream: decrypts to a temp file first if needed, then decompresses; the caller is responsible for [TarSource.close]. */
    private fun openTar(input: InputStream, pw: CharArray?): TarSource {
        if (pw == null) {
            FileLogger.i(TAG, "Plaintext backup: decompressing tar.gz directly")
            val p = BufferedInputStream(input)
            val gz = GzipCompressorInputStream(p)
            return TarSource(TarArchiveInputStream(gz), null)
        }
        FileLogger.i(TAG, "Encrypted backup: decrypting to a temp file first")
        val temp = createTempFile()
        try {
            BufferedInputStream(input).use { src ->
                FileOutputStream(temp).use { dst -> BackupCrypto.decryptStream(src, dst, pw) }
            }
            val p = FileInputStream(temp)
            val gz = GzipCompressorInputStream(p)
            return TarSource(TarArchiveInputStream(gz), temp)
        } catch (e: Throwable) {
            temp.delete()
            throw e
        }
    }

    // ── Export helpers ──────────────────────────────────────────────

    private suspend fun writeTarGz(file: File, options: BackupOptions) {
        FileOutputStream(file).use { fos ->
            GzipCompressorOutputStream(fos).use { gz ->
                TarArchiveOutputStream(gz).use { tar ->
                    tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU)
                    writeMetadataEntry(tar, buildMetadata(options))
                    if (options.workspaceFiles) {
                        writeWorkspaceEntries(tar)
                    }
                    if (options.chatHistory) {
                        writeJsonlFileEntry(tar, FILE_SESSIONS) { writer ->
                            var lastTs = 0L
                            var lastId = ""
                            while (true) {
                                val batch = chatSessionDao.getPageAfter(lastTs, lastId, PAGE_SIZE)
                                if (batch.isEmpty()) break
                                batch.forEach { writer.writeLine(json.encodeToString(ChatSessionDto.serializer(), it.toDto())) }
                                lastTs = batch.last().updatedAt
                                lastId = batch.last().id
                            }
                        }
                        writeJsonlFileEntry(tar, FILE_MESSAGES) { writer ->
                            var lastTs = 0L
                            var lastId = ""
                            while (true) {
                                val batch = agentMessageDao.getPageAfter(lastTs, lastId, PAGE_SIZE)
                                if (batch.isEmpty()) break
                                batch.forEach { writer.writeLine(json.encodeToString(AgentMessageDto.serializer(), it.toDto())) }
                                lastTs = batch.last().timestamp
                                lastId = batch.last().id
                            }
                        }
                        writeJsonlFileEntry(tar, FILE_TODOS) { writer ->
                            var lastTs = 0L
                            var lastId = ""
                            while (true) {
                                val batch = todoItemDao.getPageAfter(lastTs, lastId, PAGE_SIZE)
                                if (batch.isEmpty()) break
                                batch.forEach { writer.writeLine(json.encodeToString(TodoItemDto.serializer(), it.toDto())) }
                                lastTs = batch.last().createdAt
                                lastId = batch.last().id
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun buildMetadata(options: BackupOptions): BackupMetadata = BackupMetadata(
        schemaVersion = currentSchemaVersion(),
        appVersion = appVersionName(),
        createdAt = System.currentTimeMillis(),
        providers = if (options.providers) aiProviderDao.getAllProvidersOnce().map { it.toDto() } else emptyList(),
        remoteConnections = if (options.remoteConnections) remoteConnectionDao.getAllConnectionsOnce().map { it.toDto() } else emptyList(),
        remoteMounts = if (options.remoteConnections) remoteConnectionDao.getAllMountsOnce().map { it.toDto() } else emptyList(),
        mcpServers = if (options.mcpServers) mcpConfigRepository.getGlobalServers() else emptyList(),
        globalPermissionRules = if (options.permissionRules) permissionRulesRepository.getGlobalRulesOnce() else emptyList(),
        themeMode = if (options.appSettings) themeSettingsRepository.snapshot() else null,
        themePresetId = if (options.appSettings) themeSettingsRepository.presetSnapshot() else null,
        dynamicColorEnabled = if (options.appSettings) themeSettingsRepository.dynamicColorSnapshot() else false,
        keepaliveEnabled = if (options.appSettings) keepaliveSettingsRepository.snapshot() else false,
        screenOnEnabled = if (options.appSettings) screenOnSettingsRepository.snapshot() else false,
        agentSoundEnabled = if (options.appSettings) agentSoundSettingsRepository.snapshot() else false,
        autoRemoveStaleModels = if (options.appSettings) generalSettingsRepository.autoRemoveStaleModelsSnapshot() else true,
        startupSessionMode = if (options.appSettings) generalSettingsRepository.startupSessionModeSnapshot() else null,
        firstByteTimeoutSec = if (options.appSettings) generalSettingsRepository.firstByteTimeoutSecSnapshot() else 300,
        streamIdleTimeoutSec = if (options.appSettings) generalSettingsRepository.streamIdleTimeoutSecSnapshot() else 0,
        maxNetworkRetries = if (options.appSettings) generalSettingsRepository.maxNetworkRetriesSnapshot() else 6,
        enterToSend = if (options.appSettings) generalSettingsRepository.enterToSendSnapshot() else false,
        compactionThresholdPercent = if (options.appSettings) generalSettingsRepository.compactionThresholdPercentSnapshot() else 90,
        sendFileMaxSizeMb = if (options.appSettings) generalSettingsRepository.sendFileMaxSizeMbSnapshot() else 100,
        deleteExternalWorkspaceSessions = if (options.appSettings) generalSettingsRepository.deleteExternalWorkspaceSessionsSnapshot() else false,
        todoDisplayPosition = if (options.appSettings) generalSettingsRepository.todoDisplayPositionSnapshot() else null,
        logLevel = if (options.appSettings) logSettingsRepository.snapshot() else null,
        visionProviderId = if (options.appSettings) visionModelSettingsRepository.getVisionProviderId() else "",
        visionModel = if (options.appSettings) visionModelSettingsRepository.getVisionModel() else "",
        compactionProviderId = if (options.appSettings) compactionModelSettingsRepository.getCompactionProviderId() else "",
        compactionModel = if (options.appSettings) compactionModelSettingsRepository.getCompactionModel() else "",
        syncSettings = if (options.appSettings) syncSettingsRepository.snapshot() else null,
        workspaces = if (options.workspaceFiles) collectWorkspaceMetas() else emptyList()
    )

    private fun writeMetadataEntry(tar: TarArchiveOutputStream, metadata: BackupMetadata) {
        val content = json.encodeToString(BackupMetadata.serializer(), metadata).toByteArray(Charsets.UTF_8)
        writeTarEntry(tar, FILE_METADATA, content)
    }

    private fun writeTarEntry(tar: TarArchiveOutputStream, name: String, content: ByteArray) {
        val entry = TarArchiveEntry(name).apply { size = content.size.toLong() }
        tar.putArchiveEntry(entry)
        tar.write(content)
        tar.closeArchiveEntry()
    }

    /**
     * Writes the jsonl to a temp file first to get the exact [File.length] for TarArchiveEntry.size,
     * then streams it into the TarArchiveOutputStream, avoiding an out-of-bounds write caused by setting size to 0 in the header.
     */
    private suspend fun writeJsonlFileEntry(
        tar: TarArchiveOutputStream,
        entryName: String,
        block: suspend (JsonlWriter) -> Unit
    ) {
        val tmp = createTempFile()
        try {
            FileOutputStream(tmp).use { fos ->
                val writer = JsonlWriter(fos)
                block(writer)
                writer.flush()
            }
            val entry = TarArchiveEntry(entryName).apply { size = tmp.length() }
            tar.putArchiveEntry(entry)
            FileInputStream(tmp).use { fis -> fis.copyTo(tar) }
            tar.closeArchiveEntry()
        } finally {
            tmp.delete()
        }
    }

    // ── Workspace file backup ──────────────────────────────────────────

    /** Backs up only internal local workspaces; external local workspaces are directories on the user's device, not the app's private data, and are not included in the backup. */
    private fun backupLocalWorkspaces(): List<Workspace> =
        workspaceRepository.workspaces.value.filter { it.type != WorkspaceType.EXTERNAL_LOCAL }

    /** First pass: counts the files to be backed up in each local workspace (for writing metadata). */
    private fun collectWorkspaceMetas(): List<WorkspaceBackupMeta> =
        backupLocalWorkspaces().mapNotNull { ws ->
            var count = 0
            walkWorkspaceFiles(ws) { _, _ -> count++ }
            if (count > 0) WorkspaceBackupMeta(ws.name, count) else null
        }

    /** Second pass: writes each local workspace's files into the tar (`workspaces/<name>/<relative path>`). */
    private fun writeWorkspaceEntries(tar: TarArchiveOutputStream) {
        backupLocalWorkspaces().forEach { ws ->
            walkWorkspaceFiles(ws) { file, parts ->
                writeTarFileEntry(tar, "workspaces/${ws.name}/${parts.joinToString("/")}", file)
            }
        }
    }

    /**
     * Recursively walks a local workspace, excluding files per ".gitignore (anchored) + sync ignore list";
     * the `.git` directory is always included (its contents are not subject to ignore checks); symbolic links are skipped (to prevent cycles).
     */
    private fun walkWorkspaceFiles(
        workspace: Workspace,
        onFile: (File, List<String>) -> Unit
    ) {
        val root = File(workspace.path)
        if (!root.isDirectory) return  // Remote workspace or does not exist: no local files to back up
        val customIgnores = syncSettingsRepository.ignoredPatterns.value
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val gitignorePatterns = parseGitIgnore(File(root, ".gitignore"))

        fun walk(dir: File, relParts: List<String>) {
            dir.listFiles()?.forEach { f ->
                val parts = relParts + f.name
                if (java.nio.file.Files.isSymbolicLink(f.toPath())) return@forEach
                if (parts.first() != ".git" &&
                    (customIgnores.any { it in parts } ||
                        GitIgnoreMatcher.isIgnored(gitignorePatterns, parts, anchored = true))
                ) {
                    return@forEach
                }
                if (f.isDirectory) walk(f, parts) else onFile(f, parts)
            }
        }
        walk(root, emptyList())
    }

    /** Parses the workspace-root .gitignore: strips blank lines/comments/trailing slashes. */
    private fun parseGitIgnore(file: File): List<String> =
        if (!file.isFile) emptyList()
        else file.readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.trim().removeSuffix("/") }

    private fun writeTarFileEntry(tar: TarArchiveOutputStream, name: String, file: File) {
        val entry = TarArchiveEntry(name).apply { size = file.length() }
        tar.putArchiveEntry(entry)
        FileInputStream(file).use { it.copyTo(tar) }
        tar.closeArchiveEntry()
    }

    // ── Import helpers ──────────────────────────────────────────────

    private suspend fun restoreFromTar(tar: TarArchiveInputStream, selectedWorkspaces: Set<String>?): RestoreStats {
        val restoreMapping = mutableMapOf<String, Workspace>()
        var metadata: BackupMetadata? = null
        var stats = RestoreStats()
        var entry = tar.nextEntry
        while (entry != null) {
            when (entry.name) {
                FILE_LEGACY_SNAPSHOT -> {
                    val plain = tar.readBytes()
                    val snapshot = json.decodeFromString(BackupSnapshot.serializer(), String(plain, Charsets.UTF_8))
                    checkVersion(snapshot.schemaVersion)
                    return restoreLegacy(snapshot)
                }
                FILE_METADATA -> {
                    val plain = tar.readBytes()
                    metadata = json.decodeFromString(BackupMetadata.serializer(), String(plain, Charsets.UTF_8))
                    checkVersion(metadata.schemaVersion)
                }
                FILE_SESSIONS -> {
                    val count = restoreJsonl(tar, ChatSessionDto.serializer()) { dtos ->
                        chatSessionDao.upsertAll(
                            dtos.map { dto ->
                                dto.copy(
                                    workspacePath = resolveSessionWorkspace(dto.workspacePath, restoreMapping)
                                ).toEntity()
                            }
                        )
                    }
                    FileLogger.i(TAG, "Restored $count sessions")
                    stats += RestoreStats(chatSessions = count)
                }
                FILE_MESSAGES -> {
                    val count = restoreJsonl(tar, AgentMessageDto.serializer()) { dtos ->
                        agentMessageDao.insertAll(dtos.map { it.toEntity() })
                    }
                    FileLogger.i(TAG, "Restored $count messages")
                    stats += RestoreStats(agentMessages = count)
                }
                FILE_TODOS -> {
                    val count = restoreJsonl(tar, TodoItemDto.serializer()) { dtos ->
                        todoItemDao.upsertAll(dtos.map { it.toEntity() })
                    }
                    FileLogger.i(TAG, "Restored $count todos")
                    stats += RestoreStats(todoItems = count)
                }
                else -> {
                    if (entry.name.startsWith(WORKSPACE_PREFIX)) {
                        stats += restoreWorkspaceEntry(tar, entry.name, selectedWorkspaces, restoreMapping)
                    }
                }
            }
            entry = tar.nextEntry
        }
        val meta = metadata ?: run {
            FileLogger.e(TAG, "Import failed: metadata.json missing from tar")
            error("Not a valid AiCode backup file: missing metadata.json")
        }
        FileLogger.i(TAG, "Tar parsing complete, starting to restore the metadata section")
        return stats + restoreMeta(meta)
    }

    /** Parses a jsonl entry line by line, invoking the batch-insert callback every [PAGE_SIZE] items; returns the total count for the file. */
    private suspend fun <T> restoreJsonl(tar: TarArchiveInputStream, serializer: KSerializer<T>, insert: suspend (List<T>) -> Unit): Int {
        val buffer = ByteArray(64 * 1024)
        val line = ByteArrayOutputStream(16 * 1024)
        val batch = ArrayList<T>(PAGE_SIZE)
        var count = 0
        while (true) {
            val n = tar.read(buffer)
            if (n < 0) break
            for (i in 0 until n) {
                if (buffer[i] == '\n'.code.toByte()) {
                    if (line.size() > 0) {
                        // Note: ByteArrayOutputStream.toString(Charset) is only available from API 33,
                        // and throws NoSuchMethodError below Android 13, so the String(byte[], Charset) constructor must be used.
                        batch.add(json.decodeFromString(serializer, String(line.toByteArray(), Charsets.UTF_8)))
                        line.reset()
                        if (batch.size >= PAGE_SIZE) {
                            count += batch.size
                            insert(batch.toList())
                            batch.clear()
                        }
                    } else {
                        line.reset()
                    }
                } else {
                    line.write(buffer[i].toInt())
                }
            }
        }
        if (line.size() > 0) {
            batch.add(json.decodeFromString(serializer, String(line.toByteArray(), Charsets.UTF_8)))
        }
        if (batch.isNotEmpty()) {
            count += batch.size
            insert(batch.toList())
        }
        return count
    }

    private fun checkVersion(schemaVersion: Int) {
        if (schemaVersion > currentSchemaVersion()) {
            error("The backup database version v$schemaVersion is newer than this app v${currentSchemaVersion()}; please upgrade the app")
        }
    }

    /** Restores the legacy format (single-file snapshot.json full snapshot). */
    private suspend fun restoreLegacy(snapshot: BackupSnapshot): RestoreStats {
        var stats = restoreMeta(snapshot.toMetadata())
        if (snapshot.chatSessions.isNotEmpty()) {
            // Resolves each workspace path from the backup to a target workspace one by one (creating an empty workspace automatically if none exists), instead of putting everything into the current workspace.
            val restoreMapping = mutableMapOf<String, Workspace>()
            chatSessionDao.upsertAll(
                snapshot.chatSessions.map {
                    it.copy(workspacePath = resolveSessionWorkspace(it.workspacePath, restoreMapping)).toEntity()
                }
            )
        }
        if (snapshot.agentMessages.isNotEmpty()) {
            agentMessageDao.insertAll(snapshot.agentMessages.map { it.toEntity() })
        }
        if (snapshot.todoItems.isNotEmpty()) {
            todoItemDao.upsertAll(snapshot.todoItems.map { it.toEntity() })
        }
        return stats + RestoreStats(
            chatSessions = snapshot.chatSessions.size,
            agentMessages = snapshot.agentMessages.size,
            todoItems = snapshot.todoItems.size
        )
    }

    /** Restores the metadata section (small tables + app settings), shared by the new and legacy formats. */
    private suspend fun restoreMeta(meta: BackupMetadata): RestoreStats {
        FileLogger.i(
            TAG,
            "Restoring metadata: providers=${meta.providers.size} remoteConnections=${meta.remoteConnections.size} remoteMounts=${meta.remoteMounts.size} " +
                "mcpServers=${meta.mcpServers.size} permissionRules=${meta.globalPermissionRules.size} syncSettings=${meta.syncSettings != null}"
        )
        if (meta.providers.isNotEmpty()) {
            aiProviderDao.insertAllProviders(meta.providers.map { it.toEntity() })
        }
        if (meta.remoteConnections.isNotEmpty()) {
            remoteConnectionDao.insertAllConnections(meta.remoteConnections.mapNotNull { it.toEntity() })
        }
        if (meta.remoteMounts.isNotEmpty()) {
            // Mounts pointing to a removed protocol (the legacy local channel) are also skipped to avoid foreign-key constraint failures.
            val connectionIds = remoteConnectionDao.getAllConnectionsOnce().map { it.id }.toSet()
            remoteConnectionDao.insertAllMounts(
                meta.remoteMounts.map { it.toEntity() }.filter { it.connectionId in connectionIds }
            )
        }
        if (meta.mcpServers.isNotEmpty()) {
            mcpConfigRepository.setGlobalServers(meta.mcpServers)
            mcpManager.reload()
        }
        if (meta.globalPermissionRules.isNotEmpty()) {
            permissionRulesRepository.setGlobalRules(meta.globalPermissionRules)
        }
        meta.themeMode?.let { themeSettingsRepository.restore(it) }
        themeSettingsRepository.restoreColors(meta.themePresetId, meta.dynamicColorEnabled)
        keepaliveSettingsRepository.restore(meta.keepaliveEnabled)
        screenOnSettingsRepository.restore(meta.screenOnEnabled)
        agentSoundSettingsRepository.restore(meta.agentSoundEnabled)
        generalSettingsRepository.restoreAutoRemoveStaleModels(meta.autoRemoveStaleModels)
        generalSettingsRepository.restoreStartupSessionMode(meta.startupSessionMode)
        generalSettingsRepository.restoreFirstByteTimeoutSec(meta.firstByteTimeoutSec)
        generalSettingsRepository.restoreStreamIdleTimeoutSec(meta.streamIdleTimeoutSec)
        generalSettingsRepository.restoreMaxNetworkRetries(meta.maxNetworkRetries)
        generalSettingsRepository.restoreEnterToSend(meta.enterToSend)
        generalSettingsRepository.restoreCompactionThresholdPercent(meta.compactionThresholdPercent)
        generalSettingsRepository.restoreSendFileMaxSizeMb(meta.sendFileMaxSizeMb)
        generalSettingsRepository.restoreDeleteExternalWorkspaceSessions(meta.deleteExternalWorkspaceSessions)
        generalSettingsRepository.restoreTodoDisplayPosition(meta.todoDisplayPosition)
        logSettingsRepository.restore(meta.logLevel)
        if (meta.visionProviderId.isNotBlank() || meta.visionModel.isNotBlank()) {
            visionModelSettingsRepository.setVisionModel(meta.visionProviderId, meta.visionModel)
        }
        if (meta.compactionProviderId.isNotBlank() || meta.compactionModel.isNotBlank()) {
            compactionModelSettingsRepository.setCompactionModel(meta.compactionProviderId, meta.compactionModel)
        }
        meta.syncSettings?.let { syncSettingsRepository.restore(it) }

        return RestoreStats(
            providers = meta.providers.size,
            remoteConnections = meta.remoteConnections.size,
            remoteMounts = meta.remoteMounts.size,
            mcpServers = meta.mcpServers.size,
            globalPermissionRules = meta.globalPermissionRules.size
        )
    }

    /**
     * Restores a single workspace file entry: `workspaces/<name>/<relative path>`.
     * Only processes selected workspaces (when [selectedWorkspaces] is non-null), and only allows writing to internal workspaces; creates one automatically when no local workspace with the same name exists,
     * so workspace files imported on a new device / after reinstall are not lost because no target was found.
     */
    private suspend fun restoreWorkspaceEntry(
        tar: TarArchiveInputStream,
        entryName: String,
        selectedWorkspaces: Set<String>?,
        restoreMapping: MutableMap<String, Workspace>
    ): RestoreStats {
        val segments = entryName.split("/", limit = 3)
        if (segments.size != 3) return RestoreStats()
        val wsName = segments[1]
        if (selectedWorkspaces != null && wsName !in selectedWorkspaces) return RestoreStats()

        val ws = resolveWorkspaceForRestore(wsName, restoreMapping) ?: return RestoreStats()

        val wsDir = File(ws.path)
        if (!isPathInsideWorkspace(wsDir, segments[2])) return RestoreStats()
        val target = File(wsDir, segments[2])
        target.parentFile?.mkdirs()
        FileOutputStream(target).use { out -> tar.copyTo(out) }
        FileLogger.i(TAG, "Restored workspace file: ${ws.name}/${segments[2]}")
        return RestoreStats(workspaceFiles = 1)
    }

    /**
     * Resolves a workspace name from the backup to a target workspace: prefers reusing the mapping already resolved in this import, then reusing a local internal workspace with the same name,
     * and creates an empty internal workspace automatically if neither exists. Workspace file entries and sessions share the same mapping, so both land in the same workspace.
     */
    private suspend fun resolveWorkspaceForRestore(
        wsName: String,
        restoreMapping: MutableMap<String, Workspace>
    ): Workspace? {
        restoreMapping[wsName]?.let { return it }
        val reservedNames = restoreMapping.values.map { it.name }.toSet()
        val existing = workspaceRepository.workspaces.value.firstOrNull {
            it.name == wsName && it.type == WorkspaceType.INTERNAL && it.name !in reservedNames
        }
        val resolved = existing ?: run {
            val occupied = workspaceRepository.workspaces.value.map { it.name }.toSet() + reservedNames
            val targetName = WorkspaceRepository.uniqueName(wsName, occupied)
            workspaceRepository.createWorkspace(targetName) ?: return null
        }
        restoreMapping[wsName] = resolved
        FileLogger.i(TAG, "Import session target workspace: ${resolved.name} -> ${resolved.path}")
        return resolved
    }

    /**
     * Resolves a session's original workspace path from the backup to a workspace on the target device:
     * 1. If an existing workspace path matches the backup (same-device reinstall scenario, for both internal/external local) it is reused directly;
     * 2. Otherwise the last path segment is treated as the workspace name and resolved via [resolveWorkspaceForRestore] to reuse a same-named internal workspace or create an empty one automatically;
     * 3. If the path is blank or resolution fails (e.g. cannot be created), falls back to the current workspace.
     */
    private suspend fun resolveSessionWorkspace(
        backupWorkspacePath: String,
        restoreMapping: MutableMap<String, Workspace>
    ): String {
        if (backupWorkspacePath.isBlank()) return workspaceRepository.currentPath()
        workspaceRepository.workspaces.value
            .firstOrNull { it.path == backupWorkspacePath && it.name !in restoreMapping.keys }
            ?.let { return it.path }
        val name = backupWorkspacePath.trimEnd('/').substringAfterLast('/')
        if (name.isEmpty()) return workspaceRepository.currentPath()
        return resolveWorkspaceForRestore(name, restoreMapping)?.path ?: workspaceRepository.currentPath()
    }

    /** Guards against path traversal: the workspace must be a local directory, and the normalized relative path must still be inside it. */
    private fun isPathInsideWorkspace(wsDir: File, relPath: String): Boolean {
        if (!wsDir.isDirectory) return false
        val root = wsDir.canonicalPath
        val target = File(wsDir, relPath).canonicalPath
        return target == root || target.startsWith(root + File.separator)
    }

    private fun createTempFile(): File = File.createTempFile("backup", ".tmp", context.cacheDir)

    // ── Entity ↔ DTO conversion ──────────────────────────────────────

    private fun AIProviderEntity.toDto() = ProviderDto(
        id = id,
        name = name,
        type = type,
        apiKey = KeystoreCipher.decryptString(apiKey),
        baseUrl = baseUrl,
        defaultModel = defaultModel,
        models = models,
        selectedModel = selectedModel,
        isEnabled = isEnabled,
        useFullUrl = useFullUrl,
        useResponseApi = useResponseApi,
        anthropicCacheBreakpoints = anthropicCacheBreakpoints,
        openaiChatCacheKey = openaiChatCacheKey,
        dashboardScriptPath = dashboardScriptPath,
        dashboardRefreshInterval = dashboardRefreshInterval,
        sortOrder = sortOrder,
        multiKeyEnabled = multiKeyEnabled,
        apiKeys = KeystoreCipher.decryptString(apiKeys),
        keyRotationStrategy = keyRotationStrategy,
        keyFailoverThreshold = keyFailoverThreshold,
        keyCooldownMinutes = keyCooldownMinutes,
        scriptParams = KeystoreCipher.decryptString(scriptParams),
        keySwitchStatusCodes = keySwitchStatusCodes
    )

    private fun ProviderDto.toEntity() = AIProviderEntity(
        id = id,
        name = name,
        type = type,
        apiKey = KeystoreCipher.encryptString(apiKey),
        baseUrl = baseUrl,
        defaultModel = defaultModel,
        models = models,
        selectedModel = selectedModel,
        isEnabled = isEnabled,
        useFullUrl = useFullUrl,
        useResponseApi = useResponseApi,
        anthropicCacheBreakpoints = anthropicCacheBreakpoints ?: true,
        openaiChatCacheKey = openaiChatCacheKey ?: false,
        dashboardScriptPath = dashboardScriptPath ?: "",
        dashboardRefreshInterval = dashboardRefreshInterval ?: 5,
        sortOrder = sortOrder ?: 0,
        multiKeyEnabled = multiKeyEnabled ?: false,
        apiKeys = KeystoreCipher.encryptString(apiKeys ?: ""),
        keyRotationStrategy = keyRotationStrategy ?: "SEQUENTIAL",
        keyFailoverThreshold = keyFailoverThreshold ?: 2,
        keyCooldownMinutes = keyCooldownMinutes ?: 5,
        scriptParams = KeystoreCipher.encryptString(scriptParams ?: ""),
        keySwitchStatusCodes = keySwitchStatusCodes ?: ""
    )

    private fun RemoteConnectionEntity.toDto() = RemoteConnectionDto(
        id, name, protocol.name, host, port, username, authType,
        KeystoreCipher.decryptString(authData), passphrase?.let { KeystoreCipher.decryptString(it) }
    )

    /** Legacy backups may contain a removed protocol (local channel); such records are skipped and not restored. */
    private fun RemoteConnectionDto.toEntity(): RemoteConnectionEntity? {
        val parsed = runCatching { RemoteProtocol.valueOf(protocol) }.getOrNull() ?: return null
        return RemoteConnectionEntity(
            id, name, parsed, host, port, username, authType,
            if (authType.equals("PASSWORD", ignoreCase = true)) KeystoreCipher.encryptString(authData) else authData,
            passphrase?.let { KeystoreCipher.encryptString(it) }
        )
    }

    private fun RemoteMountEntity.toDto() = RemoteMountDto(id, connectionId, remotePath, localMountPath, isActive, autoConnect)
    private fun RemoteMountDto.toEntity() = RemoteMountEntity(id, connectionId, remotePath, localMountPath, isActive, autoConnect)

    private fun ChatSessionEntity.toDto() = ChatSessionDto(
        id = id, title = title, createdAt = createdAt, updatedAt = updatedAt, workspacePath = workspacePath,
        mode = mode, modeBeforePlan = modeBeforePlan, reasoningEffort = reasoningEffort, providerId = providerId,
        model = model, isPinned = isPinned, parentId = parentId, subagentType = subagentType
    )

    private fun ChatSessionDto.toEntity() = ChatSessionEntity(
        id = id, title = title, createdAt = createdAt, updatedAt = updatedAt, workspacePath = workspacePath,
        mode = mode, modeBeforePlan = modeBeforePlan, reasoningEffort = reasoningEffort, providerId = providerId,
        model = model, isPinned = isPinned, parentId = parentId, subagentType = subagentType
    )

    private fun AgentMessageEntity.toDto() = AgentMessageDto(
        id, sessionId, role, content, timestamp, toolCallsJson, toolCallId, toolName, toolArgs,
        isError, reasoning, signature, attachmentsJson, isCompacted, isContextSummary, isCompactionMarker,
        thinkingBlocksJson, isContextExcluded, compactedBySummaryId
    )

    private fun AgentMessageDto.toEntity(): AgentMessageEntity {
        val summary = isContextSummary || (role == "ASSISTANT" && content.startsWith(
            com.aicode.feature.agent.domain.model.CONTEXT_SUMMARY_LEGACY_PREFIX
        ))
        val excluded = isContextExcluded || (
            isCompacted && !summary && !isCompactionMarker && role == "ASSISTANT" &&
                toolCallsJson == null && compactedBySummaryId == null && isLegacyUsageTable(content)
        )
        return AgentMessageEntity(
            id, sessionId, role, content, timestamp, toolCallsJson, toolCallId, toolName, toolArgs,
            isError, reasoning, signature, attachmentsJson, isCompacted && !excluded, summary, isCompactionMarker,
            thinkingBlocksJson = thinkingBlocksJson,
            isContextExcluded = excluded,
            compactedBySummaryId = if (excluded) null else compactedBySummaryId
        )
    }

    private fun isLegacyUsageTable(content: String): Boolean {
        val lines = content.split('\n')
        if (lines.size != 7 || lines[0] != "| 项目 | 今日 | 累计 |" || lines[1] != "|---|---|---|") {
            return false
        }
        val labels = listOf("调用次数", "输入 tokens", "输出 tokens", "缓存命中 tokens", "预估费用")
        return labels.indices.all { index ->
            val line = lines[index + 2]
            line.startsWith("| ${labels[index]} | ") && line.endsWith(" |") &&
                line.count { it == '|' } == 4 && line.split('|').let { columns ->
                    columns[2].startsWith(" ") && columns[2].endsWith(" ") &&
                        columns[3].startsWith(" ") && columns[3].endsWith(" ")
                }
        }
    }

    private fun TodoItemEntity.toDto() = TodoItemDto(id, sessionId, subject, description, status, priority, order, createdAt, updatedAt)
    private fun TodoItemDto.toEntity() = TodoItemEntity(id, sessionId, subject, description, status, priority, order, createdAt, updatedAt)

    private companion object {
        const val TAG = "BackupManager"
        const val PAGE_SIZE = 500
        const val FILE_METADATA = "metadata.json"
        const val FILE_SESSIONS = "chatSessions.jsonl"
        const val FILE_MESSAGES = "messages.jsonl"
        const val FILE_TODOS = "todoItems.jsonl"
        const val FILE_LEGACY_SNAPSHOT = "snapshot.json"
        const val WORKSPACE_PREFIX = "workspaces/"
    }
}

/** A jsonl writer with an internal buffer: flushes to the tar stream when the line buffer reaches 64KB, avoiding a syscall per line. */
private class JsonlWriter(private val out: OutputStream) {
    private val buffer = ByteArrayOutputStream(64 * 1024)

    fun writeLine(line: String) {
        buffer.write(line.toByteArray(Charsets.UTF_8))
        buffer.write('\n'.code)
        if (buffer.size() >= 64 * 1024) flush()
    }

    fun flush() {
        buffer.writeTo(out)
        buffer.reset()
    }
}

/** Wrapper for an opened tar stream: carries the temp file used for decryption on encrypted imports, and cleans it up on close. */
private class TarSource(
    val tar: TarArchiveInputStream,
    private val temp: File?
) : AutoCloseable {
    override fun close() {
        runCatching { tar.close() }
        temp?.delete()
    }
}
