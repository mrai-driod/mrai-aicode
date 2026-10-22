package com.aicode.feature.agent.data.remote.openai

/**
 * Function tool definition for the Responses API: the fields are laid out directly on the item, with no `function`
 * nesting layer as in Chat Completions. Sending a nested structure is rejected by the server with "tools[0]: missing field `name`".
 */
data class ResponsesToolDefinition(
    val type: String = "function",
    val name: String,
    val description: String,
    val parameters: Map<String, Any>,
    /**
     * In the official schema `strict` has no optional marker and is always sent as false: strict mode requires the argument schema
     * to satisfy the structured-output subset (all fields required + additionalProperties:false), which tools are not guaranteed to satisfy.
     */
    val strict: Boolean = false
)

/**
 * Semantic event types for Responses streaming SSE.
 * Unlike Chat Completions delta frames, the stream ends with one of [COMPLETED] / [INCOMPLETE] / [FAILED],
 * and **does not send `data: [DONE]`**, so the end can only be determined from the terminating event.
 */
object ResponsesEvent {
    const val OUTPUT_TEXT_DELTA = "response.output_text.delta"
    /** Model refusal: the refusal explanation goes in a separate event and a separate part, and does not appear in output_text. */
    const val REFUSAL_DELTA = "response.refusal.delta"
    const val REASONING_TEXT_DELTA = "response.reasoning_text.delta"
    /** The thinking summary for official reasoning models (the o series / gpt-5 series) goes through this event, rather than reasoning_text. */
    const val REASONING_SUMMARY_TEXT_DELTA = "response.reasoning_summary_text.delta"
    const val OUTPUT_ITEM_ADDED = "response.output_item.added"
    const val OUTPUT_ITEM_DONE = "response.output_item.done"
    const val FUNCTION_CALL_ARGS_DELTA = "response.function_call_arguments.delta"
    const val FUNCTION_CALL_ARGS_DONE = "response.function_call_arguments.done"
    const val COMPLETED = "response.completed"
    const val INCOMPLETE = "response.incomplete"
    const val FAILED = "response.failed"
    /** Transport-layer error event: sits alongside the three terminating events and itself carries code / message. */
    const val ERROR = "error"
    /** Same as above; the official SDK handles both namings. */
    const val RESPONSE_ERROR = "response.error"
}

/** Values for the `type` of Responses input/output items. */
object ResponsesItem {
    const val MESSAGE = "message"
    const val FUNCTION_CALL = "function_call"
    const val FUNCTION_CALL_OUTPUT = "function_call_output"
    const val REASONING = "reasoning"
    /** Image item produced by the model via the server-side image_generation tool; `result` is base64. */
    const val IMAGE_GENERATION_CALL = "image_generation_call"
}

/** Values for the `type` of Responses content parts. */
object ResponsesPart {
    const val INPUT_TEXT = "input_text"
    const val OUTPUT_TEXT = "output_text"
    const val INPUT_IMAGE = "input_image"
    const val REASONING_TEXT = "reasoning_text"
    /** Element type of the `summary` array of a reasoning item. */
    const val SUMMARY_TEXT = "summary_text"
    /** The part in a message item when the model refuses; the text is in the `refusal` field rather than `text`. */
    const val REFUSAL = "refusal"
}
