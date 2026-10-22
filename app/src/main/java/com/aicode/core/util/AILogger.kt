package com.aicode.core.util

import android.content.Context
import android.util.Log
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.io.Writer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * AI provider "full request / response" log: **one file per session (sessionId)**, writing each call in detail to disk,
 * with its URL, request body and response, for offline diagnosis of model interaction problems without packet-capture tools.
 *
 * Independent from [FileLogger] (the general app log split by day): this class archives by "session", is larger,
 * and more complete (including full conversation history, tool definitions, and the raw SSE stream), so it is kept in separate files and cleaned up separately.
 *
 * Files land in the external private directory `getExternalFilesDir/ai-logs/session-<id>.log` (falling back to the internal
 * `filesDir/ai-logs/` when unavailable). All writes are serialised onto a single background thread and do not block the caller's coroutine.
 * The request body contains no API Key (the secret is in the HTTP header; this class only records the URL and body), so it is safe to retain.
 *
 * No length truncation: request/response bodies are serialised on the write thread and written directly to disk without building one large string; raw SSE is written in batches
 * -- so long histories / large attachments do not OOM either, and the log stays complete for reproducing problems. Oversized base64 media (images, etc.)
 * is redacted to a placeholder before writing based on the **JSON structure** (field name + value shape), to avoid a single line of several MB blowing up the log file.
 *
 * Call [init] once in [android.app.Application.onCreate] before use.
 */
object AILogger {

    private const val TAG = "AILogger"
    private const val MAX_AGE_DAYS = 7
    private const val MAX_FILE_BYTES = 20 * 1024 * 1024 // Per-session file limit 20MB (the full history is resent each turn, so it grows fast)
    /** Buffer threshold (characters) for writing raw SSE in batches: once this amount accumulates it is flushed to disk, avoiding keeping the whole response in memory. */
    private const val SSE_FLUSH_CHARS = 64 * 1024
    /** When a field name hints at base64 content, a value longer than this is treated as media data. */
    private const val BASE64_MIN_CHARS = 256
    /** Fallback: a run of consecutive base64 characters anywhere longer than this is treated as media data (regardless of the field name). */
    private const val BASE64_RUN_CHARS = 1024

    /** Common field names carrying base64 (Anthropic `source.data`, attachment `base64Data`, OpenAI `image_url.url`, etc.). */
    private val BASE64_FIELD_NAMES = setOf("data", "base64Data", "image_data", "url")

