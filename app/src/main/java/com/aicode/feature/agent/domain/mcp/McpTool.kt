package com.aicode.feature.agent.domain.mcp

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.PendingToolPermission
import com.aicode.feature.agent.domain.tool.ToolCapability
import com.aicode.feature.agent.domain.tool.ToolParameter
import com.aicode.feature.agent.domain.tool.ToolPermissionPolicy
import com.aicode.feature.agent.domain.tool.ToolResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.security.MessageDigest

/**
 * Adapts an MCP server tool into an in-app [AgentTool]; once registered in ToolRegistry it can be reused by the Agent loop.
 *
 * Key points:
 * - [toJsonSchema] is overridden to pass through the server's original inputSchema (converted to plain Kotlin types for Gson serialisation),
 *   bypassing the limited ParameterType enum -- MCP tools can have arbitrarily complex argument schemas.
 * - The [name] exposed to the model is namespaced (`mcp__server__tool`) and sanitised to function-calling-legal characters,
 *   avoiding name clashes between tools from multiple servers; the actual call uses the original [remoteName] via tools/call.
 */
class McpTool(
    private val client: McpClient,
    private val descriptor: McpToolDescriptor
) : AgentTool() {

    companion object {
        private const val TAG = "McpTool"
        private const val NAME_PREFIX = "mcp"

        /**
         * Builds the namespaced name and sanitises it to function-calling-legal characters (^[a-zA-Z0-9_-]{1,64}$).
         * Must judge by ASCII only -- [Char.isLetterOrDigit] would let Chinese and other Unicode letters through as legal characters.
         */
        internal fun buildNamespacedName(server: String, tool: String): String {
            val raw = "${NAME_PREFIX}__${sanitize(server)}__${sanitize(tool)}"
            if (raw.length <= 64) return raw

            val hash = sha1(raw).take(8)
            val suffix = "__$hash"
            return raw.take(64 - suffix.length).trimEnd('_', '-') + suffix
        }

        private fun sanitize(s: String): String = s.map {
            if (it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '-') it else '_'
        }.joinToString("")

        private fun sha1(value: String): String {
            val digest = MessageDigest.getInstance("SHA-1").digest(value.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }
    }

    /** The original tool name on the server; tools/call must use it. */
    val remoteName: String = descriptor.name

    override val name: String = buildNamespacedName(client.serverName, descriptor.name)

    override val description: String =
        descriptor.description ?: "MCP tool ${descriptor.name} (from ${client.serverName})"
    override val capabilities = setOf(ToolCapability.EXTERNAL_TOOL)

    // All MCP tools uniformly go through tool permissions: review required by default, with an "always allow" memory option (see ToolPermissionPolicyEngine).
    override val permissionPolicy = ToolPermissionPolicy.ASK

    // MCP tools use the raw schema directly and do not go through parameters; an empty map is kept to satisfy the base-class contract.
    override val parameters: Map<String, ToolParameter> = emptyMap()

    /** Passes through the server's inputSchema; falls back to an empty-object schema when missing. */
    override fun toJsonSchema(): Map<String, Any> {
        val schema = descriptor.inputSchema
        if (schema == null || schema.isEmpty()) {
            return mapOf("type" to "object", "properties" to emptyMap<String, Any>())
        }
        @Suppress("UNCHECKED_CAST")
        return (jsonElementToAny(schema) as? Map<String, Any>)
            ?: mapOf("type" to "object", "properties" to emptyMap<String, Any>())
    }

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        return try {
            FileLogger.d(TAG, "Calling MCP tool $name (remote=$remoteName) args=${args.keys}")
            val call = client.callTool(remoteName, JsonObject(args))
            if (call.isError) {
                ToolResult.Error(call.text)
            } else {
                ToolResult.Success(JsonPrimitive(call.text))
            }
        } catch (e: McpException) {
            FileLogger.e(TAG, "MCP tool call failed: $name", e)
            ToolResult.Error("MCP tool execution failed: ${e.message}")
        } catch (e: Exception) {
            FileLogger.e(TAG, "MCP tool call exception: $name", e)
            ToolResult.Error("MCP tool execution error: ${e.message}")
        }
    }

    /** The permission request shown when the tool is called, providing clear MCP tool context. */
    override fun buildPermissionRequest(
        callId: String,
        args: Map<String, JsonElement>,
        argsPreview: String
    ): PendingToolPermission {
        return PendingToolPermission(
            id = callId,
            toolName = name,
            title = "Confirm MCP tool call",
            summary = "AI requests calling MCP tool \"$remoteName\" (server $serverName)",
            details = "MCP tool: $remoteName\nServer: $serverName\nArguments: $argsPreview",
            argsPreview = argsPreview
        )
    }

    private val serverName: String get() = client.serverName

    /** kotlinx JsonElement -> plain Kotlin types (Map/List/String/Number/Boolean/null), for Gson to serialise correctly. */
    private fun jsonElementToAny(element: JsonElement): Any? = when (element) {
        is JsonObject -> element.mapValues { (_, v) -> jsonElementToAny(v) }
        is JsonArray -> element.map { jsonElementToAny(it) }
        is JsonNull -> null
        is JsonPrimitive -> when {
            element.isString -> element.content
            element.booleanOrNull != null -> element.booleanOrNull
            element.longOrNull != null -> element.longOrNull
            element.doubleOrNull != null -> element.doubleOrNull
            else -> element.content
        }
    }
}
