package com.aicode.core.util

import java.io.Closeable
import java.io.Reader

/**
 * Maximum single-line output (characters). When `BufferedReader.readLine()` encounters a very long single line (a command dumping a binary/large file to
 * stdout, such as `cat` on an executable or `base64` on a large file) it loads the whole line into memory and OOMs directly on the device.
 * Exceeding the limit truncates and discards the rest of the line, keeping memory bounded.
 */
internal const val MAX_STREAM_LINE_CHARS = 64 * 1024

/** Note line appended after a very long line is truncated. */
internal const val LINE_TRUNCATED_NOTE = "[Line output too long, truncated]"

/** One line of output; [truncated] is true if the line exceeded [MAX_STREAM_LINE_CHARS] and was truncated. */
internal class StreamLine(val text: String, val truncated: Boolean)

/**
 * Reads [reader] line by line, with line-splitting semantics matching [java.io.BufferedReader.readLine] (`\n` / `\r` / `\r\n`),
 * but caps a single line at [maxChars]: after reaching the cap it stops accumulating, continues consuming and discarding the rest of the line, avoiding OOM from a very long line.
 */
internal class BoundedLineReader(
    private val reader: Reader,
    private val maxChars: Int = MAX_STREAM_LINE_CHARS
) : Closeable {
    private val buf = CharArray(8192)
    private var pos = 0
    private var len = 0
    private var skipLf = false

    override fun close() = reader.close()

    fun readLine(): StreamLine? {
        val sb = StringBuilder()
        var truncated = false
        var sawContent = false
        while (true) {
            if (pos >= len) {
                if (len == -1) return if (sawContent) StreamLine(sb.toString(), truncated) else null
                len = reader.read(buf)
                pos = 0
                continue
            }
            val c = buf[pos++]
            if (skipLf) {
                skipLf = false
                if (c == '\n') continue
            }
            when (c) {
                '\n' -> return StreamLine(sb.toString(), truncated)
                '\r' -> {
                    skipLf = true
                    return StreamLine(sb.toString(), truncated)
                }
                else -> {
                    sawContent = true
                    if (sb.length < maxChars) sb.append(c) else truncated = true
                }
            }
        }
    }
}

/**
 * Lazy line-by-line reading: only materialises the current line each time (single-line cap [MAX_STREAM_LINE_CHARS]), reads as far as consumed, and never loads the whole file into memory.
 *
 * Each iteration calls [open] to reopen the reader, so the sequence can be iterated repeatedly; it closes automatically when an iteration is fully consumed or throws.
 */
internal fun boundedLines(open: () -> Reader): Sequence<String> = sequence {
    BoundedLineReader(open()).use { reader ->
        while (true) {
            val line = reader.readLine() ?: break
            yield(line.text)
        }
    }
}