package com.aicode.feature.agent.domain.provider

import com.aicode.core.util.FileLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import retrofit2.HttpException
import retrofit2.await
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.net.ssl.SSLException
import kotlin.coroutines.coroutineContext
import kotlin.math.min
import kotlin.math.pow
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

/**
 * Network request retry strategy referencing opencode: exponential backoff, only for transient failures.
 */
private const val TAG = "RetryPolicy"

// Corresponds to opencode's attempts = 3 (at most 2 retries) -> raised to 6 retries to cope with unstable networks
const val MAX_NETWORK_RETRIES = 6

/**
 * The upper limit on waiting from starting a request to the first valid content for each streaming attempt, including waiting for the response headers.
 *
 * OkHttp's readTimeout is set to unlimited, and before the first content the application layer cancels the request as a fallback,
 * so it is relaxed to 5 minutes to accommodate slow-starting and long-thinking models.
 */
const val FIRST_BYTE_TIMEOUT_MS = 300_000L

class FirstContentGuard {
    @Volatile private var close: (() -> Unit)? = null
    @Volatile private var cancelCall: (() -> Unit)? = null
    private var received = false
    private var expired = false
    @Volatile private var closed = false
    internal var timer: Job? = null

    fun attach(close: () -> Unit) {
        this.close = close
        if (closed) closeBody()
    }

    suspend fun awaitBody(call: retrofit2.Call<okhttp3.ResponseBody>): okhttp3.ResponseBody {
        cancelCall = { call.cancel() }
        if (closed) call.cancel()
        return call.await()
    }

    fun receivedContent() {
        synchronized(this) {
            received = true
            timer?.cancel()
        }
    }

    internal fun expire(): Boolean = synchronized(this) {
        if (received || expired) false else {
            expired = true
            true
        }
    }

    fun closeBody() {
        closed = true
        runCatching { cancelCall?.invoke() }
        runCatching { close?.invoke() }
    }
}

@OptIn(InternalCoroutinesApi::class)
suspend fun withFirstContentTimeout(
    timeoutMs: Long,
    block: suspend (FirstContentGuard) -> Unit
) {
    val guard = FirstContentGuard()
    val timedOut = java.util.concurrent.atomic.AtomicBoolean(false)
    try {
        coroutineScope {
            val attemptJob = coroutineContext[Job]!!
            // A blocking readLine cannot be woken by coroutine cancellation, so the underlying Call must be cancelled when entering the cancelling state.
            val handle = attemptJob.invokeOnCompletion(onCancelling = true, invokeImmediately = true) {
                if (it != null) guard.closeBody()
            }
            val timer = launch(start = CoroutineStart.LAZY) {
                if (timeoutMs <= 0) return@launch
                delay(timeoutMs)
                if (guard.expire()) {
                    timedOut.set(true)
                    attemptJob.cancel(CancellationException("First content timeout"))
                }
            }
            guard.timer = timer
            timer.start()
            try {
                block(guard)
            } finally {
                timer.cancel()
                handle.dispose()
            }
        }
    } catch (e: Throwable) {
        coroutineContext.ensureActive()
        if (timedOut.get()) throw SocketTimeoutException("First content timeout after ${timeoutMs}ms")
        throw e
    }
}

/**
 * Streaming response "data chunk interval" watchdog: if more than [timeoutMs] passes between two adjacent data chunks, it calls [close],
 * causing the blocked read to throw an IOException, which is handed to the retry mechanism.
 *
 * Call [touch] once for each data chunk received to restart the timer; call [cancel] when the stream ends (normally or abnormally).
 * [timeoutMs] <= 0 means no limit, in which case both methods are no-ops.
 */
class StreamIdleWatchdog(
    private val scope: CoroutineScope,
    private val timeoutMs: Long,
    private val close: () -> Unit
) {
    private var timer: Job? = null

    fun touch() {
        if (timeoutMs <= 0) return
        timer?.cancel()
        timer = scope.launch {
            delay(timeoutMs)
            runCatching { close() }
        }
    }

    fun cancel() {
        timer?.cancel()
        timer = null
    }
}

