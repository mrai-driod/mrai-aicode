package com.aicode.feature.agent.domain.provider

import com.aicode.feature.agent.data.remote.gemini.InteractionContent
import com.aicode.feature.agent.data.remote.gemini.InteractionDelta
import com.aicode.feature.agent.data.remote.gemini.InteractionEvent
import com.aicode.feature.agent.data.remote.gemini.InteractionStep
import com.aicode.feature.agent.data.remote.gemini.TERMINAL_STATUSES
import com.aicode.feature.agent.domain.model.AgentImage
import com.aicode.feature.agent.domain.tool.ToolCall
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/** Parse result of an Interactions `steps` array. */
internal data class InteractionsOutput(
    val text: String = "",
    val reasoning: String = "",
    val toolCalls: List<ToolCall> = emptyList(),
    /** Images produced by the model (Nano Banana image model), each element carrying base64 data; always empty for non-image models. */
    val images: List<AgentImage> = emptyList(),
    /** Verbatim snapshot of model-produced steps, sent back as-is on the next stateless replay; null when no snapshot is needed. */
    val stepsSnapshotJson: String? = null
)

/** Interactions token usage. */
internal data class InteractionsUsage(
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val cachedInputTokens: Int = 0
)

/**
 * Compared with generateContent, the usage fields are all renamed, and `total_output_tokens` **does not include** thinking tokens
 * (in the official example, `total_tokens = input + output + thought`). Thinking is billed at the output price, so the two added together
 * are the real output amount.
 */
internal fun parseInteractionsUsage(usage: JsonObject?): InteractionsUsage {
    if (usage == null) return InteractionsUsage()
    return InteractionsUsage(
        inputTokens = usage.int("total_input_tokens"),
        outputTokens = usage.int("total_output_tokens") + usage.int("total_thought_tokens"),
        cachedInputTokens = usage.int("total_cached_tokens")
    )
}

/**
 * Parses an Interactions `steps` array: shared by non-streaming responses and as a fallback for streaming terminal events.
 *
 * The key difference from generateContent: body text, thinking and tool calls are no longer siblings in the same `parts` array,
 * but independent steps on a timeline — a tool call is a `function_call` step (carrying its own `id` / `name` /
 * `arguments` object), so looking for it inside content the old way would never parse it.
 */
internal fun parseInteractionSteps(steps: JsonArray?): InteractionsOutput {
    if (steps == null) return InteractionsOutput()
    val text = StringBuilder()
    val reasoning = StringBuilder()
    val toolCalls = mutableListOf<ToolCall>()
    val images = mutableListOf<AgentImage>()
    steps.forEach { element ->
        // A single step failing to parse should not discard the whole response
        runCatching {
            val step = element.asJsonObject
            when (step.str("type")) {
                InteractionStep.MODEL_OUTPUT -> step.arr("content")?.forEach { block ->
                    val content = block.asJsonObject
                    when (content.str("type")) {
                        InteractionContent.TEXT -> text.append(content.str("text").orEmpty())
                        InteractionContent.IMAGE -> content.toAgentImage()?.let { images.add(it) }
                    }
                }

                InteractionStep.THOUGHT -> step.arr("summary")?.forEach { block ->
                    val content = block.asJsonObject
                    if (content.str("type") == InteractionContent.TEXT) {
                        reasoning.append(content.str("text").orEmpty())
                    }
                }

                InteractionStep.FUNCTION_CALL -> {
                    val name = step.str("name").orEmpty()
                    toolCalls.add(
                        ToolCall(
                            // Parallel calls are paired by id; only fall back to the function name when missing (calling the same-named tool twice would collide on id).
                            id = step.str("id")?.takeIf { it.isNotBlank() } ?: name,
                            name = name,
                            arguments = parseToolArguments(step.obj("arguments")?.toString().orEmpty())
                        )
                    )
                }
            }
        }
    }
    return InteractionsOutput(
        text = text.toString(),
        reasoning = reasoning.toString(),
        toolCalls = toolCalls,
        images = images,
        stepsSnapshotJson = snapshotInteractionSteps(steps)
    )
}

