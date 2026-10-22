package com.aicode.feature.agent.presentation.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aicode.R
import com.aicode.core.theme.Brand
import com.aicode.core.theme.Spacing
import com.aicode.feature.agent.domain.provider.RetryErrorInfo
import com.aicode.feature.agent.domain.provider.RetryErrorKind
import compose.icons.FeatherIcons
import compose.icons.feathericons.AlertCircle
import com.aicode.core.ui.ExpandableChevronIcon
import compose.icons.feathericons.Clock
import compose.icons.feathericons.Key
import kotlinx.coroutines.delay

/** Period of one back-and-forth ripple highlight cycle (ms). */
private const val RIPPLE_PERIOD_MS = 1600

/** Width of the highlight band as a fraction of the content width: too wide looks like the whole passage changes colour, too narrow is invisible. */
private const val RIPPLE_BAND_RATIO = 0.5f

/** Opacity at the edges of the highlight band (the two ends of the gradient). */
private const val RIPPLE_EDGE_ALPHA = 0.12f

/**
 * Peak opacity of the highlight band.
 *
 * The highlight is white (the mainstream approach): on a dark theme it 'lights up' the text, on a light theme it brightens the text toward the background into a sheen.
 * The peak must not be too high -- on a light theme white at 0.85 would erase the text outright; around 0.5 is 'brightens briefly but the text is still readable'.
 */
private const val RIPPLE_PEAK_ALPHA = 0.5f

/**
 * Ripple highlight: a highlight band, bright in the middle and transparent at both ends, periodically sweeps from left to right.
 *
 * Overlaid on already-drawn content with [BlendMode.SrcAtop] -- it only affects opaque pixels, so it will not draw
 * a coloured band block in blank areas. The highlight defaults to white (the Claude Code / mainstream shimmer approach); on a light theme white will,
 * on text pixels, brighten the text toward the background, forming a passing sheen rather than covering it with a layer of grey.
 *
 * Performance: progress is read during the draw phase of [drawWithContent] (the `by` delegate is evaluated inside the lambda), so it only redraws, never recomposes.
 */
@Composable
internal fun Modifier.rippleHighlight(
    highlight: Color = Color.White,
    bandRatio: Float = RIPPLE_BAND_RATIO,
    periodMs: Int = RIPPLE_PERIOD_MS
): Modifier {
    val transition = rememberInfiniteTransition(label = "ripple-highlight")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = periodMs, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ripple-progress"
    )
    return drawWithContent {
        drawContent()
        val band = size.width * bandRatio
        // The start moves fully out on the left and the end fully out on the right, leaving one band's worth of margin at each end for a 'pause' feel
        val center = -band + progress * (size.width + 2 * band)
        drawRect(
            brush = Brush.linearGradient(
                // Five-step gradient: fully transparent at both ends, one soft peak in the middle, so sweeping over is a 'brief brightening' rather than a hard-edged colour band
                colors = listOf(
                    Color.Transparent,
                    highlight.copy(alpha = RIPPLE_EDGE_ALPHA),
                    highlight.copy(alpha = RIPPLE_PEAK_ALPHA),
                    highlight.copy(alpha = RIPPLE_EDGE_ALPHA),
                    Color.Transparent
                ),
                start = Offset(center - band / 2f, 0f),
                end = Offset(center + band / 2f, 0f)
            ),
            blendMode = BlendMode.SrcAtop
        )
    }
}

/**
 * A piece of text with a moving ripple highlight (the text version of [Modifier.rippleHighlight]).
 *
 * The ripple is drawn over **the text's own width**: callers must not give it `weight`, otherwise the band will crawl across the entire allocatable width and
 * sweeping over these few characters takes only an instant (if space needs reserving, let the outer container reserve it).
 *
 * Passing null for [highlight] means drawing static text only: when the same copy needs to switch between 'idle' and 'in progress' at the same place,
 * use the same component and change only this one parameter (avoiding writing a separate set of Text parameters for each of the two branches).
 */
@Composable
internal fun RippleText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    style: TextStyle = MaterialTheme.typography.labelMedium,
    highlight: Color? = Color.White
) {
    val rippleModifier = if (highlight != null) modifier.rippleHighlight(highlight) else modifier
    Text(
        text = text,
        color = color,
        style = style,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = rippleModifier
    )
}

/**
 * Unified 'what is it busy with' status row: ripple-highlight copy (+ optional bouncing three dots).
 *
 * The highlight only covers the copy; the three bouncing dots are an independent animation and do not take part in the highlight (two animation layers stacked together would blur).
 * The outer [modifier] (filling the width, fixing the height) is left to the container; the inner content is laid out left-aligned at its own width.
 *
 * Screen-reader semantics are uniformly provided by this row (the copy is the semantics), so the internal [TypingDots] turns off its own announcement.
 */
