package com.aicode.feature.agent.domain.tool

import com.aicode.feature.agent.domain.model.AgentImage
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

@Serializable
sealed class ToolResult {
    @Serializable
    @SerialName("success")
    data class Success(
        val data: JsonElement,
        val images: List<AgentImage> = emptyList()
    ) : ToolResult()

    @Serializable
    @SerialName("error")
    data class Error(val message: String, val code: String = "UNKNOWN") : ToolResult()

    @Serializable
    @SerialName("partial")
    data class Partial(val data: JsonElement, val message: String) : ToolResult()
}

private val ToolResultTransportJson = Json {
    classDiscriminator = "status"
    encodeDefaults = true
}

fun ToolResult.toTransportString(): String {
    return ToolResultTransportJson.encodeToString(this)
}

data class ToolParameter(
    val name: String,
    val type: ParameterType,
    val description: String,
    val required: Boolean = true,
    val enum: List<String>? = null,
    /**
     * When [type] is [ParameterType.ARRAY], the JSON Schema describing the array elements (merged as-is into
     * the items field of function-calling). For example, when the elements are objects, pass
     * `{"type":"object","properties":{...},"required":[...]}`. If empty, no items are emitted.
     */
    val itemsSchema: Map<String, Any>? = null
)

enum class ParameterType {
    STRING, INTEGER, BOOLEAN, ARRAY, OBJECT
}

enum class ToolPermissionPolicy {
    AUTO_APPROVE, ASK
}

enum class ToolCapability {
    READ_WORKSPACE,
    WRITE_WORKSPACE,
    EXECUTE_COMMANDS,
    NETWORK_READ,
    NETWORK_WRITE,
    READ_AGENT_CONFIG,
    MODIFY_AGENT_CONFIG,
    MODIFY_CONTAINER_ENV,
    USER_INTERACTION,
    MODIFY_SESSION_STATE,
    MODIFY_TODO_STATE,
    EXTERNAL_TOOL
}

data class PendingToolPermission(
    val id: String,
    val toolName: String,
    val title: String,
    val summary: String,
    val details: String,
    val argsPreview: String,
    /**
     * The list of patterns remembered by "Always allow" (for shell commands, the command prefix: subcommand dispatchers record prefixes with subcommands such as `git pull`,
     * ordinary programs record `cat`/`ls`; for non-shell tools, `*`).
     * An empty list means this call cannot be remembered (the command cannot be determined statically); the UI should disable "Always allow" and offer only one-time approval.
     * Filled in after evaluation by [com.aicode.feature.agent.domain.permission.ToolPermissionPolicyEngine].
     */
    val rememberablePatterns: List<String> = emptyList(),
    val rememberDisabledReason: String? = null,
    /** The session id that initiated this authorization request. Used when multiple sessions run in parallel to tell dialogs apart in the UI and light up the sidebar's awaiting-authorization indicator. */
    val sessionId: String = "",
    /**
     * The danger description given by the AI command reviewer at the "autonomous execution" level (filled in when the review rejects a command or is uncertain and escalates to a human);
     * when non-empty, the dialog should display it prominently to warn the user about the consequences of execution.
     */
    val reviewNote: String? = null
)

abstract class AgentTool {
    abstract val name: String
    abstract val description: String
    abstract val parameters: Map<String, ToolParameter>
    open val permissionPolicy: ToolPermissionPolicy = ToolPermissionPolicy.AUTO_APPROVE
    open val capabilities: Set<ToolCapability> = emptySet()

    open fun effectiveCapabilities(args: Map<String, JsonElement>): Set<ToolCapability> {
        return capabilities
    }

    abstract suspend fun execute(args: Map<String, JsonElement>): ToolResult

    open suspend fun executeWithContext(args: Map<String, JsonElement>, context: com.aicode.feature.agent.domain.model.AgentContext): ToolResult {
        return execute(args)
    }
    open fun buildPermissionRequest(
        callId: String,
        args: Map<String, JsonElement>,
        argsPreview: String
    ): PendingToolPermission {
        return PendingToolPermission(
            id = callId,
            toolName = name,
            title = "Confirm tool execution",
            summary = "AI requests to execute $name",
            details = argsPreview,
            argsPreview = argsPreview
        )
    }

    /**
     * Generates a parameter description conforming to JSON Schema, for passing to the model's real function-calling interface
     * (OpenAI's function.parameters / Anthropic's input_schema).
     *
     * Set to open: external tools such as MCP carry arbitrary raw inputSchema that cannot be expressed with the restricted [ParameterType]
     * enum, so this method needs to be overridden to pass the server-side schema through directly.
     */
    open fun toJsonSchema(): Map<String, Any> {
        val properties = LinkedHashMap<String, Any>()
        val required = mutableListOf<String>()
        parameters.forEach { (key, param) ->
            val prop = LinkedHashMap<String, Any>()
            prop["type"] = param.type.name.lowercase()
            prop["description"] = param.description
            param.enum?.let { prop["enum"] = it }
            if (param.type == ParameterType.ARRAY) param.itemsSchema?.let { prop["items"] = it }
            properties[key] = prop
            if (param.required) required.add(key)
        }
        return mapOf(
            "type" to "object",
            "properties" to properties,
            "required" to required
        )
    }
}

@Serializable
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: Map<String, JsonElement>
)

/**
 * A tool that can execute as a stream: before the final result is produced, it gradually emits process output (such as a command's stdout line by line),
 * letting the UI show the "execution process" in real time rather than only the final result.
 *
 * Implementations should also implement [AgentTool.execute] as a non-streaming fallback; the workflow prefers [executeStream].
 */
interface StreamingAgentTool {
    fun executeStream(args: Map<String, JsonElement>, context: com.aicode.feature.agent.domain.model.AgentContext): Flow<ToolStreamEvent>
}

/** Events produced during streaming tool execution. */
sealed class ToolStreamEvent {
    /** A chunk of new process output (usually one line). */
    data class Progress(val chunk: String) : ToolStreamEvent()
    /** Execution finished, with the final aggregated result (for feeding back to the model). */
    data class Completed(val result: ToolResult) : ToolStreamEvent()
}
