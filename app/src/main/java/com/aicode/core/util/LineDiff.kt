package com.aicode.core.util

/**
 * Line-level text diff (LCS). Used to render an edit_file old_string->new_string change
 * as a "+added / -removed / context" diff view, similar to Claude's diff display.
 *
 * Unified text format: column 0 of each line is the marker (`+` added / `-` removed / ` ` context),
 * the rest is the original line content. This avoids ambiguity even if the code itself starts with +/- -- when parsing, only the first column is taken as the marker,
 * and the remaining substring(1) is the original text.
 */
object LineDiff {

    enum class LineType { ADD, REMOVE, CONTEXT }

    data class DiffLine(val type: LineType, val text: String)

    /** Computes the line-level diff from [oldText] to [newText]. */
    fun diff(oldText: String, newText: String): List<DiffLine> {
        val a = oldText.split("\n")
        val b = newText.split("\n")
        val n = a.size
        val m = b.size

        // LCS length table: dp[i][j] = length of the longest common subsequence of a[i..] and b[j..].
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                dp[i][j] = if (a[i] == b[j]) {
                    dp[i + 1][j + 1] + 1
                } else {
                    maxOf(dp[i + 1][j], dp[i][j + 1])
                }
            }
        }

        val out = ArrayList<DiffLine>(n + m)
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                a[i] == b[j] -> { out.add(DiffLine(LineType.CONTEXT, a[i])); i++; j++ }
                dp[i + 1][j] >= dp[i][j + 1] -> { out.add(DiffLine(LineType.REMOVE, a[i])); i++ }
                else -> { out.add(DiffLine(LineType.ADD, b[j])); j++ }
            }
        }
        while (i < n) { out.add(DiffLine(LineType.REMOVE, a[i])); i++ }
        while (j < m) { out.add(DiffLine(LineType.ADD, b[j])); j++ }
        return out
    }

    /** Renders as unified text: each line starts with `+`/`-`/` `. */
    fun toUnified(oldText: String, newText: String): String =
        diff(oldText, newText).joinToString("\n") { line ->
            when (line.type) {
                LineType.ADD -> "+${line.text}"
                LineType.REMOVE -> "-${line.text}"
                LineType.CONTEXT -> " ${line.text}"
            }
        }
}