/**
 * Interactions `status` -> unified stopReason.
 *
 * `incomplete` (hit `max_output_tokens`) must map to `length`: after aligning with Chat Completions' values,
 * [AIResponse.isTruncated] holds and the Agent loop continues writing automatically; otherwise the answer appears silently truncated.
 * Conversely, `incomplete` must not be put directly into [AIResponse.TRUNCATION_STOP_REASONS] — the Responses API's
 * same-named status also covers non-truncation reasons such as content_filter, and sharing one word would misjudge those as truncation too.
 *
 * Other statuses are passed through as-is: `requires_action` means there are tools awaiting execution, and `failed` / `budget_exceeded` hit
 * [AIResponse.ABORT_STOP_REASONS], explained to the user by the upper layer rather than completing silently.
 */
internal fun interactionStopReason(status: String?): String? =
    if (status == "incomplete") "length" else status

/** Incremental text produced by streaming events, for the upper layer to push to the UI. */
internal sealed class InteractionsDelta {
    data class Text(val text: String) : InteractionsDelta()
    data class Reasoning(val text: String) : InteractionsDelta()

    /** A function_call has just started being produced and the tool name is known (the arguments are still streaming), so the UI can say early "what is being called". */
    data class ToolCallDeclared(val name: String) : InteractionsDelta()
}

/**
 * Interactions streaming event accumulator.
 *
 * The event discriminator field is **`event_type`** (the migration guide's example writes `type`, contradicting the reference docs; both are accepted here).
 * Step events are grouped by `index`: `step.start` gives the step type and the function_call's id/name,
 * `step.delta` delivers body text / thinking summary / thinking signature / tool arguments piece by piece, and `step.stop` closes it off.
 *
 * The stream **does not send `[DONE]`**, and **when there are tools awaiting execution the server stops at `requires_action` and sends no further `completed`**,
 * so the end is judged by whether the interaction's status has reached a terminal state ([TERMINAL_STATUSES]); only waiting for completed
 * would read all the way to the end of the stream and be misjudged as a disconnection.
 */
internal class GeminiInteractionsStreamAccumulator {
    var receivedContent: Boolean = false
        private set

    private class StepAcc {
        var type: String? = null
        var id = ""
        var name = ""
        var signature: String? = null
        val text = StringBuilder()
        val summary = StringBuilder()

        /** Argument fragments accumulated character by character from `arguments_delta`. */
        val argsDelta = StringBuilder()

        /** The complete arguments given directly by `step.start` (some implementations do not go through delta). */
        var argsComplete: String? = null

        /** The raw step object from `step.start`: steps we do not parse, such as server tools, are kept as-is for replay. */
        var raw: JsonObject? = null

        /** When step.start is missing, infer the type from the deltas received so far. */
        fun effectiveType(): String? = type ?: when {
            text.isNotEmpty() -> InteractionStep.MODEL_OUTPUT
            summary.isNotEmpty() || signature != null -> InteractionStep.THOUGHT
            name.isNotEmpty() -> InteractionStep.FUNCTION_CALL
            else -> null
        }

        /** Prefer the delta-accumulated value; only fall back to the complete arguments given by step.start when it is missing or assembled incorrectly. */
        fun arguments(): kotlinx.serialization.json.JsonObject =
            parseArgsOrNull(argsDelta.toString())
                ?: parseArgsOrNull(argsComplete.orEmpty())
                ?: kotlinx.serialization.json.JsonObject(emptyMap())
    }

    private val steps = LinkedHashMap<Int, StepAcc>()
    private val budget = StreamBudget()
    private var status: String? = null
    private var statusDetail: String? = null
    private var usage = InteractionsUsage()

    /** Output parsed as a fallback from the terminal event (the source for completing anything the stream deltas missed). */
    private var finalOutput: InteractionsOutput? = null

    /** Whether a terminal state has been read. Disconnecting before a terminal state means the stream was truncated, and the caller should handle it as an exception. */
    var terminated = false
        private set

    /**
     * The stream was cut before a terminal state (peer EOF / proxy closing the connection early / network switch), and body text already exists for degraded delivery.
     * Mutually exclusive with [terminated]: once a terminal state was reached it is not set.
     */
    var truncatedByNetwork = false
        private set

