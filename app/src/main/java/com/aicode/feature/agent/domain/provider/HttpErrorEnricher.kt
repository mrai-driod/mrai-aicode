package com.aicode.feature.agent.domain.provider

import retrofit2.HttpException

/**
 * Converts an HTTP error returned by upstream into an exception carrying the **response body**, so the user can pinpoint the real cause of a 4xx/5xx.
 *
 * Retrofit's [HttpException] default message is only a status-code description like `"HTTP 400 Bad Request"`;
 * the concrete reason the server wrote in the response body (e.g. `model not found`, `invalid api key`, `Invalid value for tools`)
 * is never read out, and the user only sees a useless status code. Here the errorBody is read and appended to the message,
 * with the original exception kept as the cause.
 *
 * Note: errorBody can only be read once; calling this consumes it.
 */
fun Throwable.enrichWithHttpErrorBody(): Throwable {
    if (this !is HttpException) return this
    val raw = this.response()?.errorBody()?.string()?.trim()
    val body = runCatching {
        if (raw.isNullOrBlank()) return@runCatching ""
        // Common formats: {"error":{"message":"...","type":"..."}} or {"message":"..."}; extract readable text as far as possible.
        val obj = com.google.gson.JsonParser.parseString(raw).asJsonObject
        obj.get("error")?.takeIf { it.isJsonObject }?.asJsonObject?.get("message")?.asString
            ?: obj.get("message")?.asString
            ?: raw
    }.getOrNull().orEmpty()
    val detail = if (body.isBlank()) raw?.take(500).orEmpty() else body
    val composed = if (detail.isBlank()) {
        this.message() ?: "HTTP ${this.code()}"
    } else {
        "HTTP ${this.code()}: $detail"
    }
    return IllegalStateException(composed, this)
}
