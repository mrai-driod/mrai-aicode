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
                FileLogger.w(TAG, "read_file 缺少 path 参数")
                return ToolResult.Error("Missing required parameter: path", "MISSING_PATH")
            }
            FileLogger.d(TAG, "read_file path=$path")

            if (!fileAccess.exists(path)) {
                FileLogger.w(TAG, "read_file 文件不存在: $path")
                return ToolResult.Error("File does not exist: $path", "FILE_NOT_FOUND")
            }

            val startLine = args["start_line"]?.jsonPrimitive?.intOrNull?.coerceAtLeast(1) ?: 1
            val requestedEnd = args["end_line"]?.jsonPrimitive?.intOrNull
            // 行窗口上界：显式 end_line 与 start_line+MAX_LINES 取较小值，缺省则按 MAX_LINES 上限。
            val lineCap = startLine + MAX_LINES - 1
            val endCap = if (requestedEnd != null) minOf(requestedEnd, lineCap) else lineCap

            // 逐行流式读取，避免 readText() 整篇进内存导致移动端 OOM；
            // 只保留 [startLine, endCap] 窗口，并在累计字节超过 MAX_BYTES 时提前停止。
            // 走 IO 线程：本地 File 读与远程 exec 流的阻塞读写都不应占着调用方线程。
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
                        // 已越过窗口，但仍需继续计数以得到准确 total_lines。
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
            // 用户想要的窗口末行：给了 end_line 取 min(end_line, EOF)，否则到 EOF。
            // 我们只发到了 lastEmittedLine，若它落在窗口末行之前，说明被截断、还有内容可读。
            val wantedEnd = if (requestedEnd != null) minOf(requestedEnd, totalLines) else totalLines
            val truncated = emittedLines > 0 && lastEmittedLine < wantedEnd
            val note = when {
                !truncated -> null
                truncatedByBytes -> "Content exceeded the ${MAX_BYTES / 1024}KB limit and was truncated; continue reading from line ${lastEmittedLine + 1} using start_line."
                else -> "Content exceeded the $MAX_LINES-line limit and was truncated; continue reading from line ${lastEmittedLine + 1} using start_line."
            }

            FileLogger.v(TAG, "read_file 成功 path=$path total=$totalLines emitted=$emittedLines bytes=$byteCount truncated=$truncated")
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
            FileLogger.e(TAG, "read_file 异常", e)
            ToolResult.Error(e.message ?: "Failed to read file", "READ_ERROR")
        }
    }

    private companion object {
        /** 单次最多返回的行数，超出截断并提示用 start_line 续读。 */
        const val MAX_LINES = 2000
        /** 单次最多返回的字节数（UTF-8，约 200KB），防止超大行撑爆内存/上下文。 */
        const val MAX_BYTES = 200 * 1024
    }
}

/**
 * 写入整个文件，文件不存在时会自动创建（含父目录）。
 *
 * 通过 overwrite 参数吸收了旧 create_file 的「不覆盖已有文件」语义：
 * overwrite=false 且目标已存在时报错，可用于安全地新建文件。
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
                FileLogger.w(TAG, "write_file 缺少 path 参数")
                return ToolResult.Error("Missing required parameter: path", "MISSING_PATH")
            }
            val content = args["content"]?.jsonPrimitive?.contentOrNull ?: ""
            val overwrite = args["overwrite"]?.jsonPrimitive?.booleanOrNull ?: true

            FileLogger.d(TAG, "write_file path=$path (${content.length} 字符, overwrite=$overwrite)")
            val existed = fileAccess.exists(path)
            if (existed && !overwrite) {
                FileLogger.w(TAG, "write_file 文件已存在且 overwrite=false: $path")
                return ToolResult.Error("File already exists: $path (overwrite=false)", "FILE_EXISTS")
            }

            // 写前留存旧内容，供生成「旧→新」差异（与 edit_file 同构，UI 据此渲染彩色 diff）。
            val oldContent = if (existed) runCatching { fileAccess.readFile(path) }.getOrDefault("") else ""

            fileAccess.writeFile(path, content, overwrite = true)

            // 生成统一差异文本：新建文件按「整体新增」呈现（旧内容视为空，避免一行伪删除）；
            // 覆盖写则计算旧→新的行级增删。LineDiff 为 O(n·m) 内存，超大文件重写时跳过 LCS、
            // 退化为整体新增，防止移动端因构造 DP 表而 OOM。
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

            FileLogger.v(TAG, "write_file 成功 path=$path created=${!existed} lines=${content.lines().size} (+$added -$removed)")
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
            FileLogger.e(TAG, "write_file 异常", e)
            ToolResult.Error(e.message ?: "Failed to write file", "WRITE_ERROR")
        }
    }

    /**
     * 构造 write_file 的统一差异文本（每行以 `+`/`-`/` ` 起头）：
     * - 新建文件：旧内容视为空，整篇按「全部新增」呈现，不跑 LCS；
     * - 覆盖写且规模可控：计算旧→新的行级 LCS 差异；
     * - 覆盖写但任一侧行数超过 [MAX_DIFF_LINES]：跳过 O(n·m) 的 LCS（移动端易 OOM），
     *   退化为整篇「全部新增」，仅展示落盘后的内容。
     */
    private fun buildWriteDiff(existed: Boolean, oldContent: String, newContent: String): String {
        val newLines = newContent.split("\n")
        if (!existed) return newLines.joinToString("\n") { "+$it" }
        val oldLines = oldContent.split("\n")
        if (maxOf(oldLines.size, newLines.size) > MAX_DIFF_LINES) {
            FileLogger.w(TAG, "write_file 文件过大跳过 LCS 差异 (old=${oldLines.size}, new=${newLines.size})")
            return newLines.joinToString("\n") { "+$it" }
        }
        return LineDiff.toUnified(oldContent, newContent)
    }

    private companion object {
        /** 旧/新任一侧行数超过此值即跳过 LCS：DP 表为 O(n·m) ints，过大会拖垮移动端内存。 */
        const val MAX_DIFF_LINES = 2000
    }
}
