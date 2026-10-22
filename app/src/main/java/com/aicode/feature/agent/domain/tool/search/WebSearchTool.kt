package com.aicode.feature.agent.domain.tool.search

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.ParameterType
import com.aicode.feature.agent.domain.tool.ToolParameter
import com.aicode.feature.agent.domain.tool.ToolCapability
import com.aicode.feature.agent.domain.tool.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class WebSearchTool @Inject constructor() : AgentTool() {

    private companion object {
        const val TAG = "WebSearchTool"
        // 使用与 opencode 一致的 Parallel AI 公开 MCP 接口
        const val PARALLEL_MCP_URL = "https://search.parallel.ai/mcp"

        // 走全局/提供商代理链路（AppProxy 的全局 ProxySelector + 按 host 分派的认证），
        // 与对话等其它 HTTP 请求保持一致；lazy 确保 client 在 applyGlobal 之后首次构建。
        val client by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .proxyAuthenticator(com.aicode.core.net.AppProxy.okHttpAuthenticator)
                .build()
        }
    }

    override val name = "websearch"
    override val description = "Search the internet for real-time information. Suitable for tasks that need the latest or time-sensitive information."
    override val capabilities = setOf(ToolCapability.NETWORK_READ)

    override val parameters: Map<String, ToolParameter> = mapOf(
        "query" to ToolParameter(
            name = "query",
            type = ParameterType.STRING,
            description = "Search keywords. Please distill an accurate, search-friendly phrase.",
            required = true
        )
    )

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        val query = args["query"]?.jsonPrimitive?.contentOrNull 
            ?: return ToolResult.Error("Missing required parameter: query")

        return withContext(Dispatchers.IO) {
            try {
                val requestBody = JsonObject(
                    mapOf(
                        "jsonrpc" to JsonPrimitive("2.0"),
                        "id" to JsonPrimitive(1),
                        "method" to JsonPrimitive("tools/call"),
                        "params" to JsonObject(
                            mapOf(
                                "name" to JsonPrimitive("web_search"),
                                "arguments" to JsonObject(
                                    mapOf(
                                        "objective" to JsonPrimitive(query),
                                        "search_queries" to JsonArray(listOf(JsonPrimitive(query))),
                                        "session_id" to JsonPrimitive("aicode-android")
                                    )
                                )
                            )
                        )
                    )
                ).toString()

                FileLogger.i(TAG, "发起 WebSearch 请求: $query")

                val request = Request.Builder()
                    .url(PARALLEL_MCP_URL)
                    .header("Accept", "application/json, text/event-stream")
                    .header("User-Agent", "aicode/1.0")
                    .post(requestBody.toRequestBody("application/json".toMediaType()))
                    .build()

                client.newCall(request).execute().use { resp ->
                    val responseCode = resp.code
                    if (responseCode !in 200..299) {
                        val errorStr = resp.body?.string()
                        FileLogger.e(TAG, "WebSearch 失败: HTTP $responseCode, $errorStr")
                        return@withContext ToolResult.Error("Web search failed (HTTP $responseCode)")
                    }

                    // 尝试直接读取普通 JSON 或解析 Event-Stream (SSE) 格式
                    val responseBody = resp.body?.string().orEmpty()

                    // 解析 MCP 返回体
                    val rawResultText = parseMcpResponse(responseBody)

                    if (rawResultText.isNullOrBlank()) {
                        ToolResult.Success(kotlinx.serialization.json.JsonPrimitive("No search results found for 「$query」; please try different keywords and retry."))
                    } else {
                        ToolResult.Success(parseSearchResultPayload(rawResultText) ?: JsonPrimitive(rawResultText))
                    }
                }
            } catch (e: Exception) {
                FileLogger.e(TAG, "WebSearch 发生异常", e)
                ToolResult.Error("Search failed: ${e.message}")
            }
        }
    }

    /**
     * 兼容解析直接的 JSON 或 SSE (Server-Sent Events) 格式。
     */
    private fun parseMcpResponse(body: String): String? {
        val lines = body.lines().map { it.trim() }.filter { it.isNotEmpty() }
        
        for (line in lines) {
            val jsonStr = if (line.startsWith("data: ")) {
                line.substring(6)
            } else if (line.startsWith("{")) {
                line
            } else {
                continue
            }
            
            try {
                val json = Json.parseToJsonElement(jsonStr).jsonObject
                if (json.containsKey("result")) {
                    val contentArr = json["result"]?.jsonObject?.get("content")?.jsonArray
                    if (!contentArr.isNullOrEmpty()) {
                        return contentArr[0].jsonObject["text"]?.jsonPrimitive?.contentOrNull
                    }
                }
            } catch (e: Exception) {
                // Ignore parse errors for partial lines
            }
        }
        return null
    }

    private fun parseSearchResultPayload(raw: String): JsonElement? {
        return runCatching {
            val element = Json.parseToJsonElement(raw.trim())
            val obj = element as? JsonObject ?: return@runCatching null
            if (obj["results"] is JsonArray) element else null
        }.getOrNull()
    }
}