/** Creates a [StreamIdleWatchdog] in the current coroutine; when [timeoutMs] <= 0, the created instance does nothing. */
suspend fun launchStreamIdleWatchdog(timeoutMs: Long, close: () -> Unit): StreamIdleWatchdog =
    StreamIdleWatchdog(CoroutineScope(coroutineContext[Job]!!), timeoutMs, close)

private val TRANSIENT_MESSAGES = listOf(
    "load failed",
    "network connection was lost",
    "network request failed",
    "failed to fetch",
    "econnreset",
    "econnrefused",
    "etimedout",
    "socket hang up"
)

/**
 * Corresponds to opencode's delay=500, factor=2, maxDelay=10000.
 *
 * No longer used as the default retry wait (changed to the fixed [NETWORK_RETRY_DELAY_MILLIS]); kept for scenarios that need
 * a long backoff (such as a fallback when all Keys are rate-limited).
 */
fun exponentialDelayMillis(retryIndex: Int): Long {
    val delay = 500L
    val factor = 2.0
    val maxDelay = 10000L
    val wait = (delay * factor.pow(retryIndex)).toLong()
    return min(wait, maxDelay)
}

/**
 * The retry interval for network failures (milliseconds): **fixed at 3 seconds**.
 *
 * Mobile network jitter and transient relay 5xx/resets usually recover within 1-2 seconds; exponential backoff (0.5->1->2->4->8->10 seconds)
 * and the server's common `Retry-After: 23` only make the user wait. Retrying uniformly every 3 seconds, combined with the default limit of 6 retries,
 * means one stall costs at most about 18 extra seconds, rather than routinely over half a minute.
 */
const val NETWORK_RETRY_DELAY_MILLIS = 3_000L

/**
 * An error event received in an SSE stream, carrying an error code for retry decisions.
 *
 * Aligned with Codex CLI's error classification: cyber_policy / invalid_request / context_window_exceeded /
 * quota_exceeded / usage_not_included, etc. are non-retriable; server_error / server_is_overloaded, etc. are retriable by default.
 */
class StreamApiException(
    val code: String?,
    message: String,
    val retryAfterMillis: Long? = null
) : Exception(message.ifBlank { code ?: "stream error" })

// Aligned with Codex CLI's is_retryable(): these error codes are explicitly non-retriable.
// The rate-limit codes (rate_limit_*) are listed here so they go through multi-Key automatic switching first; when a single Key has no candidates, the caller retries with rate-limit backoff.
// Quota codes (quota_exceeded / insufficient_quota / usage_*, etc.) are not transient rate limiting; retrying is useless, so they remain non-retriable.
private val NON_RETRYABLE_STREAM_CODES = setOf(
    "cyber_policy",
    "invalid_request_error",
    "invalid_request",
    "context_window_exceeded",
    "quota_exceeded",
    "usage_not_included",
    "usage_limit_reached",
    "insufficient_quota",
    "rate_limit_exceeded",
    "rate_limit_error",
    "invalid_image_request",
    "response_too_large"
)

/**
 * Corresponds to opencode's isTransientError, and is also compatible with Android's network exception types.
 *
 * In addition to the traditional IOException check, it supports HTTP status code awareness:
 * - 408 (request timeout), 5xx (500/502/503/504, etc.) -> retriable (transient server failure)
 * - 429 (rate limiting) -> not handled here; handed to multi-Key automatic switching; when there is no Key to switch to, the caller retries with rate-limit backoff (see [isRateLimitError])
 * - Other 4xx -> not retried (client error; retrying is meaningless)
 */
fun isRetriableNetworkError(t: Throwable): Boolean {
    if (t is CancellationException) return false

    if (t is StreamApiException) {
        return !NON_RETRYABLE_STREAM_CODES.contains(t.code)
    }

    // Compatible with native network exceptions
    if (t is SocketTimeoutException || t is InterruptedIOException ||
        t is java.net.UnknownHostException || t is java.net.ConnectException ||
        t is javax.net.ssl.SSLException || t is IOException) {
        return true
    }

    // HTTP status code awareness: 408/5xx are treated as transient failures and retriable; 429 goes to multi-Key switching
    if (t is HttpException) {
        val code = t.code()
        return code == 408 || code >= 500
    }

    val message = t.message?.lowercase() ?: t.toString().lowercase()
    return TRANSIENT_MESSAGES.any { message.contains(it) }
}