@Composable
internal fun AgentBusyIndicator(
    label: String,
    modifier: Modifier = Modifier,
    showDots: Boolean = false,
    dotColor: Color = MaterialTheme.colorScheme.primary,
    dotSize: Dp = 6.dp
) {
    Box(modifier = modifier, contentAlignment = Alignment.CenterStart) {
        Row(
            modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = label },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            RippleText(text = label)
            if (showDots) TypingDots(color = dotColor, dotSize = dotSize, announce = false)
        }
    }
}

/**
 * Status row while waiting for the model to respond: ripple-highlight copy + three bouncing dots, without an outlined card.
 *
 * [label] is decided by the caller, saying 'thinking' or a more specific scenario (when the model is already emitting the arguments of a tool call,
 * say 'editing file'), see [toolRunningLabelRes].
 */
@Composable
internal fun ThinkingBubble(label: String) {
    AgentBusyIndicator(
        label = label,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ChatStyle.toolRowMinHeight),
        showDots = true
    )
}

/** Temporary status row during context compaction, not persisted. */
@Composable
internal fun CompactionProgressBubble() {
    AgentBusyIndicator(
        label = stringResource(R.string.chat_compressing_context),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ChatStyle.toolRowMinHeight),
        showDots = true
    )
}

/** Temporary status row during a network retry, not persisted. The first line shows the specific error that triggered the retry (e.g. 429/500/network disconnect), the second line shows the retry progress. */
@Composable
internal fun RetryingBubble(attempt: Int, maxRetries: Int, error: RetryErrorInfo?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.xs),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        if (error != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                Icon(
                    FeatherIcons.AlertCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = retryErrorLabel(error),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Text(
                text = stringResource(R.string.chat_retrying, attempt, maxRetries),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
            TypingDots(color = MaterialTheme.colorScheme.primary)
        }
    }
}

/**
 * Temporary status row during automatic multi-Key switching, not persisted. Shows that the current Key is unavailable and the request has been resent using Key N/M.
 *
 * Like [RetryingBubble] and [CompactionProgressBubble], this is a transient status row in the tail, following the flat document flow:
 * no outlined card, only an icon + copy + three bouncing dots.
 */
@Composable
internal fun KeySwitchedBubble(newIndex: Int, total: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        Icon(
            FeatherIcons.Key,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(14.dp)
        )
        Text(
            text = stringResource(R.string.chat_key_switched, newIndex, total),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall
        )
        TypingDots(color = MaterialTheme.colorScheme.primary)
    }
}

/** Error summary text: category copy + status code (e.g. 'Rate limit (429)'); with no status code, only the category copy. */
@Composable
private fun retryErrorLabel(error: RetryErrorInfo): String {
    val base = stringResource(
        when (error.kind) {
            RetryErrorKind.RATE_LIMIT -> R.string.retry_error_rate_limit
            RetryErrorKind.SERVER_OVERLOADED -> R.string.retry_error_server_overloaded
            RetryErrorKind.SERVER_ERROR -> R.string.retry_error_server
            RetryErrorKind.TIMEOUT -> R.string.retry_error_timeout
            RetryErrorKind.CONNECTION_REFUSED -> R.string.retry_error_connection_refused
            RetryErrorKind.DNS_FAILED -> R.string.retry_error_dns_failed
            RetryErrorKind.CONNECTION_RESET -> R.string.retry_error_connection_reset
            RetryErrorKind.SSL_ERROR -> R.string.retry_error_ssl
            RetryErrorKind.NETWORK -> R.string.retry_error_network
            RetryErrorKind.UNKNOWN -> R.string.retry_error_unknown
        }
    )
    val code = error.statusCode
    return if (code != null) stringResource(R.string.retry_error_with_code, base, code) else base
}

/** Streaming render throttle: after the text changes, wait this long before updating the rendered text, to reduce md parsing frequency. */
private const val STREAMING_RENDER_DEBOUNCE_MS = 120L

/**
 * Throttled rendering of continuously growing streaming text: the first frame renders the current text immediately, afterwards each text change updates at most
 * once per [STREAMING_RENDER_DEBOUNCE_MS]. Every upstream delta carries the full accumulated text;
 * without throttling, every token would trigger a full md parse (on long text parsing is slow and rendering lags).
 * The returned text is only for rendering; real-time logic such as collapse decisions still uses the original [text] directly.
 */
