package com.aicode.feature.agent.domain.provider

import com.aicode.feature.settings.domain.model.DEFAULT_KEY_SWITCH_STATUS_CODES
import retrofit2.HttpException

/**
 * Determines whether an LLM call failure can be attributed to the API Key currently in use — i.e. whether multi-Key automatic switching should be triggered.
 *
 * Only recognises authentication/permission, billing/quota and rate-limit failures: HTTP status codes default to 401/402/403/429 (overridable per provider,
 * see [AIProviderConfig.keySwitchStatusCodes]), plus quota/authentication error codes within the stream. 5xx, 408, timeouts,
 * DNS/connection faults are server-side or link problems; switching Key is useless for them and should not be triggered.
 *
 * [enrichWithHttpErrorBody] wraps [HttpException] into IllegalStateException and attaches the original exception
 * as the cause, so look for the status code along the cause chain here, and only fall back to message-text matching if it is not found.
 */
fun Throwable.isKeySwitchFailure(statusCodes: Set<Int> = DEFAULT_KEY_SWITCH_STATUS_CODES): Boolean {
    var current: Throwable? = this
    var depth = 0
    while (current != null && depth < MAX_CAUSE_DEPTH) {
        when (current) {
            is HttpException -> if (current.code() in statusCodes) return true
            is StreamApiException -> {
                if (current.code?.lowercase() in KEY_FAILURE_STREAM_CODES) return true
            }
        }
        current = current.cause
        depth++
    }
    val text = message?.lowercase() ?: return false
    // The HTTP status-code text fallback is likewise constrained by [statusCodes]; otherwise a custom code table could not actually narrow the trigger scope
    if (statusCodes.any { text.contains("http $it") }) return true
    return KEY_FAILURE_MESSAGES.any { text.contains(it) }
}

private const val MAX_CAUSE_DEPTH = 5

private val KEY_FAILURE_STREAM_CODES = setOf(
    "insufficient_quota",
    "quota_exceeded",
    "usage_limit_reached",
    "usage_not_included",
    "rate_limit_exceeded",
    "rate_limit_error",
    "invalid_api_key",
    "authentication_error",
    "permission_error",
    "permission_denied"
)

private val KEY_FAILURE_MESSAGES = listOf(
    "invalid api key",
    "invalid_api_key",
    "incorrect api key",
    "insufficient_quota",
    "insufficient balance",
    "quota exceeded",
    "authentication_error",
    "permission_denied"
)
