package com.aicode.feature.agent.domain.provider

import android.net.Uri
import com.aicode.feature.agent.data.remote.openai.OpenAIApi
import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.tool.AgentTool
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Mrai AI — the keyless fallback chain from Mrai Buddy (`mrai-ai.py`), ported to a
 * native provider. Same rungs, same order, same contract:
 *
 *  1. Pollinations, OpenAI-compatible POST (full agent path — tool calls work).
 *  2. PowerBrain "gpt-5" (public client token from the open-source tgpt client;
 *     no signup, no personal key). Text only.
 *  3. Pollinations plain-text GET (last resort). Text only.
 *
 * A rung counts as failed when it throws OR answers blank; the chain then moves
 * on silently. The user never sees which rung answered, and provider promo
 * blocks (`[ADVERTISEMENT]...[/ADVERTISEMENT]`) are stripped from every reply.
 *
 * Streaming note: rung 1 is buffered rather than relayed live, because a rung
 * that dies mid-stream must not leak partial text the next rung cannot retract.
 */
class MraiChainAdapter(api: OpenAIApi) : AIProvider {

    private val delegate = OpenAIAdapter(api)

    init {
        delegate.baseUrl = POLLINATIONS_URL
        delegate.useFullUrl = true
        delegate.model = RUNG1_MODEL
    }

