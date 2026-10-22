package com.aicode.feature.agent.data.remote.anthropic

data class AnthropicMessageRequest(
    val model: String,
    val messages: List<AnthropicMessage>,
    // Plain text or an array of content blocks (the block array is used when setting a cache_control breakpoint).
    val system: Any? = null,
    val max_tokens: Int = 16384,
    // When extended thinking is enabled, temperature must not be carried (official requirement); set to null so Gson skips the field.
    val temperature: Float? = null,
    val thinking: AnthropicThinkingConfig? = null,
    // The effort level for adaptive thinking (newer models, e.g. opus-4.7+/sonnet-5).
    val output_config: AnthropicOutputConfig? = null,
    val tools: List<AnthropicToolDefinition>? = null,
    val stream: Boolean = false
)

/**
 * Anthropic thinking configuration:
 * - Older models (4.5 and earlier): type="enabled" + budget_tokens (thinking token budget)
 * - Newer models (4.6+/5 series): type="adaptive" + display (how thinking is shown), effort goes through [AnthropicOutputConfig]
 * - Thinking off: type="disabled"
 * Unused fields are set to null so Gson skips them.
 */
data class AnthropicThinkingConfig(
    val type: String = "enabled",
    val budget_tokens: Int? = null,
    val display: String? = null
)

/** The effort level for adaptive thinking (low/medium/high/xhigh/max). */
data class AnthropicOutputConfig(
    val effort: String? = null
)

data class AnthropicMessage(
    val role: String, // "user" or "assistant"
    val content: Any // Can be String or List<AnthropicContentBlock>
)

data class AnthropicContentBlock(
    val type: String, // "text", "tool_use", "tool_result", "thinking", "redacted_thinking"
    val text: String? = null,
    val source: Map<String, Any>? = null,
    val id: String? = null, // for tool_use
    val name: String? = null, // for tool_use
    val input: Map<String, Any>? = null, // for tool_use
    val tool_use_id: String? = null, // for tool_result
    val content: Any? = null, // for tool_result: String or List<AnthropicContentBlock>
    val is_error: Boolean? = null, // for tool_result
    val thinking: String? = null, // for thinking block: thinking summary text
    val signature: String? = null, // for thinking block: encrypted signature, must be sent back verbatim across turns / in the tool loop
    val data: String? = null, // for redacted_thinking: opaque encrypted string, likewise must be sent back verbatim
    val cache_control: Map<String, String>? = null // Explicit cache breakpoint, only {type: ephemeral}
)

data class AnthropicToolDefinition(
    val name: String,
    val description: String,
    val input_schema: Map<String, Any>,
    val cache_control: Map<String, String>? = null // Explicit cache breakpoint, set only on the last tool
)

data class AnthropicMessageResponse(
    val id: String,
    val type: String,
    val role: String,
    val content: List<AnthropicContentBlock>,
    val model: String,
    val stop_reason: String?,
    val stop_sequence: String?,
    /** Structured explanation for refusals and other abnormal endings (when stop_reason=refusal the body may be empty and the reason is only here). */
    val stop_details: AnthropicStopDetails? = null,
    val usage: AnthropicUsage
)

/** `stop_details`: supplementary explanation for scenarios such as refusals / downgrades. */
data class AnthropicStopDetails(
    val type: String? = null,
    val category: String? = null,
    val explanation: String? = null,
    val recommended_model: String? = null
)

data class AnthropicUsage(
    val input_tokens: Int,
    val output_tokens: Int,
    /** Number of prefix tokens that hit the cache (returned when prompt caching is in effect). */
    val cache_read_input_tokens: Int? = null,
    /** Number of new tokens written to the cache this time. */
    val cache_creation_input_tokens: Int? = null
)