    private val ioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ai-logger").apply { isDaemon = true }
    }
    private val timestampFormat = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(java.time.ZoneId.systemDefault())
    // Aligns with Retrofit's GsonConverter behaviour (default field names, ignore nulls), additionally enabling indentation for readability,
    // and disables HTML escaping to avoid turning < > & in prompts into entities and hurting readability.
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    @Volatile
    private var logDir: File? = null

    /** Per-session call counter: used to give the REQUEST / RESPONSE of the same interaction the same sequence number. */
    private val counters = ConcurrentHashMap<String, AtomicInteger>()

    /** Initialises the log directory. Safe to call repeatedly. */
    fun init(context: Context) {
        if (logDir != null) return
        // Prefers the external private directory for easy retrieval (under root or adb); falls back to internal storage when unavailable.
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = File(base, "ai-logs").apply { mkdirs() }
        logDir = dir
        ioExecutor.execute { cleanupOldLogs(dir) }
        FileLogger.i(TAG, "AILogger initialised, AI session log directory: ${dir.absolutePath}")
    }

    /**
     * Logs one request's URL and body, and increments this session's counter by 1 (as this interaction's sequence number).
     *
     * @return The sequence number `n` allocated this time; the caller must pass it unchanged to the corresponding [logResponse] /
     *   [logError] / [beginRawSse], otherwise concurrent requests in the same session (such as title generation in parallel with the main request)
     *   will mismatch the REQUEST and RESPONSE/ERROR numbers -- a late response would read the latest counter value.
     */
    fun logRequest(sessionId: String?, provider: String, model: String, method: String, url: String, body: Any?): Int {
        val n = counter(sessionId).incrementAndGet()
        appendToSession(sessionId) { w ->
            w.write("\n")
            w.write("=".repeat(78))
            w.write("\n")
            w.write("${now()}  REQUEST #$n   [$provider / $model]\n")
            w.write("$method $url\n")
            w.write("--- request body ---\n")
            writeBody(w, body)
            w.write("\n")
        }
        return n
    }

    /** Logs one non-streaming response object (serialised to JSON with Gson). [seq] must come from the corresponding [logRequest] return value. */
    fun logResponse(sessionId: String?, provider: String, body: Any?, seq: Int) {
        appendToSession(sessionId) { w ->
            w.write("${now()}  RESPONSE #$seq   [$provider]\n")
            w.write("--- response body ---\n")
            writeBody(w, body)
            w.write("\n")
        }
    }

    /** Logs one request failure (cancellation is not a failure and should not reach here). [seq] must come from the corresponding [logRequest] return value. */
    fun logError(sessionId: String?, provider: String, throwable: Throwable, seq: Int) {
        appendToSession(sessionId) { w ->
            w.write("${now()}  ERROR #$seq   [$provider]\n")
            w.write("${throwable.javaClass.name}: ${throwable.message ?: ""}\n")
        }
    }

    /**
     * Opens a raw SSE log handle for one streaming response: the caller calls [RawSseLog.append] for each line received, and at the end of the turn
     * (success / failure / cancellation) calls [RawSseLog.finish] in `finally`. Content is written in batches and does not stay in memory as a whole.
     *
     * @param seq Must come from the corresponding [logRequest] return value.
     */
    fun beginRawSse(sessionId: String?, provider: String, seq: Int): RawSseLog =
        RawSseLog(sessionId, provider, seq)

    /** Raw SSE log buffer handle for one streaming response; flushes to disk in batches once the threshold is reached. */
    class RawSseLog internal constructor(
        private val sessionId: String?,
        private val provider: String,
        private val seq: Int
    ) {
        private val buffer = StringBuilder()
        private var headerWritten = false

        fun append(line: String) {
            buffer.append(redactString(line, null)).append('\n')
            if (buffer.length >= SSE_FLUSH_CHARS) flush()
        }

        fun finish() {
            if (buffer.isEmpty() && !headerWritten) {
                headerWritten = true
                writeChunk(header() + "(empty response)\n")
            } else {
                flush()
            }
        }

        private fun flush() {
            if (buffer.isEmpty() && headerWritten) return
            val chunk = buffer.toString()
            buffer.setLength(0)
            val prefix = if (!headerWritten) {
                headerWritten = true
                header()
            } else ""
            writeChunk(prefix + chunk)
        }

        private fun header(): String =
            "${now()}  RESPONSE #$seq   [$provider / stream]\n--- raw SSE ---\n"

        private fun writeChunk(text: String) {
            appendToSession(sessionId) { w -> w.write(text) }
        }
    }

    private fun counter(sessionId: String?): AtomicInteger =
        counters.getOrPut(sessionId ?: "unknown") { AtomicInteger(0) }

    /** Returns all current session log files, sorted by file name, for usage statistics and cleanup. */
    fun listLogFiles(): List<File> {
        val dir = logDir ?: return emptyList()
        return dir.listFiles { f -> f.isFile && f.name.startsWith("session-") }
            ?.sortedBy { it.name }
            ?: emptyList()
    }

    /**
     * Deletes all session logs and returns the number of bytes freed.
     *
     * Queued onto [ioExecutor] to avoid interleaving with queued append writes (each write in this class appends then closes, holding no handle).
     * Also resets the call sequence, so new logs after clearing start from #1.
     */
    fun clearLogs(): Long {
        val dir = logDir ?: return 0L
        val freed = java.util.concurrent.atomic.AtomicLong(0)
        val latch = java.util.concurrent.CountDownLatch(1)
        ioExecutor.execute {
            runCatching {
                dir.listFiles { f -> f.isFile && f.name.startsWith("session-") }?.forEach { file ->
                    val size = file.length()
                    if (file.delete()) freed.addAndGet(size)
                }
                counters.clear()
            }.onFailure { Log.e(TAG, "Failed to clear AI session logs", it) }
            latch.countDown()
        }
        runCatching { latch.await(5, java.util.concurrent.TimeUnit.SECONDS) }
        return freed.get()
    }

    private fun now(): String = timestampFormat.format(java.time.Instant.now())

    /** Redacts the body and streams it into [w]: objects go through Gson tree serialisation (without building one large string). */
    private fun writeBody(w: Writer, body: Any?) {
        when (body) {
            null -> w.write("null")
            is String -> w.write(redactString(body, null))
            else -> {
                val tree = runCatching { gson.toJsonTree(body) }.getOrNull()
                if (tree == null) {
                    w.write(redactString(body.toString(), null))
                } else {
                    gson.toJson(redactInPlace(tree), w)
                }
            }
        }
    }

    /** For tests: serialises the body to JSON text using the log redaction rules (without writing to disk). */
    internal fun redactToJson(body: Any?): String =
        java.io.StringWriter().also { writeBody(it, body) }.toString()

    /** Recursively walks the JSON tree, replacing media base64 in place with placeholders (the tree is freshly created by [GsonBuilder] and safe to modify). */
    private fun redactInPlace(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> {
            element.keySet().toList().forEach { key ->
                val value = element.get(key)
                when {
                    value is JsonObject || value is JsonArray -> redactInPlace(value)
                    value is JsonPrimitive && value.isString ->
                        element.add(key, JsonPrimitive(redactString(value.asString, key)))
                }
            }
            element
        }
        is JsonArray -> {
            for (i in 0 until element.size()) {
                val value = element.get(i)
                when {
                    value is JsonObject || value is JsonArray -> redactInPlace(value)
                    value is JsonPrimitive && value.isString ->
                        element.set(i, JsonPrimitive(redactString(value.asString, null)))
                }
            }
            element
        }
        else -> element
    }

    /**
     * Redaction of a single string value: replaces inline data URLs, bare base64 judged by field name, and very long base64 runs anywhere
     * with placeholders. Matches only within a single value, not across fields, avoiding the fragility of full-text regexes that "miss when the format changes".
     */
    private fun redactString(value: String, key: String?): String {
        if (value.isEmpty()) return value
        var out = DATA_URL_REGEX.replace(value) { m -> "[base64 omitted: ${m.value.length} chars]" }
        if (key != null && key in BASE64_FIELD_NAMES && out.length >= BASE64_MIN_CHARS && out.all { it.isBase64Char() }) {
            return "[base64 omitted: ${out.length} chars]"
        }
        out = LONG_BASE64_RUN_REGEX.replace(out) { m -> "[base64 omitted: ${m.value.length} chars]" }
        return out
    }

    private fun Char.isBase64Char(): Boolean =
        this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9' ||
            this == '+' || this == '/' || this == '=' || this == '_' || this == '-'

    /** Opens the session log file (append mode) on the background single thread and runs the write block; if the file exceeds the limit it is reset first. */
    private fun appendToSession(sessionId: String?, block: (Writer) -> Unit) {
        val dir = logDir ?: return // If not initialised, discard directly, to avoid spamming errors when there is no directory
        val safeId = (sessionId ?: "unknown").replace(Regex("[^A-Za-z0-9_-]"), "_")
        ioExecutor.execute {
            runCatching {
                val file = File(dir, "session-$safeId.log")
                if (file.length() > MAX_FILE_BYTES) {
                    // If over the limit, truncate and reopen, to avoid a single file growing without bound.
                    file.writeText("--- AI session log exceeded ${MAX_FILE_BYTES / 1024 / 1024}MB, reset ---\n")
                }
                BufferedWriter(OutputStreamWriter(FileOutputStream(file, true), Charsets.UTF_8)).use { w ->
                    block(w)
                    w.flush()
                }
            }.onFailure { Log.e(TAG, "Failed to write AI session log", it) }
        }
    }

    /** Deletes session log files not updated for more than [MAX_AGE_DAYS] days. */
    private fun cleanupOldLogs(dir: File) {
        val cutoff = System.currentTimeMillis() - MAX_AGE_DAYS * 24L * 60 * 60 * 1000
        dir.listFiles { f -> f.isFile && f.name.startsWith("session-") }?.forEach { file ->
            if (file.lastModified() < cutoff) runCatching { file.delete() }
        }
    }

    /** Inline base64 data URL (`data:<mime>;base64,...`, mime may be empty/anything). */
    private val DATA_URL_REGEX = Regex("data:[A-Za-z0-9.+/-]*;base64,[A-Za-z0-9+/=_-]{$BASE64_MIN_CHARS,}")
    /** Fallback: a run of >= [BASE64_RUN_CHARS] consecutive base64 characters anywhere. */
    private val LONG_BASE64_RUN_REGEX = Regex("[A-Za-z0-9+/=_-]{$BASE64_RUN_CHARS,}")
}
