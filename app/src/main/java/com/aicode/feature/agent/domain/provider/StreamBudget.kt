package com.aicode.feature.agent.domain.provider

/**
 * Upper limit on accumulated delta characters allowed for a single streaming response.
 *
 * The log buffer `rawSse` already has a 512K cap, but the **business-side** accumulators (body / thinking / tool arguments) previously had no limit at all:
 * a provider's baseUrl is user-configurable, and an abnormal or hijacked upstream can send deltas indefinitely; accepting them all would blow up the heap.
 */
const val MAX_STREAM_CHARS = 4 * 1024 * 1024

/**
 * Counts the accumulated delta characters of a single streaming response, and throws [StreamApiException] to abort this request once [maxChars] is exceeded.
 *
 * Interrupting is chosen over silent truncation: truncated tool arguments would become partial JSON executed as parameters, and truncated body text would give the user an incomplete answer,
 * both worse than reporting an error directly. 4M characters is two orders of magnitude above any model's normal output, so normal use will never trigger it.
 */
class StreamBudget(private val maxChars: Int = MAX_STREAM_CHARS) {
    private var total = 0L

    fun add(delta: String) {
        if (delta.isEmpty()) return
        total += delta.length
        if (total > maxChars) {
            throw StreamApiException(
                code = "response_too_large",
                message = "Abnormal content returned by upstream (single response exceeds ${maxChars / 1024}KB), aborted"
            )
        }
    }
}