@Composable
private fun rememberThrottledStreamingText(text: String): String {
    var renderText by remember { mutableStateOf(text) }
    LaunchedEffect(text) {
        if (renderText == text) return@LaunchedEffect
        delay(STREAMING_RENDER_DEBOUNCE_MS)
        if (renderText != text) renderText = text
    }
    return renderText
}

/** Typewriter minimum display rate (characters/second): when upstream stalls it still catches up at a steady rate, ensuring the ending can be seen. */
private const val TYPEWRITER_MIN_RATE = 30f

/** Normal display rate cap (characters/second): the ceiling while lag is within [TYPEWRITER_LAG_TARGET]. */
private const val TYPEWRITER_MAX_RATE = 220f

/** Emergency display rate cap after lag exceeds the target (characters/second): allows breaking through the normal cap to quickly consume the backlog. */
private const val TYPEWRITER_BURST_RATE = 1600f

/** Display rate = upstream rate x this factor: slightly less than 1, preserving the lag feel of 'always half a beat behind'. */
private const val TYPEWRITER_FOLLOW_RATIO = 0.95f

/**
 * Allowed lag (characters): the display falls at most this many characters behind upstream.
 *
 * Once exceeded it is no longer constrained by [TYPEWRITER_MAX_RATE]. This cap is the key to 'no twitch at the finishing instant' --
 * what needs to be made up when upstream ends is always only this last small segment, not a whole passage of hundreds of characters built up.
 */
private const val TYPEWRITER_LAG_TARGET = 12f

/** Catch-up time constant (seconds): lag converges at this pace; the smaller it is, the tighter the catch-up. */
private const val TYPEWRITER_SETTLE_SECONDS = 0.06f

/**
 * Target duration (seconds) for typing out the remaining text after upstream ends.
 *
 * After ending it no longer completes in one frame (that is exactly the source of 'suddenly jumping to the end'), but types out evenly at 'remaining / this duration':
 * it neither teleports nor drags on until the user thinks it is stuck. A typical remainder (a dozen or so characters) finishes in about 0.2 seconds.
 */
private const val TYPEWRITER_DRAIN_SECONDS = 0.2f

/** Display rate cap for the finishing phase (characters/second): even with a lot remaining it will not flash past. */
private const val TYPEWRITER_DRAIN_MAX_RATE = 400f

/** Hard cap for finishing (ms): even in extreme cases (the text is replaced wholesale, rendering cannot keep up) it must catch up within this. */
private const val TYPEWRITER_DRAIN_HARD_MS = 600L

/** Sliding window duration (ms) for estimating the upstream typing rate. */
private const val TYPEWRITER_RATE_WINDOW_MS = 500L

/**
 * Typewriter render throttle interval (ms).
 *
 * Each snapshot = one full Markdown re-parse of the whole text + a full re-layout measurement of the body Text (heavy work at millisecond scale on long replies).
 * 24ms (~42 times/second) on a 120Hz screen means shouldering that heavy work every 3 frames, and during streaming the frame budget is systematically squeezed out.
 * The typewriter is a discrete animation of 'characters appearing one by one' rather than continuous motion; the human eye perceives smoothness at ~20fps,
 * so it is relaxed to 48ms (~21 times/second): the heavy-work frequency is halved, with no loss in perceived quality.
 */
private const val TYPEWRITER_RENDER_INTERVAL_MS = 48L

/** Truncate a string by code point count, avoiding splitting emoji and other surrogate pairs into isolated half characters. */
private fun truncateToCodePoints(text: String, codePoints: Int): String {
    if (codePoints <= 0) return ""
    if (codePoints >= text.codePointCount(0, text.length)) return text
    var index = 0
    var count = 0
    while (index < text.length && count < codePoints) {
        index += Character.charCount(text.codePointAt(index))
        count++
    }
    return text.substring(0, index)
}

/** Prefix sampling cap for the continuation criterion (characters): only the fingerprint is stored, not the full text, to avoid large streaming text entering saveable. */
private const val CONTINUITY_HEAD_CHARS = 64

/** Prefix fingerprint of the seen text, which together with its length forms the criterion for 'continuation of the same turn'. */
internal fun streamHeadFingerprint(text: String): Int =
    text.take(CONTINUITY_HEAD_CHARS).hashCode()

/**
 * Whether [text] is a continuation of that seen text with 'length [seenChars], prefix fingerprint [seenHead]'.
 *
 * Streaming text grows by prefix delta by delta; within the same turn the current text necessarily has the seen text as a prefix. After switching pages or an item being recycled and
 * remounted, this validates whether the restored typing progress / timing start still belongs to the same turn: if the turn has changed in the meantime, the new text is shorter
 * or starts differently, it is judged not a continuation, and progress and timing start from scratch.
 */
