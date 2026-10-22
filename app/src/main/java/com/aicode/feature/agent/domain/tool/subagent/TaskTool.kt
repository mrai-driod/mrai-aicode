package com.aicode.feature.agent.domain.tool.subagent

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.data.local.dao.AgentMessageDao
import com.aicode.feature.agent.data.local.dao.ChatSessionDao
import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.model.ReasoningEffort
import com.aicode.feature.agent.domain.session.SessionUseCase
import com.aicode.feature.agent.domain.subagent.AgentDefinition
import com.aicode.feature.agent.domain.subagent.AgentDefinitionRepository
import com.aicode.feature.agent.domain.subagent.SubAgentEvent
import com.aicode.feature.agent.domain.subagent.SubAgentEventBus
import com.aicode.feature.agent.domain.subagent.SubAgentEventType
import com.aicode.feature.agent.domain.tool.AbstractContextualTool
import com.aicode.feature.agent.domain.tool.ParameterType
import com.aicode.feature.agent.domain.tool.ToolCapability
import com.aicode.feature.agent.domain.tool.ToolParameter
import com.aicode.feature.agent.domain.tool.ToolPermissionPolicy
import com.aicode.feature.agent.domain.tool.ToolResult
import com.aicode.feature.agent.presentation.MessageRole
import com.aicode.feature.settings.domain.repository.AIProviderRepository
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import javax.inject.Inject

/**
 * The sub-agent tool `task`: centrally manages sub-agents' lifecycles.
 *
 * Operations are distinguished by the `action` parameter:
 * - `create` (default): creates a sub-agent session and has the AI send it a message on the user's behalf; the sub-agent starts replying automatically.
 * - `send`: sends a message to the specified sub-agent (can be sent repeatedly). A running sub-agent receives it piggybacked in the next batch of tool results;
 *   a completed sub-agent is reawakened; messages are delivered in send order.
 * - `read`: reads the specified sub-agent's last output (its last assistant reply).
 * - `stop`: stops the specified sub-agent's execution (cancels its AI task).
 * - `del`: deletes the specified sub-agent session and all its messages.
 * - `list`: lists all sub-agents of the current session and their states.
 *
 * At most 5 sub-agents may run at the same time (the cap is checked on create).
 * Sub-agents cannot create nested sub-agents (their tool set does not include `task`).
 *
 * `create` can use the `agent` parameter to specify a custom sub-agent definition (`agents/<name>.md`),
 * which determines that sub-session's model, tool set and system prompt; when omitted, the default general sub-agent inheriting the parent session's model is used.
 */
