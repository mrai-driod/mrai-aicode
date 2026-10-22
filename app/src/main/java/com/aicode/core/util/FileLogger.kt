package com.aicode.core.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter

import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Log level, from low to high. When [NONE] is used as the threshold it disables all output (no level is >= NONE).
 *
 * The order is the severity: logs below the [FileLogger.minLevel] threshold (both logcat and disk) are discarded.
 */
enum class LogLevel {
    VERBOSE, DEBUG, INFO, WARN, ERROR, NONE
}

/**
 * Writes logs to the app's internal storage, for debugging without an adb connection.
 *
 * Logs are written under the external private directory `getExternalFilesDir/logs/` (falling back to the internal `filesDir/logs/` when unavailable),
 * split into one file per day (log-yyyy-MM-dd.txt). The external private directory can be viewed in a file manager at
 * `/storage/emulated/0/Android/data/<package name>/files/logs/` directly, without root.
 * All writes are serialised onto a single background thread, avoiding blocking the main thread and multi-thread interleaving. A copy is also mirrored to [android.util.Log].
 *
 * Supports filtering by level: logs below [minLevel] (including the logcat mirror and disk write) are all skipped. The level is
 * persisted by `LogSettingsRepository`, and `AIEditorApp` syncs it via [setMinLevel] on startup and when settings change.
 * Defaults to [LogLevel.VERBOSE] during development (full logging).
 *
 * Call [init] once in [Application.onCreate] before use.
 */
object FileLogger {

    private const val TAG = "FileLogger"
    private const val MAX_AGE_DAYS = 7
    private const val MAX_FILE_BYTES = 5 * 1024 * 1024 // Per-log-file limit 5MB (grows quickly under VERBOSE)
    /** Coalesced flush delay: after a batch of logs has been quiet for this long it is flushed to disk. ERROR level does not wait for the delay and flushes immediately. */
    private const val FLUSH_DELAY_MS = 500L

    /** Runs all disk writes serially on a single thread, and also serves as the scheduler for the delayed coalesced flush. */
    private val ioExecutor = ScheduledThreadPoolExecutor(1) { r ->
        Thread(r, "file-logger").apply { isDaemon = true }
    }
    private val fileNameFormat = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(java.time.ZoneId.systemDefault())
    private val timestampFormat = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(java.time.ZoneId.systemDefault())

    @Volatile
    private var logDir: File? = null

    // The following fields are only accessed by the ioExecutor single thread and need no synchronisation.
    private var writer: java.io.BufferedWriter? = null
    private var writerDate: String? = null
    private var flushScheduled = false

    /** Current minimum log level; logs below it are all skipped. Defaults by build type: debug=VERBOSE (full during development), release=INFO. */
    @Volatile
    var minLevel: LogLevel = LogLevel.VERBOSE
        private set

    /** Sets the minimum log level (thread-safe). Called by the settings item / startup sync. */
    fun setMinLevel(level: LogLevel) {
        if (minLevel != level) {
            minLevel = level
            // The level change itself is logged via logcat, to avoid being filtered out by the new threshold
            Log.i(TAG, "Log level switched to $level")
        }
    }

    private fun shouldLog(level: LogLevel): Boolean = level.ordinal >= minLevel.ordinal

