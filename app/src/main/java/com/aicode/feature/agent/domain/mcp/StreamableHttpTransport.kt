package com.aicode.feature.agent.domain.mcp

import com.aicode.core.util.FileLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.atomic.AtomicLong

/**
 * MCP "Streamable HTTP" transport implementation.
 *
 * Single endpoint POSTing JSON-RPC; the server can reply with `application/json` (a single response) or
 * `text/event-stream` (SSE; we only need to read the first message event with an id in it, because this transport is
 * request-response and does not maintain a server-push channel). The `Mcp-Session-Id` header in the `initialize` response is recorded,
 * and sent with every subsequent request (as the spec requires).
 *
 * SSE is parsed the same way as in AnthropicAdapter -- reading `data:` lines manually, avoiding introducing okhttp-sse.
 */
class StreamableHttpTransport(
    private val endpoint: String,
    private val client: OkHttpClient,
    private val extraHeaders: Map<String, String> = emptyMap(),
    private val json: Json = DEFAULT_JSON
) : McpTransport {

    private companion object {
        const val TAG = "McpHttpTransport"
        val JSON_MEDIA = "application/json".toMediaType()
        @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
        val DEFAULT_JSON = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }
    }

    private val idCounter = AtomicLong(0)

    @Volatile
    private var sessionId: String? = null

    override suspend fun request(method: String, params: JsonObject?): JsonRpcResponse =
        withContext(Dispatchers.IO) {
            val id = idCounter.incrementAndGet()
            val payload = JsonRpcRequest(id = id, method = method, params = params)
            val bodyJson = json.encodeToString(JsonRpcRequest.serializer(), payload)
            FileLogger.d(TAG, "→ [$method] id=$id")

            val httpReq = buildRequest(bodyJson)
            client.newCall(httpReq).execute().use { resp ->
                // The session id is issued in the first response (initialize); record it for reuse by subsequent requests.
                resp.header("Mcp-Session-Id")?.let { if (it.isNotBlank()) sessionId = it }

                if (!resp.isSuccessful) {
                    throw McpException(message = "HTTP ${resp.code} call to $method failed: ${resp.message}")
                }

                val contentType = resp.header("Content-Type").orEmpty()
                val rawJson = if (contentType.contains("text/event-stream", ignoreCase = true)) {
                    extractSseJson(resp.body?.charStream()?.buffered())
                        ?: throw McpException(message = "No data for $method found in the SSE response")
                } else {
                    resp.body?.string()
                        ?: throw McpException(message = "$method response body is empty")
                }

                parseAndValidate(rawJson, id, method)
            }
        }

    override suspend fun notify(method: String, params: JsonObject?) = withContext(Dispatchers.IO) {
        val payload = JsonRpcNotification(method = method, params = params)
        val bodyJson = json.encodeToString(JsonRpcNotification.serializer(), payload)
        FileLogger.d(TAG, "→ notify [$method]")
        client.newCall(buildRequest(bodyJson)).execute().use { resp ->
            // For notifications the server usually returns 202 with no body per spec; a non-2xx is only logged and does not block the flow.
            if (!resp.isSuccessful) {
                FileLogger.w(TAG, "Notification $method returned HTTP ${resp.code}")
            }
        }
    }

    override fun close() {
        sessionId = null
    }

    private fun buildRequest(bodyJson: String): Request {
        val headers = Headers.Builder().apply {
            add("Content-Type", "application/json")
            // Accept both response forms, letting the server decide between a single JSON response and SSE.
            add("Accept", "application/json, text/event-stream")
            sessionId?.let { add("Mcp-Session-Id", it) }
            extraHeaders.forEach { (k, v) -> add(k, v) }
        }.build()

        return Request.Builder()
            .url(endpoint)
            .headers(headers)
            .post(bodyJson.toRequestBody(JSON_MEDIA))
            .build()
    }

    /** Takes the first `data:` payload from the SSE stream (which may span multiple lines) and assembles it into complete JSON text.
     * Multi-line data belongs to the same event and is joined with newlines per the SSE spec (JSON allows whitespace, so the joined text is still parseable). */
    private fun extractSseJson(reader: java.io.BufferedReader?): String? {
        reader ?: return null
        val data = StringBuilder()
        var line = reader.readLine()
        while (line != null) {
            when {
                line.startsWith("data:") -> data.append(line.removePrefix("data:").trim()).append('\n')
                line.isBlank() && data.isNotEmpty() -> return data.toString().trimEnd('\n')
            }
            line = reader.readLine()
        }
        return data.takeIf { it.isNotEmpty() }?.toString()?.trimEnd('\n')
    }

    private fun parseAndValidate(rawJson: String, expectedId: Long, method: String): JsonRpcResponse {
        val response = runCatching {
            json.decodeFromString(JsonRpcResponse.serializer(), rawJson)
        }.getOrElse {
            throw McpException(message = "$method response JSON parsing failed: ${it.message}", cause = it)
        }

        response.error?.let {
            throw McpException(rpcCode = it.code, message = "$method returned error [${it.code}] ${it.message}")
        }
        if (response.id != null && response.id != expectedId) {
            FileLogger.w(TAG, "Response id mismatch: expected $expectedId, actual ${response.id}")
        }
        return response
    }
}
