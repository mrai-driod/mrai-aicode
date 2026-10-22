package com.aicode.feature.agent.domain.mcp

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Minimal JSON-RPC 2.0 message model for communicating with MCP servers.
 *
 * Covers only the subset MCP currently needs: requests/responses with an id, and notifications without an id.
 * Ids are incrementing integers; the server must return them unchanged, and [McpClient] uses them to pair responses to requests.
 */
const val JSONRPC_VERSION = "2.0"

@Serializable
data class JsonRpcRequest(
    val id: Long,
    val method: String,
    val params: JsonElement? = null,
    val jsonrpc: String = JSONRPC_VERSION
)

/** Notifications have no id and the server does not reply (e.g. notifications/initialized). */
@Serializable
data class JsonRpcNotification(
    val method: String,
    val params: JsonElement? = null,
    val jsonrpc: String = JSONRPC_VERSION
)

@Serializable
data class JsonRpcResponse(
    val id: Long? = null,
    val result: JsonObject? = null,
    val error: JsonRpcError? = null,
    val jsonrpc: String = JSONRPC_VERSION
)

@Serializable
data class JsonRpcError(
    val code: Int,
    val message: String,
    val data: JsonElement? = null
)

/** Thrown when calling an MCP server fails; carries the JSON-RPC error code so callers can distinguish cases. */
class McpException(
    val rpcCode: Int? = null,
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

// ---- MCP domain models (return fragments of initialize / tools/list)----

@Serializable
data class McpToolDescriptor(
    val name: String,
    val description: String? = null,
    @SerialName("inputSchema") val inputSchema: JsonObject? = null
)

@Serializable
data class McpToolsListResult(
    val tools: List<McpToolDescriptor> = emptyList(),
    val nextCursor: String? = null
)
