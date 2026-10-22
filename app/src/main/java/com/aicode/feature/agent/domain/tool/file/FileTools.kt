package com.aicode.feature.agent.domain.tool.file

import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.ParameterType
import com.aicode.feature.agent.domain.tool.PendingToolPermission
import com.aicode.feature.agent.domain.tool.ToolParameter
import com.aicode.feature.agent.domain.tool.ToolCapability
import com.aicode.feature.agent.domain.tool.ToolPermissionPolicy
import com.aicode.feature.agent.domain.tool.ToolResult
import com.aicode.core.util.FileLogger
import com.aicode.core.util.LineDiff
import com.aicode.feature.workspace.domain.FileAccessProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

private const val TAG = "FileTools"

class ReadFileTool @Inject constructor(
    private val fileAccess: FileAccessProvider
) : AgentTool() {
    override val name = "readFile"
    override val description = "Read file contents (workspace files or system files at absolute container paths). A single read has line and byte limits; use start_line to continue reading in segments beyond them."
    override val capabilities = setOf(ToolCapability.READ_WORKSPACE)
    override val parameters = mapOf(
        "path" to ToolParameter("path", ParameterType.STRING, "File path: ~/workspace/... for project files; other absolute paths (e.g. /etc/..., /root/...) are container system files.", required = true),
        "start_line" to ToolParameter("start_line", ParameterType.INTEGER, "Starting line number (counting from 1).", required = false),
        "end_line" to ToolParameter("end_line", ParameterType.INTEGER, "Ending line number; the span from start_line is at most 2000 lines, truncated at 2000 lines if exceeded.", required = false)
    )

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        return try {
            val path = args["path"]?.jsonPrimitive?.contentOrNull ?: run {
                FileLogger.w(TAG, "read_file missing path argument")
                return ToolResult.Error("Missing required parameter: path", "MISSING_PATH")
            }
            FileLogger.d(TAG, "read_file path=$path")

            if (!fileAccess.exists(path)) {
                FileLogger.w(TAG, "read_file file does not exist: $path")
                return ToolResult.Error("File does not exist: $path", "FILE_NOT_FOUND")
            }

            val startLine = args["start_line"]?.jsonPrimitive?.intOrNull?.coerceAtLeast(1) ?: 1
            val requestedEnd = args["end_line"]?.jsonPrimitive?.intOrNull
            // Upper bound of the line window: the smaller of an explicit end_line and start_line+MAX_LINES; when not given, the MAX_LINES cap applies.
            val lineCap = startLine + MAX_LINES - 1
            val endCap = if (requestedEnd != null) minOf(requestedEnd, lineCap) else lineCap

            // Read line by line as a stream, avoiding readText() loading the whole file into memory and causing OOM on mobile;
            // keep only the [startLine, endCap] window, and stop early once the accumulated bytes exceed MAX_BYTES.
            // Run on the IO thread: neither local File reads nor the blocking reads/writes of a remote exec stream should occupy the caller's thread.
            val sb = StringBuilder()
            var totalLines = 0
            var emittedLines = 0
            var byteCount = 0
            var truncatedByBytes = false
            var lineNo = 0
            withContext(Dispatchers.IO) {
                fileAccess.readLines(path).forEach { line ->
                    lineNo++
                    totalLines = lineNo
                    if (lineNo < startLine) return@forEach
                    if (lineNo > endCap) {
                        // Past the window, but counting must continue to get an accurate total_lines.
                        return@forEach
                    }
                    if (!truncatedByBytes) {
                        val lineBytes = line.toByteArray(Charsets.UTF_8).size + 1
                        if (byteCount + lineBytes > MAX_BYTES && emittedLines > 0) {
                            truncatedByBytes = true
                        } else {
                            if (emittedLines > 0) sb.append('\n')
                            sb.append(line)
                            byteCount += lineBytes
                            emittedLines++
                        }
                    }
                }
            }

            val lastEmittedLine = startLine + emittedLines - 1
            // The last line of the window the user wants: if end_line is given, min(end_line, EOF); otherwise to EOF.
            // We only emitted up to lastEmittedLine; if it falls before the window's last line, the output was truncated and more content can be read.
            val wantedEnd = if (requestedEnd != null) minOf(requestedEnd, totalLines) else totalLines
            val truncated = emittedLines > 0 && lastEmittedLine < wantedEnd
            val note = when {
                !truncated -> null
                truncatedByBytes -> "Content exceeded the ${MAX_BYTES / 1024}KB limit and was truncated; continue reading from line ${lastEmittedLine + 1} using start_line."
                else -> "Content exceeded the $MAX_LINES-line limit and was truncated; continue reading from line ${lastEmittedLine + 1} using start_line."
            }

            FileLogger.v(TAG, "read_file succeeded path=$path total=$totalLines emitted=$emittedLines bytes=$byteCount truncated=$truncated")
            val resultMap = mutableMapOf<String, JsonElement>(
                "content" to JsonPrimitive(sb.toString()),
                "total_lines" to JsonPrimitive(totalLines),
                "start_line" to JsonPrimitive(startLine),
                "end_line" to JsonPrimitive(maxOf(lastEmittedLine, startLine - 1)),
                "read_lines" to JsonPrimitive(emittedLines),
                "truncated" to JsonPrimitive(truncated)
            )
            if (note != null) resultMap["note"] = JsonPrimitive(note)
            ToolResult.Success(JsonObject(resultMap))
        } catch (e: Exception) {
            FileLogger.e(TAG, "read_file error", e)
            ToolResult.Error(e.message ?: "Failed to read file", "READ_ERROR")
        }
    }

    private companion object {
        /** The maximum number of lines returned in one call; beyond that it truncates and hints to continue reading with start_line. */
        const val MAX_LINES = 2000
        /** The maximum number of bytes returned in one call (UTF-8, about 200KB), preventing very long lines from blowing up memory/context. */
        const val MAX_BYTES = 200 * 1024
    }
}

