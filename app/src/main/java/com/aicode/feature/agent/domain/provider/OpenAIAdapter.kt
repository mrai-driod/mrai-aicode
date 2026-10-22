package com.aicode.feature.agent.domain.provider

import com.aicode.core.util.AILogger
import com.aicode.feature.agent.data.remote.openai.OpenAIApi
import com.aicode.feature.settings.domain.model.ProviderType
import com.aicode.feature.settings.domain.model.defaultProviderApiPath
import java.io.IOException
import com.aicode.feature.agent.data.remote.openai.ChatCompletionRequest
import com.aicode.feature.agent.data.remote.openai.OpenAIChatMessage
import com.aicode.feature.agent.domain.model.AgentImage
import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.ToolCall
import com.aicode.feature.agent.domain.tool.modelToolResultText
import com.google.gson.JsonParser
import javax.inject.Inject
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import com.aicode.feature.agent.data.remote.openai.OpenAIToolCall
import com.aicode.feature.agent.data.remote.openai.OpenAIToolDefinition
import com.aicode.feature.agent.data.remote.openai.OpenAIFunctionDefinition
import com.aicode.feature.agent.data.remote.openai.OpenAIImagePart
import com.aicode.feature.agent.data.remote.openai.StreamOptions

class OpenAIAdapter @Inject constructor(
    private val api: OpenAIApi
) : AIProvider {

    override var apiKey = ""
    override var keySwitcher: (suspend (Throwable, String, Set<String>) -> KeySwitchOutcome?)? = null
    override var baseUrl = "https://api.openai.com/"
    override var useFullUrl = false
    override var useResponseApi = false
    override var model = "gpt-4-turbo"
    override var providerId = ""
    override var logSessionId: String? = null
    override var firstByteTimeoutMs: Long = FIRST_BYTE_TIMEOUT_MS
    override var streamIdleTimeoutMs: Long = 0L
    override var maxNetworkRetries: Int = MAX_NETWORK_RETRIES

    /**
     * Whether to send `prompt_cache_key` (cache shard routing) on the Chat Completion path.
     * Off by default -- OpenAI's official Chat Completion does not accept unknown fields (400); only third-party compatible services (DeepInfra/Cerebras, etc.) support it.
     * The Responses API path is natively supported by the official API, is always sent, and is unaffected by this switch.
     */
    var chatCacheKeyEnabled: Boolean = false

    /** Custom request headers: placeholders are replaced before being written out, completely overriding the same-named default headers. */
    override var customHeaders: Map<String, String> = emptyMap()

    // The OpenAI family does not send an output limit parameter (the server default is used); kept only for interface completeness.
    override var maxOutputTokens: Int? = null

    // When null, the request body does not include temperature (Gson skips null fields) and the server default is used.
    override var temperature: Float? = null

    private fun extraHeaders(): Map<String, String> =
        resolveCustomHeaders(customHeaders, logSessionId, apiKey)

    /**
     * Target endpoint: when the Responses API is enabled, use `v1/responses`; otherwise use the chat/completions path ([defaultProviderApiPath]).
     * When useResponseApi is on, the request body is in Responses format (`input`), and if it were still sent to chat/completions the server would reject it with
     * "missing field `messages`", so the endpoint must be switched at the same time. When useFullUrl is on, the complete baseUrl filled in by the user is used directly.
     */
    private fun resolveApiUrl(): String {
        if (useFullUrl) return baseUrl
        val path = if (useResponseApi) "v1/responses" else defaultProviderApiPath(ProviderType.OPENAI)
        return joinUrl(baseUrl, path)
    }

    override suspend fun complete(
        systemPrompt: String,
        messages: List<AgentMessage>,
        tools: List<AgentTool>,
        reasoningEffort: String?
    ): AIResponse {
        if (useResponseApi) return completeViaResponses(systemPrompt, messages, tools, reasoningEffort)

        val openAIMessages = buildList {
            if (systemPrompt.isNotBlank()) {
                add(OpenAIChatMessage(role = systemRoleForModel(), content = systemPrompt))
            }
            addAll(convertToOpenAIMessages(messages))
        }

        val toolDefs = tools.takeIf { it.isNotEmpty() }?.map { tool ->
            OpenAIToolDefinition(
                function = OpenAIFunctionDefinition(
                    name = tool.name,
                    description = tool.description,
                    parameters = tool.toJsonSchema()
                )
            )
        }

        val url = resolveApiUrl()
        val request = ChatCompletionRequest(
            model = model,
            messages = openAIMessages,
            temperature = temperature,
            reasoning_effort = normalizeReasoningEffort(reasoningEffort),
            tools = toolDefs,
            tool_choice = if (toolDefs != null) "auto" else null,
            stream = false,
            prompt_cache_key = if (chatCacheKeyEnabled) logSessionId else null
        )
        val seq = AILogger.logRequest(logSessionId, "OpenAI", model, "POST", url, request)

        val triedKeys = mutableSetOf(apiKey)
        val response = try {
            retryStaircase(
                maxRetries = maxNetworkRetries,
                onKeyFailure = { e, _ -> switchKeyOnFailure(e, triedKeys) != null }
            ) {
                api.createChatCompletion(url = url, authorization = "Bearer $apiKey", extraHeaders = extraHeaders(), request = request)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val enriched = e.enrichWithHttpErrorBody()
            AILogger.logError(logSessionId, "OpenAI", enriched, seq)
            throw enriched
        }
        AILogger.logResponse(logSessionId, "OpenAI", response, seq)

        val message = response.choices.firstOrNull()?.message
        val finishReason = response.choices.firstOrNull()?.finish_reason
        val content = message?.content.asTextContent()
        val images = message?.images?.mapNotNull { it.toAgentImage() } ?: emptyList()
        val toolCalls = message?.tool_calls?.map { convertToToolCall(it) } ?: emptyList()
        val reasoning = message?.reasoning_content?.takeIf { it.isNotEmpty() }
            ?: message?.reasoning?.takeIf { it.isNotEmpty() }
        val usage = response.usage

        return AIResponse(content = content, toolCalls = toolCalls, stopReason = finishReason, reasoning = reasoning, inputTokens = usage?.prompt_tokens ?: 0, outputTokens = usage?.completion_tokens ?: 0, cachedInputTokens = usage?.prompt_tokens_details?.cached_tokens ?: 0, cacheCreationTokens = usage?.prompt_tokens_details?.cache_write_tokens ?: 0, images = images)
    }

    /**
     * The o-series reasoning models do not accept the system role and require developer instead.
     * Judged by prefix: o1/o3/o4 and later o-series (o5, o6...), as well as the gpt-5 series (officially grouped under the same "reasoning model" role convention),
     * all use developer. gpt-4 and earlier, as well as third-party compatible services (deepseek/kimi/minimax/moonshot, etc.), do not match
     * the prefix, continue to use system, and are unaffected.
     */
    private fun systemRoleForModel(): String = when {
        model.startsWith("o", ignoreCase = true) -> "developer"
        model.startsWith("gpt-5", ignoreCase = true) -> "developer"
        else -> "system"
    }

    /**
     * Reasoning effort -> OpenAI reasoning_effort.
     * OpenAI's official Chat Completions reasoning_effort only accepts low/medium/high (the gpt-5 series additionally supports
     * none/minimal, but the o-series does not), and passing "none"/"minimal" through as-is causes a 400 on most reasoning models.
     * Here "none"/"minimal" are explicitly skipped (the field is not sent, and the server default is used), "xhigh"/"max" are normalized to high
     * (OpenAI has no higher level), and low/medium/high are passed through as-is. This aligns with Anthropic/Gemini's "skip what cannot be expressed" strategy.
     */
    private fun normalizeReasoningEffort(effort: String?): String? = when (effort) {
        null -> null
        "none", "minimal" -> null
        "xhigh", "max" -> "high"
        else -> effort
    }

    /**
     * The Responses request body. The system prompt enters `input` as the first message item (equivalent to the top-level `instructions`,
     * and the server likewise treats it as the first system message), so `instructions` is not sent separately.
     */
    private fun buildResponsesRequest(
        systemPrompt: String,
        messages: List<AgentMessage>,
        tools: List<AgentTool>,
        reasoningEffort: String?,
        stream: Boolean
    ): Map<String, Any?> {
        val isDeepSeek = model.contains("deepseek", ignoreCase = true)
        val request = mutableMapOf<String, Any?>(
            "model" to model,
            "input" to buildResponsesInput(
                systemPrompt = systemPrompt,
                systemRole = systemRoleForModel(),
                messages = messages,
                // DeepSeek thinking mode: for requests with tools, the reasoning content of every past turn must be sent back (the same as the reasoning_content
                // on the Chat Completions side); the official/generic path also automatically sends it back when a valid snapshot is stored.
                includeReasoningItems = isDeepSeek
            )
        )
        buildResponsesTools(tools)?.let {
            request["tools"] = it
            request["tool_choice"] = "auto"
        }
        if (stream) request["stream"] = true
        normalizeReasoningEffort(reasoningEffort)?.let { effort ->
            if (isDeepSeek) {
                request["reasoning"] = mapOf("effort" to effort)
            } else {
                // Official/generic OpenAI Responses spec: explicitly enable reasoning summaries (summary: "auto"),
                // and request the encrypted_content ciphertext, for continuous reasoning in stateless multi-turn conversations.
                request["reasoning"] = mapOf("effort" to effort, "summary" to "auto")
                request["include"] = listOf("reasoning.encrypted_content")
            }
        }
        // OpenAI's official Responses natively supports prompt_cache_key (routing the same session to the same cache shard);
        // services that manage the cache automatically (such as DeepSeek) silently ignore this field and do not error.
        logSessionId?.let { request["prompt_cache_key"] = it }
        return request
    }

    /**
     * The non-streaming Responses API request. Its differences from Chat Completions are concentrated in three places: the tool definition fields are flat,
     * the history is a sequence of input items (see [buildResponsesInput]), and the result is parsed from the top-level items of `output`
     * (a tool call is an independent function_call item, not inside the assistant message's content).
     */
    private suspend fun completeViaResponses(
        systemPrompt: String,
        messages: List<AgentMessage>,
        tools: List<AgentTool>,
        reasoningEffort: String?
    ): AIResponse {
        val url = resolveApiUrl()
        val request = buildResponsesRequest(systemPrompt, messages, tools, reasoningEffort, stream = false)
        val seq = AILogger.logRequest(logSessionId, "OpenAI", model, "POST", url, request)

        val triedKeys = mutableSetOf(apiKey)
        val response = try {
            retryStaircase(
                maxRetries = maxNetworkRetries,
                onKeyFailure = { e, _ -> switchKeyOnFailure(e, triedKeys) != null }
            ) {
                api.createResponses(url = url, authorization = "Bearer $apiKey", extraHeaders = extraHeaders(), request = request)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val enriched = e.enrichWithHttpErrorBody()
            AILogger.logError(logSessionId, "OpenAI", enriched, seq)
            throw enriched
        }
        AILogger.logResponse(logSessionId, "OpenAI", response, seq)

        val parsed = parseResponsesOutput(response.get("output")?.takeIf { it.isJsonArray }?.asJsonArray)
        val usage = parseResponsesUsage(response.get("usage")?.takeIf { it.isJsonObject }?.asJsonObject)
        val status = response.get("status")?.takeIf { it.isJsonPrimitive }?.asString
        val incompleteReason = response.get("incomplete_details")?.takeIf { it.isJsonObject }?.asJsonObject
            ?.get("reason")?.takeIf { it.isJsonPrimitive }?.asString
        return AIResponse(
            content = parsed.text,
            toolCalls = parsed.toolCalls,
            stopReason = responsesStopReason(status, incompleteReason, parsed.toolCalls.isNotEmpty()),
            reasoning = parsed.reasoning.takeIf { it.isNotEmpty() },
            thinkingBlocksJson = parsed.thinkingBlocksJson,
            inputTokens = usage.inputTokens,
            outputTokens = usage.outputTokens,
            cachedInputTokens = usage.cachedInputTokens,
            images = parsed.images
        )
    }

    override fun completeStream(
        systemPrompt: String,
        messages: List<AgentMessage>,
        tools: List<AgentTool>,
        reasoningEffort: String?
    ): Flow<AIStreamChunk> = flow {
        if (useResponseApi) {
            streamViaResponses(systemPrompt, messages, tools, reasoningEffort)
            return@flow
        }

        val openAIMessages = buildList {
            if (systemPrompt.isNotBlank()) {
                add(OpenAIChatMessage(role = systemRoleForModel(), content = systemPrompt))
            }
            addAll(convertToOpenAIMessages(messages))
        }
        val toolDefs = tools.takeIf { it.isNotEmpty() }?.map { tool ->
            OpenAIToolDefinition(
                function = OpenAIFunctionDefinition(
                    name = tool.name,
                    description = tool.description,
                    parameters = tool.toJsonSchema()
                )
            )
        }

        val url = resolveApiUrl()

        val request = ChatCompletionRequest(
            model = model,
            messages = openAIMessages,
            temperature = temperature,
            reasoning_effort = normalizeReasoningEffort(reasoningEffort),
            tools = toolDefs,
            tool_choice = if (toolDefs != null) "auto" else null,
            stream = true,
            stream_options = StreamOptions(include_usage = true),
            prompt_cache_key = if (chatCacheKeyEnabled) logSessionId else null
        )
        val seq = AILogger.logRequest(logSessionId, "OpenAI", model, "POST", url, request)
        // Raw SSE is written to disk in batches (see AILogger.beginRawSse) and finalized in the finally block at the end of this turn.
        val sseLog = AILogger.beginRawSse(logSessionId, "OpenAI", seq)

        // The streaming request as a whole is retriable; before a retry, the upper layer receives a Retrying event and clears the already-displayed text.
        try {
            val triedKeys = mutableSetOf(apiKey)
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
            // tool_call index -> tool call being accumulated (order-preserving).
            val toolAccs = LinkedHashMap<Int, OpenAIToolAcc>()
            // Image-generation extension: some compatible services return images as a whole block in delta.images (the data URL of image_url).
            val streamedImages = mutableListOf<AgentImage>()
            var finishReason: String? = null
            // The end of the stream was reached without receiving [DONE] (peer EOF / proxy disconnected early / network switch);
            // when body text already exists, deliver it in degraded form instead of failing the whole turn; see [logStreamTruncated].
            var truncatedByNetwork = false
            var sseLines = 0
            var streamInputTokens = 0
            var streamOutputTokens = 0
            var streamCachedInputTokens = 0
            var streamCacheCreationTokens = 0

            val body = firstContent.awaitBody(api.streamChatCompletion(
                url = url,
                authorization = "Bearer $apiKey",
                extraHeaders = extraHeaders(),
                request = request
            ))

            body.use { rb ->
                firstContent.attach { rb.close() }
                val idleWatchdog = launchStreamIdleWatchdog(streamIdleTimeoutMs) { firstContent.closeBody() }
                val closeHandle = coroutineContext[Job]?.invokeOnCompletion {
                    runCatching { rb.close() }
                }
                try {
                    val reader = rb.charStream().buffered()
                    // Receiving the server's [DONE] marker breaks out and ends normally; readLine() returning null means the end marker had not arrived yet
                    // but the connection is gone (network interruption / TCP reset / proxy disconnected early).
                    // Only when no body text has been received yet is an exception thrown to let retry/logging take over; once text has been emitted, deliver it in degraded form
                    // (mark the output as truncated and let the Agent loop continue writing automatically), avoiding failure of the whole turn and wasting all already-streamed content.
                    while (true) {
                        coroutineContext.ensureActive()
                        val line = reader.readLine()
                        if (line == null) {
                            if (!textBuilder.isSalvageableAfterTruncation()) {
                                throw IOException("SSE stream interrupted: [DONE] end marker not received (possible network disconnection)")
                            }
                            truncatedByNetwork = true
                            logStreamTruncated("OpenAI", textBuilder.length, sseLines)
                            break
                        }
                        sseLines++
                        idleWatchdog.touch()
                        if (!line.startsWith("data:")) continue
                        val data = line.removePrefix("data:").trim()
                        if (data.isEmpty()) continue
                        sseLog.append(line)
                        if (data == "[DONE]") break
                        val obj = runCatching { JsonParser.parseString(data).asJsonObject }.getOrNull() ?: continue
                        obj.get("error")?.takeIf { it.isJsonObject }?.asJsonObject?.let { errObj ->
                            val code = errObj.get("code")?.takeIf { !it.isJsonNull }?.asString
                            val msg = errObj.get("message")?.takeIf { !it.isJsonNull }?.asString ?: "Unknown error"
                            throw StreamApiException(code, msg)
                        }
                        // Single-line SSE parsing: field types occasionally differ across upstreams/models (e.g. an object written as an array, or a string written as an object),
                        // and Gson's getAsJsonObject/getAsJsonArray throw ClassCastException directly on a type mismatch,
                        // while asString/asInt throw UnsupportedOperationException on non-primitive values.
                        // A single-line error should not interrupt the whole stream -- parsing is lenient here, and on error only that line is skipped; already-accumulated text and subsequent lines are unaffected.
                        // CancellationException must be allowed through, otherwise the coroutine cancellation signal would be swallowed.
                        try {
                            obj.get("usage")?.takeIf { it.isJsonObject }?.asJsonObject?.let { u ->
                                streamInputTokens = u.get("prompt_tokens")?.takeIf { !it.isJsonNull }?.asInt ?: streamInputTokens
                                streamOutputTokens = u.get("completion_tokens")?.takeIf { !it.isJsonNull }?.asInt ?: streamOutputTokens
                                streamCachedInputTokens = u.getAsJsonObject("prompt_tokens_details")?.get("cached_tokens")?.takeIf { !it.isJsonNull }?.asInt ?: streamCachedInputTokens
                                streamCacheCreationTokens = u.getAsJsonObject("prompt_tokens_details")?.get("cache_write_tokens")?.takeIf { !it.isJsonNull }?.asInt ?: streamCacheCreationTokens
                            }
                            val choice = obj.getAsJsonArray("choices")?.firstOrNull()?.asJsonObject ?: continue
                            val delta = choice.getAsJsonObject("delta") ?: continue

                            choice.get("finish_reason")?.takeIf { !it.isJsonNull }?.asString?.let {
                                finishReason = it
                            }

                            // Text delta
                            delta.get("content")?.takeIf { !it.isJsonNull }?.asString?.let { c ->
                                if (c.isNotEmpty()) {
                                    budget.add(c)
                                    textBuilder.append(c)
                                    firstContent.receivedContent()
                                    onContent()
                                    emit(AIStreamChunk.TextDelta(c))
                                }
                            }
                            // Reasoning delta: standard OpenAI uses reasoning_content, while some third-party compatible services
                            // (such as mimo) use the top-level reasoning or reasoning_details (a reasoning.text array).
                            // Used only for real-time UI display and not counted in the body (reasoning is not persisted; on retry it simply streams out again, with no risk of duplicated text),
                            // but receiving it means the connection is alive, so the first-byte timeout is cancelled.
                            val reasoningText = delta.get("reasoning_content")?.takeIf { !it.isJsonNull }?.asString
                                ?: delta.get("reasoning")?.takeIf { !it.isJsonNull }?.asString
                                ?: delta.get("reasoning_details")?.takeIf { it.isJsonArray }?.asJsonArray
                                    ?.joinToString("") { el ->
                                        el.asJsonObject.get("text")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
                                    }
                            if (!reasoningText.isNullOrEmpty()) {
                                budget.add(reasoningText)
                                firstContent.receivedContent()
                                onContent()
                                emit(AIStreamChunk.ReasoningDelta(reasoningText))
                            }
                            // Tool call delta: aggregate id/name/arguments fragments by index.
                            // Some models (such as DeepSeek) send only arguments fragments in subsequent delta chunks,
                            // with id and name as empty strings "", which should not overwrite already-received valid values -- otherwise the complete id/name received
                            // in the first chunk would be cleared by later empty values, causing the ToolCall to be lost.
                            delta.getAsJsonArray("tool_calls")?.takeIf { it.size() > 0 }?.let {
                                firstContent.receivedContent()
                                onContent()
                            }
                            delta.getAsJsonArray("tool_calls")?.forEach { el ->
                                val tc = el.asJsonObject
                                val idx = tc.get("index")?.asInt ?: 0
                                val acc = toolAccs.getOrPut(idx) { OpenAIToolAcc() }
                                // Update only when id/name is non-empty, to avoid an empty value in a delta chunk overwriting the valid value from the first chunk
                                tc.get("id")?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotEmpty() }?.let { acc.id = it }
                                tc.getAsJsonObject("function")?.let { fn ->
                                    fn.get("name")?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotEmpty() }?.let { name ->
                                        // The first time the tool name is obtained (subsequent deltas only give arguments fragments): notify the UI
                                        // what the model is about to call, so it does not stay on "Thinking" during long argument streaming
                                        if (acc.name.isEmpty()) emit(AIStreamChunk.ToolCallDeclared(name))
                                        acc.name = name
                                    }
                                    fn.get("arguments")?.takeIf { !it.isJsonNull }?.asString?.let { budget.add(it); acc.args.append(it) }
                                }
                            }
                            // Image-generation extension: images arrive as a whole block with no deltas; accumulate them for Final to hand to the workflow to save to disk.
                            delta.getAsJsonArray("images")?.forEach { el ->
                                val img = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
                                img.toOpenAIAgentImage()?.let { image ->
                                    streamedImages.add(image)
                                    firstContent.receivedContent()
                                    onContent()
                                }
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: StreamApiException) {
                            throw e
                        } catch (e: Exception) {
                            coroutineContext.ensureActive()
                            // This line of SSE failed to parse; skip it. It does not affect already-accumulated text or subsequent lines.
                        }
                    }
                } finally {
                    idleWatchdog.cancel()
                    closeHandle?.dispose()
                }
            }

            val toolCalls = toolAccs.values
                .filter { it.id.isNotEmpty() || it.name.isNotEmpty() }
                // When the stream is cut off, the arguments are almost certainly incomplete: executing a tool with incomplete arguments (writing a file / running a command) is far riskier than discarding this call.
                .filter { !truncatedByNetwork || looksLikeCompleteJsonObject(it.args.toString()) }
                .map { acc -> ToolCall(id = acc.id, name = acc.name, arguments = parseToolArguments(acc.args.toString())) }
            emit(AIStreamChunk.Final(AIResponse(content = textBuilder.toString(), toolCalls = toolCalls, stopReason = if (truncatedByNetwork) STREAM_TRUNCATED_STOP_REASON else finishReason, inputTokens = streamInputTokens, outputTokens = streamOutputTokens, cachedInputTokens = streamCachedInputTokens, cacheCreationTokens = streamCacheCreationTokens, images = streamedImages)))
                    }
            },
            onRetry = { attempt, max, error -> emit(AIStreamChunk.Retrying(attempt, max, error)) }
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            coroutineContext.ensureActive()
            val enriched = e.enrichWithHttpErrorBody()
            AILogger.logError(logSessionId, "OpenAI", enriched, seq)
            throw enriched
        } finally {
            // Regardless of success/failure/cancellation, write the raw SSE received so far to disk (on retry, writing resumes from the last interruption point).
            sseLog.finish()
        }
    }.flowOn(Dispatchers.IO)

    /**
     * The streaming Responses API request. Unlike Chat Completions' delta frames, this is a semantic event stream (see
     * [ResponsesStreamAccumulator]), and **the official API does not send `[DONE]`**: the end can only be judged by response.completed /
     * incomplete / failed. Keeping the old "report a cut-off stream if [DONE] is not received" logic would treat a normal ending as an error,
     * so [ResponsesStreamAccumulator.terminated] is used here as the exit condition, and reaching the end of the stream without termination counts as truncated.
     */
    private suspend fun FlowCollector<AIStreamChunk>.streamViaResponses(
        systemPrompt: String,
        messages: List<AgentMessage>,
        tools: List<AgentTool>,
        reasoningEffort: String?
    ) {
        val url = resolveApiUrl()
        val request = buildResponsesRequest(systemPrompt, messages, tools, reasoningEffort, stream = true)
        val seq = AILogger.logRequest(logSessionId, "OpenAI", model, "POST", url, request)
        // Raw SSE is written to disk in batches (see AILogger.beginRawSse) and finalized in the finally block at the end of this turn.
        val sseLog = AILogger.beginRawSse(logSessionId, "OpenAI", seq)
        try {
            val triedKeys = mutableSetOf(apiKey)
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
                    val acc = ResponsesStreamAccumulator()

                    val body = firstContent.awaitBody(api.streamResponses(
                        url = url,
                        authorization = "Bearer $apiKey",
                        extraHeaders = extraHeaders(),
                        request = request
                    ))

                    body.use { rb ->
                        firstContent.attach { rb.close() }
                        val idleWatchdog = launchStreamIdleWatchdog(streamIdleTimeoutMs) { firstContent.closeBody() }
                        val closeHandle = coroutineContext[Job]?.invokeOnCompletion {
                            runCatching { rb.close() }
                        }
                        try {
                            val reader = rb.charStream().buffered()
                            var sseLines = 0
                            while (!acc.terminated) {
                                coroutineContext.ensureActive()
                                val line = reader.readLine()
                                if (line == null) {
                                    // The connection is gone before the termination event arrived. Only when no body text has been received yet is an exception thrown to let retry/logging take over;
                                    // once text has been emitted, deliver it in degraded form (mark it as truncated output and let the Agent loop continue writing automatically).
                                    if (!acc.hasSalvageableText()) {
                                        throw IOException("SSE stream interrupted: response.completed end event not received (possible network disconnection)")
                                    }
                                    logStreamTruncated("OpenAI/Responses", acc.textLength, sseLines)
                                    acc.markNetworkTruncated()
                                    break
                                }
                                sseLines++
                                idleWatchdog.touch()
                                if (!line.startsWith("data:")) continue
                                val data = line.removePrefix("data:").trim()
                                if (data.isEmpty()) continue
                                sseLog.append(line)
                                // Official Responses does not send [DONE], but some compatible services add it; receiving it is treated as the end of the stream.
                                if (data == "[DONE]") break
                                val obj = runCatching { JsonParser.parseString(data).asJsonObject }.getOrNull() ?: continue
                                obj.get("error")?.takeIf { it.isJsonObject }?.asJsonObject?.let { errObj ->
                                    val code = errObj.get("code")?.takeIf { !it.isJsonNull }?.asString
                                    val msg = errObj.get("message")?.takeIf { !it.isJsonNull }?.asString ?: "Unknown error"
                                    throw StreamApiException(code, msg)
                                }
                                // An abnormal field type in a single event should not ruin the whole stream; only that event is skipped;
                                // but StreamApiException (response.failed) and cancellation signals must be allowed through.
                                val delta = try {
                                    acc.accept(obj).also {
                                        if (acc.receivedContent) {
                                            firstContent.receivedContent()
                                            onContent()
                                        }
                                    }
                                } catch (e: StreamApiException) {
                                    throw e
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    coroutineContext.ensureActive()
                                    null
                                }
                                when (delta) {
                                    is ResponsesDelta.Text -> {
                                        firstContent.receivedContent()
                                        onContent()
                                        emit(AIStreamChunk.TextDelta(delta.text))
                                    }
                                    // Reasoning deltas are used only for UI display and are not counted in the body; receiving one means the connection is alive, so the first-byte timeout is cancelled.
                                    is ResponsesDelta.Reasoning -> {
                                        firstContent.receivedContent()
                                        onContent()
                                        emit(AIStreamChunk.ReasoningDelta(delta.text))
                                    }
                                    // The tool name arrives before the arguments: notify the UI to switch the status to a specific scenario early
                                    is ResponsesDelta.ToolCallDeclared -> {
                                        firstContent.receivedContent()
                                        onContent()
                                        emit(AIStreamChunk.ToolCallDeclared(delta.name))
                                    }
                                    null -> {}
                                }
                            }
                        } finally {
                            idleWatchdog.cancel()
                            closeHandle?.dispose()
                        }
                    }

                    emit(AIStreamChunk.Final(acc.toResponse()))
                    }
                },
                onRetry = { attempt, max, error -> emit(AIStreamChunk.Retrying(attempt, max, error)) }
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            coroutineContext.ensureActive()
            val enriched = e.enrichWithHttpErrorBody()
            AILogger.logError(logSessionId, "OpenAI", enriched, seq)
            throw enriched
        } finally {
            sseLog.finish()
        }
    }

    /** The state of a tool call accumulated by index during streaming. */
    private class OpenAIToolAcc {
        var id = ""
        var name = ""
        val args = StringBuilder()
    }

    private fun convertToOpenAIMessages(messages: List<AgentMessage>): MutableList<OpenAIChatMessage> {
        val raw = messages.map { message ->
            when (message) {
                is AgentMessage.UserMessage -> OpenAIChatMessage(
                    role = "user",
                    content = message.toOpenAIUserContent()
                )
                is AgentMessage.AssistantMessage -> {
                    val toolCalls = if (message.toolCalls.isNotEmpty()) {
                        message.toolCalls.map { convertToOpenAIToolCall(it) }
                    } else null
                    // DeepSeek thinking mode requires the assistant message's reasoning_content field to be present
                    // (it must be included even if it is an empty string), otherwise the API returns 400 when a tool-call turn is sent back.
                    val reasoningContent = if (model.contains("deepseek", ignoreCase = true)) {
                        message.reasoning
                    } else {
                        message.reasoning.ifEmpty { null }
                    }
                    OpenAIChatMessage(
                        role = "assistant",
                        content = message.content,
                        tool_calls = toolCalls,
                        reasoning_content = reasoningContent
                    )
                }
                is AgentMessage.ToolResultMessage -> {
                    // File tools feed the model a condensed projection text (reducing the tokens of sending back the full diff), while the UI/persistence still use the complete result.
                    val modelText = message.modelResult
                        ?: modelToolResultText(message.toolName, message.result)
                        ?: message.result
                    val content: Any = if (message.images.isNotEmpty()) {
                        val parts = mutableListOf<Map<String, Any>>()
                        if (modelText.isNotBlank()) {
                            parts.add(mapOf("type" to "text", "text" to modelText))
                        }
                        message.images.forEach { image ->
                            parts.add(image.toOpenAIImagePart())
                        }
                        parts
                    } else {
                        modelText
                    }
                    OpenAIChatMessage(
                        role = "tool",
                        content = content,
                        tool_call_id = message.id
                    )
                }
            }
        }

        // Defensive cleanup: ensure each assistant(tool_calls) and its tool response messages are paired one-to-one by tool_call_id and follow immediately,
        // to avoid an upstream 400. Scenarios that can break the pairing:
        // 1) Concurrent/asynchronous tool results landing out of order (e.g. other tool results cutting in while askUserQuestion blocks waiting),
        //    separating an assistant(tool_calls) from its responses with other messages -> pull the matching tool responses back to immediately follow it;
        // 2) An orphaned tool message (the preceding assistant has no tool_calls, e.g. pairing broken by context compression) -> skip it,
        //    otherwise OpenAI reports "Messages with role 'tool' must be a response to a preceding
        //    message with 'tool_calls'";
        // 3) An assistant declares tool_calls with no corresponding response (e.g. some calls were not executed because the user rejected them) -> trim them,
        //    otherwise OpenAI reports "insufficient tool messages following tool_calls message".
        val cleaned = mutableListOf<OpenAIChatMessage>()
        val consumed = BooleanArray(raw.size)
        for (i in raw.indices) {
            if (consumed[i]) continue
            val msg = raw[i]
            if (msg.role == "assistant" && msg.tool_calls?.isNotEmpty() == true) {
                val remaining = msg.tool_calls!!.map { it.id }.toMutableSet()
                val matchedTools = mutableListOf<OpenAIChatMessage>()
                for (j in i + 1 until raw.size) {
                    if (consumed[j]) continue
                    val m = raw[j]
                    if (m.role == "tool" && m.tool_call_id != null && m.tool_call_id in remaining) {
                        matchedTools.add(m)
                        consumed[j] = true
                        remaining.remove(m.tool_call_id)
                        if (remaining.isEmpty()) break
                    }
                }
                val keptCalls = if (remaining.isEmpty()) msg.tool_calls
                else msg.tool_calls!!.filter { it.id !in remaining }
                cleaned.add(if (keptCalls === msg.tool_calls) msg else msg.copy(tool_calls = keptCalls.ifEmpty { null }))
                cleaned.addAll(matchedTools)
            } else if (msg.role == "tool") {
                consumed[i] = true // Orphaned tool message, skip
            } else {
                cleaned.add(msg)
            }
        }
        return cleaned
    }

    private fun AgentMessage.UserMessage.toOpenAIUserContent(): Any {
        if (images.isEmpty()) return content

        val parts = mutableListOf<Map<String, Any>>()
        if (content.isNotBlank()) {
            parts.add(mapOf("type" to "text", "text" to content))
        }
        images.forEach { image ->
            parts.add(image.toOpenAIImagePart())
        }
        return parts
    }

    private fun AgentImage.toOpenAIImagePart(): Map<String, Any> = mapOf(
        "type" to "image_url",
        "image_url" to mapOf(
            "url" to "data:$mimeType;base64,$base64Data",
            "detail" to "auto"
        )
    )

    /** `data:` URL (base64 embedded) -> AgentImage; remote URLs / non-base64 / invalid input all return null. */
    private fun dataUrlToAgentImage(url: String): AgentImage? {
        if (!url.startsWith("data:", ignoreCase = true)) return null
        val comma = url.indexOf(',')
        if (comma < 0) return null
        val header = url.substring(5, comma)
        if (!header.contains(";base64", ignoreCase = true)) return null
        val mime = header.substringBefore(';').ifBlank { "image/png" }
        val data = url.substring(comma + 1)
        if (data.isBlank()) return null
        return AgentImage(mimeType = mime, base64Data = data)
    }

    /** A single element in streaming delta.images -> AgentImage. */
    private fun com.google.gson.JsonObject.toOpenAIAgentImage(): AgentImage? {
        val url = when (val img = get("image_url")) {
            is com.google.gson.JsonPrimitive -> img.asString
            is com.google.gson.JsonObject -> img.get("url")?.takeIf { it.isJsonPrimitive }?.asString
            else -> null
        } ?: return null
        return dataUrlToAgentImage(url)
    }

    /** A single image item in non-streaming message.images -> AgentImage. */
    private fun OpenAIImagePart.toAgentImage(): AgentImage? {
        val url = image_url?.url ?: return null
        return dataUrlToAgentImage(url)
    }

    /**
     * The content returned by an OpenAI chat completion may be a string or an array (multimodal / image-generation models).
     * Array elements may contain an image_url base64 data URL (several MB), and calling toString() directly would store the entire base64 blob
     * as assistant text in the database, overflowing the SQLite CursorWindow and crashing startup. Only the text portion is extracted here;
     * for images only a description / remote URL reference is kept, and base64 is never written into content.
     */
    private fun Any?.asTextContent(): String = when (this) {
        null -> ""
        is String -> this
        is List<*> -> extractTextFromContentParts(this)
        else -> toString()
    }

    private fun extractTextFromContentParts(parts: List<*>): String {
        val text = StringBuilder()
        for (part in parts) {
            when (part) {
                is Map<*, *> -> {
                    when (val type = part["type"] as? String) {
                        "text", "input_text", "output_text" -> {
                            (part["text"] as? String)?.let { text.append(it) }
                        }
                        "image_url" -> {
                            val url = when (val img = part["image_url"]) {
                                is String -> img
                                is Map<*, *> -> img["url"] as? String
                                else -> null
                            }
                            if (url != null && url.startsWith("data:image", ignoreCase = true)) {
                                text.append("\n[Image omitted: embedded image data too large]")
                            } else if (!url.isNullOrBlank()) {
                                text.append("\n[Image: ").append(url).append("]")
                            }
                        }
                        "input_image" -> {
                            text.append("\n[Image omitted: embedded image data too large]")
                        }
                        else -> {}
                    }
                }
                is String -> text.append(part)
                else -> {}
            }
        }
        return text.toString()
    }

    private fun convertToToolCall(openAIToolCall: OpenAIToolCall): ToolCall {
        val argumentsJson = runCatching {
            Json.parseToJsonElement(openAIToolCall.function.arguments).jsonObject
        }.getOrElse { JsonObject(emptyMap()) }
        return ToolCall(
            id = openAIToolCall.id,
            name = openAIToolCall.function.name,
            arguments = argumentsJson
        )
    }

    private fun convertToOpenAIToolCall(toolCall: ToolCall): OpenAIToolCall {
        return OpenAIToolCall(
            id = toolCall.id,
            type = "function",
            function = com.aicode.feature.agent.data.remote.openai.OpenAIFunctionCall(
                name = toolCall.name,
                arguments = JsonObject(toolCall.arguments).toString()
            )
        )
    }
}