    /** Set by the caller after a disconnection; ignored if a terminal state was already reached (a normal end, which should not be marked truncated). */
    fun markTruncatedByNetwork() {
        if (!terminated) truncatedByNetwork = true
    }

    /** The length of accumulated body text, for the caller to judge whether degraded delivery is worthwhile on disconnection. */
    val streamedTextLength: Int
        get() = steps.values.sumOf { acc ->
            if (acc.effectiveType() == InteractionStep.MODEL_OUTPUT) acc.text.length else 0
        }

    /** Processes the event object parsed from one SSE data line, returning the incremental text this event brings (null if none). */
    fun accept(event: JsonObject): InteractionsDelta? {
        // Usage may be attached to the metadata of any event; absorb it unconditionally once first.
        event.obj("metadata")?.obj("total_usage")?.let { usage = parseInteractionsUsage(it) }

        val eventType = event.str("event_type") ?: event.str("type")
        when {
            eventType == InteractionEvent.STEP_START -> {
                val step = event.obj("step") ?: return null
                val acc = steps.getOrPut(event.int("index")) { StepAcc() }
                acc.type = step.str("type")
                acc.raw = step
                step.str("id")?.takeIf { it.isNotBlank() }?.let { acc.id = it }
                // For a function-call step the tool name arrives before the arguments: let the UI switch from "thinking" to a concrete scenario early.
                // The name is only recorded, not returned — step.start may also carry complete arguments / a body-text fallback,
                // and returning early here would drop them; this branch finishes and then returns uniformly.
                var declaredName: String? = null
                step.str("name")?.takeIf { it.isNotBlank() }?.let { name ->
                    if (acc.name.isBlank()) declaredName = name
                    acc.name = name
                }
                step.str("signature")?.let { acc.signature = it }
                step.obj("arguments")?.let { budget.add(it.toString()); acc.argsComplete = it.toString() }
                // Fallback: some implementations carry the full body text directly in step.start's content (with no delta afterwards),
                // in which case the text delta gets no body text. Extract it only if nothing has been accumulated yet, to avoid writing it twice with delta.
                if (acc.text.isEmpty()) {
                    step.arr("content")?.forEach { block ->
                        val content = block.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
                        if (content.str("type") == InteractionContent.TEXT) {
                            val text = content.str("text").orEmpty()
                            budget.add(text)
                            acc.text.append(text)
                            if (text.isNotEmpty()) receivedContent = true
                        }
                    }
                }
                step.arr("content")?.forEach { block ->
                    val content = block.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
                    if (content.str("type") == InteractionContent.IMAGE && content.toAgentImage() != null) {
                        receivedContent = true
                    }
                }
                declaredName?.let { return InteractionsDelta.ToolCallDeclared(it) }
            }

            eventType == InteractionEvent.STEP_DELTA -> {
                val delta = event.obj("delta") ?: return null
                val acc = steps.getOrPut(event.int("index")) { StepAcc() }
                when (delta.str("type")) {
                    InteractionDelta.TEXT -> {
                        val text = delta.str("text").orEmpty()
                        if (text.isEmpty()) return null
                        budget.add(text)
                        acc.text.append(text)
                        return InteractionsDelta.Text(text)
                    }

                    // The thinking summary text is in `content.text` (a Content object); some implementations put it directly in `text`.
                    InteractionDelta.THOUGHT_SUMMARY, InteractionDelta.THOUGHT -> {
                        val text = delta.obj("content")?.str("text") ?: delta.str("text").orEmpty()
                        if (text.isEmpty()) return null
                        budget.add(text)
                        acc.summary.append(text)
                        return InteractionsDelta.Reasoning(text)
                    }

                    InteractionDelta.THOUGHT_SIGNATURE ->
                        delta.str("signature")?.let { acc.signature = it }

                    InteractionDelta.ARGUMENTS, InteractionDelta.ARGUMENTS_LEGACY -> {
                        val args = delta.str("arguments") ?: delta.str("partial_arguments").orEmpty()
                        budget.add(args)
                        acc.argsDelta.append(args)
                    }
                }
            }

            eventType == InteractionEvent.STEP_STOP ->
                event.obj("usage")?.let { usage = parseInteractionsUsage(it) }

            // Transport-layer / platform error: set the termination flag first, to stop the caller continuing to read to the end of the stream and falsely reporting a disconnection.
            eventType == InteractionEvent.ERROR -> {
                terminated = true
                val error = event.obj("error")
                throw StreamApiException(
                    code = error?.str("code"),
                    message = error?.str("message") ?: "Interactions stream returned an error event"
                )
            }

            eventType != null && eventType.startsWith(InteractionEvent.LIFECYCLE_PREFIX) ->
                absorbLifecycle(eventType, event)
        }
        return null
    }

