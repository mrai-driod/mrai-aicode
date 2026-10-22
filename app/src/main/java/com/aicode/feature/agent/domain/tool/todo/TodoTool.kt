package com.aicode.feature.agent.domain.tool.todo

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.data.local.dao.TodoItemDao
import com.aicode.feature.agent.data.local.entity.TodoItemEntity
import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.model.TodoStatus
import com.aicode.feature.agent.domain.tool.AbstractContextualTool
import com.aicode.feature.agent.domain.tool.ParameterType
import com.aicode.feature.agent.domain.tool.ToolParameter
import com.aicode.feature.agent.domain.tool.ToolCapability
import com.aicode.feature.agent.domain.tool.ToolPermissionPolicy
import com.aicode.feature.agent.domain.tool.ToolResult
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import javax.inject.Inject

/**
 * Manages the AI Agent's task list (todo list) for the current session.
 *
 * The tool uses a snapshot interface: each time the current full items list is passed in, the tool replaces the session's todo list with it.
 * The AI does not need to query or remember todo_id, nor distinguish create, update and delete actions.
 */
class TodoTool @Inject constructor(
    private val todoItemDao: TodoItemDao
) : AbstractContextualTool() {

    private companion object {
        const val TAG = "TodoTool"
    }

    override val name = "todo"
    override val description = "Replace the session task list with the current complete items list. Just submit the complete list, " +
        "with no action, todo_id, or per-item updates needed. items can be an empty array to clear the list; each item is " +
        "{subject, description, status, priority}. status can be pending, in_progress, or completed."

    override val permissionPolicy = ToolPermissionPolicy.AUTO_APPROVE
    override val capabilities = setOf(ToolCapability.MODIFY_TODO_STATE)

    /** The JSON Schema of a single todo item in the items array */
    private val todoItemSchema: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "subject" to mapOf(
                "type" to "string",
                "description" to "Short todo title (imperative sentence, e.g. 'Analyze project structure')"
            ),
            "description" to mapOf(
                "type" to "string",
                "description" to "Detailed description (optional)"
            ),
            "status" to mapOf(
                "type" to "string",
                "enum" to listOf("pending", "in_progress", "completed"),
                "description" to "Status, defaults to pending. in_progress means in progress, completed means done."
            ),
            "priority" to mapOf(
                "type" to "integer",
                "description" to "Priority, 0=normal, larger values mean higher priority"
            )
        ),
        "required" to listOf("subject")
    )

    override val parameters: Map<String, ToolParameter> = mapOf(
        "items" to ToolParameter(
            name = "items",
            type = ParameterType.ARRAY,
            description = "The current complete todo list. Passing an empty array clears the task list; each item is an object containing subject.",
            required = true,
            itemsSchema = todoItemSchema
        )
    )

    override suspend fun executeWithContext(
        args: Map<String, JsonElement>,
        context: AgentContext
    ): ToolResult {
        val sessionId = context.sessionId
            ?: return ToolResult.Error("No associated session", "NO_SESSION")

        return try {
            replaceTodos(args, sessionId)
        } catch (e: Exception) {
            FileLogger.e(TAG, "todo tool execution failed: ${e.message}", e)
            ToolResult.Error("Todo operation failed: ${e.message}")
        }
    }

    private suspend fun replaceTodos(args: Map<String, JsonElement>, sessionId: String): ToolResult {
        val itemElements = args["items"] as? JsonArray
            ?: return ToolResult.Error("Missing required parameter: items", "MISSING_ITEMS")
        val existingBySubject = todoItemDao.getBySessionOnce(sessionId)
            .groupBy { normalizeSubject(it.subject) }
            .mapValues { (_, items) -> items.toMutableList() }
        val now = System.currentTimeMillis()
        val entities = mutableListOf<TodoItemEntity>()

        for ((idx, element) in itemElements.withIndex()) {
            val draft = parseTodoDraft(element, idx) ?: return ToolResult.Error(
                "Item ${idx + 1} is missing a string title or subject field",
                "INVALID_ITEM"
            )
            val previous = existingBySubject[normalizeSubject(draft.subject)]?.removeFirstOrNull()

            entities.add(TodoItemEntity(
                id = previous?.id ?: UUID.randomUUID().toString(),
                sessionId = sessionId,
                subject = draft.subject,
                description = draft.description,
                status = draft.status.name,
                priority = draft.priority,
                order = idx,
                createdAt = previous?.createdAt ?: now,
                updatedAt = now
            ))
        }

        todoItemDao.deleteBySession(sessionId)
        if (entities.isNotEmpty()) {
            todoItemDao.upsertAll(entities)
        }
        FileLogger.d(TAG, "todo replace: synced ${entities.size} todo items")

        return listTodos(sessionId)
    }

    private suspend fun listTodos(sessionId: String): ToolResult {
        val items = todoItemDao.getBySessionOnce(sessionId)
        val total = items.size
        val completed = items.count { it.status == "COMPLETED" }

        return ToolResult.Success(JsonObject(mapOf(
            "total" to JsonPrimitive(total),
            "completed" to JsonPrimitive(completed),
            "items" to JsonArray(
                items.map { entity ->
                    JsonObject(mapOf(
                        "id" to JsonPrimitive(entity.id),
                        "subject" to JsonPrimitive(entity.subject),
                        "description" to JsonPrimitive(entity.description),
                        "status" to JsonPrimitive(entity.status.lowercase()),
                        "priority" to JsonPrimitive(entity.priority),
                        "order" to JsonPrimitive(entity.order)
                    ))
                }
            )
        )))
    }

    private fun parseTodoDraft(element: JsonElement, index: Int): TodoDraft? {
        if (element is JsonPrimitive) {
            val subject = element.contentOrNull?.trim().orEmpty()
            return if (subject.isBlank()) null else TodoDraft(subject = subject)
        }

        val obj = runCatching { element.jsonObject }.getOrNull() ?: return null
        val subject = obj["subject"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (subject.isBlank()) return null

        val status = parseStatus(obj["status"]?.jsonPrimitive?.contentOrNull)
            ?: throw IllegalArgumentException("Item ${index + 1} has an invalid status")

        return TodoDraft(
            subject = subject,
            description = obj["description"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty(),
            status = status,
            priority = obj["priority"]?.jsonPrimitive?.intOrNull ?: 0
        )
    }

    private fun parseStatus(raw: String?): TodoStatus? {
        val normalized = raw
            ?.trim()
            ?.replace("-", "_")
            ?.replace(" ", "_")
            ?.uppercase()
            ?: return TodoStatus.PENDING
        return runCatching { TodoStatus.valueOf(normalized) }.getOrNull()
    }

    private fun normalizeSubject(subject: String): String {
        return subject.trim().lowercase()
    }
}

private data class TodoDraft(
    val subject: String,
    val description: String = "",
    val status: TodoStatus = TodoStatus.PENDING,
    val priority: Int = 0
)