internal fun isStreamContinuation(text: String, seenChars: Int, seenHead: Int): Boolean {
    if (seenChars <= 0 || text.length < seenChars) return false
    return text.take(minOf(seenChars, CONTINUITY_HEAD_CHARS)).hashCode() == seenHead
}

/**
 * Typewriter display rate for a single frame (code points/second). [lag] = characters arrived upstream - characters already displayed, [arrivalRate] is the sliding-window estimate of the
 * upstream typing rate, non-null [drainRate] indicates the finishing phase after upstream has ended (type out the remainder at that constant rate).
 *
 * During playback, take the larger of the two:
 * - Follow term = upstream rate x [TYPEWRITER_FOLLOW_RATIO] (slightly slower than upstream, preserving the 'half a beat behind' feel);
 * - Catch-up term = lag / [TYPEWRITER_SETTLE_SECONDS] (the larger the lag, the faster the catch-up).
 *
 * The normal cap [TYPEWRITER_MAX_RATE] only takes effect while lag has not exceeded the target: once lag exceeds [TYPEWRITER_LAG_TARGET]
 * it is relaxed to [TYPEWRITER_BURST_RATE]. This rule is the key to 'no twitch at the end' -- the old implementation hard-capped the rate at
 * 200 characters/second; when the model typed quickly or in bursts the lag only grew (steady state about 1.8x the upstream rate, often hundreds of characters),
 * and as soon as upstream ended the whole passage jumped out at once, looking like a sudden twitch. Now the lag is kept within a dozen or so characters,
 * and in the finishing phase that small segment is typed out evenly at [TYPEWRITER_DRAIN_SECONDS], so it does not teleport.
 */
internal fun typewriterRate(lag: Float, arrivalRate: Float, drainRate: Float? = null): Float {
    if (lag <= 0f) return 0f
    if (drainRate != null) {
        return drainRate.coerceIn(TYPEWRITER_MIN_RATE, TYPEWRITER_BURST_RATE)
    }
    val followRate = arrivalRate * TYPEWRITER_FOLLOW_RATIO
    val catchUpRate = lag / TYPEWRITER_SETTLE_SECONDS
    val ceiling = if (lag > TYPEWRITER_LAG_TARGET) TYPEWRITER_BURST_RATE else TYPEWRITER_MAX_RATE
    return maxOf(followRate, catchUpRate).coerceIn(TYPEWRITER_MIN_RATE, ceiling)
}

/**
 * Finishing rate (code points/second): type out the [lag] remaining code points evenly within [TYPEWRITER_DRAIN_SECONDS].
 *
 * Taking 'spread the remaining amount over the target duration' rather than 'catch up at a fixed multiple' ensures the finishing time is constant-scale (about 0.2 seconds):
 * with a dozen or so characters remaining you can clearly see it typing, and with a few hundred remaining it will not grind character by character until the user thinks it is stuck.
 */
internal fun typewriterDrainRate(lag: Float): Float =
    (lag / TYPEWRITER_DRAIN_SECONDS).coerceIn(TYPEWRITER_MIN_RATE, TYPEWRITER_DRAIN_MAX_RATE)

/** Current typewriter rendering result: [text] is the text to render (a prefix of the full upstream text), [settled] indicates whether it has caught up with the full text. */
internal data class TypewriterText(val text: String, val settled: Boolean)

/**
 * Rate-adaptive typewriter: the displayed text lags behind the upstream accumulated text, and the typing speed follows the model's typing speed.
 *
 * Every upstream delta carries the full accumulated text, and its arrival rhythm is the model's typing rhythm. Two progresses are maintained here:
 * arrival progress (the code point count of [text]) and display progress (the code points already shown). Display progress is driven by animation frames; for the rate see
 * [typewriterRate]: lag is kept near [TYPEWRITER_LAG_TARGET] and does not accumulate further and further.
 *
 * The rendered text is snapshotted once every [TYPEWRITER_RENDER_INTERVAL_MS] (throttle rather than debounce,
 * ensuring the rendering keeps visibly growing while typing), keeping the md parsing frequency at ~10fps; when the text changes abruptly (switching session /
 * new turn / retry) it is completed to the current full text, then continues typing along with subsequent deltas.
 *
 * When upstream ends ([active] becomes false) it **no longer completes in one frame**: the remaining small segment is typed out evenly at [typewriterDrainRate]
 * (about 0.2 seconds, with the hard cap [TYPEWRITER_DRAIN_HARD_MS] as a backstop), during which [TypewriterText.settled]
 * stays false, and the caller uses this to hand over rendering of this text to the just-persisted assistant message (see AIChatPanel),
 * and after typing finishes the persisted message takes over completely -- the user sees neither 'the whole passage suddenly jumping out' nor the same text duplicated in two copies.
 *
 * Callers should hold this state outside the LazyColumn, to avoid the tail item scrolling out of the viewport, being disposed, and
 * recomposing and losing typing progress. Switching pages (the whole chat subtree leaving the NavHost composition) cannot be avoided by where it is held,
 * and is carried by the internal saveable progress.
 */
