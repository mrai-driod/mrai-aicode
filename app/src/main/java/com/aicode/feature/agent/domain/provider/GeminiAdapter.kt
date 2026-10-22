package com.aicode.feature.agent.domain.provider

import com.aicode.core.util.AILogger
import com.aicode.feature.agent.data.remote.gemini.GeminiApi
import com.aicode.feature.agent.domain.model.AgentImage
import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.ToolCall
import com.aicode.feature.agent.domain.tool.modelToolResultText
import com.google.gson.JsonParser
import com.google.gson.JsonObject
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
import kotlinx.serialization.json.jsonObject
import java.io.IOException

class GeminiAdapter @Inject constructor(
    private val api: GeminiApi
) : AIProvider {

    override var apiKey = ""
    override var keySwitcher: (suspend (Throwable, String, Set<String>) -> KeySwitchOutcome?)? = null
    override var baseUrl = "https://generativelanguage.googleapis.com/"
    override var useFullUrl = false
    override var useResponseApi = false
    override var model = "gemini-1.5-flash"
    override var providerId = ""
    override var logSessionId: String? = null
    override var firstByteTimeoutMs: Long = FIRST_BYTE_TIMEOUT_MS
    override var streamIdleTimeoutMs: Long = 0L
    override var maxNetworkRetries: Int = MAX_NETWORK_RETRIES

    /** Custom request headers: written out after placeholder replacement, completely overriding default headers of the same name. */
    override var customHeaders: Map<String, String> = emptyMap()

    // Gemini sends generationConfig.maxOutputTokens (the output limit from the model metadata); when null, that parameter is not sent and the server default is used.
    override var maxOutputTokens: Int? = null

    // Native generateContent does not send generationConfig.temperature: since Gemini 2.0 the server default is already 1.0, matching the recommended value.
    override var temperature: Float? = null

    private fun extraHeaders(): Map<String, String> =
        resolveCustomHeaders(customHeaders, logSessionId, apiKey)

    /**
     * The Interactions API target endpoint. Two differences from generateContent:
     * the model name is in the request body (so there is no special case like "baseUrl ends with the model name"), and streaming and non-streaming use **the same endpoint**,
     * switched by `?alt=sse` + `stream: true` in the request body. When useFullUrl, the full baseUrl the user entered is used directly.
     */
    private fun resolveInteractionsUrl(stream: Boolean): String {
        if (useFullUrl) return baseUrl
        val url = joinUrl(baseUrl, "v1beta/interactions")
        return if (stream) "$url?alt=sse" else url
    }

    override suspend fun complete(
        systemPrompt: String,
        messages: List<AgentMessage>,
        tools: List<AgentTool>,
        reasoningEffort: String?
    ): AIResponse {
        if (useResponseApi) {
            return completeViaInteractions(systemPrompt, messages, tools, reasoningEffort)
        }

        val request = buildRequestBody(systemPrompt, messages, tools, reasoningEffort)

        val url = if (useFullUrl) {
            baseUrl
        } else {
            val path = if (baseUrl.trimEnd('/').endsWith(model)) {
                baseUrl.trimEnd('/') + ":generateContent"
            } else {
                joinUrl(baseUrl, "v1beta/models/$model:generateContent")
            }
            path
        }
        val seq = AILogger.logRequest(logSessionId, "Gemini", model, "POST", url, request)

        val triedKeys = mutableSetOf(apiKey)
        val response = try {
            retryStaircase(
                maxRetries = maxNetworkRetries,
                onKeyFailure = { e, _ -> switchKeyOnFailure(e, triedKeys) != null }
            ) {
                api.generateContent(url = url, apiKey = apiKey, extraHeaders = extraHeaders(), request = request)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val enriched = e.enrichWithHttpErrorBody()
            AILogger.logError(logSessionId, "Gemini", enriched, seq)
            throw enriched
        }
        AILogger.logResponse(logSessionId, "Gemini", response, seq)

        var contentText = ""
        var thinkingText = ""
        val toolCalls = mutableListOf<ToolCall>()
        val images = mutableListOf<AgentImage>()
        var finishReason: String? = null
        var partsSnapshot: String? = null

        // Non-streaming response field types occasionally differ (when content-safety filtering applies, candidates may be missing or empty, and finishReason/text may be
        // JSON null), and Gson's getAsJsonArray/getAsJsonObject/asString throws directly in these cases.
        // Aligned with the streaming path (:208-218): guard every field with takeIf { !it.isJsonNull }, wrap the whole block in runCatching as a fallback,
        // and a bad response returns a clear result with a stopReason for the upper layer to handle, rather than throwing the parsing exception at the user.
        val parseFailure = runCatching {
            val candidates = response.get("candidates")?.takeIf { it.isJsonArray }?.asJsonArray
            candidates?.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject?.let { candidate ->
                finishReason = candidate.get("finishReason")?.takeIf { !it.isJsonNull }?.asString
                val content = candidate.get("content")?.takeIf { it.isJsonObject }?.asJsonObject
                val parts = content?.get("parts")?.takeIf { it.isJsonArray }?.asJsonArray
                parts?.forEach { partEl ->
                    val part = partEl.asJsonObject
                    val isThought = part.get("thought")?.takeIf { !it.isJsonNull }?.asBoolean == true
                    if (part.has("text")) {
                        val text = part.get("text")?.takeIf { !it.isJsonNull }?.asString ?: ""
                        if (isThought) thinkingText += text else contentText += text
                    }
                    part.inlineImagePart()?.let { images.add(it) }
                    if (part.has("functionCall")) {
                        val fnCall = part.get("functionCall")?.takeIf { it.isJsonObject }?.asJsonObject
                        val name = fnCall?.get("name")?.takeIf { !it.isJsonNull }?.asString ?: ""
                        // In parallel calls the server gives an id; only fall back to the function name when missing (calling the same-named tool twice would collide on id).
                        val callId = fnCall?.get("id")?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotBlank() } ?: name
                        val argsStr = fnCall?.get("args")?.takeIf { it.isJsonObject }?.asJsonObject?.toString() ?: "{}"
                        val argsJson = parseArgs(argsStr)
                        toolCalls.add(ToolCall(id = callId, name = name, arguments = argsJson))
                    }
                }
                partsSnapshot = snapshotOf(parts)
            }

            // Content-safety filtering: candidates is empty but promptFeedback gives a blockReason (SAFETY / PROHIBITED_CONTENT
            // etc.); pass it up as the stopReason, so the upper layer displays the reason as an "abort" instead of silently returning empty body text.
            if (finishReason == null && (candidates == null || candidates.size() == 0)) {
                response.get("promptFeedback")?.takeIf { it.isJsonObject }?.asJsonObject
                    ?.get("blockReason")?.takeIf { !it.isJsonNull }?.asString
                    ?.let { finishReason = it }
            }
        }.exceptionOrNull()

        val usageMetadata = response.get("usageMetadata")?.takeIf { it.isJsonObject }?.asJsonObject
        val inputTokens = usageMetadata?.get("promptTokenCount")?.takeIf { !it.isJsonNull }?.asInt ?: 0
        // candidatesTokenCount does not include thinking tokens, and thinking is billed at the output price, so the two added together are the real output amount.
        val outputTokens = (usageMetadata?.get("candidatesTokenCount")?.takeIf { !it.isJsonNull }?.asInt ?: 0) +
            (usageMetadata?.get("thoughtsTokenCount")?.takeIf { !it.isJsonNull }?.asInt ?: 0)
        val cachedInputTokens = usageMetadata?.get("cachedContentTokenCount")?.takeIf { !it.isJsonNull }?.asInt ?: 0

        // The response structure is corrupted (the parsing process threw) and there is no displayable body/reason: return a clear abort result instead of crashing;
        // stopReason=failed hits isAborted, and the upper layer displays the error text.
        val aborted = parseFailure != null && finishReason == null
        return AIResponse(
            content = contentText,
            toolCalls = toolCalls,
            stopReason = if (aborted) "failed" else finishReason,
            stopDetail = if (aborted) parseFailure?.message ?: "Gemini response parsing failed" else null,
            reasoning = thinkingText.ifEmpty { null },
            thinkingBlocksJson = partsSnapshot,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            cachedInputTokens = cachedInputTokens,
            images = images
        )
    }

    override fun completeStream(
        systemPrompt: String,
        messages: List<AgentMessage>,
        tools: List<AgentTool>,
        reasoningEffort: String?
    ): Flow<AIStreamChunk> = flow {
        if (useResponseApi) {
            streamViaInteractions(systemPrompt, messages, tools, reasoningEffort)
            return@flow
        }

        val request = buildRequestBody(systemPrompt, messages, tools, reasoningEffort)

        val url = if (useFullUrl) {
            baseUrl
        } else {
            val path = if (baseUrl.trimEnd('/').endsWith(model)) {
                baseUrl.trimEnd('/') + ":streamGenerateContent?alt=sse"
            } else {
                joinUrl(baseUrl, "v1beta/models/$model:streamGenerateContent?alt=sse")
            }
            path
        }
        
        val seq = AILogger.logRequest(logSessionId, "Gemini", model, "POST", url, request)
        val sseLog = AILogger.beginRawSse(logSessionId, "Gemini", seq)

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
                val toolCalls = mutableListOf<ToolCall>()
                val images = mutableListOf<AgentImage>()
                // Verbatim snapshot of a model turn's parts: text fragments are merged by segment, and functionCall and thoughtSignature are kept as-is.
                val snapshotParts = mutableListOf<JsonObject>()
                var currentFinishReason: String? = null
                // Reached the end of the stream without waiting for a finishReason (peer EOF / proxy disconnecting early / network switch);
                // when body text already exists, deliver in degraded form instead of failing the whole turn; see [logStreamTruncated].
                var truncatedByNetwork = false
                var sseLines = 0
                var streamInputTokens = 0
                var streamOutputTokens = 0
                var streamCachedInputTokens = 0

                val body = firstContent.awaitBody(api.streamGenerateContent(url = url, apiKey = apiKey, extraHeaders = extraHeaders(), request = request))

                body.use { rb ->
                    firstContent.attach { rb.close() }
                    val idleWatchdog = launchStreamIdleWatchdog(streamIdleTimeoutMs) { firstContent.closeBody() }
                    val closeHandle = coroutineContext[Job]?.invokeOnCompletion {
                        runCatching { rb.close() }
                    }
                    try {
                        val reader = rb.charStream().buffered()
                        // Break and end normally as soon as a finishReason is received; readLine() returning null means it had not yet arrived and
                        // the connection was already gone. Only throw an exception to let retry/logging take over when no body text has been received yet; once text has streamed out, deliver in degraded form
                        // (mark the output as truncated, and the Agent loop continues writing automatically), avoiding failing the whole turn and discarding all already streamed content.
                        while (true) {
                            coroutineContext.ensureActive()
                            val line = reader.readLine()
                            if (line == null) {
                                if (!textBuilder.isSalvageableAfterTruncation()) {
                                    throw IOException("SSE stream interrupted (suspected network disconnect)")
                                }
                                truncatedByNetwork = true
                                logStreamTruncated("Gemini", textBuilder.length, sseLines)
                                break
                            }
                            sseLines++
                            idleWatchdog.touch()
                            if (!line.startsWith("data:")) continue
                            val data = line.removePrefix("data:").trim()
                            if (data.isEmpty()) continue
                            sseLog.append(line)
                            val obj = runCatching { JsonParser.parseString(data).asJsonObject }.getOrNull() ?: continue
                            
                            try {
                                obj.get("usageMetadata")?.takeIf { it.isJsonObject }?.asJsonObject?.let { um ->
                                    streamInputTokens = um.get("promptTokenCount")?.takeIf { !it.isJsonNull }?.asInt ?: streamInputTokens
                                    // Thinking tokens are not in candidatesTokenCount, but are billed at the output price; the two added together are the real output amount.
                                    val candidateTokens = um.get("candidatesTokenCount")?.takeIf { !it.isJsonNull }?.asInt
                                    val thoughtTokens = um.get("thoughtsTokenCount")?.takeIf { !it.isJsonNull }?.asInt
                                    if (candidateTokens != null || thoughtTokens != null) {
                                        streamOutputTokens = (candidateTokens ?: 0) + (thoughtTokens ?: 0)
                                    }
                                    streamCachedInputTokens = um.get("cachedContentTokenCount")?.takeIf { !it.isJsonNull }?.asInt ?: streamCachedInputTokens
                                }
                                val chunkCandidates = obj.getAsJsonArray("candidates")
                                chunkCandidates?.firstOrNull()?.asJsonObject?.let { candidate ->
                                    val reason = candidate.get("finishReason")?.takeIf { !it.isJsonNull }?.asString
                                    if (reason != null && reason != "null") currentFinishReason = reason

                                    val content = candidate.getAsJsonObject("content")
                                    content?.getAsJsonArray("parts")?.forEach { partEl ->
                                        val part = partEl.asJsonObject
                                        val isThought = part.get("thought")?.asBoolean == true
                                        if (part.has("functionCall")) {
                                            firstContent.receivedContent()
                                            onContent()
                                            budget.add(part.toString())
                                        }
                                        accumulateSnapshotPart(snapshotParts, part, isThought)
                                        if (part.has("text")) {
                                            val text = part.get("text")?.asString ?: ""
                                            if (text.isNotEmpty()) {
                                                budget.add(text)
                                                if (isThought) {
                                                    // Thinking delta: shown live in the UI only, not counted as body text (not persisted, so it can safely stream again on retry)
                                                    firstContent.receivedContent()
                                                    onContent()
                                                    emit(AIStreamChunk.ReasoningDelta(text))
                                                } else {
                                                    textBuilder.append(text)
                                                    firstContent.receivedContent()
                                                    onContent()
                                                    emit(AIStreamChunk.TextDelta(text))
                                                }
                                            }
                                        }
                                        part.inlineImagePart()?.let { img ->
                                            images.add(img)
                                            firstContent.receivedContent()
                                            onContent()
                                        }
                                        if (part.has("functionCall")) {
                                            val fnCall = part.getAsJsonObject("functionCall")
                                            val name = fnCall.get("name")?.asString ?: ""
                                            // As soon as a tool name appears, notify the UI, replacing "thinking" with a concrete scenario
                                            if (name.isNotEmpty()) emit(AIStreamChunk.ToolCallDeclared(name))
                                            val callId = fnCall.get("id")?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotBlank() } ?: name
                                            val argsStr = fnCall.getAsJsonObject("args")?.toString() ?: "{}"
                                            val argsJson = parseArgs(argsStr)
                                            toolCalls.add(ToolCall(id = callId, name = name, arguments = argsJson))
                                        }
                                    }
                                }
                                if (currentFinishReason != null) break
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                coroutineContext.ensureActive()
                                // ignore
                            }
                        }
                    } finally {
                        idleWatchdog.cancel()
                        closeHandle?.dispose()
                    }
                }

                // On degraded delivery after disconnection, mark the stopReason as length, so the Agent loop continues writing automatically instead of failing the whole turn.
                val truncated = truncatedByNetwork && currentFinishReason == null
                emit(AIStreamChunk.Final(AIResponse(content = textBuilder.toString(), toolCalls = toolCalls, stopReason = if (truncated) STREAM_TRUNCATED_STOP_REASON else currentFinishReason, thinkingBlocksJson = snapshotOf(snapshotParts), inputTokens = streamInputTokens, outputTokens = streamOutputTokens, cachedInputTokens = streamCachedInputTokens, images = images)))
                    }
                },
                onRetry = { attempt, max, error -> emit(AIStreamChunk.Retrying(attempt, max, error)) }
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            coroutineContext.ensureActive()
            val enriched = e.enrichWithHttpErrorBody()
            AILogger.logError(logSessionId, "Gemini", enriched, seq)
            throw enriched
        } finally {
            sseLog.finish()
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Interactions request body. The differences from generateContent are concentrated in four places:
     * - `system_instruction` is a **top-level string**, no longer a `{role, parts}` object;
     * - history is a flat step sequence (see [buildInteractionsInput]), and the model name goes into the request body;
     * - tool declarations are flat, no longer wrapped in `functionDeclarations`;
     * - thinking has only `thinking_level` (the 2.5 series' `thinkingBudget` set does not exist here).
     *
     * Always send `store=false` for stateless operation: the local messages list is the sole source of truth, and context compression, regeneration and
     * failure retries all depend on the client holding the full history; server-side state would conflict directly with them.
     * Do not send `temperature` — the Interactions GenerationConfig has no such field.
     */
    private fun buildInteractionsRequest(
        systemPrompt: String,
        messages: List<AgentMessage>,
        tools: List<AgentTool>,
        reasoningEffort: String?,
        stream: Boolean
    ): Map<String, Any?> {
        val request = mutableMapOf<String, Any?>(
            "model" to model,
            "input" to buildInteractionsInput(messages),
            "store" to false
        )
        if (systemPrompt.isNotBlank()) request["system_instruction"] = systemPrompt
        buildInteractionsTools(tools)?.let { request["tools"] = it }
        if (stream) request["stream"] = true

        val generationConfig = mutableMapOf<String, Any>()
        maxOutputTokens?.takeIf { it > 0 }?.let { generationConfig["max_output_tokens"] = it }
        interactionsThinkingLevel(reasoningEffort)?.let {
            generationConfig["thinking_level"] = it
            // Without explicitly asking for a summary you only get the thinking signature, not displayable thinking text, and the UI's thinking area stays empty.
            // Send it together with thinking_level: models that do not think (gemma / image / music) should not receive either of these fields.
            generationConfig["thinking_summaries"] = "auto"
        }
        if (generationConfig.isNotEmpty()) request["generation_config"] = generationConfig
        return request
    }

    /** Thinking strength -> `thinking_level`, supporting only minimal/low/medium/high; none skips it (no thinking is sent), xhigh/max are normalised to high. */
    private fun interactionsThinkingLevel(reasoningEffort: String?): String? = when (reasoningEffort) {
        null, "none" -> null
        "xhigh", "max" -> "high"
        else -> reasoningEffort
    }

    /**
     * The Interactions non-streaming request. The result is parsed from the `steps` timeline (see [parseInteractionSteps]);
     * the end reason is no longer `finishReason` but the interaction's `status`: `incomplete` means it hit the output limit,
     * `requires_action` means there are tools awaiting execution; see [interactionStopReason] for value normalisation.
     */
    private suspend fun completeViaInteractions(
        systemPrompt: String,
        messages: List<AgentMessage>,
        tools: List<AgentTool>,
        reasoningEffort: String?
    ): AIResponse {
        val url = resolveInteractionsUrl(stream = false)
        val request = buildInteractionsRequest(systemPrompt, messages, tools, reasoningEffort, stream = false)
        val seq = AILogger.logRequest(logSessionId, "Gemini", model, "POST", url, request)

        val triedKeys = mutableSetOf(apiKey)
        val response = try {
            retryStaircase(
                maxRetries = maxNetworkRetries,
                onKeyFailure = { e, _ -> switchKeyOnFailure(e, triedKeys) != null }
            ) {
                api.createInteraction(url = url, apiKey = apiKey, extraHeaders = extraHeaders(), request = request)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val enriched = e.enrichWithHttpErrorBody()
            AILogger.logError(logSessionId, "Gemini", enriched, seq)
            throw enriched
        }
        AILogger.logResponse(logSessionId, "Gemini", response, seq)

        val parsed = parseInteractionSteps(response.get("steps")?.takeIf { it.isJsonArray }?.asJsonArray)
        val usage = parseInteractionsUsage(response.get("usage")?.takeIf { it.isJsonObject }?.asJsonObject)
        val status = response.get("status")?.takeIf { it.isJsonPrimitive }?.asString
        // When status=failed the body is usually empty and the reason is only in errors; extract it and hand it to the upper layer to display.
        val errorDetail = response.get("errors")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
            ?.get("message")?.takeIf { it.isJsonPrimitive }?.asString

        return AIResponse(
            content = parsed.text,
            toolCalls = parsed.toolCalls,
            stopReason = interactionStopReason(status),
            stopDetail = errorDetail,
            reasoning = parsed.reasoning.takeIf { it.isNotEmpty() },
            thinkingBlocksJson = parsed.stepsSnapshotJson,
            inputTokens = usage.inputTokens,
            outputTokens = usage.outputTokens,
            cachedInputTokens = usage.cachedInputTokens,
            images = parsed.images
        )
    }

    /**
     * The Interactions streaming request. Unlike generateContent's candidate fragments, this is a semantic event stream
     * (see [GeminiInteractionsStreamAccumulator]), and there is no `[DONE]`: the end is judged by the interaction's
     * status reaching a terminal state; only reaching the end of the stream still unterminated counts as truncated.
     */
    private suspend fun FlowCollector<AIStreamChunk>.streamViaInteractions(
        systemPrompt: String,
        messages: List<AgentMessage>,
        tools: List<AgentTool>,
        reasoningEffort: String?
    ) {
        val url = resolveInteractionsUrl(stream = true)
        val request = buildInteractionsRequest(systemPrompt, messages, tools, reasoningEffort, stream = true)
        val seq = AILogger.logRequest(logSessionId, "Gemini", model, "POST", url, request)
        // Raw SSE is written to disk in batches (see AILogger.beginRawSse), finalised in finally at the end of this turn.
        val sseLog = AILogger.beginRawSse(logSessionId, "Gemini", seq)
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
                    val acc = GeminiInteractionsStreamAccumulator()

                    val body = firstContent.awaitBody(api.streamInteraction(
                        url = url,
                        apiKey = apiKey,
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
                            // Same strategy as generateContent: if the connection is lost before a terminal state, only throw when no body text has been received yet, to let
                            // retry take over; once text has streamed out, deliver in degraded form (mark as truncated, and the Agent loop continues writing).
                            while (!acc.terminated) {
                                coroutineContext.ensureActive()
                                val line = reader.readLine()
                                if (line == null) {
                                    if (acc.streamedTextLength <= 0) {
                                        throw IOException("SSE stream interrupted: interaction did not reach a terminal state (suspected network disconnect)")
                                    }
                                    acc.markTruncatedByNetwork()
                                    logStreamTruncated("Gemini/Interactions", acc.streamedTextLength, sseLines)
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
                                // A field-type anomaly in a single event should not discard the whole stream; skip only that event;
                                // but StreamApiException (error events) and cancellation signals must be let through.
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
                                    is InteractionsDelta.Text -> {
                                        firstContent.receivedContent()
                                        onContent()
                                        emit(AIStreamChunk.TextDelta(delta.text))
                                    }
                                    // Thinking deltas are only for UI display and are not counted as body text; receiving one means the connection is alive.
                                    is InteractionsDelta.Reasoning -> {
                                        firstContent.receivedContent()
                                        onContent()
                                        emit(AIStreamChunk.ReasoningDelta(delta.text))
                                    }
                                    // The tool name arrives before the arguments: notify the UI to switch the status to a concrete scenario early
                                    is InteractionsDelta.ToolCallDeclared -> {
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
            AILogger.logError(logSessionId, "Gemini", enriched, seq)
            throw enriched
        } finally {
            sseLog.finish()
        }
    }

    /**
     * Request body shared by generateContent / streamGenerateContent.
     * thinkingConfig and maxOutputTokens in `generationConfig` must be written merged (assigning them twice in separate steps would overwrite each other).
     */
    private fun buildRequestBody(
        systemPrompt: String,
        messages: List<AgentMessage>,
        tools: List<AgentTool>,
        reasoningEffort: String?
    ): MutableMap<String, Any> {
        val request = mutableMapOf<String, Any>(
            "contents" to convertToGeminiContents(messages)
        )
        if (systemPrompt.isNotBlank()) {
            request["systemInstruction"] = mapOf(
                "role" to "system",
                "parts" to listOf(mapOf("text" to systemPrompt))
            )
        }
        tools.takeIf { it.isNotEmpty() }?.map { tool ->
            mapOf(
                "name" to tool.name,
                "description" to tool.description,
                "parameters" to tool.toJsonSchema()
            )
        }?.let { request["tools"] = listOf(mapOf("functionDeclarations" to it)) }

        val generationConfig = mutableMapOf<String, Any>()
        buildThinkingConfig(reasoningEffort)?.let { generationConfig["thinkingConfig"] = it }
        maxOutputTokens?.takeIf { it > 0 }?.let { generationConfig["maxOutputTokens"] = it }
        if (generationConfig.isNotEmpty()) request["generationConfig"] = generationConfig
        return request
    }

    /**
     * Verbatim snapshot of a model turn's parts; generated only when content that must be sent back as-is exists (thoughtSignature or functionCall);
     * a pure-text turn returns null, letting history be rebuilt conventionally (avoiding body text being written twice).
     */
    private fun snapshotOf(parts: com.google.gson.JsonArray?): String? {
        if (parts == null || parts.size() == 0) return null
        val needsSnapshot = parts.any { el ->
            val o = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@any false
            o.has("functionCall") || o.has("thoughtSignature") || o.has("inlineData")
        }
        return if (needsSnapshot) parts.toString() else null
    }

    private fun snapshotOf(parts: List<JsonObject>): String? {
        if (parts.isEmpty()) return null
        val array = com.google.gson.JsonArray().apply { parts.forEach { add(it) } }
        return snapshotOf(array)
    }

    /**
     * Merges one streaming part into the snapshot: a functionCall is kept as its own segment as-is; text fragments are merged in segments by "whether thought",
     * and a fragment's thoughtSignature is written back to its segment (the signature is part-level metadata and only arrives with one fragment).
     */
    /** Image part (camelCase inlineData / snake_case inline_data) -> [AgentImage]; an image produced directly by an image model arrives as a whole block. */
    private fun JsonObject.inlineImagePart(): AgentImage? =
        (get("inlineData") ?: get("inline_data"))?.takeIf { it.isJsonObject }?.asJsonObject?.let { inline ->
            val data = inline.get("data")?.takeIf { !it.isJsonNull }?.asString
            if (data.isNullOrBlank()) null
            else AgentImage(
                mimeType = inline.get("mimeType")?.takeIf { !it.isJsonNull }?.asString
                    ?: inline.get("mime_type")?.takeIf { !it.isJsonNull }?.asString
                    ?: "image/png",
                base64Data = data
            )
        }

    private fun accumulateSnapshotPart(snapshot: MutableList<JsonObject>, part: JsonObject, isThought: Boolean) {
        if (part.has("functionCall")) {
            snapshot.add(part.deepCopy())
            return
        }
        if (!part.has("text")) {
            // An image arrives as a whole block with no text fragments to merge; it goes into the snapshot as-is for use in the next editing turn (including metadata such as SynthID).
            if (part.has("thoughtSignature") || part.has("inlineData")) snapshot.add(part.deepCopy())
            return
        }
        val last = snapshot.lastOrNull()?.takeIf { prev ->
            !prev.has("functionCall") &&
                prev.has("text") &&
                (prev.get("thought")?.asBoolean == true) == isThought
        }
        if (last != null) {
            last.addProperty("text", last.get("text").asString + (part.get("text")?.asString ?: ""))
            part.get("thoughtSignature")?.takeIf { !it.isJsonNull }?.let { last.add("thoughtSignature", it) }
        } else {
            snapshot.add(part.deepCopy())
        }
    }

    /** Restores a snapshot into a parts array; returns null when empty or corrupted, and the caller falls back to the rebuild logic. */
    private fun decodeSnapshotParts(json: String): com.google.gson.JsonArray? {
        if (json.isBlank()) return null
        return runCatching {
            JsonParser.parseString(json).asJsonArray.takeIf { it.size() > 0 }
        }.getOrNull()
    }

    /** Thinking strength -> Gemini thinkingConfig. If the model name contains gemini-3 use thinkingLevel, otherwise use thinkingBudget (2.5 series). Explicitly include includeThoughts to return thinking content. */
    private fun buildThinkingConfig(reasoningEffort: String?): Map<String, Any>? {
        if (reasoningEffort == null) return null
        return if (model.contains("gemini-3")) {
            // thinkingLevel supports only minimal/low/medium/high; none skips it (no thinking is sent), xhigh/max are normalised to high (when the metadata has no match, the UI offers all levels)
            val level = when (reasoningEffort) {
                "none" -> return null
                "xhigh", "max" -> "high"
                else -> reasoningEffort
            }
            mapOf("thinkingLevel" to level, "includeThoughts" to true)
        } else {
            val budget = when (reasoningEffort) {
                "low" -> 1024
                "medium" -> 4096
                "high", "xhigh", "max" -> 8192
                else -> return null
            }
            mapOf("thinkingBudget" to budget, "includeThoughts" to true)
        }
    }

    private fun parseArgs(raw: String): kotlinx.serialization.json.JsonObject {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return kotlinx.serialization.json.JsonObject(emptyMap())
        return runCatching { Json.parseToJsonElement(trimmed).jsonObject }.getOrElse { kotlinx.serialization.json.JsonObject(emptyMap()) }
    }

    private fun convertToGeminiContents(messages: List<AgentMessage>): List<Map<String, Any>> {
        val result = mutableListOf<Map<String, Any>>()
        // Defensive tracking: whether the previous assistant (i.e. model) message contains a functionCall
        var lastModelHadFunctionCall = false
        // Multiple tool results from the same model turn must be merged into the same user content: in parallel calls, Gemini and the various compatible
        // gateways pair them by "return all functionResponses in one turn"; splitting them across multiple turns is judged as missing tool results (HTTP 400).
        var pendingToolResponseParts: MutableList<Map<String, Any>>? = null

        for (message in messages) {
            when (message) {
                is AgentMessage.UserMessage -> {
                    val parts = mutableListOf<Map<String, Any>>()
                    if (message.content.isNotBlank()) {
                        parts.add(mapOf("text" to message.content))
                    }
                    message.images.forEach { image ->
                        parts.add(image.toGeminiInlineDataPart())
                    }
                    result.add(
                        mapOf(
                            "role" to "user",
                            "parts" to parts
                        )
                    )
                    lastModelHadFunctionCall = false
                    pendingToolResponseParts = null
                }
                is AgentMessage.AssistantMessage -> {
                    lastModelHadFunctionCall = message.toolCalls.isNotEmpty()
                    pendingToolResponseParts = null
                    // When the previous model output has a verbatim snapshot stored, send it back as-is directly: thoughtSignature is part-level metadata,
                    // and rebuilding the parts would lose the signature, so the Gemini 3 series could not continue the previous turn's reasoning context.
                    val snapshot = decodeSnapshotParts(message.thinkingBlocksJson)
                    if (snapshot != null) {
                        result.add(mapOf("role" to "model", "parts" to snapshot))
                        continue
                    }
                    val parts = mutableListOf<Map<String, Any>>()
                    if (message.content.isNotEmpty()) {
                        parts.add(mapOf("text" to message.content))
                    }
                    for (toolCall in message.toolCalls) {
                        parts.add(
                            mapOf(
                                "functionCall" to mapOf(
                                    "name" to toolCall.name,
                                    "args" to toolCall.arguments
                                )
                            )
                        )
                    }
                    if (parts.isNotEmpty()) {
                        result.add(
                            mapOf(
                                "role" to "model",
                                "parts" to parts
                            )
                        )
                    }
                }
                is AgentMessage.ToolResultMessage -> {
                    // Defensive cleanup: skip orphan functionResponses with no paired functionCall
                    if (!lastModelHadFunctionCall) continue
                    val parts = pendingToolResponseParts ?: mutableListOf<Map<String, Any>>().also {
                        result.add(mapOf("role" to "user", "parts" to it))
                        pendingToolResponseParts = it
                    }
                    // File tools feed the model a compact projection text; the UI/persistence still use the full result.
                    val modelText = message.modelResult
                        ?: modelToolResultText(message.toolName, message.result)
                        ?: message.result
                    // Send the function name as name, and in parallel calls additionally send the id back for pairing (in old data the id is the function name, in which case no id is sent).
                    val functionName = message.toolName.ifBlank { message.id }
                    val functionResponse = mutableMapOf<String, Any>(
                        "name" to functionName,
                        "response" to mapOf("result" to modelText)
                    )
                    if (message.id.isNotBlank() && message.id != functionName) {
                        functionResponse["id"] = message.id
                    }
                    parts.add(mapOf("functionResponse" to functionResponse))
                    message.images.forEach { image ->
                        parts.add(image.toGeminiInlineDataPart())
                    }
                }
            }
        }
        return result
    }

    private fun AgentImage.toGeminiInlineDataPart(): Map<String, Any> {
        return mapOf(
            "inline_data" to mapOf(
                "mime_type" to mimeType,
                "data" to base64Data
            )
        )
    }
}
