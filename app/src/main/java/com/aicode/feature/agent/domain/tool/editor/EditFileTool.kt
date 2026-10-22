package com.aicode.feature.agent.domain.tool.editor

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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

private const val TAG = "EditFileTool"

/**
 * A file editing tool based on exact string matching, replacing the old line-number insert/replace/delete trio.
 *
 * Why string matching rather than line numbers: during consecutive edits, the first change shifts all subsequent line numbers,
 * so the line-number approach is inherently fragile. String matching depends only on the content itself, and one tool covers three semantics:
 *   - Replace: old_string -> new_string
 *   - Delete: pass an empty string as new_string
 *   - Insert: replace a passage with "itself + the new content"
 *
 * One call can make several edits to the same file: pass an edits array, applied in array order,
 * with each edit matching on top of the result of the previous edit. The whole batch is all-or-nothing -- if any
 * edit fails to match (not found, or matching several places without replace_all enabled), the whole call writes nothing to disk and reports an error,
 * so the file is never left half-edited.
 *
 * For safety, by default each old_string must be unique in the current content; if there are multiple matches, provide longer
 * context, or explicitly set replace_all=true on that edit to replace them all.
 */
class EditFileTool @Inject constructor(
    private val fileAccess: FileAccessProvider
) : AgentTool() {
    override val name = "editFile"
    override val description =
        "Locally edit an existing file by exact string matching; this is the preferred way to modify files. edits can submit multiple changes at once, applied atomically as a batch (if any one fails, all are rolled back). Use writeFile to rewrite a whole file."
    override val permissionPolicy = ToolPermissionPolicy.ASK
    override val capabilities = setOf(ToolCapability.WRITE_WORKSPACE)

    /** The structure of one element of the edits array, for the function-calling items schema. */
    private val editItemSchema: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "old_string" to mapOf(
                "type" to "string",
                "description" to "The original text to be replaced; must exactly match the file's current content (including indentation and line breaks). Include enough context to ensure uniqueness."
            ),
            "new_string" to mapOf(
                "type" to "string",
                "description" to "The new content after replacement. Pass an empty string to delete the matched content."
            ),
            "replace_all" to mapOf(
                "type" to "boolean",
                "description" to "Whether to replace all matches of this old_string. Defaults to false (requires a unique match)."
            )
        ),
        "required" to listOf("old_string", "new_string")
    )

    override val parameters = mapOf(
        "path" to ToolParameter("path", ParameterType.STRING, "File path: ~/workspace/... for project files; other absolute paths (e.g. /etc/..., /root/...) are container system files.", required = true),
        "edits" to ToolParameter(
            "edits",
            ParameterType.ARRAY,
            "The list of edits to apply, applied in order; each edit matches against the result of the previous one." +
                "Use a single-element array even for a single change. Each element: {old_string, new_string, replace_all?}.",
            required = true,
            itemsSchema = editItemSchema
        )
    )

    /** One parsed edit. */
    private data class Edit(val oldString: String, val newString: String, val replaceAll: Boolean)

    /** The difference after applying one edit, for UI rendering and feeding back to the model. */
    private data class Hunk(val startLine: Int, val added: Int, val removed: Int, val diff: String)

    override fun buildPermissionRequest(
        callId: String,
        args: Map<String, JsonElement>,
        argsPreview: String
    ): PendingToolPermission {
        val path = args["path"]?.jsonPrimitive?.contentOrNull ?: "unknown file"
        val editCount = (args["edits"] as? JsonArray)?.size ?: 1
        return PendingToolPermission(
            id = callId,
            toolName = name,
            title = "Confirm file edit",
            summary = "AI requests to edit $path",
            details = "Number of edits: $editCount\nThe tool matches everything first; if any edit fails, nothing is written.",
            argsPreview = argsPreview
        )
    }

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        return try {
            val path = args["path"]?.jsonPrimitive?.contentOrNull
                ?: return ToolResult.Error("Missing required parameter: path", "MISSING_PATH")

            val edits = parseEdits(args)
                ?: return ToolResult.Error("Missing required parameter: edits", "MISSING_EDITS")

            // Validate each edit itself for validity (empty old_string / no change), avoiding meaningless changes.
            edits.forEachIndexed { i, e ->
                if (e.oldString.isEmpty()) {
                    return ToolResult.Error("Edit ${i + 1}'s old_string must not be empty; use writeFile to create a file", "EMPTY_OLD_STRING")
                }
                if (e.oldString == e.newString) {
                    return ToolResult.Error("Edit ${i + 1}'s old_string and new_string are identical; no change needed", "NO_OP")
                }
            }

            FileLogger.d(TAG, "edit_file path=$path (edits=${edits.size})")
            if (!fileAccess.exists(path)) {
                FileLogger.w(TAG, "edit_file file does not exist: $path")
                return ToolResult.Error("File does not exist: $path", "FILE_NOT_FOUND")
            }

            // First apply all edits in order in memory; any failure returns immediately and never writes to disk (all or nothing).
            var content = fileAccess.readFile(path)
            val hunks = ArrayList<Hunk>(edits.size)
            var totalReplacements = 0

            edits.forEachIndexed { i, e ->
                val occurrences = content.split(e.oldString).size - 1
                if (occurrences == 0) {
                    FileLogger.w(TAG, "edit_file edit ${i + 1} did not match: $path")
                    return ToolResult.Error(
                        "Edit ${i + 1}'s old_string was not found in the file; make sure the content (including indentation/line breaks) matches the current file exactly",
                        "NO_MATCH"
                    )
                }
                if (occurrences > 1 && !e.replaceAll) {
                    FileLogger.w(TAG, "edit_file edit ${i + 1} matched $occurrences places and replace_all is not set: $path")
                    return ToolResult.Error(
                        "Edit ${i + 1}'s old_string matches $occurrences places in the file; provide a longer unique context, or set replace_all=true for this edit",
                        "MULTIPLE_MATCHES"
                    )
                }

                // The starting line number (1-based) of the change in the content "before this edit is applied". Because the content already includes the results of all previous edits,
                // this line number already reflects the line drift caused by earlier edits, and is accurate for the usual top-to-bottom edit order.
                val matchIndex = content.indexOf(e.oldString)
                val startLine = if (matchIndex >= 0) content.substring(0, matchIndex).count { it == '\n' } + 1 else 1

                val diff = LineDiff.toUnified(e.oldString, e.newString)
                val added = diff.lines().count { it.startsWith("+") }
                val removed = diff.lines().count { it.startsWith("-") }
                hunks.add(Hunk(startLine = startLine, added = added, removed = removed, diff = diff))

                content = if (e.replaceAll) content.replace(e.oldString, e.newString)
                else content.replaceFirst(e.oldString, e.newString)
                totalReplacements += if (e.replaceAll) occurrences else 1
            }

            fileAccess.writeFile(path, content, overwrite = true)

            val addedTotal = hunks.sumOf { it.added }
            val removedTotal = hunks.sumOf { it.removed }
            val hunksJson = JsonArray(hunks.map { h ->
                JsonObject(mapOf(
                    "start_line" to JsonPrimitive(h.startLine),
                    "added" to JsonPrimitive(h.added),
                    "removed" to JsonPrimitive(h.removed),
                    "diff" to JsonPrimitive(h.diff)
                ))
            })

            FileLogger.v(TAG, "edit_file succeeded path=$path edits=${edits.size} replacements=$totalReplacements")
            ToolResult.Success(
                JsonObject(mapOf(
                    "status" to JsonPrimitive("edited"),
                    "path" to JsonPrimitive(fileAccess.toDisplayPath(path)),
                    "edits_count" to JsonPrimitive(edits.size),
                    "replacements" to JsonPrimitive(totalReplacements),
                    "total_lines" to JsonPrimitive(content.lines().size),
                    "added_lines" to JsonPrimitive(addedTotal),
                    "removed_lines" to JsonPrimitive(removedTotal),
                    "hunks" to hunksJson
                ))
            )
        } catch (e: Exception) {
            FileLogger.e(TAG, "edit_file error", e)
            ToolResult.Error(e.message ?: "Failed to edit file", "EDIT_ERROR")
        }
    }

    /**
     * Parses the edit list: reads the edits array.
     */
    private fun parseEdits(args: Map<String, JsonElement>): List<Edit>? {
        val arr = args["edits"] as? JsonArray ?: return null
        if (arr.isEmpty()) return null
        return arr.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val old = obj["old_string"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val new = obj["new_string"]?.jsonPrimitive?.contentOrNull ?: ""
            val all = obj["replace_all"]?.jsonPrimitive?.booleanOrNull ?: false
            Edit(old, new, all)
        }.takeIf { it.isNotEmpty() }
    }
}