@Composable
internal fun rememberTypewriterStreamingText(
    text: String,
    active: Boolean,
    /**
     * The session the text belongs to. When switching to another session that is currently outputting, the part it has already produced is an established fact and must be displayed completed directly --
     * this function's state is attached at the call site, and switching sessions does not rebuild it; without explicit distinction it would be judged by the 'new turn'
     * decision below as a new turn, and that content would be typed out again character by character in front of the user.
     */
    sessionKey: String? = null
): TypewriterText {
    // The length and prefix fingerprint of the already-rendered text go into saveable: after returning from another page, typing progress continues from there, avoiding already-output
    // body text being retyped from the start. If validation fails (the turn or session changed in the meantime), complete to the current full text instead of typing from the start:
    // text first seen at the moment of mounting is history to the user; retyping it would only make them think the model is repeating its output.
    var shownChars by rememberSaveable { mutableStateOf(0) }
    var shownHead by rememberSaveable { mutableStateOf(0) }
    val restored = remember {
        if (isStreamContinuation(text, shownChars, shownHead)) text.substring(0, shownChars) else text
    }
    var shownCodePoints by remember {
        mutableStateOf(restored.codePointCount(0, restored.length).toFloat())
    }
    var renderText by remember { mutableStateOf(restored) }
    // Upstream arrival event window: (frame timestamp, cumulative code point count), used to estimate the typing rate
    val arrivals = remember { ArrayDeque<Pair<Long, Int>>() }
    var lastArrivalNanos by remember { mutableStateOf(0L) }
    var lastText by remember { mutableStateOf(restored) }
    var lastSessionKey by remember { mutableStateOf(sessionKey) }
    // The rendered text and its saveable fingerprint must be updated in sync, otherwise on restore the fingerprint would be used to validate a different piece of text
    val commitRender: (String) -> Unit = { snapshot ->
        renderText = snapshot
        shownChars = snapshot.length
        shownHead = streamHeadFingerprint(snapshot)
    }

    LaunchedEffect(text, active, sessionKey) {
        // The text is not a continuation of the current progress (session switch / new turn / retry): complete to the current full text, then follow subsequent deltas typing.
        // Must not reset to zero and retype -- when switching to another session that is outputting, its already-produced hundreds of characters would happen again in front of the user.
        // A session switch must be judged separately: currentSessionId and streamingText may not arrive in the same frame, and relying only on prefix judgement
        // would miss the earlier frame (that frame's text is still the old session's, showing no abrupt change).
        // The start of a new turn also goes through this path, but at that point text is only the few characters of the first delta, and completing vs retyping is visually indistinguishable.
        val sessionChanged = sessionKey != lastSessionKey
        lastSessionKey = sessionKey
        if (sessionChanged || (lastText.isNotEmpty() && !text.startsWith(lastText))) {
            shownCodePoints = text.codePointCount(0, text.length).toFloat()
            commitRender(text)
            arrivals.clear()
            lastArrivalNanos = 0L
        }
        lastText = text

        // Upstream has ended, entering the finishing phase: no longer completing in one frame (that is exactly the source of 'a twitch at the finishing instant'),
        // the remaining few characters are typed out evenly at a constant rate. At this point text will not change again, and this coroutine will not be interrupted during finishing.
        val draining = !active
        val now = System.nanoTime()
        val codePoints = text.codePointCount(0, text.length)
        // The finishing rate is set from 'the remaining amount when entering finishing', ensuring the finishing duration is constant and it can finish exactly (leaving no fractional remainder)
        val drainRate = if (draining) {
            typewriterDrainRate((codePoints - shownCodePoints).coerceAtLeast(0f))
        } else {
            null
        }
        val drainDeadlineNanos = now + TYPEWRITER_DRAIN_HARD_MS * 1_000_000L
        if (!draining) {
            // Record this arrival event, trim the rate window (keep at least 2 entries within the most recent WINDOW)
            if (lastArrivalNanos != 0L) {
                arrivals.addLast(now to codePoints)
                val windowNanos = TYPEWRITER_RATE_WINDOW_MS * 1_000_000L
                while (arrivals.size > 2 && now - arrivals.first().first > windowNanos) {
                    arrivals.removeFirst()
                }
            }
            lastArrivalNanos = now
        }

        // Frame-driven advance of display progress; exit once caught up with this text (when text changes again this coroutine is cancelled and restarted)
        var lastRenderNanos = 0L
        var lastFrameNanos = 0L
        while (shownCodePoints < codePoints) {
            withFrameNanos { frameNanos ->
                if (lastFrameNanos != 0L) {
                    val dtSec = (frameNanos - lastFrameNanos) / 1_000_000_000f
                    // The upstream rate only needs estimating during playback: in the finishing phase upstream is no longer moving
                    var arrivalRate = 0f
                    if (!draining) {
                        val oldest = arrivals.firstOrNull()
                        if (arrivals.size >= 2 && oldest != null) {
                            val spanSec = (frameNanos - oldest.first) / 1_000_000_000f
                            if (spanSec > 0f) {
                                arrivalRate = (arrivals.last().second - oldest.second) / spanSec
                            }
                        }
                    }
                    val rate = typewriterRate(
                        lag = codePoints - shownCodePoints,
                        arrivalRate = arrivalRate,
                        drainRate = drainRate
                    )
                    shownCodePoints = (shownCodePoints + rate * dtSec)
                        .coerceAtMost(codePoints.toFloat())
                }
                lastFrameNanos = frameNanos

                // Finishing hard cap: even in extreme cases (the text is replaced wholesale, rendering cannot keep up) it must catch up within this,
                // otherwise the tail will stay in the not-caught-up state forever and the persisted message will never get the handover.
                if (draining && frameNanos >= drainDeadlineNanos) {
                    shownCodePoints = codePoints.toFloat()
                }

                // Render throttle: snapshot the current display progress when the interval is reached; force-render the full text at the instant of catching up
                val intervalNanos = TYPEWRITER_RENDER_INTERVAL_MS * 1_000_000L
                if (frameNanos - lastRenderNanos >= intervalNanos || shownCodePoints >= codePoints) {
                    val snapshot = truncateToCodePoints(text, shownCodePoints.toInt())
                    if (snapshot != renderText) {
                        lastRenderNanos = frameNanos
                        commitRender(snapshot)
                    }
                }
            }
        }
        // After catching up, ensure the full text is rendered (on exiting the while loop shownCodePoints has reached available)
        if (renderText != text) commitRender(text)
    }
    // settled is given directly by 'whether the rendered text equals the full upstream text': during finishing the caller uses this to decide rendering ownership
    return TypewriterText(text = renderText, settled = renderText == text)
}

