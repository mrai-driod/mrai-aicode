package com.aicode.feature.agent.presentation.component

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aicode.R
import com.aicode.core.theme.semanticColors
import com.mikepenz.markdown.compose.LazyMarkdownSuccess
import com.mikepenz.markdown.compose.LocalMarkdownColors
import com.mikepenz.markdown.compose.LocalMarkdownDimens
import com.mikepenz.markdown.compose.LocalMarkdownPadding
import com.mikepenz.markdown.compose.Markdown
import com.mikepenz.markdown.compose.MarkdownSuccess
import com.mikepenz.markdown.compose.components.MarkdownComponents
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeBackground
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.compose.elements.MarkdownTable
import com.mikepenz.markdown.compose.elements.MarkdownTableHeader
import com.mikepenz.markdown.compose.elements.MarkdownTableRow
import com.mikepenz.markdown.compose.elements.material.MarkdownBasicText
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.markdownAnimations
import com.mikepenz.markdown.model.markdownDimens
import com.mikepenz.markdown.model.markdownPadding
import com.mikepenz.markdown.model.rememberMarkdownState
import com.mikepenz.markdown.model.State as MarkdownParseState
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.BoldHighlight
import dev.snipme.highlights.model.ColorHighlight
import dev.snipme.highlights.model.SyntaxLanguage
import dev.snipme.highlights.model.SyntaxThemes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Code blocks and inline code share a font. The system monospace font (FontFamily.Monospace) lacks subscript glyphs and does not fall back,
// while the bundled JetBrains Mono NL, loaded via resources, can use system fallback and can display subscript/superscript characters such as ₀-₉.
private val CodeFontFamily = FontFamily(Font(R.font.jetbrains_mono_nl))

/** Base increment for streaming parse throttling (characters): scaled proportionally with text length (+1 per 256 characters).
 *  A typewriter snapshot is about one frame every 24ms; at low output speed a batch differs by only a few characters (visually still character by character); when catching up at high speed a batch is at most
 *  8+len/256 characters (imperceptible visually at the end of a long text), keeping the full preprocess+parse execution frequency at 1/3 to 1/10 of the original. */
private const val STREAMING_PARSE_MIN_BASE = 8

internal class MarkdownRenderCache(
    // 200 entries: when scrolling back and forth through hundreds of messages in a long session, a capacity of 80 would cause LRU thrashing (a message just scrolled past entering
    // the viewport again would miss and re-parse in full). A single AST+state is a few KB to tens of KB, so the memory use of 200 entries is acceptable.
    private val maxEntries: Int = 200
) {
    private val parsedStates = object : LinkedHashMap<String, MarkdownParseState.Success>(
        maxEntries,
        0.75f,
        true
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MarkdownParseState.Success>?): Boolean {
            return size > maxEntries
        }
    }

    fun get(text: String): MarkdownParseState.Success? =
        parsedStates[text] ?: parsedStates[text.trim()]

    /**
     * Find in the cache the longest parsed state that is a prefix of [text].
     * At the handover instant from streaming to persisted, the full text may have trailing characters beyond the previous frame's typewriter content; using the longest-prefix AST directly as a fallback
     * ensures the first frame is presented as typeset Markdown, completely eliminating a flash of raw plain text (- **xxx**).
     */
    fun getBestPrefix(text: String): MarkdownParseState.Success? {
        val exact = get(text)
        if (exact != null) return exact
        var bestMatch: MarkdownParseState.Success? = null
        var bestLength = 0
        for ((key, value) in parsedStates) {
            if (key.length in (bestLength + 1)..text.length && text.startsWith(key)) {
                bestMatch = value
                bestLength = key.length
            }
        }
        return bestMatch
    }

    fun put(state: MarkdownParseState.Success) {
        parsedStates[state.content] = state
        val trimmed = state.content.trim()
        if (trimmed != state.content) {
            parsedStates[trimmed] = state
        }
    }
}