    /**
     * Lifecycle events: the status may be in `interaction.status` (created / completed) or at the top level of the event
     * (`interaction.status_update`); forms that write the status into the event name, such as `interaction.requires_action`,
     * must also be recognised, otherwise a tool turn would never wait for a terminal state.
     *
     * The event name is only a fallback — the payload of `interaction.completed` may say `incomplete` / `failed`
     * (completed but with an incomplete result); overwriting with the event name would wipe both truncation and failure into a normal completion.
     */
    private fun absorbLifecycle(eventType: String, event: JsonObject) {
        val interaction = event.obj("interaction")
        val payloadStatus = interaction?.str("status") ?: event.str("status")
        val nameStatus = eventType.removePrefix(InteractionEvent.LIFECYCLE_PREFIX)
            .takeIf { it in TERMINAL_STATUSES }
        (payloadStatus ?: nameStatus)?.let { status = it }
        interaction?.obj("usage")?.let { usage = parseInteractionsUsage(it) }
        interaction?.arr("steps")?.takeIf { it.size() > 0 }?.let { finalOutput = parseInteractionSteps(it) }
        // Platform error details: when status=failed the body is usually empty, and only here is there a displayable reason.
        interaction?.arr("errors")?.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
            ?.str("message")?.let { statusDetail = it }
        if (status in TERMINAL_STATUSES) terminated = true
    }

    fun toResponse(): AIResponse {
        val streamed = buildStreamedOutput()
        val toolCalls = streamed.toolCalls.ifEmpty { finalOutput?.toolCalls.orEmpty() }
        // In streaming, images arrive as a whole block (including step.start's complete payload) and produce no streaming delta; the terminal event also has fallback parsing.
        val images = streamed.images.ifEmpty { finalOutput?.images.orEmpty() }
        // Degraded delivery after disconnection: mark the stopReason as length, so the Agent loop continues writing automatically instead of failing the whole turn.
        val truncated = truncatedByNetwork && status !in TERMINAL_STATUSES
        return AIResponse(
            content = streamed.text.ifEmpty { finalOutput?.text.orEmpty() },
            toolCalls = toolCalls,
            stopReason = if (truncated) STREAM_TRUNCATED_STOP_REASON else interactionStopReason(status),
            stopDetail = statusDetail,
            reasoning = streamed.reasoning.ifEmpty { finalOutput?.reasoning.orEmpty() }.takeIf { it.isNotEmpty() },
            thinkingBlocksJson = streamed.stepsSnapshotJson ?: finalOutput?.stepsSnapshotJson,
            inputTokens = usage.inputTokens,
            outputTokens = usage.outputTokens,
            cachedInputTokens = usage.cachedInputTokens,
            images = images
        )
    }