/**
 * Live bubble while the model is streaming output: left-aligned, same style as the assistant bubble.
 * Three bouncing dots at the tail indicate it is still generating. After this turn ends, the persisted assistant bubble takes over.
 *
 * During streaming it is rendered with a typewriter effect (typing progress is driven by the caller via
 * [rememberTypewriterStreamingText], see [AIChatPanel]), the typing speed adapts to the model's output
 * speed, and when upstream ends it automatically completes to the full text, seamlessly relaying to the persisted message.
 */
@Composable
internal fun StreamingBubble(
    text: String,
    cache: MarkdownRenderCache? = null,
    /** Streaming in progress (typewriter has not caught up): MarkdownContent parsing is throttled incrementally, and intermediate snapshots are not written to the cache. */
    streaming: Boolean = false
) {
    val renderText = text
    // Structurally the same as the persisted assistant body: no container, laid directly on the page background, with the 'generating' dots hung at the bottom
    Column(modifier = Modifier.fillMaxWidth()) {
        MarkdownContent(
            text = renderText,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth(),
            cache = cache,
            streaming = streaming
        )
        Spacer(Modifier.height(Spacing.xs))
        TypingDots(color = MaterialTheme.colorScheme.primary, dotSize = 5.dp)
    }
}

/**
 * The one line of reasoning content shown in the collapsed row: while reasoning is in progress take **the last line** (scrolling quickly along with what the model is currently writing),
 * after reasoning ends take **the first line** (the content is settled, fixed as a one-sentence preview).
 *
 * The caller renders it as a single line + ellipsis, so this strips the Markdown markers at the start of the line (headings, lists, quotes) and the closing
 * markers at the end of the line (bold, inline code), to avoid exposing source markers like `##` / `**` in the collapsed row; if the whole line is still too long
 * it is left to ellipsis truncation. When there is no visible content it returns an empty string, and the caller falls back to the 'reasoning process' copy.
 */