internal fun formatTokenCount(tokens: Long): String = when {
    tokens >= 1_000_000_000L -> "%.1fB".format(tokens / 1_000_000_000.0)
    tokens >= 1_000_000L -> "%.1fM".format(tokens / 1_000_000.0)
    tokens >= 1_000L -> "%.1fk".format(tokens / 1_000.0)
    else -> tokens.toString()
}

@Composable
internal fun MarkdownContent(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    cache: MarkdownRenderCache? = null,
    compact: Boolean = false,
    /** Content to show while parsing is unfinished (Loading); when null, falls back to showing the original plain text (chat streaming scenario). */
    loading: (@Composable () -> Unit)? = null,
    /** Virtualised rendering of long text: the body uses a LazyColumn rendering block by block (the caller must pair it with a finite height constraint such as heightIn
     *  for lazy loading to trigger), avoiding jank from composing/measuring a huge md all at once. */
    lazyScroll: Boolean = false,
    /** The caller is streaming-typing (typewriter text advances about every ~24ms): the parse input is throttled by an increment threshold -- only after accumulating
     *  [STREAMING_PARSE_MIN_BASE] + length/256 characters does it re-run preprocess+parse; other frames render using
     *  the previous successful result (content only grows, never jumps, lagging by at most one batch of characters). At the same time intermediate streaming snapshots are not written to [cache]:
     *  one every ~24ms would fill the LRU and evict all historical messages' parse results, and scrolling back to history would re-parse every one in full,
     *  which is exactly the source of composition spikes (15ms+ per frame) and dropped frames when scrolling the chat history. */
    streaming: Boolean = false
) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val semantic = MaterialTheme.semanticColors

    // markdownColor / markdownTypography / markdownPadding / markdownDimens / markdownComponents
    // All are @Composable factory functions provided by the library (with their own remember memoisation inside); they cannot and need not be wrapped in another layer of
    // remember (wrapping them would report '@Composable invocations can only happen from ...'). Just call them directly.
    val mdColors = markdownColor(
        text = color,
        codeBackground = semantic.mutedSurface,
        inlineCodeBackground = MaterialTheme.colorScheme.primary.copy(alpha = if (isDark) 0.22f else 0.12f),
        dividerColor = semantic.subtleBorder,
        tableBackground = semantic.mutedSurface,
    )

    val typography = MaterialTheme.typography
    // compact: body and lists use bodySmall, headings drop one level; used for secondary text areas such as reasoning bubbles
    val body = if (compact) typography.bodySmall else typography.bodyMedium
    val bodyLineHeight = if (compact) 18.sp else 20.sp
    val codeSize = if (compact) 12.sp else 13.sp
    val mdTypography = markdownTypography(
        h1 = (if (compact) typography.titleMedium else typography.headlineSmall).copy(fontWeight = FontWeight.Bold, color = color),
        h2 = (if (compact) typography.titleSmall else typography.titleLarge).copy(fontWeight = FontWeight.Bold, color = color),
        h3 = (if (compact) typography.bodyLarge else typography.titleMedium).copy(fontWeight = FontWeight.SemiBold, color = color),
        h4 = (if (compact) typography.bodyMedium else typography.titleSmall).copy(fontWeight = FontWeight.SemiBold, color = color),
        h5 = (if (compact) typography.bodySmall else typography.bodyLarge).copy(fontWeight = FontWeight.Medium, color = color),
        h6 = (if (compact) typography.bodySmall else typography.bodyMedium).copy(fontWeight = FontWeight.Medium, color = color),
        paragraph = body.copy(color = color, lineHeight = bodyLineHeight),
        code = TextStyle(fontFamily = CodeFontFamily, fontSize = codeSize, color = MaterialTheme.colorScheme.onSurface),
        inlineCode = TextStyle(fontFamily = CodeFontFamily, color = MaterialTheme.colorScheme.onSurface),
        ordered = body.copy(color = color, lineHeight = bodyLineHeight),
        bullet = body.copy(color = color, lineHeight = bodyLineHeight),
        table = typography.bodySmall.copy(color = color),
    )

    val mdPadding = markdownPadding(
        block = 4.dp,
        list = 2.dp,
        listItemBottom = 1.dp,
        listIndent = 12.dp,
        codeBlock = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
    )

    val mdDimens = markdownDimens(
        codeBackgroundCornerSize = 6.dp,
        tableCellPadding = 6.dp,
        tableCornerSize = 6.dp,
    )

    val highlightsBuilder = remember(isDark) {
        Highlights.Builder().theme(SyntaxThemes.atom(darkMode = isDark))
    }

    // Streaming throttling: new text advanced by the typewriter about every ~24ms is batched first -- when the increment is below the threshold, the previously submitted parse text is reused,
    // and neither preprocess nor Markdown parsing is re-run; text shortening/replacement (non-growth) passes through immediately, to prevent flicker.
    // After settle (streaming becomes false) this branch no longer hits, and the final full text is necessarily parsed completely once.
    var lastParsedText by remember { mutableStateOf<String?>(null) }
    val parseText: String = if (!streaming) {
        text
    } else {
        val last = lastParsedText
        val delta = STREAMING_PARSE_MIN_BASE + text.length / 256
        when {
            last == null || !text.startsWith(last) || text.length - last.length >= delta ->
                text.also { lastParsedText = it }
            else -> last
        }
    }
    val processed = remember(parseText) { MarkdownPreprocessor.process(parseText) }
    val baseTransformer = LocalMarkdownImageTransformer.current
    val mathTransformer = remember(baseTransformer, body.fontSize.value) {
        MathImageTransformer(baseTransformer, body.fontSize.value)
    }

    // Prewarm all LaTeX formulas in this message in the background: when the item is first composed, measure+render is computed in the background and
    // written to the cache, so when a fast fling passes over it the main thread's getOrMeasure hits directly and no longer builds a drawable synchronously (measured at a single
    // time up to 113ms, the direct root cause of fling judder).
    val density = LocalDensity.current
    val textSizePx = with(density) { body.fontSize.toPx() }
    val colorArgb = color.toArgb()
    LaunchedEffect(processed, textSizePx, colorArgb) {
        if (!processed.contains("aicode-math-")) return@LaunchedEffect
        withContext(Dispatchers.Default) {
            MathImageTransformer.prewarm(processed, textSizePx, colorArgb)
        }
    }

    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.material3.LocalContentColor provides color
    ) {
        // rememberMarkdownState must participate in composition unconditionally: if a cache hit took the if branch and skipped it, its internal
        // remember state would be disposed; when the text changes in the next frame (streaming growth/resume after a pause) and a cache miss rebuilds it,
        // the initial state is Loading and the raw md text would flash (the root cause of parsed/unparsed flip-flopping).
        // The cache is only a rendering accelerator: if the parse result matches the current text it is used directly; if it does not match but the cache hits the current text,
        // use the cache; otherwise enter Loading. Note: this library's rememberMarkdownState, when the text changes,
        // unconditionally sets the state to Loading (the retainState parameter has no effect in this version); if the Loading branch fell back to
        // showing the original plain text, every text change in the streaming scenario would flash 'the raw text of the latest text' (characters flickering at the bottom).
        // Therefore during Loading, prefer rendering the most recent successful parse result (the old text's complete md typesetting), and after parsing
        // completes, switch smoothly to the new text, with content only growing and never jumping.
        val mdState = rememberMarkdownState(content = processed, retainState = true)
        val parseState by mdState.state.collectAsState()
        val cachedState = cache?.get(processed)
        val prefixFallback = cache?.getBestPrefix(processed)
        // A delegated property cannot smart-cast; take a local snapshot first, then judge
        val currentState = parseState
        val parsedState: MarkdownParseState = when {
            currentState is MarkdownParseState.Success && currentState.content == processed -> currentState
            cachedState != null -> cachedState
            else -> currentState
        }

        // The most recent successful parse result: the rendering fallback during Loading (the first frame already uses the prefix cache, eliminating raw plain text exposure)
        var lastSuccessState by remember { mutableStateOf<MarkdownParseState.Success?>(prefixFallback) }
        if (parsedState is MarkdownParseState.Success) {
            lastSuccessState = parsedState
            // Write to the cache synchronously: so the persisted message relaying in the same turn can hit on its first frame, avoiding a raw plain-text flash caused by LaunchedEffect's asynchronous delay.
            // Intermediate streaming snapshots are not written: one every ~24ms would fill the LRU and evict historical messages' parse results (scrolling back to history would re-parse every one);
            // only the final full text after settle (streaming=false) is cached, for the persisted message and subsequent scrolling to hit.
            if (!streaming) cache?.put(parsedState)
        }

        // Success renders the current result; Loading prefers rendering this component's or the prefix cache's successful result (eliminating raw plain-text flashes); Error falls back to the original text
        val renderState: MarkdownParseState.Success? = when (parsedState) {
            is MarkdownParseState.Success -> parsedState
            is MarkdownParseState.Loading -> lastSuccessState ?: prefixFallback
            is MarkdownParseState.Error -> null
        }

        if (renderState != null) {
            // When long text is rendered block by block with a LazyColumn, animations (such as text-size animation) do not apply, as expected.
            // The three objects below (successRenderer / mdComponents / mdAnimations) are all remembered: without caching,
            // every recomposition would create new lambda instances, preventing downstream Markdown from skipping.
            val successRenderer: @Composable (
                state: MarkdownParseState.Success,
                components: MarkdownComponents,
                modifier: Modifier
            ) -> Unit = remember(lazyScroll) {
                if (lazyScroll) {
                    { state, components, m ->
                        LazyMarkdownSuccess(state = state, components = components, modifier = m)
                    }
                } else {
                    { state, components, m ->
                        MarkdownSuccess(state = state, components = components, modifier = m)
                    }
                }
            }

            // markdownComponents is @Composable (with its own memoisation inside); it cannot be wrapped in remember, just call it directly.
            val mdComponents = markdownComponents(
                // The outer SelectionContainer (MessageBubbles) uniformly handles selection; over-long assistant messages have already been
                // split by AIChatPanel into multiple bounded items, so there is no selection-tree/interaction failure problem for an over-long single item.
                codeFence = {
                    MarkdownCodeFence(
                        content = it.content,
                        node = it.node,
                    ) { code, language, style ->
                        SafeMarkdownHighlightedCode(code, language, style, highlightsBuilder, showHeader = true)
                    }
                },
                codeBlock = {
                    MarkdownCodeBlock(
                        content = it.content,
                        node = it.node,
                    ) { code, language, style ->
                        SafeMarkdownHighlightedCode(code, language, style, highlightsBuilder, showHeader = true)
                    }
                },
                // The library defaults to maxLines=1 + Ellipsis, so long cell text would be truncated; here it is opened up to full multi-line display.
                table = {
                    MarkdownTable(
                        content = it.content,
                        node = it.node,
                        style = it.typography.table,
                        headerBlock = { content, header, tableWidth, style ->
                            MarkdownTableHeader(
                                content = content,
                                header = header,
                                tableWidth = tableWidth,
                                style = style,
                                maxLines = Int.MAX_VALUE,
                                overflow = TextOverflow.Clip,
                            )
                        },
                        rowBlock = { content, header, tableWidth, style ->
                            MarkdownTableRow(
                                content = content,
                                header = header,
                                tableWidth = tableWidth,
                                style = style,
                                maxLines = Int.MAX_VALUE,
                                overflow = TextOverflow.Clip,
                            )
                        },
                    )
                },
            )

            Markdown(
                state = renderState,
                modifier = modifier,
                colors = mdColors,
                typography = mdTypography,
                padding = mdPadding,
                dimens = mdDimens,
                // Turn off animateContentSize for paragraph text: under fast streaming updates it keeps chasing the target height and instead bounces elastically.
                animations = markdownAnimations(animateTextSize = { this }),
                imageTransformer = mathTransformer,
                components = mdComponents,
                success = successRenderer,
            )
        } else if (loading != null) {
            loading()
        } else {
            PlainMarkdownText(
                text = text,
                color = color,
                modifier = modifier
            )
        }
    }
}

