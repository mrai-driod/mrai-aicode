package com.aicode.feature.agent.domain.provider

import com.aicode.feature.agent.domain.model.AgentImage
import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.ToolCall
import kotlinx.coroutines.flow.Flow

data class AIResponse(
    val content: String,
    val toolCalls: List<ToolCall> = emptyList(),
    /**
     * The reason the model stopped. Anthropic: "end_turn" / "tool_use" / "max_tokens" / "refusal" /
     * "model_context_window_exceeded" / "pause_turn"; OpenAI: "stop" / "tool_calls" / "length";
     * Gemini (generateContent's finishReason, all uppercase): "STOP" / "MAX_TOKENS" / "SAFETY", etc.;
     * Gemini (Interactions' status, lowercase): "completed" / "requires_action" / "failed" /
     * "budget_exceeded" (`incomplete` is normalized to "length" by [interactionStopReason]).
     * When the value matches [TRUNCATION_STOP_REASONS], it means the output was truncated by the token limit, and the Agent loop should automatically continue writing.
     */
    val stopReason: String? = null,
    /**
     * The explanation of the stop reason given by the server (Anthropic `stop_details.explanation`).
     * On a refusal the body may be empty, and only this field holds a displayable reason. Null when unavailable.
     */
    val stopDetail: String? = null,
    /** The model's complete reasoning for this turn (corresponding to OpenAI/DeepSeek's reasoning_content). When non-empty it must be sent back to the API, otherwise DeepSeek thinking mode returns 400. */
    val reasoning: String? = null,
    /** The encrypted signature of Anthropic extended thinking (the thinking block's signature). In multi-turn / tool loops it must be sent back unchanged along with the thinking, otherwise 400. Null for other providers. */
    val signature: String? = null,
    /**
     * A verbatim snapshot (JSON text) of this turn's provider-native "thinking / signature" blocks, which must be sent back unchanged and in the original order on the next turn:
     * - Anthropic: the array of thinking / redacted_thinking content blocks (a redacted block's `data` cannot be reconstructed, and merging multiple blocks causes a 400).
     * - Gemini (generateContent): the model turn's `parts` array, because `thoughtSignature` is metadata attached to an arbitrary part
     *   (often the functionCall part or the last part), and only sending the whole block back unchanged preserves reasoning continuity.
     * - Gemini (Interactions): the `steps` array produced by the model (thought / model_output / function_call).
     *   The official docs require these steps to be sent back unchanged in stateless mode; the `signature` on thought and the function_call cannot be reconstructed.
     * Null for other providers.
     */
    val thinkingBlocksJson: String? = null,
    /** The number of input tokens for this turn (from the usage returned by the API). 0 when unavailable. */
    val inputTokens: Int = 0,
    /** The number of output tokens for this turn (from the usage returned by the API). 0 when unavailable. */
    val outputTokens: Int = 0,
    /** The portion of this turn's input that hit the server-side cache (OpenAI cached_tokens / Anthropic cache_read_input_tokens / Gemini cachedContentTokenCount). 0 when unavailable. */
    val cachedInputTokens: Int = 0,
    /** The number of tokens written to the server-side cache for this turn (Anthropic cache_creation_input_tokens). Billed at a higher rate than regular input; 0 when unavailable. */
    val cacheCreationTokens: Int = 0,
    /**
     * Images output directly by the model this turn (produced when a Gemini Nano Banana image model goes through Interactions).
     * Elements carry base64 data; the workflow is responsible for saving them to disk and building UI attachments. Always empty for other providers / models.
     */
    val images: List<AgentImage> = emptyList()
) {
    val isTruncated: Boolean
        get() = stopReason in TRUNCATION_STOP_REASONS

    /** This turn was aborted due to server policy / context limit exceeded: the body may be incomplete or empty, and the Agent loop should not treat it as a normal completion and continue. */
    val isAborted: Boolean
        get() = stopReason in ABORT_STOP_REASONS

    companion object {
        /**
         * Output truncated by the token limit (the spellings used by Anthropic / OpenAI / Gemini).
         * Gemini Interactions' `incomplete` matches after being normalized to `length` by [interactionStopReason];
         * `incomplete` is not included directly -- the same-named status in the Responses API also covers non-truncation reasons such as content_filter.
         */
        val TRUNCATION_STOP_REASONS = setOf("max_tokens", "length", "MAX_TOKENS")

        /**
         * stop_reason values that need to be explained to the user rather than completed silently.
         * The first two are Anthropic's; the uppercase group is Gemini generateContent's finishReason (safety block /
         * prohibited content / blocklist / sensitive information); the last two are Gemini Interactions' status.
         */
        val ABORT_STOP_REASONS = setOf(
            "refusal",
            "model_context_window_exceeded",
            "SAFETY",
            "PROHIBITED_CONTENT",
            "BLOCKLIST",
            "SPII",
            "failed",
            "budget_exceeded"
        )
    }
}

