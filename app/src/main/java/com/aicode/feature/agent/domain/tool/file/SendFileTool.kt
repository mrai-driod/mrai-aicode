package com.aicode.feature.agent.domain.tool.file

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.ParameterType
import com.aicode.feature.agent.domain.tool.ToolCapability
import com.aicode.feature.agent.domain.tool.ToolParameter
import com.aicode.feature.agent.domain.tool.ToolResult
import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.settings.data.repository.GeneralSettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLConnection
import javax.inject.Inject

/**
 * Sends workspace files to the chat area as "file rows" (one file per row: thumbnail/type icon + file name + size and path),
 * Clicking opens them with the corresponding system app; images are previewed full-screen in the app.
 *
 * Atomic semantics: all files must exist and be valid; any failure fails the whole call (no file rows are rendered),
 * the error message lists each failed item, and the AI can fix paths and call again.
 * File data (content/base64) does not enter the model context; the model only receives file metadata text.
 */
class SendFileTool @Inject constructor(
    private val fileAccess: FileAccessProvider,
    private val generalSettingsRepository: GeneralSettingsRepository
) : AgentTool() {
    override val name = "sendFile"

    /**
     * The maximum size of a single file can be adjusted in preferences (100MB by default). The tool definition ([description] / [parameters]) is read by the model
     * side and is a non-suspending property, so [cachedMaxFileSizeMb] caches the most recent setting value; the actual validation uses the authoritative value
     * read suspendingly inside [execute].
     */
    private val settingsScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var cachedMaxFileSizeMb = DEFAULT_MAX_FILE_SIZE_MB

    init {
        settingsScope.launch {
            generalSettingsRepository.sendFileMaxSizeMbFlow.collect { cachedMaxFileSizeMb = it }
        }
    }

    override val description get() =
        "Send existing workspace files to the chat area to show the user. All files must exist and be valid; if any fails, the whole call fails and must be fixed and retried. Display only; contents are not read and do not enter the context."

    override val capabilities = setOf(ToolCapability.READ_WORKSPACE)

    override val parameters get() = mapOf(
        "paths" to ToolParameter(
            name = "paths",
            type = ParameterType.ARRAY,
            description = "List of file paths to send; supports ~/workspace/... project files or container absolute paths; at most $MAX_FILES per call, each file no larger than ${cachedMaxFileSizeMb}MB.",
            required = true,
            itemsSchema = mapOf("type" to "string")
        ),
        "names" to ToolParameter(
            name = "names",
            type = ParameterType.ARRAY,
            description = "Optional list of custom display names, corresponding one-to-one with paths; defaults to the file name.",
            required = false,
            itemsSchema = mapOf("type" to "string")
        )
    )

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        return try {
            val paths = (args["paths"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                .orEmpty()
            if (paths.isEmpty()) {
                return ToolResult.Error("Missing required parameter: paths", "MISSING_PATHS")
            }
            if (paths.size > MAX_FILES) {
                return ToolResult.Error("At most $MAX_FILES files can be sent at once, currently ${paths.size}", "TOO_MANY_FILES")
            }
            val names = (args["names"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }
                ?.map { it.trim() }
                .orEmpty()
            if (names.isNotEmpty() && names.size != paths.size) {
                return ToolResult.Error("The number of names (${names.size}) does not match the number of paths (${paths.size})", "ARGS_MISMATCH")
            }

            // Atomic validation: it only succeeds if everything passes; any failure fails the whole call and lists all failed items.
            val maxSizeMb = generalSettingsRepository.sendFileMaxSizeMb()
            val maxSizeBytes = maxSizeMb * 1024L * 1024L
            val failures = mutableListOf<String>()
            paths.forEachIndexed { index, path ->
                when {
                    !fileAccess.exists(path) -> failures.add("File does not exist: $path")
                    !fileAccess.isFile(path) -> failures.add("Path is not a file: $path")
                    fileAccess.fileSize(path) > maxSizeBytes -> {
                        val sizeMb = fileAccess.fileSize(path) / (1024 * 1024)
                        failures.add("File exceeds the ${maxSizeMb}MB limit: $path (${sizeMb}MB)")
                    }
                }
            }
            if (failures.isNotEmpty()) {
                FileLogger.w(TAG, "sendFile atomic validation failed: ${failures.joinToString("; ")}")
                return ToolResult.Error(failures.joinToString("; "), "INVALID_FILE")
            }

            val files = paths.mapIndexed { index, path ->
                val rawName = path.substringAfterLast('/').ifBlank { path }
                // The display name may be customised; the MIME type must be inferred from the original file name, and a custom name missing a suffix / having a prefix does not affect type identification.
                val displayName = names.getOrNull(index)?.takeIf { it.isNotBlank() } ?: rawName
                JsonObject(
                    mapOf(
                        "path" to JsonPrimitive(fileAccess.toDisplayPath(path)),
                        "local_path" to JsonPrimitive(fileAccess.copyToLocal(path).absolutePath),
                        "name" to JsonPrimitive(displayName),
                        "mime_type" to JsonPrimitive(guessMimeType(rawName)),
                        "size_bytes" to JsonPrimitive(fileAccess.fileSize(path)),
                        "is_image" to JsonPrimitive(guessMimeType(rawName).startsWith("image/"))
                    )
                )
            }

            FileLogger.i(TAG, "sendFile succeeded: ${paths.size} files ${paths.joinToString(", ") { fileAccess.toDisplayPath(it) }}")
            ToolResult.Success(
                JsonObject(
                    mapOf(
                        "content" to JsonPrimitive(
                            buildString {
                                append("Sent ${files.size} files:")
                                files.forEach { file ->
                                    append('\n')
                                    append("- ")
                                    append(file["name"]?.jsonPrimitive?.contentOrNull)
                                    append(": ")
                                    append(file["path"]?.jsonPrimitive?.contentOrNull)
                                }
                            }
                        ),
                        "files" to JsonArray(files),
                        "file_count" to JsonPrimitive(files.size)
                    )
                )
            )
        } catch (e: Exception) {
            FileLogger.e(TAG, "sendFile error", e)
            ToolResult.Error(e.message ?: "Failed to send file", "SEND_FILE_ERROR")
        }
    }

    private fun guessMimeType(fileName: String): String {
        val extMime = when (fileName.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "bmp" -> "image/bmp"
            "pdf" -> "application/pdf"
            "zip" -> "application/zip"
            "txt", "md", "kt", "java", "js", "ts", "json", "xml", "html", "css", "py", "sh", "gradle", "kts" -> "text/plain"
            "apk" -> "application/vnd.android.package-archive"
            else -> null
        }
        return extMime ?: URLConnection.guessContentTypeFromName(fileName) ?: "application/octet-stream"
    }

    private companion object {
        const val TAG = "SendFileTool"
        const val MAX_FILES = 10
        /** Fallback value used before the settings are ready; matches the default in preferences. */
        const val DEFAULT_MAX_FILE_SIZE_MB = 100
    }
}