internal fun reasoningPreviewLine(raw: String, live: Boolean): String {
    val line = if (live) {
        // Step back from the end skipping whitespace and newlines, to cut out the last line. Do not trimEnd() the whole string: during streaming reasoning this function runs every frame,
        // and copying the whole string of a long reasoning text is pure waste.
        var end = raw.length
        while (end > 0 && raw[end - 1].isWhitespace()) end--
        var start = end
        while (start > 0 && raw[start - 1] != '\n') start--
        raw.substring(start, end)
    } else {
        raw.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
    }
    // Spaces and Markdown markers alternate at both ends (`- **key point**`, `## Heading`), so put them in the same set and strip them clean in one pass
    return line.trim(*REASONING_PREVIEW_MARKERS)
}

/** Characters to strip from both ends of the collapsed-row preview: whitespace + Markdown markers (heading `#`, list `-` `+` `*`, quote `>`, and the `*` `` ` `` of bold and inline code). */
private val REASONING_PREVIEW_MARKERS = charArrayOf(' ', '\t', '#', '*', '-', '>', '`', '+')

/** Maximum height of the reasoning expanded window: beyond it, scrolling happens inside the window, no longer growing indefinitely and dragging away the whole page scroll. */
private val ReasoningWindowMaxHeight = 300.dp

/** Height of the fade-out mask at the bottom of the reasoning window (shown only when the content can still scroll further down). */
private val ReasoningWindowFadeHeight = 28.dp

/**
 * Reasoning process collapsed row: left-aligned, light-coloured and de-emphasised, distinct from the formal reply. **Collapsed by default**, tap this row at any time to expand/collapse.
 *
 * The collapsed state takes only one line: a reasoning icon at the start of the line + a one-line content preview (see [reasoningPreviewLine], truncated with an ellipsis if too wide);
 * The expanded state switches to the full body (Markdown rendering). There is no intermediate state of 'showing a few tail lines' between the two states.
 *
 * [live] indicates reasoning is still in progress: the collapsed row's preview takes the last line, scrolling along with the content, so you can see what the model is thinking without expanding;
 * after reasoning ends the preview is fixed to the first line.
 */
@Composable
internal fun ReasoningBubble(
    text: String,
    cache: MarkdownRenderCache? = null,
    showTimer: Boolean = false,
    /** Total time taken by completed reasoning (milliseconds). When non-null and greater than 0, the collapsed-row copy shows the exact duration. */
    durationMs: Long? = null,
    timerStartMs: Long? = null,
    /** The text is already driven by an external typewriter (streaming tail scenario), so skip the internal debounce and render directly. */
    preRendered: Boolean = false,
    /** The session the reasoning belongs to: re-time when switching sessions, otherwise it would compute an absurd duration from the previous session's start point. */
    sessionKey: String? = null,
    /** Reasoning is still in progress: the collapsed row's preview takes the last line (scrolling along), see the KDoc above. */
    live: Boolean = false,
    /** External override of the expanded state (host-persisted, see [AIAgentViewModel.reasoningExpansionOverrides]); null = use internal state. */
    expandedOverride: Boolean? = null,
    /** Report the new state back when manually expanding/collapsing, for the upper layer to persist. */
    onExpandedChange: ((Boolean) -> Unit)? = null
) {
    var localExpanded by remember { mutableStateOf(false) }
    val expanded = expandedOverride ?: localExpanded
    val toggleExpanded: (Boolean) -> Unit = { next ->
        if (onExpandedChange != null) onExpandedChange(next) else localExpanded = next
    }
    // Store an absolute start timestamp rather than an accumulated
    // millisecond count, so after returning from another page or the bubble scrolling out of the viewport and remounting, the displayed duration is still the real duration; the start stamp, together with the length and
    // fingerprint of the seen text, goes into saveable, and on restore, if the text is not a continuation of the same turn (the turn changed in the meantime), timing restarts.
    var timerStartMillis by rememberSaveable { mutableStateOf(0L) }
    var timerSeenChars by rememberSaveable { mutableStateOf(0) }
    var timerSeenHead by rememberSaveable { mutableStateOf(0) }
    var elapsedMillis by remember { mutableStateOf(0L) }
    val latestText by rememberUpdatedState(text)
    LaunchedEffect(showTimer, live, sessionKey, timerStartMs) {
        if (!showTimer || !live) return@LaunchedEffect
        if (timerStartMs != null) {
            timerStartMillis = timerStartMs
        } else if (!isStreamContinuation(latestText, timerSeenChars, timerSeenHead)) {
            timerStartMillis = System.currentTimeMillis()
        }
        while (true) {
            elapsedMillis = (System.currentTimeMillis() - timerStartMillis).coerceAtLeast(0L)
            timerSeenChars = latestText.length
            timerSeenHead = streamHeadFingerprint(latestText)
            delay(100)
        }
    }
    // Expanded rendering uses throttled text (reduces md parsing frequency during streaming reasoning); when preRendered, the external side has already supplied the render text at the typewriter pace.
    val renderText = if (preRendered) text else rememberThrottledStreamingText(text)
    val effectiveDurationMs = durationMs?.takeIf { it > 0 } ?: elapsedMillis.takeIf { it > 0 }
    val formattedDuration = remember(effectiveDurationMs, live) {
        effectiveDurationMs?.let { ms ->
            val sec = (ms / 1000.0).coerceAtLeast(0.1)
            String.format(java.util.Locale.US, "%.1f", sec)
        }
    }
    // Collapsed-row copy: while in progress show the live duration, after completion show the total duration (when no duration data is available, only show 'Thinking complete').
    val label = when {
        live -> {
            val secText = formattedDuration ?: "0.1"
            stringResource(R.string.chat_thinking_running_time, secText)
        }
        formattedDuration != null -> stringResource(R.string.chat_thinking_done_time, formattedDuration)
        else -> stringResource(R.string.chat_thinking_done)
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 28.dp)
                .clickable { toggleExpanded(!expanded) },
            verticalAlignment = Alignment.CenterVertically
        ) {
            ThinkingGlyph(tint = Brand.IconGray, iconSize = 16.dp)
            Spacer(Modifier.width(Spacing.sm))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.width(Spacing.xs))
            ExpandableChevronIcon(
                expanded = expanded,
                contentDescription = if (expanded) stringResource(R.string.common_collapse) else stringResource(R.string.common_expand),
                tint = Brand.IconGray,
                size = 18.dp
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
            exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
        ) {
            Column {
                Spacer(Modifier.height(Spacing.sm))
                val scrollState = rememberScrollState()
                val fadeColor = MaterialTheme.colorScheme.background
                Box(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    MarkdownContent(
                        text = renderText,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        cache = cache,
                        compact = true,
                        // During streaming reasoning (external typewriter advances text), throttle parsing + do not write intermediate snapshots to the cache
                        streaming = live && preRendered,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = ReasoningWindowMaxHeight)
                            .nestedScroll(rememberBoundNestedScrollConnection(scrollState))
                            .verticalScroll(scrollState)
                            .pointerInput(text) {
                                detectTapGestures(
                                    onDoubleTap = { toggleExpanded(false) }
                                )
                            }
                    )
                    // Bottom fade: only overlay a layer when it can still scroll further down, hinting 'there is more below'
                    if (scrollState.canScrollForward) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .fillMaxWidth()
                                .height(ReasoningWindowFadeHeight)
                                .background(Brush.verticalGradient(listOf(Color.Transparent, fadeColor)))
                        )
                    }
                }
            }
        }
    }
}

