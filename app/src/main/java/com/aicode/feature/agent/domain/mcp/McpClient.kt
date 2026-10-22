package com.aicode.feature.agent.domain.mcp

import com.aicode.core.util.FileLogger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Session client for a single MCP server: handles the handshake, listing tools, and forwarding tool calls.
 *
 * Lifecycle: after [connect] completes initialize -> notifications/initialized -> tools/list,
 * [tools] holds the tool descriptors exposed by that server; [callTool] turns one call into `tools/call`.
 * Any step failing throws [McpException], handled per-server by the layer above (McpManager) without affecting other servers.
 */
class McpClient(
    val serverName: String,
    private val transport: McpTransport,
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    private companion object {
        const val TAG = "McpClient"
        const val PROTOCOL_VERSION = "2025-06-18"
    }

    @Volatile
    var tools: List<McpToolDescriptor> = emptyList()
        private set

    /** Full handshake: initialize -> initialized notification -> tools/list. Returns the number of tools obtained. */
    suspend fun connect(): Int {
        FileLogger.i(TAG, "[$serverName] Starting connection")
        initialize()
        transport.notify("notifications/initialized")
        val count = refreshTools()
        FileLogger.i(TAG, "[$serverName] Handshake complete, discovered $count tools")
        return count
    }

    private suspend fun initialize() {
        val params = buildJsonObject {
            put("protocolVersion", PROTOCOL_VERSION)
            putJsonObject("capabilities") { /* The client declares no extra capabilities for now */ }
            putJsonObject("clientInfo") {
                put("name", "ai-code-editor")
                put("version", "1.0.0")
            }
        }
        FileLogger.i(TAG, "[$serverName] Sending initialize (protocol v$PROTOCOL_VERSION)")
        val t0 = System.currentTimeMillis()
        transport.request("initialize", params)
        FileLogger.i(TAG, "[$serverName] initialize complete (${System.currentTimeMillis() - t0}ms)")
        // The returned serverInfo / capabilities are not used for now; as long as there is no error, the handshake counts as successful.
    }

    /** Re-fetches the tool list (called on config changes or manual refresh). */
    suspend fun refreshTools(): Int {
        FileLogger.i(TAG, "[$serverName] Fetching tool list tools/list")
        val t0 = System.currentTimeMillis()
        val response = transport.request("tools/list")
        val result = response.result ?: throw McpException(message = "[$serverName] tools/list has no result")
        tools = runCatching {
            json.decodeFromJsonElement(McpToolsListResult.serializer(), result).tools
        }.getOrElse {
            throw McpException(message = "[$serverName] Failed to parse tool list: ${it.message}", cause = it)
        }
        FileLogger.i(TAG, "[$serverName] tools/list complete, ${tools.size} tools (${System.currentTimeMillis() - t0}ms)")
        return tools.size
    }

    /**
     * Executes one tool call, returning the result flattened to text (fed back to the model) and whether it errored.
     * An MCP `tools/call` result is an array of content blocks; here the text blocks are concatenated and other types get a placeholder description.
     * [McpCallResult.isError] is taken directly from the result's `isError` field, avoiding using a text prefix as the criterion.
     */
    suspend fun callTool(toolName: String, arguments: JsonObject): McpCallResult {
        val params = buildJsonObject {
            put("name", toolName)
            put("arguments", arguments)
        }
        val response = transport.request("tools/call", params)
        val result = response.result ?: throw McpException(message = "[$serverName] tools/call has no result")

        val isError = (result["isError"] as? JsonPrimitive)?.contentOrNull == "true"
        val text = flattenContent(result["content"])
        return McpCallResult(text, isError)
    }

    /** Converts the blocks in the content array to plain text: text blocks take their text; other blocks get a type placeholder. */
    private fun flattenContent(content: kotlinx.serialization.json.JsonElement?): String {
        val array = content as? JsonArray ?: return ""
        val sb = StringBuilder()
        for (block in array) {
            val obj = block as? JsonObject ?: continue
            when ((obj["type"] as? JsonPrimitive)?.contentOrNull) {
                "text" -> sb.append((obj["text"] as? JsonPrimitive)?.contentOrNull.orEmpty())
                "image" -> sb.append("[image content]")
                "resource" -> sb.append("[resource content]")
                else -> obj["text"]?.let { sb.append((it as? JsonPrimitive)?.contentOrNull.orEmpty()) }
            }
            sb.append("\n")
        }
        return sb.toString().trimEnd()
    }

    fun close() = transport.close()
}

/** The result of one `tools/call`: flattened text + whether it errored (from the MCP result's `isError` field). */
data class McpCallResult(val text: String, val isError: Boolean)
