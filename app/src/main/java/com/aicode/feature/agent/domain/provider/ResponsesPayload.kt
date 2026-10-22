package com.aicode.feature.agent.domain.provider

import com.aicode.feature.agent.data.remote.openai.ResponsesItem
import com.aicode.feature.agent.data.remote.openai.ResponsesPart
import com.aicode.feature.agent.data.remote.openai.ResponsesToolDefinition
import com.aicode.feature.agent.domain.model.AgentImage
import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.modelToolResultText
import com.google.gson.JsonParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Parses the tool-argument JSON string given by the model into a JsonObject; falls back to an empty object when empty or invalid. */
internal fun parseToolArguments(raw: String): JsonObject {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return JsonObject(emptyMap())
    return runCatching { Json.parseToJsonElement(trimmed).jsonObject }.getOrElse { JsonObject(emptyMap()) }
}

/** Tool definitions -> Responses flat function definitions; returns null when there are no tools (the `tools` field is not sent). */
internal fun buildResponsesTools(tools: List<AgentTool>): List<ResponsesToolDefinition>? =
    tools.takeIf { it.isNotEmpty() }?.map { tool ->
        ResponsesToolDefinition(
            name = tool.name,
            description = tool.description,
            parameters = tool.toJsonSchema()
        )
    }

/**
 * Conversation history -> Responses' `input` item sequence.
 *
 * Unlike Chat Completions' three role message kinds, Responses splits the tool round-trip into independent items:
 * the model's call is a `function_call` (identified by `call_id`), and the execution result is a `function_call_output`,
 * and neither is attached any longer to an assistant message and a `role:"tool"` message.
 *
 * The pairing constraints are the same as for Chat Completions and likewise must be guaranteed on the client: a `function_call_output` must
 * find a `function_call` with the same `call_id`, and a declared `function_call` must also have a result. Here results are attached call by call,
 * incidentally handling two kinds of dirty historical data:
 * - Results in a displaced order (e.g. other tool results cut in while askUserQuestion is blocked) -> attached back after the call;
 * - Orphan results (the preceding call was trimmed away by context compression) -> dropped;
 * - Calls with no result (e.g. the user refused execution) -> dropped together with the call.
 *
 * **Multiple `function_call`s from the same turn must all be written consecutively first, then their `function_call_output`s written together**; calls and results must not
 * alternate: the server merges `function_call`s into the adjacent assistant message, and once a
 * `function_call_output` (equivalent to a tool message) is interleaved, subsequent calls are treated as a new assistant turn. For DeepSeek
 * thinking mode, those extra invented turns have no reasoning item, so it reports
 * 400 The `reasoning_text` in the thinking mode must be passed back to the API.
 * Writing them together is also equivalent to Chat Completions' "one assistant carrying N tool_calls + N tool messages".
 *
 * Thinking content is sent back as an independent `reasoning` item only when [includeReasoningItems] is true, immediately before the assistant content it belongs to
 * (in the same item order as the model output; the server merges it into the adjacent assistant message); when thinking is empty,
 * an empty-text placeholder is sent. The switch exists because the two providers demand opposite things:
 * - In DeepSeek thinking mode, as long as the request carries `tools`, every historical assistant turn must send its thinking content back in full
 *   (even if that turn did not actually call a tool), otherwise a 400 results; it only accepts plaintext `content`, not summary / encrypted_content.
 * - OpenAI's official reasoning item must carry `id`/`summary` and `encrypted_content`,
 *   and is only sent back when valid ciphertext is stored; if missing it is skipped outright, to prevent plaintext-only content triggering a server 400 rejection.
 */