/** In-stream rate-limit error codes (aligned with each provider's SSE error.code). */
private val RATE_LIMIT_STREAM_CODES = setOf(
    "rate_limit_exceeded",
    "rate_limit_error",
    "too_many_requests"
)

/** Rate-limit error text fallback: after an HttpException is wrapped by enrich into an IllegalStateException, it can only be identified by the message text. */
private val RATE_LIMIT_MESSAGES = listOf(
    "rate limit",
    "too many requests",
    "http 429"
)

/** The maximum depth to search along the cause chain, kept consistent with [isKeySwitchFailure]. */
private const val MAX_CAUSE_DEPTH = 5

/**
 * Determines whether a failure is a rate-limit error (HTTP 429 / in-stream rate_limit_* / server rate-limit text).
 *
 * 429 is handled by multi-Key automatic switching, so [isRetriableNetworkError] returns false for it; but when there is no Key
 * to switch to (single Key) and no text has been emitted yet, backoff retry is the correct handling -- 429 is transient rate limiting and often carries Retry-After.
 */
fun isRateLimitError(t: Throwable): Boolean {
    var current: Throwable? = t
    var depth = 0
    while (current != null && depth < MAX_CAUSE_DEPTH) {
        when (current) {
            is HttpException -> if (current.code() == 429) return true
            is StreamApiException -> if (current.code?.lowercase() in RATE_LIMIT_STREAM_CODES) return true
        }
        current = current.cause
        depth++
    }
    val message = t.message?.lowercase() ?: return false
    return RATE_LIMIT_MESSAGES.any { message.contains(it) }
}

/** The user-visible category of a retry error, used by the UI to show the specific reason (rather than a vague "network fluctuation"). */
enum class RetryErrorKind {
    /** HTTP 429 / server rate limit errors. */
    RATE_LIMIT,
    /** HTTP 503 server overload (or streaming error code server_is_overloaded / overloaded). */
    SERVER_OVERLOADED,
    /** HTTP 5xx (other than 503) and other server errors. */
    SERVER_ERROR,
    /** Connection/read timeout (including a stream cut-off triggered by the first-byte watchdog). */
    TIMEOUT,
    /** No service on the target port; the connection was refused. */
    CONNECTION_REFUSED,
    /** DNS resolution failed (the domain does not exist or the network is unreachable). */
    DNS_FAILED,
    /** Reset by the peer/intermediate device after the connection was established (stream interruption, unexpected end of stream, etc.). */
    CONNECTION_RESET,
    /** TLS/SSL handshake failed. */
    SSL_ERROR,
    /** Other network-layer failures. */
    NETWORK,
    /** Other errors that cannot be classified. */
    UNKNOWN
}

/**
 * The error summary for one retry, passed through to the UI along with the Retrying event.
 * [statusCode] is the HTTP status code (such as 429/500), or null for non-HTTP errors.
 */
data class RetryErrorInfo(
    val kind: RetryErrorKind,
    val statusCode: Int? = null
)

/** Classifies the exception that triggered the retry into an error summary the UI can display. */
fun Throwable.toRetryErrorInfo(): RetryErrorInfo = when {
    this is HttpException -> when {
        code() == 429 -> RetryErrorInfo(RetryErrorKind.RATE_LIMIT, code())
        code() == 503 -> RetryErrorInfo(RetryErrorKind.SERVER_OVERLOADED, code())
        code() >= 500 -> RetryErrorInfo(RetryErrorKind.SERVER_ERROR, code())
        else -> RetryErrorInfo(RetryErrorKind.UNKNOWN, code())
    }
    this is StreamApiException -> when (code) {
        "rate_limit_exceeded", "rate_limit_error", "insufficient_quota" -> RetryErrorInfo(RetryErrorKind.RATE_LIMIT)
        "server_is_overloaded", "overloaded" -> RetryErrorInfo(RetryErrorKind.SERVER_OVERLOADED)
        "server_error", "internal_error" -> RetryErrorInfo(RetryErrorKind.SERVER_ERROR)
        else -> RetryErrorInfo(RetryErrorKind.UNKNOWN)
    }
    this is SocketTimeoutException || this is InterruptedIOException -> RetryErrorInfo(RetryErrorKind.TIMEOUT)
    this is UnknownHostException -> RetryErrorInfo(RetryErrorKind.DNS_FAILED)
    this is ConnectException -> RetryErrorInfo(RetryErrorKind.CONNECTION_REFUSED)
    // SSLException / ConnectException / UnknownHostException are all IOException subclasses and must be matched before IOException
    this is SSLException -> RetryErrorInfo(RetryErrorKind.SSL_ERROR)
    this is IOException -> if (isConnectionReset(this)) {
        RetryErrorInfo(RetryErrorKind.CONNECTION_RESET)
    } else {
        RetryErrorInfo(RetryErrorKind.NETWORK)
    }
    else -> RetryErrorInfo(RetryErrorKind.UNKNOWN)
}

