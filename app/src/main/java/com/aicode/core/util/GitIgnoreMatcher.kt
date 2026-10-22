package com.aicode.core.util

/**
 * Gitignore-style path matching (a moderate implementation, not a full spec parser):
 * - `*.ext`:matches files/directories with the same extension at any level;
 * - `**` wildcard prefix: starts at any level;
 * - multi-segment patterns (such as `build/*.log`): match at any position in the path-segment sequence;
 * - Leading `/` anchoring: when [anchored] is true and there is no `**/` prefix, the pattern must match starting from the root path segment;
 *   when false, it is conservatively treated as unanchored (prefer over-ignoring rather than missing an ignore due to a parse failure).
 * Matching is based on the full path-segment sequence; callers must pass path segments relative to the workspace root.
 */
object GitIgnoreMatcher {

    /** Ignored if any pattern matches. */
    fun isIgnored(patterns: List<String>, parts: List<String>, anchored: Boolean = false): Boolean =
        patterns.any { matches(it, parts, anchored) }

    fun matches(pattern: String, parts: List<String>, anchored: Boolean = false): Boolean {
        var p = pattern.trim().trimEnd('/').trimStart('/')
        if (p.isEmpty()) return false
        // Double-star wildcard prefix: starts at any level (anchored is ignored here, `**/` itself has unanchored semantics)
        val recursive = p.startsWith("**/")
        if (recursive) p = p.removePrefix("**/")
        if (p.isEmpty()) return false
        // *.ext file pattern: matches a file name at any level
        if (p.startsWith("*.") && '/' !in p) {
            val ext = p.removePrefix("*.")
            return parts.any { it.endsWith(ext) }
        }
        val segs = p.split('/')
        if (segs.size > parts.size) return false
        val starts = if (anchored && !recursive) 0..0 else 0..(parts.size - segs.size)
        return starts.any { start ->
            segs.indices.all { i -> segMatch(segs[i], parts[start + i]) }
        }
    }

    /** Single-segment matching: `**` and `*` wildcard segments, and exact segments. */
    private fun segMatch(pattern: String, segment: String): Boolean {
        if (pattern == "**") return true
        if ('*' !in pattern) return pattern == segment
        // Manual escaping: regex special characters get a backslash, `*` expands directly to `.*`.
        // (Regex.escape in Kotlin 2.x returns the \Q...\E literal form, so wildcards can no longer be expanded via replace)
        val escaped = buildString {
            for (ch in pattern) {
                if (ch == '*') append(".*")
                else if (ch in ".+?()[]{}\\^$|") append('\\').append(ch)
                else append(ch)
            }
        }
        return Regex("^$escaped$").matches(segment)
    }
}
