package com.aicode.feature.agent.domain.provider

import com.aicode.feature.agent.data.remote.anthropic.AnthropicApi
import com.aicode.feature.agent.data.remote.anthropic.AnthropicMessageRequest
import com.aicode.feature.agent.data.remote.anthropic.AnthropicMessage
import com.aicode.feature.agent.data.remote.anthropic.AnthropicContentBlock
import com.aicode.feature.agent.data.remote.anthropic.AnthropicThinkingConfig
import com.aicode.feature.agent.data.remote.anthropic.AnthropicOutputConfig
import com.aicode.feature.agent.data.remote.anthropic.AnthropicToolDefinition
import com.aicode.core.util.AILogger
import com.aicode.feature.agent.domain.model.AgentImage
import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.ToolCall
import com.aicode.feature.agent.domain.tool.modelToolResultText
import com.aicode.feature.settings.domain.model.ProviderType
import com.aicode.feature.settings.domain.model.defaultProviderApiPath
import com.google.gson.JsonParser
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import javax.inject.Inject
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

class AnthropicAdapter @Inject constructor(
    private val api: AnthropicApi
) : AIProvider {

    override var apiKey = ""
    override var keySwitcher: (suspend (Throwable, String, Set<String>) -> KeySwitchOutcome?)? = null
    override var baseUrl = "https://api.anthropic.com/"
    override var useFullUrl = false
    override var useResponseApi = false
    override var model = "claude-3-5-sonnet-20241022"
    override var providerId = ""
    override var logSessionId: String? = null
    override var firstByteTimeoutMs: Long = FIRST_BYTE_TIMEOUT_MS
    override var streamIdleTimeoutMs: Long = 0L
    override var maxNetworkRetries: Int = MAX_NETWORK_RETRIES

    /** Whether to enable explicit cache breakpoints (cache_control). Enabled by default; disabled via a setting when a third-party compatible gateway strictly validates unknown fields. */
    var cacheBreakpointsEnabled: Boolean = true

    /** Custom request headers: written out after placeholder replacement, completely overriding default headers of the same name. */
    override var customHeaders: Map<String, String> = emptyMap()

    override var maxOutputTokens: Int? = null

    // When null, the request body carries no temperature (Gson skips null fields), leaving it to the server default; the official requirement is that it must be omitted when thinking is enabled.
    override var temperature: Float? = null

    private fun extraHeaders(): Map<String, String> =
        resolveCustomHeaders(customHeaders, logSessionId, apiKey)

    override suspend fun complete(
        systemPrompt: String,
        messages: List<AgentMessage>,
        tools: List<AgentTool>,
        reasoningEffort: String?
    ): AIResponse {
        val anthropicMessages = convertToAnthropicMessages(messages, cacheBreakpointsEnabled)

        val toolDefs = tools.takeIf { it.isNotEmpty() }?.mapIndexed { index, tool ->
            AnthropicToolDefinition(
                name = tool.name,
                description = tool.description,
                input_schema = tool.toJsonSchema(),
                // The breakpoint budget is limited (at most 4 per request), so only mark the last tool.
                cache_control = if (cacheBreakpointsEnabled && index == tools.lastIndex) CACHE_BREAKPOINT else null
            )
        }

        val url = if (useFullUrl) baseUrl else joinUrl(baseUrl, defaultProviderApiPath(ProviderType.ANTHROPIC))
        val (thinking, outputConfig) = buildThinkingConfig(reasoningEffort)
        val request = AnthropicMessageRequest(
            model = model,
            messages = anthropicMessages,
            system = buildSystemPayload(systemPrompt),
            max_tokens = resolveMaxTokens(thinking),
            temperature = if (thinking != null) null else temperature,
            thinking = thinking,
            output_config = outputConfig,
            tools = toolDefs,
            stream = false
        )
        val seq = AILogger.logRequest(logSessionId, "Anthropic", model, "POST", url, request)

        val triedKeys = mutableSetOf(apiKey)
        val response = try {
            retryStaircase(
                maxRetries = maxNetworkRetries,
                onKeyFailure = { e, _ -> switchKeyOnFailure(e, triedKeys) != null }
            ) {
                api.createMessage(url = url, apiKey = apiKey, extraHeaders = extraHeaders(), request = request)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val enriched = e.enrichWithHttpErrorBody()
            AILogger.logError(logSessionId, "Anthropic", enriched, seq)
            throw enriched
        }
        AILogger.logResponse(logSessionId, "Anthropic", response, seq)

        var contentText = ""
        var thinkingText = ""
        var signature: String? = null
        val toolCalls = mutableListOf<ToolCall>()
        // thinking / redacted_thinking blocks are kept as-is, in their returned order; they must not be modified or reordered when sent back.
        val thinkingBlocks = mutableListOf<AnthropicContentBlock>()

        for (block in response.content) {
            when (block.type) {
                "text" -> contentText += block.text ?: ""
                "thinking" -> {
                    thinkingText += block.thinking ?: ""
                    signature = block.signature ?: signature
                    thinkingBlocks.add(block)
                }
                "redacted_thinking" -> thinkingBlocks.add(block)
                "tool_use" -> {
                    val arguments = block.input?.let { mapToJson(it) } ?: JsonObject(emptyMap())
                    toolCalls.add(
                        ToolCall(
                            id = block.id ?: "",
                            name = block.name ?: "",
                            arguments = arguments
                        )
                    )
                }
            }
        }

        return AIResponse(content = contentText, toolCalls = toolCalls, stopReason = response.stop_reason, stopDetail = response.stop_details?.explanation, reasoning = thinkingText.ifEmpty { null }, signature = signature, thinkingBlocksJson = encodeThinkingBlocks(thinkingBlocks), inputTokens = totalInputTokens(response.usage.input_tokens, response.usage.cache_read_input_tokens, response.usage.cache_creation_input_tokens), outputTokens = response.usage.output_tokens, cachedInputTokens = response.usage.cache_read_input_tokens ?: 0, cacheCreationTokens = response.usage.cache_creation_input_tokens ?: 0)
    }

    override fun completeStream(
        systemPrompt: String,
        messages: List<AgentMessage>,
        tools: List<AgentTool>,
        reasoningEffort: String?
    ): Flow<AIStreamChunk> = flow {
        val anthropicMessages = convertToAnthropicMessages(messages, cacheBreakpointsEnabled)
        val toolDefs = tools.takeIf { it.isNotEmpty() }?.mapIndexed { index, tool ->
            AnthropicToolDefinition(
                name = tool.name,
                description = tool.description,
                input_schema = tool.toJsonSchema(),
                cache_control = if (cacheBreakpointsEnabled && index == tools.lastIndex) CACHE_BREAKPOINT else null
            )
        }

        val url = if (useFullUrl) baseUrl else joinUrl(baseUrl, defaultProviderApiPath(ProviderType.ANTHROPIC))
        val (thinking, outputConfig) = buildThinkingConfig(reasoningEffort)
        val request = AnthropicMessageRequest(
            model = model,
            messages = anthropicMessages,
            system = buildSystemPayload(systemPrompt),
            max_tokens = resolveMaxTokens(thinking),
            temperature = if (thinking != null) null else temperature,
            thinking = thinking,
            output_config = outputConfig,
            tools = toolDefs,
            stream = true
        )
        val seq = AILogger.logRequest(logSessionId, "Anthropic", model, "POST", url, request)
        // Raw SSE is written to disk in batches (see AILogger.beginRawSse), finalised in finally at the end of this turn.
        val sseLog = AILogger.beginRawSse(logSessionId, "Anthropic", seq)

        // A streaming request is retryable as a whole; before a retry the upper layer receives a Retrying event and clears the displayed text.
        val triedKeys = mutableSetOf(apiKey)
        try {
            streamWithStaircaseRetry(
                maxRetries = maxNetworkRetries,
                onKeyFailure = { e, canRetry ->
                    val outcome = switchKeyOnFailure(e, triedKeys)
                    if (outcome != null && canRetry) {
                        emit(AIStreamChunk.KeySwitched(outcome.newIndex, outcome.total))
                        true
                    } else false
                },
                attemptOnce = { onContent ->
                    withFirstContentTimeout(firstByteTimeoutMs) { firstContent ->
            val textBuilder = StringBuilder()
            val budget = StreamBudget()
            // content block index -> tool_use being accumulated (entries created only for tool_use blocks, order-preserving).
            val toolBlocks = LinkedHashMap<Int, ToolBlockAcc>()
            var stopReason: String? = null
            var stopDetail: String? = null
            // Reached the end of the stream without receiving message_stop (peer EOF / proxy disconnecting early / network switch).
            // When body text already exists, deliver in degraded form instead of failing the whole turn; see [logStreamTruncated].
            var truncatedByNetwork = false
            var sseLines = 0
            var streamInputTokens = 0
            var streamOutputTokens = 0
            var streamCachedInputTokens = 0
            var streamCacheCreationTokens = 0
            // The thinking block's encrypted signature (carried by the signature_delta event), passed up with Final for the tool loop to send back.
            var signature: String? = null
            // content block index -> thinking / redacted_thinking accumulation; slotted by index so multiple thinking blocks in one turn are not merged.
            val thinkingBlocks = LinkedHashMap<Int, ThinkingBlockAcc>()

            val body = firstContent.awaitBody(api.streamMessage(url = url, apiKey = apiKey, extraHeaders = extraHeaders(), request = request))

            body.use { rb ->
                firstContent.attach { rb.close() }
                val idleWatchdog = launchStreamIdleWatchdog(streamIdleTimeoutMs) { firstContent.closeBody() }
                val closeHandle = coroutineContext[Job]?.invokeOnCompletion {
                    runCatching { rb.close() }
                }
                try {
                    val reader = rb.charStream().buffered()
                    // Break and end normally as soon as the server's message_stop event is received; readLine() returning null means
                    // the end marker had not yet arrived and the connection was already gone (network interruption/TCP reset/proxy disconnecting early).
                    // Only throw an exception to let retry/logging take over when no body text has been received yet — resending then is both safe and may get a complete answer;
                    // once text has already streamed out, deliver in degraded form (mark the output as truncated, and the Agent loop continues writing automatically),
                    // otherwise the user would see the whole turn fail and all previously streamed content discarded.
                    while (true) {
                        coroutineContext.ensureActive()
                        val line = reader.readLine()
                        if (line == null) {
                            if (!textBuilder.isSalvageableAfterTruncation()) {
                                throw IOException("SSE stream interrupted: no message_stop end marker received (suspected network disconnect)")
                            }
                            truncatedByNetwork = true
                            logStreamTruncated("Anthropic", textBuilder.length, sseLines)
                            break
                        }
                        sseLines++
                        idleWatchdog.touch()
                        if (!line.startsWith("data:")) continue
                        val data = line.removePrefix("data:").trim()
                        if (data.isEmpty()) continue
                        sseLog.append(line)
                        val obj = runCatching { JsonParser.parseString(data).asJsonObject }.getOrNull() ?: continue
                        // Single-line SSE parsing: field types from different upstreams/models occasionally differ, and Gson's getAsJsonObject/getAsJsonArray
                        // throws ClassCastException directly on a type mismatch, and asString/asInt throws UnsupportedOperationException on non-primitive values.
                        // A single-line exception should not interrupt the whole stream — parse leniently, skip the line on error; CancellationException must be let through.
                        try {
                            when (obj.get("type")?.asString) {
                                "error" -> {
                                    val errObj = obj.getAsJsonObject("error")
                                    val code = errObj?.get("type")?.takeIf { !it.isJsonNull }?.asString
                                    val msg = errObj?.get("message")?.takeIf { !it.isJsonNull }?.asString ?: "Unknown error"
                                    throw StreamApiException(code, msg)
                                }
                                "message_start" -> {
                                    val usage = obj.get("message")?.takeIf { it.isJsonObject }?.asJsonObject
                                        ?.get("usage")?.takeIf { it.isJsonObject }?.asJsonObject
                                    streamInputTokens = usage?.get("input_tokens")?.takeIf { !it.isJsonNull }?.asInt ?: 0
                                    // Cache hit/write counts are returned in the usage of message_start; some upstreams complete them in message_delta, see the override logic in that branch.
                                    streamCachedInputTokens = usage?.get("cache_read_input_tokens")?.takeIf { !it.isJsonNull }?.asInt ?: 0
                                    streamCacheCreationTokens = usage?.get("cache_creation_input_tokens")?.takeIf { !it.isJsonNull }?.asInt ?: 0
                                }
                                "content_block_start" -> {
                                    val index = obj.get("index")?.asInt ?: continue
                                    val block = obj.getAsJsonObject("content_block")
                                    when (block?.get("type")?.asString) {
                                        "tool_use" -> {
                                            firstContent.receivedContent()
                                            onContent()
                                            val name = block.get("name")?.asString ?: ""
                                            toolBlocks[index] = ToolBlockAcc(
                                                id = block.get("id")?.asString ?: "",
                                                name = name
                                            )
                                            // The tool name arrives before the arguments: immediately tell the UI "what the model is about to call",
                                            // so it does not stay on "thinking" while the arguments stream in
                                            if (name.isNotEmpty()) emit(AIStreamChunk.ToolCallDeclared(name))
                                        }
                                        "thinking" -> thinkingBlocks[index] = ThinkingBlockAcc(type = "thinking").also { acc ->
                                            val initial = block.get("thinking")?.takeIf { !it.isJsonNull }?.asString ?: ""
                                            budget.add(initial)
                                            acc.thinking.append(initial)
                                            if (initial.isNotEmpty()) {
                                                firstContent.receivedContent()
                                                onContent()
                                            }
                                            acc.signature = block.get("signature")?.takeIf { !it.isJsonNull }?.asString
                                        }
                                        // redacted_thinking's data is given in full in the start event, with no corresponding delta.
                                        "redacted_thinking" -> thinkingBlocks[index] = ThinkingBlockAcc(type = "redacted_thinking").also { acc ->
                                            acc.data = block.get("data")?.takeIf { !it.isJsonNull }?.asString
                                            if (!acc.data.isNullOrEmpty()) {
                                                firstContent.receivedContent()
                                                onContent()
                                            }
                                        }
                                    }
                                }
                                "content_block_delta" -> {
                                    val delta = obj.getAsJsonObject("delta") ?: continue
                                    when (delta.get("type")?.asString) {
                                        "text_delta" -> {
                                            val t = delta.get("text")?.asString ?: ""
                                            if (t.isNotEmpty()) {
                                                budget.add(t)
                                                textBuilder.append(t)
                                                firstContent.receivedContent()
                                                onContent()
                                                emit(AIStreamChunk.TextDelta(t))
                                            }
                                        }
                                        "thinking_delta" -> {
                                            val t = delta.get("thinking")?.asString ?: ""
                                            if (t.isNotEmpty()) {
                                                budget.add(t)
                                                obj.get("index")?.asInt?.let { idx ->
                                                    thinkingBlocks.getOrPut(idx) { ThinkingBlockAcc(type = "thinking") }
                                                        .thinking.append(t)
                                                }
                                                // Thinking content is not persisted and can be re-streamed on retry, but receiving it means the connection is alive, so cancel the first-byte timeout.
                                                firstContent.receivedContent()
                                                onContent()
                                                emit(AIStreamChunk.ReasoningDelta(t))
                                            }
                                        }
                                        "signature_delta" -> {
                                            val sig = delta.get("signature")?.asString ?: ""
                                            if (sig.isNotEmpty()) {
                                                signature = sig
                                                obj.get("index")?.asInt?.let { idx ->
                                                    thinkingBlocks.getOrPut(idx) { ThinkingBlockAcc(type = "thinking") }
                                                        .signature = sig
                                                }
                                            }
                                        }
                                        "input_json_delta" -> {
                                            val index = obj.get("index")?.asInt
                                            val partial = delta.get("partial_json")?.asString ?: ""
                                            budget.add(partial)
                                            if (index != null) toolBlocks[index]?.args?.append(partial)
                                        }
                                    }
                                }
                                "message_stop" -> break
                                "message_delta" -> {
                                    val delta = obj.get("delta")?.takeIf { it.isJsonObject }?.asJsonObject
                                    delta?.get("stop_reason")?.takeIf { !it.isJsonNull }?.asString?.let {
                                        stopReason = it
                                    }
                                    delta?.get("stop_details")?.takeIf { it.isJsonObject }?.asJsonObject
                                        ?.get("explanation")?.takeIf { !it.isJsonNull }?.asString?.let {
                                            stopDetail = it
                                        }
                                    val usage = obj.get("usage")?.takeIf { it.isJsonObject }?.asJsonObject
                                    // In the official protocol, message_delta's usage only has output_tokens, but some upstreams (e.g. New API relays)
                                    // carry the full usage here, and the input_tokens here is the final value, which may differ from message_start,
                                    // so when present it overrides, to avoid undercounting the uncached part of the total input.
                                    usage?.get("input_tokens")?.takeIf { !it.isJsonNull }?.asInt?.let {
                                        streamInputTokens = it
                                    }
                                    usage?.get("output_tokens")?.takeIf { !it.isJsonNull }?.asInt?.let {
                                        streamOutputTokens = it
                                    }
                                    usage?.get("cache_read_input_tokens")?.takeIf { !it.isJsonNull }?.asInt?.let {
                                        streamCachedInputTokens = it
                                    }
                                    usage?.get("cache_creation_input_tokens")?.takeIf { !it.isJsonNull }?.asInt?.let {
                                        streamCacheCreationTokens = it
                                    }
                                }
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            coroutineContext.ensureActive()
                            // This line of SSE failed to parse; skip it; it does not affect already accumulated text or subsequent lines.
                        }
                    }
                } finally {
                    idleWatchdog.cancel()
                    closeHandle?.dispose()
                }
            }

            val toolCalls = toolBlocks.values
                // On disconnection the arguments are almost inevitably partial; executing a tool (writing a file/running a command) with incomplete arguments is far riskier than dropping this call.
                .filter { !truncatedByNetwork || looksLikeCompleteJsonObject(it.args.toString()) }
                .map { acc ->
                    ToolCall(id = acc.id, name = acc.name, arguments = parseArgs(acc.args.toString()))
                }
            emit(AIStreamChunk.Final(AIResponse(content = textBuilder.toString(), toolCalls = toolCalls, stopReason = if (truncatedByNetwork) STREAM_TRUNCATED_STOP_REASON else stopReason, stopDetail = stopDetail, signature = signature, thinkingBlocksJson = encodeThinkingBlocks(thinkingBlocks.values.map { it.toBlock() }), inputTokens = totalInputTokens(streamInputTokens, streamCachedInputTokens, streamCacheCreationTokens), outputTokens = streamOutputTokens, cachedInputTokens = streamCachedInputTokens, cacheCreationTokens = streamCacheCreationTokens)))
                    }
                },
                onRetry = { attempt, max, error -> emit(AIStreamChunk.Retrying(attempt, max, error)) }
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            coroutineContext.ensureActive()
            val enriched = e.enrichWithHttpErrorBody()
            AILogger.logError(logSessionId, "Anthropic", enriched, seq)
            throw enriched
        } finally {
            // Whether it succeeded/failed/was cancelled, write the raw SSE received so far to disk (on retry, writing resumes from the last interruption point).
            sseLog.finish()
        }
    }.flowOn(Dispatchers.IO)

    /** The tool_use state accumulated by content block index during streaming. */
    private class ToolBlockAcc(val id: String, val name: String) {
        val args = StringBuilder()
    }

    /** The thinking / redacted_thinking state accumulated by content block index during streaming. */
    private class ThinkingBlockAcc(val type: String) {
        val thinking = StringBuilder()
        var signature: String? = null
        var data: String? = null

        fun toBlock(): AnthropicContentBlock = AnthropicContentBlock(
            type = type,
            thinking = if (type == "thinking") thinking.toString() else null,
            signature = signature,
            data = data
        )
    }

    /**
     * Maximum output tokens: prefer the output limit from the model metadata, falling back to [DEFAULT_MAX_TOKENS] when missing.
     * When thinking is enabled, the thinking budget counts toward max_tokens, so room must be left for body text; it must therefore not be less than the budget + [MIN_CONTENT_TOKENS].
     */
    private fun resolveMaxTokens(thinking: AnthropicThinkingConfig?): Int {
        val limit = maxOutputTokens?.takeIf { it > 0 } ?: DEFAULT_MAX_TOKENS
        val budget = thinking?.budget_tokens ?: return limit
        return maxOf(limit, budget + MIN_CONTENT_TOKENS)
    }

    /** Serialises thinking / redacted_thinking blocks as-is into a snapshot; null when there are no blocks. */
    private fun encodeThinkingBlocks(blocks: List<AnthropicContentBlock>): String? =
        blocks.takeIf { it.isNotEmpty() }?.let { gson.toJson(it) }

    /** Deserialises the [encodeThinkingBlocks] snapshot; returns an empty list when corrupted or empty, and the caller falls back to the old logic. */
    private fun decodeThinkingBlocks(json: String): List<AnthropicContentBlock> {
        if (json.isBlank()) return emptyList()
        return runCatching {
            gson.fromJson(json, Array<AnthropicContentBlock>::class.java)?.toList().orEmpty()
        }.getOrDefault(emptyList())
    }

    /**
     * Thinking strength -> Anthropic thinking configuration.
     * - New models (4.6+/5 series, supporting adaptive): the effort level is passed directly to output_config, and thinking uses adaptive+summarized;
     *   "none" disables thinking using disabled; "minimal" has no corresponding level and is normalised to low (officially only low/medium/high/xhigh/max).
     * - Old models (4.5 and earlier, budget_tokens only): low/medium/high map to 1024/4096/8192 (must be less than max_tokens),
     *   minimal is normalised to low, xhigh/max are normalised to high.
     */
    private fun buildThinkingConfig(reasoningEffort: String?): Pair<AnthropicThinkingConfig?, AnthropicOutputConfig?> {
        if (reasoningEffort == null) return null to null
        if (supportsAdaptiveThinking()) {
            if (reasoningEffort == "none") {
                return AnthropicThinkingConfig(type = "disabled") to null
            }
            // There is no minimal level in the official effort; normalise to low to avoid passing an illegal level through and getting a 400.
            val effort = if (reasoningEffort == "minimal") "low" else reasoningEffort
            return AnthropicThinkingConfig(type = "adaptive", display = "summarized") to AnthropicOutputConfig(effort = effort)
        }
        val budget = when (reasoningEffort) {
            // Anthropic has no minimal level: normalise to the closest, low (to avoid a split where the same level gets a 400 on one side and is ignored on the other).
            "minimal", "low" -> 1024
            "medium" -> 4096
            "high", "xhigh", "max" -> 8192
            else -> return null to null
        }
        return AnthropicThinkingConfig(type = "enabled", budget_tokens = budget) to null
    }

    /** Whether adaptive thinking is supported: 4.6+ and the 5 series (claude-<family>-4-6/4-7/4-8 or major version 5). */
    private fun supportsAdaptiveThinking(): Boolean {
        val m = Regex("claude-(?:opus|sonnet|haiku|fable|mythos)-(\\d+)(?:-(\\d+))?").find(model)
        val major = m?.groupValues?.get(1)?.toIntOrNull() ?: return false
        val minor = m?.groupValues?.get(2)?.toIntOrNull()
        return major >= 5 || (major == 4 && (minor ?: 0) >= 6)
    }

    /** Parses the accumulated tool-argument JSON string into a JsonObject; falls back to an empty object when empty or invalid. */
    private fun parseArgs(raw: String): JsonObject {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return JsonObject(emptyMap())
        return runCatching { Json.parseToJsonElement(trimmed).jsonObject }.getOrElse { JsonObject(emptyMap()) }
    }

    private fun convertToAnthropicMessages(messages: List<AgentMessage>, addMessageBreakpoint: Boolean): MutableList<AnthropicMessage> {
        val result = mutableListOf<AnthropicMessage>()
        // Defensive tracking: whether the previous assistant message contains tool_use
        var lastAssistantHadToolUse = false
        // The index in result of the last plain user message (not a tool_result), for marking messages breakpoints.
        var lastPlainUserIndex: Int? = null

        for (message in messages) {
            when (message) {
                is AgentMessage.UserMessage -> {
                    result.add(AnthropicMessage(role = "user", content = message.toAnthropicUserContent()))
                    lastPlainUserIndex = result.lastIndex
                    lastAssistantHadToolUse = false
                }
                is AgentMessage.AssistantMessage -> {
                    val contentBlocks = mutableListOf<AnthropicContentBlock>()
                    // In tool loops / multi-turn use, the previous turn's thinking / redacted_thinking blocks must be sent back as-is and in original order, otherwise a 400 results.
                    // Prefer the native snapshot (the only form that can satisfy "do not modify"); for old data/backup restores with only a signature, fall back to a single block.
                    val snapshot = decodeThinkingBlocks(message.thinkingBlocksJson)
                    if (snapshot.isNotEmpty()) {
                        contentBlocks.addAll(snapshot)
                    } else if (message.signature.isNotEmpty()) {
                        contentBlocks.add(
                            AnthropicContentBlock(
                                type = "thinking",
                                thinking = message.reasoning,
                                signature = message.signature
                            )
                        )
                    }
                    if (message.content.isNotEmpty()) {
                        contentBlocks.add(AnthropicContentBlock(type = "text", text = message.content))
                    }

                    for (toolCall in message.toolCalls) {
                         @Suppress("UNCHECKED_CAST")
                         val inputMap = jsonElementToMap(JsonObject(toolCall.arguments)) as Map<String, Any>

                         contentBlocks.add(
                            AnthropicContentBlock(
                                type = "tool_use",
                                id = toolCall.id,
                                name = toolCall.name,
                                input = inputMap
                            )
                        )
                    }

                    lastAssistantHadToolUse = message.toolCalls.isNotEmpty()

                    if (contentBlocks.isNotEmpty()) {
                        result.add(AnthropicMessage(role = "assistant", content = contentBlocks))
                    }
                }
                is AgentMessage.ToolResultMessage -> {
                    // Defensive cleanup: skip orphan tool_results with no paired tool_use
                    if (!lastAssistantHadToolUse) continue
                    // File tools feed the model a compact projection text (reducing the tokens of returning a full diff); the UI/persistence still use the full result.
                    val modelText = message.modelResult
                        ?: modelToolResultText(message.toolName, message.result)
                        ?: message.result
                    val content: Any = if (message.images.isNotEmpty()) {
                        val contentList = mutableListOf<AnthropicContentBlock>()
                        if (modelText.isNotBlank()) {
                            contentList.add(AnthropicContentBlock(type = "text", text = modelText))
                        }
                        message.images.forEach { img ->
                            contentList.add(img.toAnthropicImageBlock())
                        }
                        contentList
                    } else {
                        modelText
                    }
                    val resultBlock = AnthropicContentBlock(
                        type = "tool_result",
                        tool_use_id = message.id,
                        content = content
                    )
                    // For multiple tool_uses in the same assistant message, all their tool_results must go into the single immediately following user message:
                    // the official endpoint merges consecutive user messages into one turn, but some compatible gateways do not merge and report directly
                    // "`tool_use` ids were found without `tool_result` blocks immediately after".
                    val previous = result.lastOrNull()
                    val previousBlocks = (previous?.content as? List<*>)?.filterIsInstance<AnthropicContentBlock>()
                    if (
                        previous?.role == "user" &&
                        !previousBlocks.isNullOrEmpty() &&
                        previousBlocks.all { it.type == "tool_result" }
                    ) {
                        result[result.lastIndex] = previous.copy(content = previousBlocks + resultBlock)
                    } else {
                        result.add(AnthropicMessage(role = "user", content = listOf(resultBlock)))
                    }
                }
            }
        }

        // Messages breakpoint: placed on the text block of the last plain user message (Anthropic does not allow it on tool_result/thinking/image blocks).
        // When the last user message in a tool loop is a tool_result, do not mark it; keeping just the system+tools two breakpoints is enough.
        if (addMessageBreakpoint) {
            val idx = lastPlainUserIndex
            if (idx != null) {
                val msg = result[idx]
                val newContent = when (val c = msg.content) {
                    is String -> listOf(
                        AnthropicContentBlock(type = "text", text = c, cache_control = CACHE_BREAKPOINT)
                    )
                    is List<*> -> {
                        val blocks = c.map { it as AnthropicContentBlock }
                        val lastText = blocks.indexOfLast { it.type == "text" }
                        if (lastText >= 0) {
                            blocks.mapIndexed { i, b ->
                                if (i == lastText) b.copy(cache_control = CACHE_BREAKPOINT) else b
                            }
                        } else {
                            blocks
                        }
                    }
                    else -> c
                }
                result[idx] = msg.copy(content = newContent)
            }
        }

        return result
    }

    /** Explicit cache breakpoint value: Anthropic ephemeral prompt caching. */
    private fun buildSystemPayload(systemPrompt: String): Any? {
        if (systemPrompt.isBlank()) return null
        if (!cacheBreakpointsEnabled) return systemPrompt
        return listOf(
            mapOf(
                "type" to "text",
                "text" to systemPrompt,
                "cache_control" to CACHE_BREAKPOINT
            )
        )
    }

    /** Convert a Map<String, Any> (from Gson) to a JsonObject */
    private fun mapToJson(map: Map<String, Any>): JsonObject {
        val mutable = mutableMapOf<String, kotlinx.serialization.json.JsonElement>()
        for ((k, v) in map) {
            mutable[k] = when (v) {
                is String -> JsonPrimitive(v)
                is Number -> JsonPrimitive(v)
                is Boolean -> JsonPrimitive(v)
                is Map<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    mapToJson(v as Map<String, Any>)
                }
                else -> JsonPrimitive(v.toString())
            }
        }
        return JsonObject(mutable)
    }

    /** Convert a JsonObject back to Map<String, Any> for Anthropic API */
    private fun jsonElementToMap(element: kotlinx.serialization.json.JsonElement): Any {
        return when (element) {
            is JsonObject -> element.mapValues { (_, v) -> jsonElementToMap(v) }
            is kotlinx.serialization.json.JsonArray -> element.map { jsonElementToMap(it) }
            is JsonPrimitive -> element.contentOrNull ?: ""
        }
    }

    private fun AgentMessage.UserMessage.toAnthropicUserContent(): Any {
        if (images.isEmpty()) return content

        val blocks = mutableListOf<AnthropicContentBlock>()
        if (content.isNotBlank()) {
            blocks.add(AnthropicContentBlock(type = "text", text = content))
        }
        images.forEach { image ->
            blocks.add(image.toAnthropicImageBlock())
        }
        return blocks
    }

    private fun AgentImage.toAnthropicImageBlock(): AnthropicContentBlock {
        return AnthropicContentBlock(
            type = "image",
            source = mapOf(
                "type" to "base64",
                "media_type" to mimeType,
                "data" to base64Data
            )
        )
    }

    private companion object {
        /** Explicit cache breakpoint: Anthropic ephemeral prompt caching. */
        val CACHE_BREAKPOINT = mapOf("type" to "ephemeral")

        /**
         * Anthropic's `input_tokens` only counts the part that missed the cache; the total input must also add cache_read / cache_creation —
         * aligning with the conventions of OpenAI (`prompt_tokens` includes `cached_tokens`) and Gemini (`promptTokenCount`).
         * Context usage, compression triggering, cost and cache hit rate are all calculated on the total input.
         */
        fun totalInputTokens(input: Int, cacheRead: Int?, cacheCreation: Int?): Int =
            input + (cacheRead ?: 0) + (cacheCreation ?: 0)

        /** Fallback maximum output tokens when the model metadata lacks an output limit. */
        const val DEFAULT_MAX_TOKENS = 16384

        /** Minimum number of tokens reserved for body text when thinking is enabled (max_tokens must be greater than the thinking budget). */
        const val MIN_CONTENT_TOKENS = 4096

        val gson = com.google.gson.Gson()
    }
}