internal fun buildResponsesInput(
    systemPrompt: String,
    systemRole: String,
    messages: List<AgentMessage>,
    includeReasoningItems: Boolean = false
): List<Map<String, Any?>> {
    val items = mutableListOf<Map<String, Any?>>()
    if (systemPrompt.isNotBlank()) {
        items.add(mapOf("role" to systemRole, "content" to systemPrompt))
    }

    val consumed = BooleanArray(messages.size)
    for (i in messages.indices) {
        if (consumed[i]) continue
        when (val message = messages[i]) {
            is AgentMessage.UserMessage -> items.add(
                mapOf("role" to "user", "content" to message.toResponsesContent())
            )

            is AgentMessage.AssistantMessage -> {
                // Pair up the calls that have results first: calls with no result are dropped (e.g. the user refused execution); if the whole turn
                // is dropped and there is no body text, this turn writes no items, and the reasoning has nowhere to attach.
                val paired = message.toolCalls.mapNotNull { call ->
                    val resultIndex = (i + 1 until messages.size).firstOrNull { j ->
                        !consumed[j] && (messages[j] as? AgentMessage.ToolResultMessage)?.id == call.id
                    } ?: return@mapNotNull null
                    consumed[resultIndex] = true
                    call to (messages[resultIndex] as AgentMessage.ToolResultMessage)
                }
                // Services in thinking mode require the previous turn's thinking content to be sent back as-is; missing it reports a 400:
                // The `reasoning_text` in the thinking mode must be passed back to the API.
                val hasBody = message.content.isNotBlank() || paired.isNotEmpty()
                val encryptedReasoning = decodeResponsesEncryptedReasoning(message.thinkingBlocksJson)
                if (hasBody) {
                    if (encryptedReasoning != null) {
                        // Prefer sending back the official OpenAI / general Responses encrypted snapshot (including summary and encrypted_content)
                        items.add(encryptedReasoning)
                    } else if (includeReasoningItems) {
                        // DeepSeek thinking mode: send back the plaintext reasoning_text
                        items.add(
                            mapOf(
                                "type" to ResponsesItem.REASONING,
                                "content" to listOf(
                                    mapOf(
                                        "type" to ResponsesPart.REASONING_TEXT,
                                        "text" to message.reasoning
                                    )
                                )
                            )
                        )
                    }
                }
                if (message.content.isNotBlank()) {
                    items.add(
                        mapOf(
                            "role" to "assistant",
                            "content" to listOf(
                                mapOf("type" to ResponsesPart.OUTPUT_TEXT, "text" to message.content)
                            )
                        )
                    )
                }
                for ((call, _) in paired) {
                    items.add(
                        mapOf(
                            "type" to ResponsesItem.FUNCTION_CALL,
                            "call_id" to call.id,
                            "name" to call.name,
                            "arguments" to JsonObject(call.arguments).toString()
                        )
                    )
                }
                for ((call, result) in paired) {
                    items.add(
                        mapOf(
                            "type" to ResponsesItem.FUNCTION_CALL_OUTPUT,
                            "call_id" to call.id,
                            "output" to result.toResponsesOutput()
                        )
                    )
                }
            }

            is AgentMessage.ToolResultMessage -> consumed[i] = true
        }
    }
    return items
}

/**
 * Deserialises the official OpenAI Responses encrypted reasoning item from snapshot JSON.
 * Strictly follows the OpenCode / OpenAI defensive rules: it is only allowed to be sent back when it contains a non-empty string `encrypted_content`;
 * otherwise return null, and never send an incomplete reasoning item that would make the server throw a 400 error.
 */
internal fun decodeResponsesEncryptedReasoning(thinkingBlocksJson: String?): Map<String, Any?>? {
    if (thinkingBlocksJson.isNullOrBlank()) return null
    return runCatching {
        val obj = JsonParser.parseString(thinkingBlocksJson).asJsonObject
        if (obj.get("type")?.asString != ResponsesItem.REASONING) return null
        val encryptedContent = obj.get("encrypted_content")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString
        if (encryptedContent.isNullOrBlank()) return null
        val summaryArr = obj.get("summary")?.takeIf { it.isJsonArray }?.asJsonArray
        val summaryList = summaryArr?.mapNotNull { el ->
            if (el.isJsonObject) {
                val o = el.asJsonObject
                val map = mutableMapOf<String, Any>()
                o.get("type")?.takeIf { !it.isJsonNull }?.asString?.let { map["type"] = it }
                o.get("text")?.takeIf { !it.isJsonNull }?.asString?.let { map["text"] = it }
                map.takeIf { it.isNotEmpty() }
            } else null
        } ?: emptyList()
        mapOf(
            "type" to ResponsesItem.REASONING,
            "summary" to summaryList,
            "encrypted_content" to encryptedContent
        )
    }.getOrNull()
}

/** User message content: plain text is given directly as a string; with images it is split into an input_text / input_image part list. */
private fun AgentMessage.UserMessage.toResponsesContent(): Any {
    if (images.isEmpty()) return content
    val parts = mutableListOf<Map<String, Any>>()
    if (content.isNotBlank()) {
        parts.add(mapOf("type" to ResponsesPart.INPUT_TEXT, "text" to content))
    }
    images.forEach { parts.add(it.toResponsesImagePart()) }
    return parts
}

/** Tool result: plain text is given directly as a string; with images a part list is used (Responses allows images inside function_call_output). */
private fun AgentMessage.ToolResultMessage.toResponsesOutput(): Any {
    // File tools feed the model a compact projection text; the UI/persistence still use the full result.
    val modelText = modelResult
        ?: modelToolResultText(toolName, result)
        ?: result
    if (images.isEmpty()) return modelText
    val parts = mutableListOf<Map<String, Any>>()
    if (modelText.isNotBlank()) {
        parts.add(mapOf("type" to ResponsesPart.INPUT_TEXT, "text" to modelText))
    }
    images.forEach { parts.add(it.toResponsesImagePart()) }
    return parts
}

private fun AgentImage.toResponsesImagePart(): Map<String, Any> = mapOf(
    "type" to ResponsesPart.INPUT_IMAGE,
    "image_url" to "data:$mimeType;base64,$base64Data",
    "detail" to "auto"
)