/**
 * Code block highlight rendering: equivalent to the library's `MarkdownHighlightedCode`, but before writing the highlight spans into the AnnotatedString
 * the range is validated first. The highlights engine can, for some content, give ranges with start > end; the library's original implementation calls
 * addStyle directly, and the AnnotatedString.Range constructor then throws "Reversed range is not supported"; that computation runs in
 * the library's own background coroutine (produceState + Dispatchers.Default), and the exception reaches the thread's default handler directly,
 * so the caller's try/catch cannot intercept it, manifesting as the chat page crashing. Out-of-bounds ranges (end > code.length) likewise cause a crash,
 * and are discarded together.
 */
/**
 * Process-level cache of code highlighting results. Highlighting itself runs on Dispatchers.Default (not blocking the main thread), but without a cross-viewport
 * cache, an item re-entering the viewport would re-run highlighting every time, and on completion trigger another main-thread recomposition (switching from plain text to
 * highlighted text). A fast fling scrolling over many code blocks would fill the Default threads and create dense recomposition. On a cache hit,
 * the result is obtained synchronously as the produceState initial value, with neither recomputation nor a plain-text flash. The key includes isDark (the theme affects colours).
 */
private object CodeHighlightCache {
    private data class Key(val code: String, val language: String?, val dark: Boolean)