/**
 * Three looping bouncing dots: a general 'typing/generating' indicator, replacing the spinning spinner.
 * The three dots bounce up and down in turn with a fixed phase difference, forming a wave-like rhythm.
 *
 * Performance optimisation: use graphicsLayer { translationY } instead of offset(y); animation value changes are handled in the draw phase
 * without triggering compose/recompose, eliminating the overhead of an infinite animation causing the parent layout to recompose every frame.
 * The container height is fixed, preventing layout fluctuations from propagating to the LazyColumn.
 */
@Composable
internal fun TypingDots(
    color: Color,
    dotSize: androidx.compose.ui.unit.Dp = 6.dp,
    /** Whether to announce 'generating' to screen readers on its own: when nested inside [AgentBusyIndicator], the outer status row announces it uniformly. */
    announce: Boolean = true
) {
    val transition = rememberInfiniteTransition(label = "typing-dots")
    val label = stringResource(R.string.chat_status_generating)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        // Give screen readers a semantics: the three bouncing dots are otherwise completely invisible to TalkBack.
        modifier = Modifier
            .height(dotSize + 10.dp)
            .then(
                if (announce) Modifier.semantics { contentDescription = label } else Modifier
            )
    ) {
        repeat(3) { index ->
            val offsetY by transition.animateFloat(
                initialValue = 0f,
                targetValue = 0f,
                animationSpec = infiniteRepeatable(
                    animation = keyframes {
                        durationMillis = 900
                        0f at 0
                        -5f at 180
                        0f at 360
                        0f at 900
                    },
                    repeatMode = RepeatMode.Restart,
                    initialStartOffset = StartOffset(index * 150)
                ),
                label = "dot-$index"
            )
            Box(
                modifier = Modifier
                    .graphicsLayer { translationY = offsetY }
                    .size(dotSize)
                    .clip(CircleShape)
                    .background(color)
            )
            if (index < 2) Spacer(Modifier.width(4.dp))
        }
    }
}
