package com.aicode.feature.agent.domain.mcp

import kotlinx.serialization.json.JsonObject

/**
 * MCP transport abstraction: delivers a JSON-RPC request to the server and retrieves the single response.
 *
 * Deliberately a pluggable interface: remote traffic goes through [StreamableHttpTransport], in-container subprocesses through [StdioTransport],
 * [McpClient] does not know about the concrete transport implementation.
 */
interface McpTransport {
    /**
     * Sends a request that expects a reply, blocking until the paired [JsonRpcResponse] arrives.
     * Transport/protocol errors are thrown as [McpException].
     */
    suspend fun request(method: String, params: JsonObject? = null): JsonRpcResponse

    /** Sends a notification that needs no reply (fire-and-forget). */
    suspend fun notify(method: String, params: JsonObject? = null)

    /** Releases underlying resources (connections, sessions, etc.). Safe to call repeatedly. */
    fun close()
}
