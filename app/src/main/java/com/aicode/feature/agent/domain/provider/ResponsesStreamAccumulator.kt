package com.aicode.feature.agent.domain.provider

import com.aicode.feature.agent.data.remote.openai.ResponsesEvent
import com.aicode.feature.agent.data.remote.openai.ResponsesItem
import com.aicode.feature.agent.data.remote.openai.ResponsesPart
import com.aicode.feature.agent.domain.model.AgentImage
import com.aicode.feature.agent.domain.tool.ToolCall
import com.google.gson.JsonArray
import com.google.gson.JsonObject

/** Parse result of a Responses response body's `output` array. */
internal data class ResponsesOutput(
    val text: String = "",
    val reasoning: String = "",
    val toolCalls: List<ToolCall> = emptyList(),
    /** Images produced by the model via the server-side image_generation tool (`result` base64). */
    val images: List<AgentImage> = emptyList(),
    /** Official Responses thinking-item snapshot (including encrypted_content and summary), for safe stateless multi-turn resending. */
    val thinkingBlocksJson: String? = null
)

/** Responses token usage. */
internal data class ResponsesUsage(
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val cachedInputTokens: Int = 0,
    val cacheCreationTokens: Int = 0
)

/**
 * Parses a Responses `output` array: shared by non-streaming responses and streaming terminal events.
 *
 * The key difference from Chat Completions: a tool call is a **top-level item** in the output (`type: "function_call"`,
 * carrying its own `call_id` / `name` / `arguments`), not a part inside an assistant message's content.
 * Looking for it in message.content the old way would never parse a tool call.
 */
internal fun parseResponsesOutput(output: JsonArray?): ResponsesOutput {
    if (output == null) return ResponsesOutput()
    val text = StringBuilder()
    val reasoning = StringBuilder()
    val toolCalls = mutableListOf<ToolCall>()
    val images = mutableListOf<AgentImage>()
    var thinkingBlocksSnapshot: String? = null
    output.forEach { element ->
        // Upstream field types occasionally differ; a single item failing to parse should not discard the whole response
        runCatching {
            val item = element.asJsonObject
            when (item.str("type")) {
                ResponsesItem.MESSAGE -> item.arr("content")?.forEach { partEl ->
                    val part = partEl.asJsonObject
                    when (part.str("type")) {
                        ResponsesPart.OUTPUT_TEXT -> text.append(part.str("text").orEmpty())
                        // The refusal explanation is in the refusal field; without including it the whole reply would be blank
                        ResponsesPart.REFUSAL -> text.append(part.str("refusal").orEmpty())
                    }
                }

                ResponsesItem.FUNCTION_CALL -> toolCalls.add(
                    ToolCall(
                        id = item.str("call_id") ?: item.str("id").orEmpty(),
                        name = item.str("name").orEmpty(),
                        arguments = parseToolArguments(item.str("arguments").orEmpty())
                    )
                )

                // Server-side image generation: the image arrives as a whole block in the item's result (base64), with no streaming delta.
                ResponsesItem.IMAGE_GENERATION_CALL -> {
                    item.str("result")?.takeIf { it.isNotEmpty() }?.let { result ->
                        images.add(AgentImage(mimeType = mimeForImageFormat(item.str("output_format")), base64Data = result))
                    }
                }

                // Thinking content may be in content (reasoning_text) or summary (summary_text), depending on the service;
                // either may be null (e.g. when no thinking plaintext is returned).
                // Also extract the encrypted thinking snapshot (encrypted_content), for safe stateless multi-turn resending.
                ResponsesItem.REASONING -> {
                    item.arr("content")?.forEach { partEl ->
                        val part = partEl.asJsonObject
                        if (part.str("type") == ResponsesPart.REASONING_TEXT) {
                            reasoning.append(part.str("text").orEmpty())
                        }
                    }
                    item.arr("summary")?.forEach { partEl ->
                        val part = partEl.asJsonObject
                        if (part.str("type") == ResponsesPart.SUMMARY_TEXT) {
                            reasoning.append(part.str("text").orEmpty())
                        }
                    }
                    val encContent = item.str("encrypted_content")?.takeIf { it.isNotEmpty() }
                    if (encContent != null && thinkingBlocksSnapshot == null) {
                        val snapshot = JsonObject().apply {
                            addProperty("type", ResponsesItem.REASONING)
                            addProperty("encrypted_content", encContent)
                            item.arr("summary")?.let { add("summary", it) } ?: add("summary", JsonArray())
                        }
                        thinkingBlocksSnapshot = snapshot.toString()
                    }
                }
            }
        }
    }
    return ResponsesOutput(text.toString(), reasoning.toString(), toolCalls, images, thinkingBlocksSnapshot)
}

