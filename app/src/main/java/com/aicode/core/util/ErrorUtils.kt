package com.aicode.core.util

import java.io.IOException

/**
 * Converts an exception into meaningful prompt text for the user.
 *
 * For many exceptions (such as an NPE or a cancelled request), [Throwable.message] is null,
 * and using it directly would display as "null". This falls back to the exception class name and traces up the cause chain,
 * to provide as much information as possible to locate the problem.
 */
fun Throwable.toUserMessage(): String {
    val parts = mutableListOf<String>()
    var current: Throwable? = this
    var depth = 0
    while (current != null && depth < 4) {
        val msg = current.message?.trim().orEmpty()
        val label = if (msg.isNotEmpty()) msg else friendlyName(current)
        if (parts.none { it == label }) parts.add(label)
        current = current.cause
        depth++
    }
    return parts.joinToString(" · ").ifBlank { "Unknown error" }
}

/** Provides friendlier explanations for common exception types. */
private fun friendlyName(t: Throwable): String = when (t) {
    is java.net.UnknownHostException -> "Unable to connect to server (DNS resolution failed, please check your network or Base URL)"
    is java.net.SocketTimeoutException -> "Request timed out (network is slow or the service is not responding)"
    is java.net.ConnectException -> "Connection refused (please check the Base URL and port)"
    is javax.net.ssl.SSLException -> "SSL connection failed"
    is IOException -> "Network read/write failed: ${t.javaClass.simpleName}"
    else -> t.javaClass.simpleName
}
