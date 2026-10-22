package com.aicode.feature.agent.domain.provider

import com.aicode.feature.agent.data.remote.gemini.InteractionContent
import com.aicode.feature.agent.data.remote.gemini.InteractionStep
import com.aicode.feature.agent.domain.model.AgentImage
import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.ToolCall
import com.aicode.feature.agent.domain.tool.modelToolResultText
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Tool definitions -> Interactions' flat function declarations; returns null when there are no tools (the `tools` field is not sent).
 *
 * Unlike generateContent: no longer wrapped in the `[{functionDeclarations: [...]}]` layer; each tool is simply
 * an object with `type: "function"` in the `tools` array.
 */
internal fun buildInteractionsTools(tools: List<AgentTool>): List<Map<String, Any>>? =
    tools.takeIf { it.isNotEmpty() }?.map { tool ->
        mapOf(
            "type" to "function",
            "name" to tool.name,
            "description" to tool.description,
            "parameters" to tool.toJsonSchema()
        )
    }

/**
 * Conversation history -> Interactions' `input` step sequence (stateless mode, the request carries `store=false`).
 *
 * Unlike generateContent's `contents` (two roles, user/model, plus parts): this is a flat
 * timeline, where model output, thinking, tool calls and tool results are each independent steps.
 *
 * The pairing constraints are the same as for Responses and likewise must be guaranteed on the client: a `function_result` must find a `function_call` with the same `call_id`,
 * and a declared `function_call` must also have a result. Here results are attached call by call, incidentally
 * handling two kinds of dirty historical data:
 * - Results in a displaced order (e.g. other tool results cut in while askUserQuestion is blocked) -> attached back after the call;
 * - Orphan results (the preceding call was trimmed away by context compression) -> dropped;
 * - Calls with no result (e.g. the user refused execution) -> dropped together with the call.
 *
 * **Multiple `function_call`s from the same turn are written consecutively first, then their `function_result`s are written together**, matching the official stateless
 * example (append the whole batch of model steps first, then append the results).
 */
internal fun buildInteractionsInput(messages: List<AgentMessage>): List<Any> {
    val steps = mutableListOf<Any>()
    val consumed = BooleanArray(messages.size)
    for (i in messages.indices) {
        if (consumed[i]) continue
        when (val message = messages[i]) {
            is AgentMessage.UserMessage -> steps.add(
                mapOf(
                    "type" to InteractionStep.USER_INPUT,
                    "content" to message.toInteractionContent()
                )
            )

            is AgentMessage.AssistantMessage -> {
                val paired = message.toolCalls.mapNotNull { call ->
                    val resultIndex = (i + 1 until messages.size).firstOrNull { j ->
                        !consumed[j] && (messages[j] as? AgentMessage.ToolResultMessage)?.id == call.id
                    } ?: return@mapNotNull null
                    consumed[resultIndex] = true
                    call to (messages[resultIndex] as AgentMessage.ToolResultMessage)
                }
                steps.addAll(message.toModelSteps(paired.map { it.first }))
                for ((call, result) in paired) {
                    steps.add(
                        mapOf(
                            "type" to InteractionStep.FUNCTION_RESULT,
                            "call_id" to call.id,
                            "name" to call.name,
                            "result" to result.toInteractionResult()
                        )
                    )
                }
            }

            is AgentMessage.ToolResultMessage -> consumed[i] = true
        }
    }
    return steps
}

/**
 * One assistant turn's output -> model step sequence.
 *
 * The official requirement is to send model-produced steps back **as-is** in stateless mode (a `thought`'s signature and function_calls
 * cannot be rebuilt), so the persisted snapshot is preferred. But the premise of verbatim replay is that every call of this turn got a result: once a call is
 * dropped (e.g. the user refused execution), the snapshot's signature no longer matches the actually sent call sequence, and it falls back to rebuilding from the body text.
 */
private fun AgentMessage.AssistantMessage.toModelSteps(keptCalls: List<ToolCall>): List<Any> {
    // Turns with images do not use the snapshot: a snapshot would carry large base64 (a 4K image is about 10MB) and blow up the database row, and a snapshot contains no image replay,
    // which would break multi-turn editing. Prefer rebuilding (such turns almost never involve tools, so losing the thought signature is acceptable),
    // and merge the image blocks into model_output to send them back in full.
    decodeInteractionSteps(thinkingBlocksJson)
        ?.takeIf { keptCalls.size == toolCalls.size && images.isEmpty() }
        ?.let { return it }

    val steps = mutableListOf<Any>()
    val contentBlocks = buildList {
        if (content.isNotBlank()) add(textContent(content))
        images.forEach { add(it.toImageContent()) }
    }
    if (contentBlocks.isNotEmpty()) {
        steps.add(
            mapOf(
                "type" to InteractionStep.MODEL_OUTPUT,
                "content" to contentBlocks
            )
        )
    }
    keptCalls.forEach { call ->
        steps.add(
            mapOf(
                "type" to InteractionStep.FUNCTION_CALL,
                "id" to call.id,
                "name" to call.name,
                "arguments" to call.argumentsAsJson()
            )
        )
    }
    return steps
}