/** image_generation_call's `output_format` -> MIME; defaults to the official default, png. */
private fun mimeForImageFormat(format: String?): String = when (format?.lowercase()) {
    "jpeg" -> "image/jpeg"
    "webp" -> "image/webp"
    else -> "image/png"
}

internal fun parseResponsesUsage(usage: JsonObject?): ResponsesUsage {
    if (usage == null) return ResponsesUsage()
    return ResponsesUsage(
        inputTokens = usage.int("input_tokens"),
        outputTokens = usage.int("output_tokens"),
        cachedInputTokens = usage.obj("input_tokens_details")?.int("cached_tokens") ?: 0,
        cacheCreationTokens = usage.obj("input_tokens_details")?.int("cache_write_tokens") ?: 0
    )
}

/**
 * Responses `status` -> unified stopReason (reusing Chat Completions' values, so the upper layer can reuse the same judgement logic).
 * Output truncated by the token limit must map to `length`, otherwise [AIResponse.isTruncated] is false,
 * the Agent loop will not continue writing automatically, and the answer appears silently truncated. Truncation-reason values vary by provider:
 * OpenAI's official documentation example gives `max_tokens`, while DeepSeek / some compatible services give `max_output_tokens`.
 */
internal fun responsesStopReason(status: String?, incompleteReason: String?, hasToolCalls: Boolean): String? = when {
    status == "incomplete" && incompleteReason in TRUNCATION_REASONS -> "length"
    hasToolCalls -> "tool_calls"
    status == "completed" -> "stop"
    else -> status
}

private val TRUNCATION_REASONS = setOf("max_output_tokens", "max_tokens")

/** Incremental text produced by streaming events, for the upper layer to push to the UI. */
internal sealed class ResponsesDelta {
    data class Text(val text: String) : ResponsesDelta()
    data class Reasoning(val text: String) : ResponsesDelta()

    /** A function_call has just started being produced and the tool name is known (the arguments are still streaming), so the UI can say early "what is being called". */
    data class ToolCallDeclared(val name: String) : ResponsesDelta()
}

/**
 * Responses streaming semantic event accumulator.
 *
 * Events are grouped by `output_index`: `response.output_item.added` gives the function_call's call_id/name,
 * `response.function_call_arguments.delta` appends arguments piece by piece, and `response.output_item.done` /
 * `response.function_call_arguments.done` gives the complete arguments (used to overwrite, guarding against dropped delta frames).
 * Terminal events (completed / incomplete) carry the full response object, used to complete anything missing, take usage and status;
 * failed throws [StreamApiException] directly for the retry judgement.
 */
internal class ResponsesStreamAccumulator {
    var receivedContent: Boolean = false
        private set

    private class CallAcc {
        var callId = ""
        var name = ""
        var args = StringBuilder()
    }

    private val text = StringBuilder()
    private val reasoning = StringBuilder()
    private val budget = StreamBudget()
    private var thinkingBlocksSnapshotJson: String? = null
    private val calls = LinkedHashMap<String, CallAcc>()
    /** Images arriving in output_item.done during the stream, deduplicated by item id. */
    private val images = LinkedHashMap<String, AgentImage>()
    /** Images parsed as a fallback from the terminal event (the source for completing anything the stream delta events missed). */
    private val finalImages = mutableListOf<AgentImage>()
    /** Tool calls parsed as a fallback from the terminal event (the source for completing anything the stream delta events missed). */
    private val finalCalls = mutableListOf<ToolCall>()
    private var status: String? = null
    private var incompleteReason: String? = null
    private var usage = ResponsesUsage()

