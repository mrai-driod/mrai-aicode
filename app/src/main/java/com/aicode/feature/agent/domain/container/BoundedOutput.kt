package com.aicode.feature.agent.domain.container

/**
 * Hard upper limit (in characters) on the output of uncapped commands (git diff, file contents, etc.): output
 * beyond it is truncated and [CommandResult.outputTruncated] is set, so unbounded output cannot exhaust memory.
 */
const val MAX_UNBOUNDED_CHARS = 8 * 1024 * 1024

/**
 * Accumulates command output while limiting memory use: keeps the first [headLimit] characters and the last
 * [tailLimit] characters and discards the middle, so huge output (such as `cat` on a large file, or install logs)
 * cannot exhaust memory, the model context, or UI rendering.
 *
 * Why keep both head and tail: the beginning usually holds the command intent and starting information, and the
 * end usually holds errors and the final result; both are the most valuable parts for the model and the user.
 * The mass of repetitive output in the middle can safely be omitted, with the number of discarded characters noted at the omission point.
 *
 * Thread safety: append is called from coroutines concurrently reading stdout/stderr, so it is synchronized at method level.
 */
class BoundedOutput(
    private val headLimit: Int = DEFAULT_HEAD,
    private val tailLimit: Int = DEFAULT_TAIL
) {
    private val head = StringBuilder()
    // Sliding window keeping only the most recent tailLimit characters (excess is dropped from the head).
    private val tail = StringBuilder()
    private var total = 0L

    /** Total number of raw characters received (before truncation). */
    val totalChars: Long
        @Synchronized get() = total

    /** Whether middle truncation occurred (received amount exceeded the head+tail capacity). */
    val truncated: Boolean
        @Synchronized get() = total > head.length.toLong() + tail.length.toLong()

    @Synchronized
    fun append(text: String) {
        if (text.isEmpty()) return
        total += text.length
        if (head.length < headLimit) {
            val room = headLimit - head.length
            if (text.length <= room) {
                head.append(text)
                return
            }
            // Fill the head first; any overflow goes into the tail window.
            head.append(text, 0, room)
            appendTail(text, room, text.length)
        } else {
            appendTail(text, 0, text.length)
        }
    }

    private fun appendTail(text: String, start: Int, end: Int) {
        tail.append(text, start, end)
        if (tail.length > tailLimit) tail.delete(0, tail.length - tailLimit)
    }

    /** Builds the final text: the complete output when not truncated; head + omission note + tail when truncated. */
    @Synchronized
    fun build(): String {
        if (!truncated) return head.toString() + tail.toString()
        val omitted = total - head.length - tail.length
        return buildString {
            append(head)
            if (tail.isEmpty()) {
                append("\n\n…[Output too long; omitted the following ")
                append(omitted)
                append(" characters]…")
            } else {
                append("\n\n…[Output too long; omitted the middle ")
                append(omitted)
                append(" characters; kept only the beginning and end]…\n\n")
                append(tail)
            }
        }
    }

    companion object {
        const val DEFAULT_HEAD = 20_000
        const val DEFAULT_TAIL = 20_000

        /**
         * Hard-cap mode that keeps only the beginning, up to [maxChars] characters (tail capacity is 0).
         *
         * Unlike the default head+tail mode, the omission note appears only at the **end**, because callers
         * (git diff / file contents) parse the output line by line, and inserting a note in the middle would break parsing.
         */
        fun hardCapped(maxChars: Int): BoundedOutput = BoundedOutput(maxChars, 0)
    }
}
