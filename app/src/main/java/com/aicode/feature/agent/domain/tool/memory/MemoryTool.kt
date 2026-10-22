package com.aicode.feature.agent.domain.tool.memory

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.memory.MemoryEdit
import com.aicode.feature.agent.domain.memory.MemoryEditResult
import com.aicode.feature.agent.domain.memory.MemoryRepository
import com.aicode.feature.agent.domain.memory.MemoryScope
import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.tool.AbstractContextualTool
import com.aicode.feature.agent.domain.tool.ParameterType
import com.aicode.feature.agent.domain.tool.ToolCapability
import com.aicode.feature.agent.domain.tool.ToolParameter
import com.aicode.feature.agent.domain.tool.ToolPermissionPolicy
import com.aicode.feature.agent.domain.tool.ToolResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

class MemoryTool @Inject constructor(
    private val memoryRepository: MemoryRepository
) : AbstractContextualTool() {
    private companion object {
        const val TAG = "MemoryTool"
    }

    override val name = "memory"
    override val permissionPolicy = ToolPermissionPolicy.AUTO_APPROVE
    override val capabilities = setOf(ToolCapability.READ_AGENT_CONFIG, ToolCapability.MODIFY_AGENT_CONFIG)

    override fun effectiveCapabilities(args: Map<String, JsonElement>): Set<ToolCapability> {
        return when (args["action"]?.jsonPrimitive?.contentOrNull) {
            "read", "list" -> setOf(ToolCapability.READ_AGENT_CONFIG)
            else -> capabilities
        }
    }
    override val description =
        "Manage the AI's long-term memory (read/save/local edit/delete/list). Proactively record new user preferences, project conventions, or architecture decisions when you discover them."

    /** edits 数组单个元素的结构，供 function-calling 的 items schema，语义与 editFile 一致。 */
    private val editItemSchema: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "old_string" to mapOf(
                "type" to "string",
                "description" to "The original text to be replaced; must exactly match the current memory body (including indentation and line breaks). Include enough context to ensure uniqueness."
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

    override val parameters: Map<String, ToolParameter> = mapOf(
        "action" to ToolParameter(
            name = "action",
            type = ParameterType.STRING,
            description = "Operation type: read=read the memory body; save=save (create or fully overwrite); edit=locally edit an existing body; delete=delete; list=list summaries of all memories",
            enum = listOf("read", "save", "edit", "delete", "list"),
            required = true
        ),
        "name" to ToolParameter(
            name = "name",
            type = ParameterType.STRING,
            description = "Short name for the memory (used as the file name, e.g. conventions). May be omitted for the list operation.",
            required = false
        ),
        "description" to ToolParameter(
            name = "description",
            type = ParameterType.STRING,
            description = "One-sentence summary (required for save; appears in the memory list in the system prompt).",
            required = false
        ),
        "content" to ToolParameter(
            name = "content",
            type = ParameterType.STRING,
            description = "The detailed body of the memory (Markdown format, required for save).",
            required = false
        ),
        "edits" to ToolParameter(
            name = "edits",
            type = ParameterType.ARRAY,
            description = "The list of edits to apply for the edit operation, applied in order; each edit matches against the result of the previous one." +
                "Use a single-element array even for a single change. Each element: {old_string, new_string, replace_all?}.",
            required = false,
            itemsSchema = editItemSchema
        ),
        "scope" to ToolParameter(
            name = "scope",
            type = ParameterType.STRING,
            description = "Scope: project=specific to the current project; global=shared across projects. Defaults to project.",
            enum = listOf("project", "global"),
            required = false
        )
    )

    override suspend fun executeWithContext(
        args: Map<String, JsonElement>,
        context: AgentContext
    ): ToolResult {
        val action = args["action"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: return ToolResult.Error("Missing required parameter: action", "MISSING_ACTION")
        
        val memoryName = args["name"]?.jsonPrimitive?.contentOrNull?.trim()
        val scopeStr = args["scope"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
        val scope = if (scopeStr == "global") MemoryScope.GLOBAL else MemoryScope.PROJECT

        return try {
            when (action) {
                "list" -> handleList(context.projectRoot)
                "read" -> handleRead(memoryName, context.projectRoot)
                "save" -> handleSave(args, memoryName, scope, context.projectRoot)
                "edit" -> handleEdit(args, memoryName, scope, context.projectRoot)
                "delete" -> handleDelete(memoryName, scope, context.projectRoot)
                else -> ToolResult.Error("Unsupported operation: $action", "UNSUPPORTED_ACTION")
            }
        } catch (e: Exception) {
            FileLogger.e(TAG, "Memory 工具执行失败: ${e.message}", e)
            ToolResult.Error("Memory operation failed: ${e.message}")
        }
    }

    private fun handleList(projectRoot: String?): ToolResult {
        val memories = memoryRepository.listMemories(projectRoot)
        if (memories.isEmpty()) return ToolResult.Success(JsonPrimitive("There are currently no memories."))
        
        val list = memories.joinToString("\n") { "- ${it.name} (${it.scope.name.lowercase()}): ${it.description}" }
        return ToolResult.Success(JsonPrimitive("Current memory list:\n$list"))
    }

    private fun handleRead(name: String?, projectRoot: String?): ToolResult {
        if (name.isNullOrEmpty()) return ToolResult.Error("Missing required parameter for read operation: name", "MISSING_NAME")
        val content = memoryRepository.loadContent(name, projectRoot)
            ?: return ToolResult.Error("Memory 「$name」 not found", "MEMORY_NOT_FOUND")
        return ToolResult.Success(JsonPrimitive(content))
    }

    private fun handleSave(args: Map<String, JsonElement>, name: String?, scope: MemoryScope, projectRoot: String?): ToolResult {
        if (name.isNullOrEmpty()) return ToolResult.Error("Missing required parameter for save operation: name", "MISSING_NAME")
        val description = args["description"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: return ToolResult.Error("Missing required parameter for save operation: description", "MISSING_DESCRIPTION")
        val content = args["content"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: return ToolResult.Error("Missing required parameter for save operation: content", "MISSING_CONTENT")

        if (scope == MemoryScope.PROJECT && projectRoot.isNullOrBlank()) {
            return ToolResult.Error("No workspace is currently selected, so project-level memory cannot be saved; use scope=global instead", "NO_WORKSPACE")
        }

        val success = memoryRepository.saveMemory(name, description, content, scope, projectRoot)
        return if (success) {
            ToolResult.Success(JsonPrimitive("Saved memory 「$name」 to the ${scope.name.lowercase()} scope. Its summary will be injected automatically when the next session starts. To use it immediately in the current session, read it with the read operation."))
        } else {
            ToolResult.Error("Failed to save memory; see the logs.", "SAVE_FAILED")
        }
    }

    private fun handleEdit(args: Map<String, JsonElement>, name: String?, scope: MemoryScope, projectRoot: String?): ToolResult {
        if (name.isNullOrEmpty()) return ToolResult.Error("Missing required parameter for edit operation: name", "MISSING_NAME")

        val edits = parseEdits(args)
            ?: return ToolResult.Error("Missing required parameter for edit operation: edits", "MISSING_EDITS")

        if (scope == MemoryScope.PROJECT && projectRoot.isNullOrBlank()) {
            return ToolResult.Error("No workspace is currently selected, so project-level memory cannot be edited; use scope=global instead", "NO_WORKSPACE")
        }

        return when (val result = memoryRepository.editMemory(name, edits, scope, projectRoot)) {
            is MemoryEditResult.Success ->
                ToolResult.Success(JsonPrimitive("Edited the body of memory 「$name」 (${scope.name.lowercase()} scope)."))
            is MemoryEditResult.NotFound ->
                ToolResult.Error("Memory 「${result.name}」 not found; create it with save first, or check that the name and scope are correct.", "MEMORY_NOT_FOUND")
            is MemoryEditResult.Error ->
                ToolResult.Error(result.message, result.code)
        }
    }

    private fun parseEdits(args: Map<String, JsonElement>): List<MemoryEdit>? {
        val arr = args["edits"] as? JsonArray ?: return null
        if (arr.isEmpty()) return null
        return arr.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val old = obj["old_string"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val new = obj["new_string"]?.jsonPrimitive?.contentOrNull ?: ""
            val all = obj["replace_all"]?.jsonPrimitive?.booleanOrNull ?: false
            MemoryEdit(old, new, all)
        }.takeIf { it.isNotEmpty() }
    }

    private fun handleDelete(name: String?, scope: MemoryScope, projectRoot: String?): ToolResult {
        if (name.isNullOrEmpty()) return ToolResult.Error("Missing required parameter for delete operation: name", "MISSING_NAME")
        
        val success = memoryRepository.deleteMemory(name, scope, projectRoot)
        return if (success) {
            ToolResult.Success(JsonPrimitive("Deleted memory 「$name」 from the ${scope.name.lowercase()} scope."))
        } else {
            ToolResult.Error("Delete failed; memory 「$name」 may not exist in this scope.", "DELETE_FAILED")
        }
    }
}