    /** Whether a terminal event has been received. Disconnecting without one means the stream was truncated, and the caller should handle it as an exception. */
    var terminated = false
        private set

    /** Degraded-delivery flag for disconnection: part of the body was received but no terminal event arrived; see [markNetworkTruncated]. */
    private var networkTruncated = false

    /** The length of accumulated body text, for logging and for the caller to judge whether degraded delivery is worthwhile on disconnection. */
    val textLength: Int get() = text.length

    /** Whether degraded delivery is worthwhile on disconnection: only if the body text is non-empty (degrading is pointless when only thinking/tool names were received). */
    fun hasSalvageableText(): Boolean = text.isNotBlank()

    /**
     * Degraded delivery on disconnection: part of the body was already received, but the connection broke first (peer EOF / proxy disconnecting early / network switch).
     *
     * Once set, [toResponse] marks this content as truncated output (stopReason=length, [AIResponse.isTruncated]
     * is true -> the Agent loop continues writing automatically), which is better than failing the whole turn and discarding all already streamed content.
     */
    fun markNetworkTruncated() {
        if (terminated) return
        networkTruncated = true
        terminated = true
    }

    /** Processes the event object parsed from one SSE data line, returning the incremental text this event brings (null if none). */
    fun accept(event: JsonObject): ResponsesDelta? {
        when (event.str("type")) {
            ResponsesEvent.OUTPUT_TEXT_DELTA, ResponsesEvent.REFUSAL_DELTA -> {
                val delta = event.str("delta").orEmpty()
                if (delta.isEmpty()) return null
                budget.add(delta)
                text.append(delta)
                return ResponsesDelta.Text(delta)
            }

            ResponsesEvent.REASONING_TEXT_DELTA, ResponsesEvent.REASONING_SUMMARY_TEXT_DELTA -> {
                val delta = event.str("delta").orEmpty()
                if (delta.isEmpty()) return null
                budget.add(delta)
                reasoning.append(delta)
                return ResponsesDelta.Reasoning(delta)
            }

            ResponsesEvent.OUTPUT_ITEM_ADDED, ResponsesEvent.OUTPUT_ITEM_DONE -> {
                val item = event.obj("item") ?: return null
                when (item.str("type")) {
                    ResponsesItem.FUNCTION_CALL -> {
                        val acc = calls.getOrPut(event.callKey()) { CallAcc() }
                        item.str("call_id")?.takeIf { it.isNotEmpty() }?.let { acc.callId = it }
                        item.str("arguments")?.takeIf { it.isNotEmpty() }?.let { budget.add(it); acc.args = StringBuilder(it) }
                        item.str("name")?.takeIf { it.isNotEmpty() }?.let { name ->
                            val first = acc.name.isEmpty()
                            acc.name = name
                            // The tool name arrives before the arguments: let the UI state it concretely early (pushed only once per call)
                            if (first) return ResponsesDelta.ToolCallDeclared(name)
                        }
                    }
                    ResponsesItem.REASONING -> {
                        val encContent = item.str("encrypted_content")?.takeIf { it.isNotEmpty() }
                        if (encContent != null) {
                            val snapshot = JsonObject().apply {
                                addProperty("type", ResponsesItem.REASONING)
                                addProperty("encrypted_content", encContent)
                                item.arr("summary")?.let { add("summary", it) } ?: add("summary", JsonArray())
                            }
                            thinkingBlocksSnapshotJson = snapshot.toString()
                        }
                    }
                    ResponsesItem.IMAGE_GENERATION_CALL -> {
                        item.str("result")?.takeIf { it.isNotEmpty() }?.let { result ->
                            val key = item.str("id") ?: event.callKey()
                            receivedContent = true
                            images[key] = AgentImage(mimeType = mimeForImageFormat(item.str("output_format")), base64Data = result)
                        }
                    }
                }
            }

            ResponsesEvent.FUNCTION_CALL_ARGS_DELTA -> {
                val delta = event.str("delta").orEmpty()
                if (delta.isEmpty()) return null
                budget.add(delta)
                calls.getOrPut(event.callKey()) { CallAcc() }.args.append(delta)
            }

            ResponsesEvent.FUNCTION_CALL_ARGS_DONE -> {
                val full = event.str("arguments").orEmpty()
                if (full.isEmpty()) return null
                budget.add(full)
                calls.getOrPut(event.callKey()) { CallAcc() }.args = StringBuilder(full)
            }

            ResponsesEvent.COMPLETED, ResponsesEvent.INCOMPLETE -> {
                // Set the flag first: terminal semantics must not be affected by a response-object parsing exception, otherwise the caller would keep reading to the end of the stream and falsely report a disconnection.
                terminated = true
                absorbFinalResponse(event.obj("response"))
            }

            ResponsesEvent.FAILED -> {
                terminated = true
                val response = event.obj("response")
                absorbFinalResponse(response)
                val error = response?.obj("error") ?: event.obj("error")
                throw StreamApiException(
                    code = error?.str("code"),
                    message = error?.str("message") ?: "Responses stream ended with response.failed"
                )
            }

            // Transport-layer error event: the code / message are on the event itself (not nested in an error object).
            ResponsesEvent.ERROR, ResponsesEvent.RESPONSE_ERROR -> throw StreamApiException(
                code = event.str("code"),
                message = event.str("message") ?: "Responses stream returned an error event"
            )
        }
        return null
    }