    private const val MAX = 256
    private val cache = object : LinkedHashMap<Key, AnnotatedString>(MAX, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, AnnotatedString>?): Boolean = size > MAX
    }

    fun get(code: String, language: String?, dark: Boolean): AnnotatedString? =
        synchronized(cache) { cache[Key(code, language, dark)] }

    fun put(code: String, language: String?, dark: Boolean, value: AnnotatedString) {
        synchronized(cache) { cache[Key(code, language, dark)] = value }
    }
}

@Composable
private fun SafeMarkdownHighlightedCode(
    code: String,
    language: String?,
    style: TextStyle,
    highlightsBuilder: Highlights.Builder,
    showHeader: Boolean,
) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val cached = CodeHighlightCache.get(code, language, isDark)
    val highlighted: AnnotatedString by produceState(
        initialValue = cached ?: AnnotatedString(code),
        code,
        language,
        isDark,
    ) {
        if (CodeHighlightCache.get(code, language, isDark) != null) return@produceState
        value = withContext(Dispatchers.Default) {
            buildHighlightedText(code, language, highlightsBuilder)
        }.also { CodeHighlightCache.put(code, language, isDark, it) }
    }

    MarkdownCodeBackground(
        color = LocalMarkdownColors.current.codeBackground,
        shape = RoundedCornerShape(LocalMarkdownDimens.current.codeBackgroundCornerSize),
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        showHeader = showHeader,
        language = language,
        code = code,
    ) {
        MarkdownBasicText(
            text = highlighted,
            style = style,
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(LocalMarkdownPadding.current.codeBlock),
        )
    }
}

private fun buildHighlightedText(
    code: String,
    language: String?,
    highlightsBuilder: Highlights.Builder,
): AnnotatedString {
    // The highlighting engine itself may also throw (language parsing etc.); on failure it falls back to plain text, without letting it affect message rendering.
    val highlights = runCatching {
        val syntaxLanguage = language?.let { SyntaxLanguage.getByName(it) }
        highlightsBuilder.code(code)
            .let { if (syntaxLanguage != null) it.language(syntaxLanguage) else it }
            .build()
            .getHighlights()
    }.getOrDefault(emptyList())

    return buildAnnotatedString {
        append(code)
        for (highlight in highlights) {
            val start = highlight.location.start
            val end = highlight.location.end
            if (start < 0 || start >= end || end > code.length) continue
            val spanStyle = when (highlight) {
                is ColorHighlight -> SpanStyle(color = Color(highlight.rgb).copy(alpha = 1f))
                is BoldHighlight -> SpanStyle(fontWeight = FontWeight.Bold)
            }
            if (spanStyle != null) addStyle(spanStyle, start, end)
        }
    }
}

@Composable
private fun PlainMarkdownText(
    text: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.bodyMedium.copy(color = color, lineHeight = 20.sp)
    )
}