    companion object {
        const val PROVIDER_ID = "mrai"
        const val MODEL_ID = "mrai-auto"
        const val POLLINATIONS_URL = "https://text.pollinations.ai/openai"

        private const val RUNG1_MODEL = "openai"
        private const val POWERBRAIN_URL = "https://powerbrainai.com/app/backend/api/api.php"
        private const val POWERBRAIN_TOKEN = "AIChatPowerBrain123@2024"
        private const val POLLINATIONS_GET_BASE = "https://text.pollinations.ai/"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"
        private const val TIMEOUT_MS = 60_000

        private val AD_BLOCK = Regex(
            """\[ADVERTISEMENT\].*?\[/ADVERTISEMENT\]""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
    }

    // --- AIProvider state: everything forwards to the rung-1 delegate. ---

    override var apiKey: String
        get() = delegate.apiKey
        set(value) { delegate.apiKey = value }

    override var baseUrl: String
        get() = delegate.baseUrl
        set(value) { delegate.baseUrl = value }

    override var useFullUrl: Boolean
        get() = delegate.useFullUrl
        set(value) { delegate.useFullUrl = value }

    override var useResponseApi: Boolean
        get() = delegate.useResponseApi
        set(value) { delegate.useResponseApi = value }

    private var externalModel: String = MODEL_ID

    /** The chain is chosen by its pseudo-model id; rung 1 always runs [RUNG1_MODEL]. */
    override var model: String
        get() = externalModel
        set(value) {
            externalModel = value
            delegate.model = if (value == MODEL_ID || value.isBlank()) RUNG1_MODEL else value
        }

    override var providerId: String
        get() = delegate.providerId
        set(value) { delegate.providerId = value }

    override var logSessionId: String?
        get() = delegate.logSessionId
        set(value) { delegate.logSessionId = value }

    override var customHeaders: Map<String, String>
        get() = delegate.customHeaders
        set(value) { delegate.customHeaders = value }

    override var keySwitcher: (suspend (Throwable, String, Set<String>) -> KeySwitchOutcome?)?
        get() = delegate.keySwitcher
        set(value) { delegate.keySwitcher = value }

    override var maxOutputTokens: Int?
        get() = delegate.maxOutputTokens
        set(value) { delegate.maxOutputTokens = value }

    override var temperature: Float?
        get() = delegate.temperature
        set(value) { delegate.temperature = value }

    override var firstByteTimeoutMs: Long
        get() = delegate.firstByteTimeoutMs
        set(value) { delegate.firstByteTimeoutMs = value }

    override var streamIdleTimeoutMs: Long
        get() = delegate.streamIdleTimeoutMs
        set(value) { delegate.streamIdleTimeoutMs = value }

    override var maxNetworkRetries: Int
        get() = delegate.maxNetworkRetries
        set(value) { delegate.maxNetworkRetries = value }

    // --- Chain ---

    override suspend fun complete(
        systemPrompt: String,
        messages: List<AgentMessage>,
        tools: List<AgentTool>,
        reasoningEffort: String?
    ): AIResponse {
        runRung1(systemPrompt, messages, tools, reasoningEffort)?.let { return it }
        val text = withContext(Dispatchers.IO) { runTextRungs(systemPrompt, messages) }
        if (text != null) return AIResponse(content = stripAds(text), stopReason = "stop")
        throw IllegalStateException(
            "Mrai AI: all free providers are unavailable right now. Check the connection and try again."
        )
    }

    override fun completeStream(
        systemPrompt: String,
        messages: List<AgentMessage>,
        tools: List<AgentTool>,
        reasoningEffort: String?
    ): Flow<AIStreamChunk> = flow {
        var final: AIResponse? = null
        var streamFailed = false
        try {
            delegate.completeStream(systemPrompt, messages, tools, reasoningEffort).collect { chunk ->
                if (chunk is AIStreamChunk.Final) final = chunk.response
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            streamFailed = true
        }
        val settled = final
        if (!streamFailed && settled != null &&
            (settled.content.isNotBlank() || settled.toolCalls.isNotEmpty())
        ) {
            val clean = settled.copy(content = stripAds(settled.content))
            if (clean.content.isNotBlank()) emit(AIStreamChunk.TextDelta(clean.content))
            emit(AIStreamChunk.Final(clean))
            return@flow
        }
        // Streaming failed or answered blank: retry rung 1 non-streaming
        // (the exact shape Buddy uses), then fall to the text rungs.
        runRung1(systemPrompt, messages, tools, reasoningEffort)?.let { resp ->
            if (resp.content.isNotBlank()) emit(AIStreamChunk.TextDelta(resp.content))
            emit(AIStreamChunk.Final(resp))
            return@flow
        }
        val text = withContext(Dispatchers.IO) { runTextRungs(systemPrompt, messages) }
        if (text != null) {
            val clean = stripAds(text)
            emit(AIStreamChunk.TextDelta(clean))
            emit(AIStreamChunk.Final(AIResponse(content = clean, stopReason = "stop")))
            return@flow
        }
        throw IllegalStateException(
            "Mrai AI: all free providers are unavailable right now. Check the connection and try again."
        )
    }

    /** Rung 1 via the OpenAI adapter. Null on any failure or blank answer. */
    private suspend fun runRung1(
        systemPrompt: String,
        messages: List<AgentMessage>,
        tools: List<AgentTool>,
        reasoningEffort: String?
    ): AIResponse? {
        val resp = try {
            delegate.complete(systemPrompt, messages, tools, reasoningEffort)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return null
        }
        return if (resp.content.isNotBlank() || resp.toolCalls.isNotEmpty()) {
            resp.copy(content = stripAds(resp.content))
        } else {
            null
        }
    }

    /** Rungs 2 and 3, in Buddy's order. Null when both fail or answer blank. */
    private fun runTextRungs(systemPrompt: String, messages: List<AgentMessage>): String? {
        runPowerBrain(systemPrompt, messages)?.let { return it }
        runPollinationsGet(systemPrompt, messages)?.let { return it }
        return null
    }

    private fun flatten(messages: List<AgentMessage>): List<Pair<String, String>> =
        messages.takeLast(24).map { msg ->
            when (msg) {
                is AgentMessage.UserMessage -> "user" to msg.content
                is AgentMessage.AssistantMessage -> "assistant" to msg.content
                is AgentMessage.ToolResultMessage -> "user" to (msg.modelResult ?: msg.result)
            }
        }

    /** Rung 2: PowerBrain. The reply text lives in the JSON `data` field. */
    private fun runPowerBrain(systemPrompt: String, messages: List<AgentMessage>): String? = try {
        val arr = JSONArray()
        arr.put(JSONObject().put("role", "system").put("content", systemPrompt))
        for ((role, content) in flatten(messages)) {
            arr.put(JSONObject().put("role", role).put("content", content))
        }
        val body = JSONObject()
            .put("model", "gpt-5")
            .put("secret_token", POWERBRAIN_TOKEN)
            .put("action", "send_message")
            .put("messages", arr)
        val text = JSONObject(postJson(POWERBRAIN_URL, body.toString())).optString("data", "").trim()
        text.ifBlank { null }
    } catch (e: Exception) {
        null
    }

    /** Rung 3: Pollinations plain-text GET, persona first, last user message last. */
    private fun runPollinationsGet(systemPrompt: String, messages: List<AgentMessage>): String? = try {
        val lastUser = flatten(messages).lastOrNull { it.first == "user" }?.second?.take(6000) ?: ""
        val prompt = "Reply conversationally in plain text, no JSON, no markdown fences.\n\n" +
            "---\nYOUR INSTRUCTIONS (fully apply them):\n" +
            systemPrompt.take(4000) + "\n---\n\n" + lastUser
        val text = httpGet(POLLINATIONS_GET_BASE + Uri.encode(prompt)).trim()
        text.ifBlank { null }
    } catch (e: Exception) {
        null
    }

    private fun postJson(url: String, json: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            return readResponse(conn)
        } finally {
            conn.disconnect()
        }
    }

    private fun httpGet(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.setRequestProperty("User-Agent", USER_AGENT)
            return readResponse(conn)
        } finally {
            conn.disconnect()
        }
    }

    private fun readResponse(conn: HttpURLConnection): String {
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        if (code !in 200..299) throw IllegalStateException("HTTP $code")
        return text
    }

    /** Free providers inject promo blocks; the user must never see them. */
    private fun stripAds(text: String): String = text.replace(AD_BLOCK, "").trim()
}