    /** Initialises the log directory. Safe to call repeatedly. */
    fun init(context: Context) {
        if (logDir != null) return
        // Prefers the external private directory so users can view it in a file manager; falls back to internal storage when unavailable.
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = File(base, "logs").apply { mkdirs() }
        logDir = dir
        // The default level falls back by build type: release does not enable full VERBOSE (frequent AI tool logs would keep writing to disk).
        // Only applies if it has never been set -- user/persisted settings later override it via setMinLevel and always take precedence.
        val debuggable = (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (minLevel == LogLevel.VERBOSE) {
            minLevel = if (debuggable) LogLevel.VERBOSE else LogLevel.INFO
        }
        ioExecutor.execute { cleanupOldLogs(dir) }
        i(TAG, "FileLogger initialised, log directory: ${dir.absolutePath}")
    }

    fun v(tag: String, message: String) {
        if (!shouldLog(LogLevel.VERBOSE)) return
        Log.v(tag, message)
        write("VERBOSE", tag, message, null)
    }

    fun d(tag: String, message: String) {
        if (!shouldLog(LogLevel.DEBUG)) return
        Log.d(tag, message)
        write("DEBUG", tag, message, null)
    }

    fun i(tag: String, message: String) {
        if (!shouldLog(LogLevel.INFO)) return
        Log.i(tag, message)
        write("INFO", tag, message, null)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        if (!shouldLog(LogLevel.WARN)) return
        Log.w(tag, message, throwable)
        write("WARN", tag, message, throwable)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (!shouldLog(LogLevel.ERROR)) return
        Log.e(tag, message, throwable)
        write("ERROR", tag, message, throwable)
    }

    /** Returns all current log files, sorted by file name (i.e. date), for use by screens such as "View logs". */
    fun listLogFiles(): List<File> {
        val dir = logDir ?: return emptyList()
        return dir.listFiles { f -> f.isFile && f.name.startsWith("log-") }
            ?.sortedBy { it.name }
            ?: emptyList()
    }

    /**
     * Deletes all log files and returns the number of bytes freed.
     *
     * The deletion must be queued onto [ioExecutor]: today's file is held open by [writer], and without first closing it and clearing the date marker,
     * subsequent writes would keep landing in the deleted inode, silently losing logs until the file rolls over the next day.
     */
    fun clearLogs(): Long {
        val dir = logDir ?: return 0L
        val freed = java.util.concurrent.atomic.AtomicLong(0)
        val latch = java.util.concurrent.CountDownLatch(1)
        ioExecutor.execute {
            runCatching {
                writer?.close()
                writer = null
                writerDate = null
                flushScheduled = false
                dir.listFiles { f -> f.isFile && f.name.startsWith("log-") }?.forEach { file ->
                    val size = file.length()
                    if (file.delete()) freed.addAndGet(size)
                }
            }.onFailure { Log.e(TAG, "Failed to clear logs", it) }
            latch.countDown()
        }
        runCatching { latch.await(5, java.util.concurrent.TimeUnit.SECONDS) }
        return freed.get()
    }

    private fun write(level: String, tag: String, message: String, throwable: Throwable?) {
        val dir = logDir ?: return // If not initialised, only logcat is used, nothing is written to disk
        val now = java.time.Instant.now()
        // Media redaction before writing to disk: prevents oversized base64 (images, etc.) from blowing up the daily log file; logcat still prints the original (protected by system truncation).
        val redacted = MediaRedactor.redact(message)
        val line = buildString {
            append(timestampFormat.format(now))
            append(" ").append(level)
            append(" [").append(tag).append("] ")
            append(redacted)
            if (throwable != null) {
                append("\n").append(stackTraceToString(throwable))
            }
            append("\n")
        }
        ioExecutor.execute {
            runCatching {
                val date = fileNameFormat.format(now)
                val file = File(dir, "log-$date.txt")
                // Rolls to a new file across days; if a single file exceeds the limit it is reset and reopened. writer is only accessed on this thread.
                // Always opened in append mode: a process restart or a second launch on the same day must not clear logs already written.
                if (writerDate != date) {
                    writer?.close()
                    writer = FileOutputStream(file, true).bufferedWriter()
                    writerDate = date
                } else if (file.length() > MAX_FILE_BYTES) {
                    writer?.close()
                    file.writeText("--- Log file exceeded ${MAX_FILE_BYTES / 1024 / 1024}MB, reset ---\n")
                    writer = FileOutputStream(file, true).bufferedWriter()
                }
                writer?.append(line)
                // ERROR flushes to disk immediately; the rest are coalesced and flushed together after FLUSH_DELAY_MS.
                // If the process is killed by the system while buffering, the last few hundred milliseconds of logs are lost, so it must not accumulate to a larger threshold.
                if (level == "ERROR") {
                    flushWriter()
                } else if (!flushScheduled) {
                    flushScheduled = true
                    ioExecutor.schedule({ runCatching { flushWriter() } }, FLUSH_DELAY_MS, TimeUnit.MILLISECONDS)
                }
            }.onFailure {
                Log.e(TAG, "Failed to write log", it)
            }
        }
    }

    /**
     * Synchronously flushes buffered logs (called from emergency paths such as the crash handler).
     * Submitted to the tail of the ioExecutor queue to guarantee previously queued appends have been written; the calling thread blocks for at most 2s.
     */
    fun flushSync() {
        val latch = java.util.concurrent.CountDownLatch(1)
        ioExecutor.execute {
            flushWriter()
            latch.countDown()
        }
        runCatching { latch.await(2, java.util.concurrent.TimeUnit.SECONDS) }
    }

    /** Pushes buffered logs to the underlying file. Only callable on the [ioExecutor] single thread. */
    private fun flushWriter() {
        runCatching { writer?.flush() }
        flushScheduled = false
    }

    private fun stackTraceToString(throwable: Throwable): String {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        return sw.toString().trimEnd()
    }

    /** Deletes log files older than [MAX_AGE_DAYS] days. */
    private fun cleanupOldLogs(dir: File) {
        val cutoff = System.currentTimeMillis() - MAX_AGE_DAYS * 24L * 60 * 60 * 1000
        dir.listFiles { f -> f.isFile && f.name.startsWith("log-") }?.forEach { file ->
            if (file.lastModified() < cutoff) {
                runCatching { file.delete() }
            }
        }
    }
}
