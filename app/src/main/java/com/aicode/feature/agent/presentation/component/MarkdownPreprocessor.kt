package com.aicode.feature.agent.presentation.component

import org.jsoup.parser.Parser
import java.util.Base64

/**
 * Lightweight preprocessing applied before handing text to the Markdown renderer:
 *
 * 1. Extracts LaTeX math (`$$...$$` block-level, `$...$` inline) and encodes it as Markdown image links with a special scheme,
 *    to be rendered by [MathImageTransformer] with jlatexmath.
 * 2. Decodes HTML entities and shows embedded HTML tags as literal text - the chat stream does not render HTML,
 *    and does no HTML-to-Markdown syntax mapping either; tags simply stay visible as-is (see the ZWSP handling in [processNormal]).
 *
 * All processing skips fenced code blocks and inline code, to avoid breaking characters such as `$` / `<` in code samples.
 */
internal object MarkdownPreprocessor {

    // Process-level result cache: process is called every time an item scrolls into the viewport (the caller only has remember(text), and scrolling out
    // disposes it, resetting remember). Text containing math delimiters has to run regexes, and re-running them repeatedly during a fast fling slows the main thread.
    // This is a process-level LRU keyed by the raw text, so each passage is processed only once.
    private const val CACHE_MAX = 256
    private val cache = object : LinkedHashMap<String, String>(CACHE_MAX, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > CACHE_MAX
    }

    fun process(raw: String): String {
        if (raw.isEmpty()) return raw
        // Fast path needing no processing: no HTML tags, no $ or \( / \[ math delimiters, and no & entities
        if (!raw.contains('<') && !raw.contains('$') && !raw.contains('\\') && !raw.contains('&')) return raw

        synchronized(cache) { cache[raw] }?.let { return it }

        val sb = StringBuilder(raw.length + 32)
        for (seg in splitPreservingCode(raw)) {
            when (seg.kind) {
                SegmentKind.NORMAL -> sb.append(processNormal(seg.text))
                // Inline code is also walked by the renderer as AST (children of CODE_SPAN), and HTML_TAG would likewise be discarded,
                // so the same `<` neutralisation is applied as for normal text; fenced code blocks are rendered verbatim and left unchanged.
                SegmentKind.INLINE_CODE -> sb.append(neutralizeHtmlOpenBracket(seg.text))
                SegmentKind.FENCE -> sb.append(seg.text)
            }
        }
        val result = sb.toString()
        synchronized(cache) { cache[raw] = result }
        return result
    }

    private enum class SegmentKind { NORMAL, INLINE_CODE, FENCE }

    private data class Segment(val kind: SegmentKind, val text: String)

    /** Scans the text, cutting fenced code blocks and inline code out as isCode=true segments kept verbatim; the rest are normal text segments. */
    private fun splitPreservingCode(text: String): List<Segment> {
        val result = ArrayList<Segment>()
        val normal = StringBuilder()
        val n = text.length
        var i = 0
        var atLineStart = true

        fun flushNormal() {
            if (normal.isNotEmpty()) {
                result.add(Segment(SegmentKind.NORMAL, normal.toString()))
                normal.clear()
            }
        }

        while (i < n) {
            val c = text[i]

            // Fenced code block: >=3 consecutive ` or ~ at the start of a line
            if (atLineStart && (c == '`' || c == '~')) {
                var j = i
                while (j < n && text[j] == c) j++
                if (j - i >= 3) {
                    val fenceLen = j - i
                    var lineEnd = j
                    while (lineEnd < n && text[lineEnd] != '\n') lineEnd++
                    var end = n
                    var lineStart = if (lineEnd < n) lineEnd + 1 else n
                    var k = lineStart
                    while (k <= n) {
                        if (k == n || text[k] == '\n') {
                            var p = lineStart
                            while (p < k && text[p] == ' ') p++
                            var q = p
                            while (q < k && text[q] == c) q++
                            if (q - p >= fenceLen) {
                                var rest = q
                                while (rest < k && text[rest] == ' ') rest++
                                if (rest == k) { end = k; break }
                            }
                            lineStart = k + 1
                        }
                        k++
                    }
                    flushNormal()
                    result.add(Segment(SegmentKind.FENCE, text.substring(i, end)))
                    i = end
                    atLineStart = i < n && text[i] == '\n'
                    continue
                }
            }

            // Inline code: wrapped in an equal-length run of `, and does not span lines
            if (c == '`') {
                var j = i
                while (j < n && text[j] == '`') j++
                val tickLen = j - i
                var k = j
                var found = -1
                while (k < n && text[k] != '\n') {
                    if (text[k] == '`') {
                        var m = k
                        while (m < n && text[m] == '`') m++
                        if (m - k == tickLen) { found = m; break }
                        k = m
                    } else k++
                }
                if (found >= 0) {
                    flushNormal()
                    result.add(Segment(SegmentKind.INLINE_CODE, text.substring(i, found)))
                    i = found
                    atLineStart = false
                    continue
                }
            }

            normal.append(c)
            atLineStart = c == '\n'
            i++
        }
        flushNormal()
        return result
    }