class TaskTool @Inject constructor(
    private val sessionUseCase: SessionUseCase,
    private val chatSessionDao: ChatSessionDao,
    private val agentMessageDao: AgentMessageDao,
    private val eventBus: SubAgentEventBus,
    private val agentDefinitionRepository: AgentDefinitionRepository,
    private val aiProviderRepository: AIProviderRepository
) : AbstractContextualTool() {

    private companion object {
        const val TAG = "TaskTool"
        const val TASK_DESCRIPTION_MAX = 30
        /** The type identifier recorded for a sub-session when no agent is specified. */
        const val DEFAULT_SUBAGENT_TYPE = "subagent"
    }

    override val name = "task"
    override val permissionPolicy = ToolPermissionPolicy.ASK
    override val capabilities = setOf(ToolCapability.MODIFY_SESSION_STATE)

    override fun effectiveCapabilities(args: Map<String, JsonElement>): Set<ToolCapability> {
        val action = (args["action"] as? JsonPrimitive)?.content?.trim()?.lowercase() ?: "create"
        return when (action) {
            "read", "list", "send" -> emptySet()
            else -> setOf(ToolCapability.MODIFY_SESSION_STATE)
        }
    }

    override val description = "Manage subagents: create, send messages, read results, stop, delete, list. Subagents have their own independent context and full tool capabilities, can work in parallel, and at most 5 may run at once. You will receive a background notification when one completes; do not poll. Use send to repeatedly add instructions or follow up with a completed subagent; a running subagent may also proactively send messages. For create, use agent to specify a custom subagent."

    override val parameters: Map<String, ToolParameter> = mapOf(
        "action" to ToolParameter(
            name = "action",
            type = ParameterType.STRING,
            description = "Action type: create (default; create a subagent and run a task) / send (send a message to a subagent, can be sent repeatedly) / read (read a subagent's last output) / stop (stop a subagent's execution) / del (delete a subagent session and its messages) / list (list all subagents of the current session)",
            required = false
        ),
        "id" to ToolParameter(
            name = "id",
            type = ParameterType.STRING,
            description = "Sub-session id (required for read/send/stop/del; taken from the id returned by task)",
            required = false
        ),
        "description" to ToolParameter(
            name = "description",
            type = ParameterType.STRING,
            description = "Subagent task description (for create; used as the sub-session title, e.g. 'Fix the login bug')",
            required = false
        ),
        "prompt" to ToolParameter(
            name = "prompt",
            type = ParameterType.STRING,
            description = "Complete instruction for the subagent (required for create); it will be its first user message, and the subagent sees a fresh context",
            required = false
        ),
        "agent" to ToolParameter(
            name = "agent",
            type = ParameterType.STRING,
            description = "Custom subagent name (optional for create): take a name from the 'Available subagents' list in the system prompt; it runs with its own dedicated prompt, model, and tool set. If omitted, the default general-purpose subagent inheriting this session's model is used",
            required = false
        ),
        "message" to ToolParameter(
            name = "message",
            type = ParameterType.STRING,
            description = "Body of the message to send to the subagent (required for send). Can be called repeatedly; a running subagent receives it as soon as possible, a completed one is reawakened",
            required = false
        )
    )

    override suspend fun executeWithContext(args: Map<String, JsonElement>, context: AgentContext): ToolResult {
        val action = (args["action"] as? JsonPrimitive)?.content?.trim()?.lowercase() ?: "create"
        return when (action) {
            "create" -> createSubagent(args, context)
            "send" -> sendToSubagent(args, context)
            "read" -> readSubagent(args, context)
            "stop" -> stopSubagent(args, context)
            "del" -> deleteSubagent(args, context)
            "list" -> listSubagents(context)
            else -> ToolResult.Error("Unknown action: $action. Supported: create / send / read / stop / del / list", "INVALID_ARGS")
        }
    }

    /** Creates a sub-agent and starts execution. */
    private suspend fun createSubagent(args: Map<String, JsonElement>, context: AgentContext): ToolResult {
        val parentSessionId = context.sessionId ?: return ToolResult.Error("Missing session context", "NO_SESSION")
        val parentSession = sessionUseCase.getSessionById(parentSessionId)
            ?: return ToolResult.Error("Current session does not exist", "SESSION_NOT_FOUND")

        // Check the concurrency cap
        if (eventBus.isFull) {
            return ToolResult.Error(
                "Subagent limit reached (at most ${SubAgentEventBus.MAX_RUNNING} running at once). Wait for one to complete or stop one with stop before creating another",
                "MAX_SUBAGENTS_REACHED"
            )
        }

        val prompt = (args["prompt"] as? JsonPrimitive)?.contentOrNull?.trim()
        if (prompt.isNullOrBlank()) {
            return ToolResult.Error("Invalid parameter: prompt must not be empty", "INVALID_ARGS")
        }
        val description = (args["description"] as? JsonPrimitive)?.contentOrNull?.trim()
            ?.replace(Regex("\\s+"), " ")
            ?.take(TASK_DESCRIPTION_MAX)
            ?: "Subagent task"

        // When an agent is specified its definition must be findable: a misspelled name reports an error and lists the available names, rather than silently falling back to a general sub-agent;
        // otherwise it would run the whole task with the wrong tool set and prompt.
        val agentName = (args["agent"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
        var definition: AgentDefinition? = null
        if (agentName != null) {
            definition = agentDefinitionRepository.find(agentName)
            if (definition == null) {
                val available = agentDefinitionRepository.listEnabled().map { it.definition.name }
                val hint = if (available.isEmpty()) "No custom subagents are currently defined" else "Available: ${available.joinToString(", ")}"
                return ToolResult.Error("Subagent definition does not exist: $agentName ($hint)", "AGENT_NOT_FOUND")
            }
        }

        // Create the sub-agent session
        val subSession = sessionUseCase.newSubSessionEntity(
            title = description,
            parentId = parentSessionId,
            parent = parentSession,
            subagentType = definition?.name ?: DEFAULT_SUBAGENT_TYPE,
            providerId = definition?.providerId?.let { resolveProviderId(it) },
            model = definition?.model,
            reasoningEffort = definition?.reasoningEffort?.let { effort ->
                ReasoningEffort.entries.firstOrNull { it.apiValue == effort }?.name
            },
            mode = definition?.mode
        )
        sessionUseCase.upsertSession(subSession)
        val subSessionId = subSession.id

        // Notify the ViewModel to start the AI workflow on the sub-session
        eventBus.emit(
            SubAgentEvent(
                subSessionId = subSessionId,
                parentSessionId = parentSessionId,
                type = SubAgentEventType.SPAWNED,
                detail = prompt
            )
        )
        FileLogger.i(TAG, "Sub-agent created: session=$subSessionId parent=$parentSessionId agent=${definition?.name ?: "-"}")

        return ToolResult.Success(
            buildJsonObject {
                put("id", subSessionId)
                put("state", "running")
                definition?.let { put("agent", it.name) }
                put("message", "Subagent created and execution started. You will be notified when the task completes. Use task(action=\"read\", id=...) to read its output, task(action=\"stop\", id=...) to stop it.")
            }
        )
    }

    /**
     * Sends a message to the specified sub-agent (can be sent repeatedly).
     *
     * The event is dispatched by the ViewModel according to the recipient's state: a running sub-agent queues the message in the notification queue and receives it piggybacked in the next batch of tool results;
     * a completed sub-agent is reawakened and starts a new round. It is not delivered directly at the tool layer, reusing the same busy/idle dispatch logic.
     */
    private suspend fun sendToSubagent(args: Map<String, JsonElement>, context: AgentContext): ToolResult {
        val parentSessionId = context.sessionId ?: return ToolResult.Error("Missing session context", "NO_SESSION")
        val subSessionId = (args["id"] as? JsonPrimitive)?.contentOrNull?.trim()
        if (subSessionId.isNullOrBlank()) {
            return ToolResult.Error("Invalid parameter: id must not be empty", "INVALID_ARGS")
        }
        val message = (args["message"] as? JsonPrimitive)?.contentOrNull?.trim()
        if (message.isNullOrBlank()) {
            return ToolResult.Error("Invalid parameter: message must not be empty", "INVALID_ARGS")
        }
        val sub = sessionUseCase.getSessionById(subSessionId)
            ?: return ToolResult.Error("Sub-session does not exist: $subSessionId", "SESSION_NOT_FOUND")
        if (sub.parentId != parentSessionId) {
            return ToolResult.Error("You can only send messages to subagents derived from the current session", "NOT_YOUR_SUBAGENT")
        }

        eventBus.emit(
            SubAgentEvent(
                subSessionId = subSessionId,
                parentSessionId = parentSessionId,
                type = SubAgentEventType.MESSAGE_FROM_PARENT,
                detail = message
            )
        )
        FileLogger.i(TAG, "Sending message to sub-agent: session=$subSessionId parent=$parentSessionId")
        return ToolResult.Success(
            buildJsonObject {
                put("id", subSessionId)
                put("state", "delivered")
                put("message", "Message delivered to the subagent. A running subagent will receive it with the next batch of tool results; a completed one will be reawakened. You can keep adding messages with send.")
            }
        )
    }

    /**
     * Resolves the provider field in a definition to a real provider id: first an exact id match, then a case-insensitive name match,
     * so users can write the provider name they see in settings directly in the frontmatter. If neither matches, returns null (inherits the parent session).
     */
    private suspend fun resolveProviderId(raw: String): String? {
        aiProviderRepository.getProviderById(raw)?.let { return it.id }
        val all = aiProviderRepository.getAllProviders().first()
        return all.firstOrNull { it.name.equals(raw, ignoreCase = true) }?.id
    }

    /** Reads the specified sub-agent's last output. */
    private suspend fun readSubagent(args: Map<String, JsonElement>, context: AgentContext): ToolResult {
        val subSessionId = (args["id"] as? JsonPrimitive)?.contentOrNull?.trim()
        if (subSessionId.isNullOrBlank()) {
            return ToolResult.Error("Invalid parameter: id must not be empty", "INVALID_ARGS")
        }
        val sub = sessionUseCase.getSessionById(subSessionId)
            ?: return ToolResult.Error("Sub-session does not exist: $subSessionId", "SESSION_NOT_FOUND")
        if (sub.parentId != context.sessionId) {
            return ToolResult.Error("You can only read subagents derived from the current session", "NOT_YOUR_SUBAGENT")
        }

        val messages = agentMessageDao.getMessagesBySessionOnce(subSessionId)
        // Take the last assistant reply with content (skipping reasoning-only intermediate messages)
        val lastAssistant = messages.lastOrNull {
            it.role == MessageRole.ASSISTANT.name && it.content.isNotBlank()
        }
        val fallback = messages.lastOrNull { it.role == MessageRole.USER.name }
        val content = when {
            lastAssistant != null -> lastAssistant.content
            fallback != null -> "(Subagent has not replied yet) Request: ${fallback.content.take(500)}"
            else -> "(Subagent session is empty)"
        }
        val last = runCatching { messages.lastOrNull()?.timestamp ?: 0L }.getOrDefault(0L)
        return ToolResult.Success(
            buildJsonObject {
                put("id", subSessionId)
                put("title", sub.title)
                put("updatedAt", last)
                put("lastOutput", content)
            }
        )
    }

    /** Stops the specified sub-agent's execution. */
    private suspend fun stopSubagent(args: Map<String, JsonElement>, context: AgentContext): ToolResult {
        val subSessionId = (args["id"] as? JsonPrimitive)?.contentOrNull?.trim()
        if (subSessionId.isNullOrBlank()) {
            return ToolResult.Error("Invalid parameter: id must not be empty", "INVALID_ARGS")
        }
        val sub = sessionUseCase.getSessionById(subSessionId)
            ?: return ToolResult.Error("Sub-session does not exist: $subSessionId", "SESSION_NOT_FOUND")
        if (sub.parentId != context.sessionId) {
            return ToolResult.Error("You can only stop subagents derived from the current session", "NOT_YOUR_SUBAGENT")
        }

        eventBus.emit(
            SubAgentEvent(
                subSessionId = subSessionId,
                parentSessionId = context.sessionId!!,
                type = SubAgentEventType.STOPPED
            )
        )
        return ToolResult.Success(
            buildJsonObject {
                put("id", subSessionId)
                put("state", "stopping")
                put("message", "Stop requested for the subagent; its AI task is being cancelled.")
            }
        )
    }

    /** Deletes the specified sub-agent session (including its messages). If it is still running, request a stop first. */
    private suspend fun deleteSubagent(args: Map<String, JsonElement>, context: AgentContext): ToolResult {
        val subSessionId = (args["id"] as? JsonPrimitive)?.contentOrNull?.trim()
        if (subSessionId.isNullOrBlank()) {
            return ToolResult.Error("Invalid parameter: id must not be empty", "INVALID_ARGS")
        }
        val sub = sessionUseCase.getSessionById(subSessionId)
            ?: return ToolResult.Error("Sub-session does not exist: $subSessionId", "SESSION_NOT_FOUND")
        if (sub.parentId != context.sessionId) {
            return ToolResult.Error("You can only delete subagents derived from the current session", "NOT_YOUR_SUBAGENT")
        }
        if (eventBus.activeSubSessionIds.value.contains(subSessionId)) {
            eventBus.emit(
                SubAgentEvent(
                    subSessionId = subSessionId,
                    parentSessionId = context.sessionId!!,
                    type = SubAgentEventType.STOPPED
                )
            )
        }
        sessionUseCase.deleteSession(subSessionId)
        FileLogger.i(TAG, "Sub-agent deleted: session=$subSessionId parent=${context.sessionId}")
        return ToolResult.Success(
            buildJsonObject {
                put("id", subSessionId)
                put("state", "deleted")
                put("message", "Subagent deleted (including all its messages).")
            }
        )
    }

    /** Lists all sub-agents of the current session. */
    private suspend fun listSubagents(context: AgentContext): ToolResult {
        val parentSessionId = context.sessionId ?: return ToolResult.Error("Missing session context", "NO_SESSION")
        val subs = chatSessionDao.getSubSessionsByParentOnce(parentSessionId)
        val activeIds = eventBus.activeSubSessionIds.value

        val jsonArray = buildJsonArray {
            subs.forEach { entity ->
                addJsonObject {
                    put("id", entity.id)
                    put("title", entity.title)
                    put("state", if (entity.id in activeIds) "running" else "completed")
                    put("createdAt", entity.createdAt)
                    put("updatedAt", entity.updatedAt)
                }
            }
        }

        return ToolResult.Success(
            buildJsonObject {
                put("subagents", jsonArray)
                put("count", subs.size)
                put("runningCount", activeIds.size)
                put("maxRunning", SubAgentEventBus.MAX_RUNNING)
            }
        )
    }
}
