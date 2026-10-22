package com.aicode.feature.agent.data.remote.openai

data class ChatCompletionRequest(
    val model: String,
    val messages: List<OpenAIChatMessage>,
    /** When null, Gson skips this field: models with a server-fixed temperature (kimi-k3, the gpt-5 series, etc.) return 400 if any value is carried. */
    val temperature: Float? = null,
    val reasoning_effort: String? = null,
    val tools: List<OpenAIToolDefinition>? = null,
    val tool_choice: String? = null,
    val stream: Boolean = false,
    val stream_options: StreamOptions? = null,
    /** Cache shard routing key (requests from the same session are routed to the same cache shard). Only supported by third-party compatible services; official OpenAI does not accept this field. */
    val prompt_cache_key: String? = null
)

data class OpenAIChatMessage(
    val role: String,
    val content: Any?,
    val name: String? = null,
    val tool_calls: List<OpenAIToolCall>? = null,
    val tool_call_id: String? = null,
    /** DeepSeek thinking mode requires the reasoning_content of the previous assistant message to be sent back verbatim, otherwise 400. */
    val reasoning_content: String? = null,
    /** Some third-party compatible services (e.g. mimo) pass thinking content via a top-level reasoning rather than reasoning_content. */
    val reasoning: String? = null,
    /** Response extension for image-generation models: some compatible services return an `images` array in the assistant message (the relay used by the users of this project uses this format). */
    val images: List<OpenAIImagePart>? = null
)

/** A single image item in the Chat Completions image-generation extension (streaming `delta.images` and non-streaming `message.images` have the same shape). */
data class OpenAIImagePart(
    val type: String? = null,
    val image_url: OpenAIImageUrl? = null,
    val index: Int? = null
)

data class OpenAIImageUrl(val url: String? = null)

data class OpenAIToolDefinition(
    val type: String = "function",
    val function: OpenAIFunctionDefinition
)

data class OpenAIFunctionDefinition(
    val name: String,
    val description: String,
    val parameters: Map<String, Any>
)

data class OpenAIToolCall(
    val id: String,
    val type: String = "function",
    val function: OpenAIFunctionCall
)

data class OpenAIFunctionCall(
    val name: String,
    val arguments: String
)

data class StreamOptions(
    val include_usage: Boolean = true
)

data class ChatCompletionResponse(
    val id: String,
    val `object`: String,
    val created: Long,
    val model: String,
    val choices: List<Choice>,
    val usage: Usage?
)

data class Choice(
    val index: Int,
    val message: OpenAIChatMessage?,
    val delta: OpenAIChatMessage?, // Used for streaming
    val finish_reason: String?
)

data class Usage(
    val prompt_tokens: Int,
    val completion_tokens: Int,
    val total_tokens: Int,
    val prompt_tokens_details: PromptTokensDetails? = null
)

/**
 * Input token details for Chat Completions: cached_tokens is the part that hit the cache,
 * cache_write_tokens is the part written to the cache (only present from GPT-5.6 onwards, billed above the input price).
 */
data class PromptTokensDetails(
    val cached_tokens: Int? = null,
    val cache_write_tokens: Int? = null
)

/**
 * Request body for the Images API (POST /images/generations). Gson serializes directly by field name,
 * so field names must stay in snake_case and match the OpenAI interface.
 *
 * Whether each parameter is carried is decided by the caller based on model compatibility (the GPT image series does not support [responseFormat]
 * and always returns b64_json; [background]/[moderation]/[outputFormat] are only valid for the GPT image series;
 * [style] is only valid for dall-e-3); when null, Gson skips the field.
 */
data class ImageGenerationRequest(
    val model: String,
    val prompt: String,
    val n: Int? = null,
    val size: String? = null,
    val quality: String? = null,
    val response_format: String? = null,
    val output_format: String? = null,
    val background: String? = null,
    val moderation: String? = null,
    val style: String? = null
)

/** A single generated result from the Images API. */
data class ImageGenerationResult(
    val b64_json: String? = null,
    val url: String? = null,
    val revised_prompt: String? = null
)

/** Images API token usage (returned by the gpt-image series; the dall-e series has no such field). */
data class ImageGenerationUsage(
    val input_tokens: Int = 0,
    val output_tokens: Int = 0,
    val total_tokens: Int = 0,
    val input_tokens_details: ImageTokenDetails? = null,
    val output_tokens_details: ImageTokenDetails? = null
)

/** Images API token details: split into image tokens and text tokens. */
data class ImageTokenDetails(
    val image_tokens: Int = 0,
    val text_tokens: Int = 0
)

/** Images API response body (deserialized by Gson; only the needed fields are used). */
data class ImageGenerationResponse(
    val created: Long = 0,
    val data: List<ImageGenerationResult> = emptyList(),
    val usage: ImageGenerationUsage? = null
)