/** Common error messages for a stream interrupted by the peer/intermediate device; a match is classified as [RetryErrorKind.CONNECTION_RESET]. */
private val RESET_MESSAGES = listOf(
    "connection reset",
    "reset by peer",
    "broken pipe",
    "unexpected end of stream",
    "connection closed",
    "socket hang up",
    "econnreset",
    "stream closed",
    "eof"
)

private fun isConnectionReset(e: IOException): Boolean {
    val message = e.message?.lowercase() ?: return false
    return RESET_MESSAGES.any { message.contains(it) }
}

/**
 * Parses `Retry-After` from an [HttpException]'s response headers and returns the wait in milliseconds.
 *
 * `Retry-After` has two formats:
 * 1. A number of seconds (such as `"30"`) -> converted directly to milliseconds
 * 2. An HTTP date (such as `"Fri, 29 Jun 2026 10:00:00 GMT"`) -> the difference from the current time is calculated
 *
 * Additionally: an in-stream SSE error event ([StreamApiException]) may carry its own [StreamApiException.retryAfterMillis],
 * which takes precedence over the HTTP header.
 *
 * Any other type, or no `Retry-After` header -> returns null.
 * A parsing failure also returns null (falling back to the fixed [NETWORK_RETRY_DELAY_MILLIS]).
 */
fun extractRetryAfterMillis(t: Throwable): Long? {
    // The server-requested wait carried by the in-stream error event itself (in the SSE scenario there is no HTTP header to read)
    if (t is StreamApiException) {
        t.retryAfterMillis?.takeIf { it > 0 }?.let { return it }
    }
    if (t !is HttpException) return null
    val header = t.response()?.headers()?.get("Retry-After") ?: return null

    // Format 1: plain seconds
    header.toLongOrNull()?.let { seconds ->
        return seconds * 1000L
    }

    // Format 2: HTTP date (RFC 1123)
    return runCatching {
        val sdf = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("GMT")
        }
        val retryTime = sdf.parse(header)?.time ?: return null
        val delay = retryTime - System.currentTimeMillis()
        if (delay > 0) delay else null
    }.getOrNull()
}

/**
 * Calculates the retry wait time: **fixed at [NETWORK_RETRY_DELAY_MILLIS] (3 seconds)**.
 *
 * The server's `Retry-After` is adopted only when it is shorter than 3 seconds; long values common from relays, such as `retry in 23s`,
 * are all retried after 3 seconds -- waiting 23 seconds on mobile is a terrible experience, and in most cases the server limit has already lifted.
 *
 * @param retryIndex Reserved parameter, for future extension if the first/subsequent retry intervals need to be distinguished.
 */
fun retryDelayMillis(retryIndex: Int, error: Throwable): Long {
    val serverDelay = extractRetryAfterMillis(error)
    if (serverDelay != null && serverDelay > 0) {
        return min(serverDelay, NETWORK_RETRY_DELAY_MILLIS)
    }
    return NETWORK_RETRY_DELAY_MILLIS
}

/**
 * Retries [block] with exponential backoff (original method name kept), for non-streaming requests.
 *
 * @param onKeyFailure Multi-Key switching callback: called first when a failure is judged "non-retriable", with inputs
 *        (triggering failure, whether resending is allowed). Returning true means the caller has switched Keys, and the retry count can be reset and the request resent;
 *        when it returns false, rate-limit failures (429 / rate_limit_*) are retried with backoff, and all others throw the original exception.
 *        Network failures (408/5xx/timeout, etc.) do not trigger this callback.
 * @param onRetry Callback before a retry, with parameters (current retry count, maximum retry count); used to notify the upper layer that it is "retrying".
 *                Placed before [block] to ensure the trailing lambda of `retryStaircase { ... }` still binds to [block].
 * @param maxRetries The maximum number of retries (not counting the first request), passed in by the caller according to "Preferences -> Network"; 0 means no retry.
 */