    fun toResponse(): AIResponse {
        val streamed = calls.values
            .filter { it.callId.isNotEmpty() || it.name.isNotEmpty() }
            // On disconnection the arguments are almost inevitably partial: executing a tool (writing a file/running a command) with incomplete arguments is far riskier than dropping this call.
            .filter { !networkTruncated || looksLikeCompleteJsonObject(it.args.toString()) }
            .map { ToolCall(id = it.callId, name = it.name, arguments = parseToolArguments(it.args.toString())) }
        val toolCalls = streamed + finalCalls.filter { call ->
            streamed.none { it.id == call.id && call.id.isNotEmpty() }
        }
        return AIResponse(
            content = text.toString(),
            toolCalls = toolCalls,
            stopReason = if (networkTruncated) STREAM_TRUNCATED_STOP_REASON else responsesStopReason(status, incompleteReason, toolCalls.isNotEmpty()),
            reasoning = reasoning.toString().takeIf { it.isNotEmpty() },
            thinkingBlocksJson = thinkingBlocksSnapshotJson,
            inputTokens = usage.inputTokens,
            outputTokens = usage.outputTokens,
            cachedInputTokens = usage.cachedInputTokens,
            cacheCreationTokens = usage.cacheCreationTokens,
            images = images.values.filter { it.base64Data.isNotEmpty() }.ifEmpty { finalImages }
        )
    }

    /** A terminal event carries the full response: take status/usage, and use the top-level output to complete anything the stream missed. */
    private fun absorbFinalResponse(response: JsonObject?) {
        if (response == null) return
        status = response.str("status")
        incompleteReason = response.obj("incomplete_details")?.str("reason")
        usage = parseResponsesUsage(response.obj("usage"))

        val parsed = parseResponsesOutput(response.arr("output"))
        if (text.isEmpty()) { budget.add(parsed.text); text.append(parsed.text) }
        if (reasoning.isEmpty()) { budget.add(parsed.reasoning); reasoning.append(parsed.reasoning) }
        if (thinkingBlocksSnapshotJson == null) thinkingBlocksSnapshotJson = parsed.thinkingBlocksJson
        finalCalls.clear()
        finalCalls.addAll(parsed.toolCalls)
        finalImages.clear()
        finalImages.addAll(parsed.images)
    }

    private fun JsonObject.callKey(): String =
        str("output_index") ?: str("item_id") ?: "0"
}

private fun JsonObject.str(name: String): String? =
    get(name)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString

private fun JsonObject.int(name: String): Int =
    get(name)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asInt ?: 0

// The next two replace Gson's own getAsJsonArray / getAsJsonObject: they cast directly, and hitting null
// (e.g. a reasoning item's `content: null`, or a missing `output` in a failed event) throws ClassCastException.
private fun JsonObject.arr(name: String): JsonArray? =
    get(name)?.takeIf { it.isJsonArray }?.asJsonArray

private fun JsonObject.obj(name: String): JsonObject? =
    get(name)?.takeIf { it.isJsonObject }?.asJsonObject