/** User message content: body text and images each become a content block. */
private fun AgentMessage.UserMessage.toInteractionContent(): List<Map<String, Any>> {
    val blocks = mutableListOf<Map<String, Any>>()
    if (content.isNotBlank()) blocks.add(textContent(content))
    images.forEach { blocks.add(it.toImageContent()) }
    // An empty array is rejected by the server; degrade to a single (possibly empty) text block.
    return blocks.ifEmpty { listOf(textContent(content)) }
}

/**
 * Tool result: sent back as a `{"result": text}` object.
 *
 * Tool-produced images are not sent back: a generated image has already been shown to the user and persisted, and putting it back into the context would only inflate tokens,
 * and some relay gateways cannot handle an array-typed result when converting interactions to generateContent
 * (they stuff the content array directly into function_response.response and report `cannot start list`).
 * The official Interactions result accepts an object/string/Content array; an object is the most stable.
 */
private fun AgentMessage.ToolResultMessage.toInteractionResult(): Any {
    // File tools feed the model a compact projection text; the UI/persistence still use the full result.
    val modelText = modelResult
        ?: modelToolResultText(toolName, result)
        ?: result
    return if (modelText.isBlank()) {
        listOf(textContent(modelText))
    } else {
        mapOf("result" to modelText)
    }
}

/**
 * Verbatim snapshot of model-produced steps (JSON array text), stored in [AgentMessage.AssistantMessage.thinkingBlocksJson],
 * and sent back as-is in the next turn.
 *
 * Generated only when non-rebuildable content exists (a `thought`'s signature / a `function_call`); a pure-text turn returns null
 * to let history be rebuilt conventionally, avoiding body text being written twice. `user_input` / `function_result` are echoes of input we sent ourselves,
 * and are filtered out here — keeping them would duplicate them in the next turn.
 */
internal fun snapshotInteractionSteps(steps: List<JsonObject>): String? {
    val generated = steps.filter { it.stepType() !in ECHOED_STEP_TYPES }
    val needsSnapshot = generated.any {
        it.stepType() == InteractionStep.THOUGHT || it.stepType() == InteractionStep.FUNCTION_CALL
    }
    if (!needsSnapshot) return null
    return JsonArray().apply { generated.forEach { add(it) } }.toString()
}

internal fun snapshotInteractionSteps(steps: JsonArray?): String? {
    if (steps == null) return null
    return snapshotInteractionSteps(steps.mapNotNull { it.takeIf { el -> el.isJsonObject }?.asJsonObject })
}

/** Snapshot -> step list; returns null when empty or corrupted, and the caller falls back to the rebuild logic. */
internal fun decodeInteractionSteps(json: String): List<JsonObject>? {
    if (json.isBlank()) return null
    return runCatching {
        JsonParser.parseString(json).asJsonArray
            .mapNotNull { it.takeIf { el -> el.isJsonObject }?.asJsonObject }
            .filter { it.stepType() !in ECHOED_STEP_TYPES }
            .takeIf { it.isNotEmpty() }
    }.getOrNull()
}

/**
 * Tool arguments -> Gson tree. Handing kotlinx's JsonObject directly to Gson would serialise its internal fields by reflection
 * (producing junk like `{"body":...,"isString":true}`), so it must first be converted to text and then parsed back by Gson.
 */
private fun ToolCall.argumentsAsJson(): JsonElement =
    runCatching {
        JsonParser.parseString(kotlinx.serialization.json.JsonObject(arguments).toString())
    }.getOrElse { JsonObject() }

/** Step types constructed by us in the conversation history that should not appear in a model-output snapshot. */
private val ECHOED_STEP_TYPES = setOf(InteractionStep.USER_INPUT, InteractionStep.FUNCTION_RESULT)

private fun textContent(text: String): Map<String, Any> =
    mapOf("type" to InteractionContent.TEXT, "text" to text)

private fun AgentImage.toImageContent(): Map<String, Any> = mapOf(
    "type" to InteractionContent.IMAGE,
    "mime_type" to mimeType,
    "data" to base64Data
)

private fun JsonObject.stepType(): String? =
    get("type")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString
