package com.aicode.feature.agent.domain.provider

import com.aicode.core.util.FileLogger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.IOException

private const val TAG = "StreamTruncation"

/**
 * Degraded-delivery marker for a streaming response where the connection is lost before the end marker arrives.
 *
 * The value must fall within [AIResponse.TRUNCATION_STOP_REASONS] (here `length` is used): that way
 * [AIResponse.isTruncated] is true, and the Agent loop automatically issues a continuation request to resume the answer,
 * instead of failing the whole turn and making the user resend it.
 *
 * Covered disconnection scenarios: direct peer EOF (proxy/CDN closing the connection early), mobile network switching, a relay station disconnecting unilaterally.
 * End markers per protocol: Anthropic `message_stop`, OpenAI Chat `[DONE]`,
 * Responses API `response.completed`, Gemini Interactions' terminal status.
 */
internal const val STREAM_TRUNCATED_STOP_REASON = "length"

/**
 * Whether degraded delivery is worthwhile on disconnection: only if the body text is non-empty.
 *
 * If there is no body text at all (the connection dropped right after being established, or only thinking/tool names were received), degrading is pointless; still throw [IOException]
 * and follow the original retry / multi-Key switching / error-reporting flow — resending then is both safe and may get a complete answer.
 */
internal fun CharSequence.isSalvageableAfterTruncation(): Boolean = isNotBlank()

internal fun logStreamTruncated(provider: String, chars: Int, lines: Int) {
    FileLogger.w(
        TAG,
        "$provider streaming response disconnected before the end marker: received $lines lines / $chars characters, " +
            "degrading to truncated output (stopReason=$STREAM_TRUNCATED_STOP_REASON), to be continued automatically by the Agent loop"
    )
}

/**
 * Whether a tool call's arguments look complete.
 *
 * Arguments are concatenated piece by piece, so on disconnection they almost inevitably stop at partial JSON — executing a tool (writing a file, running a command) with incomplete arguments
 * is far more dangerous than dropping this call. So on disconnection, keep only calls that parse into a complete JSON object:
 * partial JSON will always fail to parse, while calls closed off by `step.stop` / `arguments.done` will necessarily parse successfully.
 */
internal fun looksLikeCompleteJsonObject(raw: String): Boolean {
    val text = raw.trim()
    if (!text.startsWith("{") || !text.endsWith("}")) return false
    return runCatching { Json.parseToJsonElement(text).jsonObject }.isSuccess
}