/**
 * The result of one Key switch: the new Key and its index (starting at 1) in the candidate list, for the adapter to rewrite the credential and notify the UI.
 */
data class KeySwitchOutcome(val newKey: String, val newIndex: Int, val total: Int)

/**
 * Thrown when all candidate Keys have failed. The message already includes the original failure reason, and the upper layer should display it as-is.
 */
class AllKeysFailedException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Chunks pushed upstream during streaming completion.
 * [TextDelta] is a small piece of text newly produced by the model (incremental, not cumulative);
 * [Final] gives the complete result at the end of this turn (aggregated text + tool calls), for the Agent loop to drive subsequent tool execution.
 * [Retrying] is pushed during a network retry, for the UI to show a "retrying" hint.
 * [KeySwitched] is pushed during automatic multi-Key switching, for the UI to indicate the Key this request has switched to.
 */
sealed class AIStreamChunk {
    data class TextDelta(val text: String) : AIStreamChunk()
    /** A small piece of reasoning newly produced by the model (incremental, not cumulative). Used only for real-time UI display; does not enter context replay. */
    data class ReasoningDelta(val text: String) : AIStreamChunk()
    /**
     * The model has just started producing a tool call, and the tool name is known (the arguments are still streaming in).
     *
     * Tools such as writing a whole file or a long command have very long arguments, and this streaming may last several seconds; during that time there is neither body text nor
     * reasoning deltas, so the UI can only show a generic "Thinking". As soon as the tool name appears, upstream pushes one of these events, letting the UI
     * switch the status to a specific scenario ("Editing file"). It plays no part in any execution decision; it is purely a UI hint.
     */
    data class ToolCallDeclared(val name: String) : AIStreamChunk()
    data class Final(val response: AIResponse) : AIStreamChunk()
    /** The network request is being retried. Used only for real-time UI display; does not enter context replay. [error] is a summary of the error that triggered the retry, for the UI to show the specific reason. */
    data class Retrying(val attempt: Int, val maxRetries: Int, val error: RetryErrorInfo) : AIStreamChunk()
    /** The current Key is unavailable, and the request has automatically switched to Key [newIndex]/[total] and resent this request. Used only as a UI hint. */
    data class KeySwitched(val newIndex: Int, val total: Int) : AIStreamChunk()
}

interface AIProvider {
    var apiKey: String
    var baseUrl: String
    var useFullUrl: Boolean

    /**
     * Switch to this provider's new endpoint: OpenAI uses the Responses API (`v1/responses`),
     * Gemini uses the Interactions API (`v1beta/interactions`). Ignored by Anthropic.
     */
    var useResponseApi: Boolean
    var model: String

    /**
     * The current provider config id (database primary key), used to associate custom model metadata.
     * Set by the workflow before the call; when empty, metadata resolution falls back to fully automatic (fetched / built-in / default).
     */
    var providerId: String

    /**
     * The current session id, used only for log archiving: set by the workflow before the call, and [com.aicode.core.util.AILogger]
     * uses it to write each request/response to the corresponding session's file. When null, it falls to `session-unknown.log`.
     */
    var logSessionId: String?

    /**
     * Custom request headers (header name -> value; values may contain `{{SESSION_ID}}` / `{{API_KEY}}` placeholders).
     * Placeholders are replaced by [com.aicode.feature.agent.domain.provider.resolveCustomHeaders] before being written out,
     * completely overriding the same-named default headers for this provider's requests.
     */
    var customHeaders: Map<String, String>