/**
 * Writes the whole file; if the file does not exist it is created automatically (including parent directories).
 *
 * The overwrite parameter absorbs the old create_file's "do not overwrite an existing file" semantics:
 * with overwrite=false and the target already existing, it reports an error, so it can be used to create new files safely.
 */
class WriteFileTool @Inject constructor(
    private val fileAccess: FileAccessProvider
) : AgentTool() {
    override val name = "writeFile"
    override val description = "Write the full file contents; the file is created automatically (including parent directories) if it does not exist. For partial edits, use editFile."
    override val permissionPolicy = ToolPermissionPolicy.ASK
    override val capabilities = setOf(ToolCapability.WRITE_WORKSPACE)
    override val parameters = mapOf(
        "path" to ToolParameter("path", ParameterType.STRING, "File path: ~/workspace/... for project files; other absolute paths (e.g. /etc/..., /root/...) are container system files.", required = true),
        "content" to ToolParameter("content", ParameterType.STRING, "File content", required = true),
        "overwrite" to ToolParameter("overwrite", ParameterType.BOOLEAN, "Whether to overwrite if the target already exists. Defaults to true; if set to false, an error is returned when the file already exists.", required = false)
    )

    override fun buildPermissionRequest(
        callId: String,
        args: Map<String, JsonElement>,
        argsPreview: String
    ): PendingToolPermission {
        val path = args["path"]?.jsonPrimitive?.contentOrNull ?: "Unknown file"
        val content = args["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val overwrite = args["overwrite"]?.jsonPrimitive?.booleanOrNull ?: true
        return PendingToolPermission(
            id = callId,
            toolName = name,
            title = "Confirm file write",
            summary = "AI requests to write $path",
            details = "Characters: ${content.length}\nLines: ${content.lines().size}\nAllow overwrite: ${if (overwrite) "Yes" else "No"}",
            argsPreview = argsPreview
        )
    }

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        return try {
            val path = args["path"]?.jsonPrimitive?.contentOrNull ?: run {
                FileLogger.w(TAG, "write_file missing path argument")
                return ToolResult.Error("Missing required parameter: path", "MISSING_PATH")
            }
            val content = args["content"]?.jsonPrimitive?.contentOrNull ?: ""
            val overwrite = args["overwrite"]?.jsonPrimitive?.booleanOrNull ?: true

            FileLogger.d(TAG, "write_file path=$path (${content.length} chars, overwrite=$overwrite)")
            val existed = fileAccess.exists(path)
            if (existed && !overwrite) {
                FileLogger.w(TAG, "write_file file already exists and overwrite=false: $path")
                return ToolResult.Error("File already exists: $path (overwrite=false)", "FILE_EXISTS")
            }

            // Keep the old content before writing, to generate an "old -> new" diff (isomorphic with edit_file; the UI renders a coloured diff from it).
            val oldContent = if (existed) runCatching { fileAccess.readFile(path) }.getOrDefault("") else ""

            fileAccess.writeFile(path, content, overwrite = true)

            // Generate the unified diff text: a new file is presented as "entirely added" (old content treated as empty, avoiding a line of fake deletion);
            // an overwrite computes the line-level additions/removals from old to new. LineDiff uses O(n*m) memory; when rewriting a very large file, skip LCS and
            // degrade to entirely-added, preventing OOM on mobile from building the DP table.
            val diff = buildWriteDiff(existed, oldContent, content)
            val added = diff.lines().count { it.startsWith("+") }
            val removed = diff.lines().count { it.startsWith("-") }
            val hunksJson = JsonArray(listOf(
                JsonObject(mapOf(
                    "start_line" to JsonPrimitive(1),
                    "added" to JsonPrimitive(added),
                    "removed" to JsonPrimitive(removed),
                    "diff" to JsonPrimitive(diff)
                ))
            ))

            FileLogger.v(TAG, "write_file succeeded path=$path created=${!existed} lines=${content.lines().size} (+$added -$removed)")
            ToolResult.Success(
                JsonObject(mapOf(
                    "path" to JsonPrimitive(fileAccess.toDisplayPath(path)),
                    "created" to JsonPrimitive(!existed),
                    "bytes_written" to JsonPrimitive(content.length),
                    "lines_written" to JsonPrimitive(content.lines().size),
                    "added_lines" to JsonPrimitive(added),
                    "removed_lines" to JsonPrimitive(removed),
                    "hunks" to hunksJson
                ))
            )
        } catch (e: Exception) {
            FileLogger.e(TAG, "write_file error", e)
            ToolResult.Error(e.message ?: "Failed to write file", "WRITE_ERROR")
        }
    }

    /**
     * Builds the unified diff text for write_file (each line starts with `+`/`-`/` `):
     * - New file: the old content is treated as empty, the whole file is presented as "entirely added", and LCS is not run;
     * - Overwrite at a manageable scale: computes the line-level LCS diff from old to new;
     * - Overwrite but either side has more than [MAX_DIFF_LINES] lines: skips the O(n*m) LCS (OOM-prone on mobile),
     *   and degrades to presenting the whole file as "entirely added", showing only the content as written to disk.
     */
    private fun buildWriteDiff(existed: Boolean, oldContent: String, newContent: String): String {
        val newLines = newContent.split("\n")
        if (!existed) return newLines.joinToString("\n") { "+$it" }
        val oldLines = oldContent.split("\n")
        if (maxOf(oldLines.size, newLines.size) > MAX_DIFF_LINES) {
            FileLogger.w(TAG, "write_file file too large, skipping LCS diff (old=${oldLines.size}, new=${newLines.size})")
            return newLines.joinToString("\n") { "+$it" }
        }
        return LineDiff.toUnified(oldContent, newContent)
    }

    private companion object {
        /** If either the old or new side exceeds this many lines, LCS is skipped: the DP table is O(n*m) ints, and too large a table drags down mobile memory. */
        const val MAX_DIFF_LINES = 2000
    }
}
