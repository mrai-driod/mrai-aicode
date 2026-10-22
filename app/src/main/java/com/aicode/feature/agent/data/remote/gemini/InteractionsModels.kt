package com.aicode.feature.agent.data.remote.gemini

/**
 * Values for the step `type` in the Interactions API (`v1beta/interactions`).
 *
 * Unlike generateContent `contents[].parts[]`: a conversation turn is split into separate steps on a timeline,
 * with tool calls, thinking and model output each occupying a step; in stateless replay the whole history is the step array.
 */
object InteractionStep {
    /** User input; the body is in `content` (a Content array, a plain string is also accepted). */
    const val USER_INPUT = "user_input"

    /** Model output; the body is in `content` (a Content array). */
    const val MODEL_OUTPUT = "model_output"

    /**
     * Thought step: `signature` is the signature used for backend validation, `summary` is an optional thinking summary (Content array).
     * In stateless mode it must be sent back verbatim, otherwise it cannot connect to the reasoning context of the previous turn.
     */
    const val THOUGHT = "thought"

    /** The model requests a function call: `id` / `name` / `arguments` (an object, not a string). */
    const val FUNCTION_CALL = "function_call"

    /** Function execution result: `call_id` corresponds to the call `id`, `result` is a Content array / object / string. */
    const val FUNCTION_RESULT = "function_result"
}

/** Values for the Content block `type` in Interactions. */
object InteractionContent {
    const val TEXT = "text"
    const val IMAGE = "image"
}

/**
 * Event types for Interactions streaming SSE; the discriminator field is **`event_type`** (not `type`).
 *
 * Lifecycle events carry an `interaction` object (including status / usage), and step events are grouped by `index`.
 * The stream **does not send `[DONE]`**; the end can only be determined by whether the interaction status has reached a terminal state (see [TERMINAL_STATUSES]).
 */
object InteractionEvent {
    const val STEP_START = "step.start"
    const val STEP_DELTA = "step.delta"
    const val STEP_STOP = "step.stop"

    /** Transport-layer / platform error, itself carrying `error: {code, message}`. */
    const val ERROR = "error"

    /** Lifecycle event prefix: created / in_progress / status_update / requires_action / completed, etc. */
    const val LIFECYCLE_PREFIX = "interaction."
}

/** Values for `delta.type` in `step.delta`. */
object InteractionDelta {
    /** Body-text delta; the text is in `text`. */
    const val TEXT = "text"

    /** Thinking summary delta: the text is in `content.text` (some implementations put it directly in `text`). */
    const val THOUGHT_SUMMARY = "thought_summary"

    /** Another spelling of the above; this is the one used in the migration guide examples. */
    const val THOUGHT = "thought"

    /** Thinking signature; the value is in `signature`, attached to the thought step it belongs to. */
    const val THOUGHT_SIGNATURE = "thought_signature"

    /** Tool argument delta, character by character; the fragment is in `arguments` (the migration guide writes it as `partial_arguments`). */
    const val ARGUMENTS = "arguments_delta"

    /** Another spelling of the above. */
    const val ARGUMENTS_LEGACY = "arguments"
}

/**
 * Terminal states of an interaction. When streaming, reading any terminal state is enough to stop reading the stream:
 * when a tool is waiting to run, the server stops at `requires_action` and will **not** send `completed` afterwards;
 * waiting only for `completed` would read all the way to the end of the stream and be misjudged as a dropped stream.
 */
val TERMINAL_STATUSES = setOf(
    "completed",
    "requires_action",
    "incomplete",
    "failed",
    "cancelled",
    "budget_exceeded"
)