    /**
     * Multi-Key automatic switching hook, injected by the workflow when assembling the provider; null means disabled (e.g. bypasses such as image generation).
     * The inputs are this failure's exception, the Key currently in use, and the set of Keys already tried for this request. A non-null return means it has switched to
     * [KeySwitchOutcome.newKey], and the adapter should rewrite [apiKey] and resend; a null return means the failure is not a "Key unavailable"
     * failure or the candidates are exhausted (when exhausted, the implementation throws [AllKeysFailedException]).
     */
    var keySwitcher: (suspend (Throwable, String, Set<String>) -> KeySwitchOutcome?)?

    /**
     * The maximum number of output tokens allowed for this request, from the output limit in the model metadata (models.dev `limit.output`).
     * Set by the workflow before the call; when null, each adapter uses its own default or does not send this parameter.
     */
    var maxOutputTokens: Int?

    /**
     * The sampling temperature for this request, set by the workflow before the call according to the model metadata (see [fixedTemperature]).
     * Null means the request does not include this field and uses the server default -- for models whose temperature is fixed by the server (such as kimi-k3, the gpt-5 series, etc.),
     * sending any value causes a 400.
     */
    var temperature: Float?

    /**
     * The upper limit (in milliseconds) for a streaming request to wait for the first content chunk, written by the workflow before the call according to "Preferences -> Network".
     * Default 5 minutes; 0 means no limit, in which case only the connection timeout and manual cancellation provide a fallback.
     */
    var firstByteTimeoutMs: Long

    /**
     * The maximum wait (in milliseconds) between two adjacent data chunks in a streaming response; 0 (default) means no limit.
     * When non-zero, if no data is received for longer than this interval the stream is closed, triggering a retriable IOException.
     */
    var streamIdleTimeoutMs: Long

    /**
     * The maximum number of retries for network requests (including streaming), not counting the first request; written by the workflow before the call according to "Preferences -> Network".
     * Default 6; 0 means throw on failure with no retry.
     */
    var maxNetworkRetries: Int

    /**
     * Single-turn completion. [tools] is actually sent to the model in the provider's function-calling format,
     * and if the model decides to call a tool, the result appears in the returned [AIResponse.toolCalls].
     * [reasoningEffort] is the reasoning effort ("low"/"medium"/"high"), effective only for the OpenAI family;
     * Anthropic/Gemini and models that do not support this parameter ignore it.
     */
    suspend fun complete(
        systemPrompt: String,
        messages: List<AgentMessage>,
        tools: List<AgentTool> = emptyList(),
        reasoningEffort: String? = null
    ): AIResponse

    /**
     * Streaming single-turn completion: receives the model's reply word by word via SSE. Text is pushed incrementally as [AIStreamChunk.TextDelta],
     * and at the end of the turn the complete aggregated [AIResponse] (including tool calls) is given as [AIStreamChunk.Final].
     * The function-calling semantics of tool calls are the same as [complete].
     * [reasoningEffort] is the same as in [complete].
     */
    fun completeStream(
        systemPrompt: String,
        messages: List<AgentMessage>,
        tools: List<AgentTool> = emptyList(),
        reasoningEffort: String? = null
    ): Flow<AIStreamChunk>
}

private val VERSION_SEGMENT_REGEX = Regex("""^v\d+.*$""", RegexOption.IGNORE_CASE)

/**
 * Replaces placeholders in the provider's custom request headers with runtime values and returns the final header map to write out.
 * Currently supports `{{SESSION_ID}}` (the current session id, replaced with an empty string when there is no session) and `{{API_KEY}}` (the Key actually used for this request).
 * Empty header names have already been cleaned on the saving side; they are defensively skipped again here.
 */
fun resolveCustomHeaders(
    customHeaders: Map<String, String>,
    sessionId: String?,
    apiKey: String
): Map<String, String> = customHeaders
    .filterKeys { it.isNotBlank() }
    .mapValues { (_, raw) ->
        raw.replace("{{SESSION_ID}}", sessionId ?: "")
            .replace("{{API_KEY}}", apiKey)
    }

/** Gemini generations for which the official docs give a recommended sampling temperature and filling in another value noticeably degrades quality; other Gemini models do not send a temperature. */
private val GEMINI_MODELS_WITH_SAMPLING_DEFAULTS = listOf(
    Regex("""gemini-2[.-]5([.-]|$)"""),
    Regex("""gemini-3-(flash|pro)([.-]|$)"""),
    Regex("""gemini-3[.-]1([.-]|$)"""),
    Regex("""gemini-3[.-]5-flash(?!-lite)([.-]|$)""")
)