suspend fun <T> retryStaircase(
    maxRetries: Int = MAX_NETWORK_RETRIES,
    onKeyFailure: (suspend (Throwable, Boolean) -> Boolean)? = null,
    onRetry: (suspend (attempt: Int, maxRetries: Int, error: RetryErrorInfo) -> Unit)? = null,
    block: suspend () -> T
): T {
    var attempt = 0
    while (true) {
        try {
            return block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            coroutineContext.ensureActive()
            if (!isRetriableNetworkError(e)) {
                if (onKeyFailure?.invoke(e, true) == true) {
                    attempt = 0
                    continue
                }
                // Rate-limit backoff retry when there is no Key to switch to (single Key): 429 is transient rate limiting, and waiting then retrying is better than failing directly
                if (!isRateLimitError(e)) throw e
            }
            if (attempt >= maxRetries) throw e
            val wait = retryDelayMillis(attempt, e)
            FileLogger.w(TAG, "Network request failed, retry ${attempt + 1}/$maxRetries (waiting ${wait}ms): ${e.javaClass.simpleName} ${e.message}")
            onRetry?.invoke(attempt + 1, maxRetries, e.toRetryErrorInfo())
            attempt++
            if (wait > 0) delay(wait)
        }
    }
}

/**
 * Retry wrapper for streaming requests (original method name kept).
 *
 * @param onKeyFailure Multi-Key switching callback: called first when a failure is judged "non-retriable", with inputs
 *        (triggering failure, whether resending is allowed). Resending is allowed only when this attempt has not yet received any content;
 *        once text has been emitted, a switch may still happen (so subsequent requests use the new Key) but there is no resend, to avoid the user seeing duplicated content.
 *        When it returns false, rate-limit failures that have not yet emitted text are retried with backoff, and all others throw the original exception.
 * @param onRetry Callback before a retry, with parameters (current retry count, maximum retry count); used to notify the upper layer that it is "retrying".
 *                The callback is invoked before the delay, ensuring the UI can show the retry state immediately. Declared suspend so that
 *                the caller can push retry events through Flow's emit() inside it.
 * @param attemptOnce A single streaming request; after content is received it calls onContent, to judge whether resending is allowed when switching Keys.
 * @param maxRetries The maximum number of retries (not counting the first request), passed in by the caller according to "Preferences -> Network"; 0 means no retry.
 */
suspend fun streamWithStaircaseRetry(
    maxRetries: Int = MAX_NETWORK_RETRIES,
    onKeyFailure: (suspend (Throwable, Boolean) -> Boolean)? = null,
    attemptOnce: suspend (onContent: () -> Unit) -> Unit,
    onRetry: (suspend (attempt: Int, maxRetries: Int, error: RetryErrorInfo) -> Unit)? = null
) {
    var attempt = 0
    while (true) {
        var receivedContent = false
        try {
            attemptOnce { receivedContent = true }
            return
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            coroutineContext.ensureActive()
            if (!isRetriableNetworkError(e)) {
                if (onKeyFailure?.invoke(e, !receivedContent) == true) {
                    attempt = 0
                    continue
                }
                // Rate-limited, no text emitted yet, and no Key to switch to (single Key) -> retry with backoff; once text has been emitted there is no resend, keeping the original behavior
                if (!(isRateLimitError(e) && !receivedContent)) throw e
            }
            if (attempt >= maxRetries) throw e
            val wait = retryDelayMillis(attempt, e)
            FileLogger.w(TAG, "Streaming request failed, retry ${attempt + 1}/$maxRetries (waiting ${wait}ms): ${e.javaClass.simpleName} ${e.message}")
            onRetry?.invoke(attempt + 1, maxRetries, e.toRetryErrorInfo())
            attempt++
            if (wait > 0) delay(wait)
        }
    }
}