    private fun processNormal(input: String): String {
        var s = extractMath(input)
        s = Parser.unescapeEntities(s, false)
        return neutralizeHtmlOpenBracket(s)
    }

    /**
     * The chat stream does not render HTML: inserting a zero-width space (U+200B) after `<` stops tags being parsed as HTML_TAG nodes.
     * The renderer has no handling branch for HTML_TAG (it drops them outright); after the insertion, `<` degrades to a plain LT token and is displayed as-is,
     * so `<b>` shows as the literal text `<b>`. ZWSP is used rather than U+2060, which renders as tofu boxes in some device fonts.
     */
    private fun neutralizeHtmlOpenBracket(s: String): String = s.replace("<", "<\u200B")

    // ---------------------------------------------------------------------------------------------
    // Math formulas
    // ---------------------------------------------------------------------------------------------

    private val BLOCK_MATH_DOLLAR = Regex("""\$\$(.+?)\$\$""", RegexOption.DOT_MATCHES_ALL)
    private val BLOCK_MATH_BRACKET = Regex("""\\\[(.+?)\\\]""", RegexOption.DOT_MATCHES_ALL)

    // Inline: the inner edges of the delimiters must be non-whitespace and the content must contain no newline or $, avoiding "$5 ... $10" currency false positives of this kind (non-whitespace anchors)
    private val INLINE_MATH_DOLLAR = Regex("""\$(?=\S)([^\n$]*?\S)\$""")
    // Standard LaTeX inline delimiters \( ... \)
    private val INLINE_MATH_PAREN = Regex("""\\\((.+?)\\\)""", RegexOption.DOT_MATCHES_ALL)

    private fun extractMath(input: String): String {
        var s = input
        if (s.contains("$$")) {
            s = BLOCK_MATH_DOLLAR.replace(s) { m ->
                "\n\n" + encodeMathLink(m.groupValues[1].trim(), block = true) + "\n\n"
            }
        }
        if (s.contains("\\[")) {
            s = BLOCK_MATH_BRACKET.replace(s) { m ->
                "\n\n" + encodeMathLink(m.groupValues[1].trim(), block = true) + "\n\n"
            }
        }
        if (s.contains("\\(")) {
            s = INLINE_MATH_PAREN.replace(s) { m ->
                encodeMathLink(m.groupValues[1].trim(), block = false)
            }
        }
        if (s.contains('$')) {
            s = INLINE_MATH_DOLLAR.replace(s) { m ->
                encodeMathLink(m.groupValues[1].trim(), block = false)
            }
        }
        return s
    }

    // ---------------------------------------------------------------------------------------------
    // Math link encoding/decoding (for use by MathImageTransformer)
    // ---------------------------------------------------------------------------------------------

    const val MATH_BLOCK_SCHEME = "aicode-math-block://"
    const val MATH_INLINE_SCHEME = "aicode-math-inline://"

    fun encodeMathLink(latex: String, block: Boolean): String {
        val enc = Base64.getUrlEncoder().withoutPadding().encodeToString(latex.toByteArray(Charsets.UTF_8))
        return "![](${if (block) MATH_BLOCK_SCHEME else MATH_INLINE_SCHEME}$enc)"
    }

    /** Parses a math link; returns null for non-math links. Returns (latex, isBlock). */
    fun decodeMathLink(link: String): Pair<String, Boolean>? {
        val block = link.startsWith(MATH_BLOCK_SCHEME)
        val inline = link.startsWith(MATH_INLINE_SCHEME)
        if (!block && !inline) return null
        val enc = link.removePrefix(if (block) MATH_BLOCK_SCHEME else MATH_INLINE_SCHEME)
        return try {
            val latex = String(Base64.getUrlDecoder().decode(enc), Charsets.UTF_8)
            latex to block
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}