    /**
     * Restores the steps accumulated by index into output plus a replayable step snapshot.
     * The snapshot must include `thought`'s signature and the complete `function_call`: in stateless mode, sending these back as-is
     * is required to continue the reasoning context ([snapshotInteractionSteps] strips pure-text turns that need no snapshot).
     */
    private fun buildStreamedOutput(): InteractionsOutput {
        val text = StringBuilder()
        val reasoning = StringBuilder()
        val toolCalls = mutableListOf<ToolCall>()
        val images = mutableListOf<AgentImage>()
        val rebuilt = mutableListOf<JsonObject>()
        steps.toSortedMap().values.forEach { acc ->
            when (acc.effectiveType()) {
                InteractionStep.MODEL_OUTPUT -> {
                    text.append(acc.text)
                    // step.start's complete payload may directly carry image content (images produce no streaming delta and arrive as a whole block).
                    acc.raw?.arr("content")?.forEach { block ->
                        val content = block.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
                        if (content.str("type") == InteractionContent.IMAGE) content.toAgentImage()?.let { images.add(it) }
                    }
                    rebuilt.add(modelOutputStep(acc.text.toString()))
                }

                InteractionStep.THOUGHT -> {
                    reasoning.append(acc.summary)
                    rebuilt.add(thoughtStep(acc.signature, acc.summary.toString()))
                }

                InteractionStep.FUNCTION_CALL -> {
                    if (acc.name.isEmpty()) return@forEach
                    // On disconnection the arguments almost inevitably stop at partial JSON: executing a tool (writing a file, running a command) with incomplete arguments
                    // is far more dangerous than dropping this call; keep only calls that parse into a complete object.
                    if (truncatedByNetwork && acc.argsDelta.isNotEmpty() &&
                        parseArgsOrNull(acc.argsDelta.toString()) == null
                    ) {
                        return@forEach
                    }
                    val callId = acc.id.ifEmpty { acc.name }
                    val args = acc.arguments()
                    toolCalls.add(ToolCall(id = callId, name = acc.name, arguments = args))
                    rebuilt.add(functionCallStep(callId, acc.name, args))
                }

                // Server tools (google_search_call etc.) are not parsed by us, but they carry a signature; keep them as-is for replay.
                else -> acc.raw?.let { rebuilt.add(it) }
            }
        }
        return InteractionsOutput(
            text = text.toString(),
            reasoning = reasoning.toString(),
            toolCalls = toolCalls,
            images = images,
            stepsSnapshotJson = snapshotInteractionSteps(rebuilt)
        )
    }

    private fun textBlock(text: String) = JsonObject().apply {
        addProperty("type", InteractionContent.TEXT)
        addProperty("text", text)
    }

    private fun modelOutputStep(text: String) = JsonObject().apply {
        addProperty("type", InteractionStep.MODEL_OUTPUT)
        add("content", JsonArray().apply { add(textBlock(text)) })
    }

    private fun thoughtStep(signature: String?, summary: String) = JsonObject().apply {
        addProperty("type", InteractionStep.THOUGHT)
        signature?.let { addProperty("signature", it) }
        if (summary.isNotEmpty()) add("summary", JsonArray().apply { add(textBlock(summary)) })
    }

    private fun functionCallStep(
        id: String,
        name: String,
        arguments: kotlinx.serialization.json.JsonObject
    ) = JsonObject().apply {
        addProperty("type", InteractionStep.FUNCTION_CALL)
        addProperty("id", id)
        addProperty("name", name)
        add("arguments", JsonParser.parseString(arguments.toString()))
    }
}

private fun parseArgsOrNull(raw: String): kotlinx.serialization.json.JsonObject? =
    raw.trim().takeIf { it.isNotEmpty() }?.let {
        runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull()
    }

private fun JsonObject.str(name: String): String? =
    get(name)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString

/** Interactions image content block -> [AgentImage]. Returns null when data is missing (an abnormal response). */
private fun JsonObject.toAgentImage(): AgentImage? {
    val data = str("data").orEmpty()
    if (data.isEmpty()) return null
    return AgentImage(
        mimeType = str("mime_type") ?: str("mimeType") ?: "image/png",
        base64Data = data
    )
}

private fun JsonObject.int(name: String): Int =
    get(name)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asInt ?: 0

// The next two replace Gson's own getAsJsonArray / getAsJsonObject: they cast directly, and hitting null
// (e.g. a thought step's missing `summary`) throws ClassCastException.
private fun JsonObject.arr(name: String): JsonArray? =
    get(name)?.takeIf { it.isJsonArray }?.asJsonArray

private fun JsonObject.obj(name: String): JsonObject? =
    get(name)?.takeIf { it.isJsonObject }?.asJsonObject