/**
 * Some models have their sampling temperature pinned by the server to a specific value (or the official docs explicitly require that value); this table gives it by model id.
 * Models not in the table return null, meaning the request does not include temperature and uses the server default.
 * This table is used only when the model metadata allows a custom temperature: for models whose metadata says it is not allowed (such as kimi-k3, the gpt-5 series, etc.), it is never sent.
 */
fun fixedTemperature(modelId: String): Float? {
    val id = modelId.lowercase()
    return when {
        id.contains("glm-4.6") || id.contains("glm-4.7") -> 1.0f
        id.contains("minimax-m2") -> 1.0f
        id.contains("gemini") ->
            if (GEMINI_MODELS_WITH_SAMPLING_DEFAULTS.any { it.containsMatchIn(id) }) 1.0f else null
        // Kimi K2 thinking variants (k2-thinking / k2.5 / k2.6 ...) are fixed at 1.0, and non-thinking K2 is fixed at 0.6.
        id.contains("kimi-k2") ->
            if (listOf("thinking", "k2.", "k2p", "k2-5").any { id.contains(it) }) 1.0f else 0.6f
        else -> null
    }
}

/**
 * Builds an absolute request URL from a user-configured base URL and an API path
 * such as "v1/chat/completions". Tolerates trailing slashes and a base URL that
 * already ends with a version segment (e.g. "https://host/v1", "https://host/api/v3", "https://host/v1beta")
 * so it isn't duplicated or conflicted with "v1/".
 *
 * Boundary note (rule 2's "base version takes precedence" assumption): when the end of base is a version segment and the path to append also starts with a version segment,
 * the path's version segment is discarded outright and base's version is used. This is correct for all base forms supported by the project --
 * the only built-in source whose base ends with a version segment is Zhipu (`https://open.bigmodel.cn/api/paas/v4`, whose real endpoint is
 * `.../v4/chat/completions`), and in that case replacing the `v1` in OpenAI's default path with base's `v4` is exactly what is needed.
 * The other built-in sources (OpenAI/Anthropic/Gemini/DeepSeek, etc.) have bases with no version segment and take the append-to-end branch.
 * If a source appears in the future where "the base and path version segments differ and the path version should take precedence", this must be rewritten
 * to deduplicate only when they are equal and otherwise append directly, rather than unconditionally discarding the path version segment.
 */
/**
 * Reports a Key failure and switches to the next available Key. A null return means [keySwitcher] was not injected, the failure was judged not to be a Key failure,
 * or there is no candidate to switch to; on a successful switch, it rewrites [AIProvider.apiKey] and adds the new Key to [triedKeys].
 */
suspend fun AIProvider.switchKeyOnFailure(error: Throwable, triedKeys: MutableSet<String>): KeySwitchOutcome? {
    val switcher = keySwitcher ?: return null
    val outcome = switcher(error, apiKey, triedKeys) ?: return null
    apiKey = outcome.newKey
    triedKeys += outcome.newKey
    return outcome
}

fun joinUrl(baseUrl: String, path: String): String {
    val base = baseUrl.trim().trimEnd('/')
    val cleanPath = path.trimStart('/')
    
    val lastSegment = base.substringAfterLast('/', "")
    
    // 1. If the end of base and the start of path are exactly the same segment (e.g. /v1 and v1/chat), deduplicate
    if (lastSegment.isNotEmpty() && cleanPath.startsWith("$lastSegment/", ignoreCase = true)) {
        return "$base/${cleanPath.substring(lastSegment.length + 1)}"
    }
    
    // 2. If the end of base is already a version number (e.g. /v3, /v2, /v1beta, etc.) and the path to append also starts with a version segment (e.g. v1/chat, v1/models)
    if (lastSegment.matches(VERSION_SEGMENT_REGEX)) {
        val pathFirstSegment = cleanPath.substringBefore('/')
        if (pathFirstSegment.matches(VERSION_SEGMENT_REGEX)) {
            val remainingPath = cleanPath.substringAfter('/', "")
            return if (remainingPath.isNotEmpty()) "$base/$remainingPath" else base
        }
    }
    
    return "$base/$cleanPath"
}
