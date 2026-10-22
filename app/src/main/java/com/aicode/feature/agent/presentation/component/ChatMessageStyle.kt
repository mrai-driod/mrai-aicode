package com.aicode.feature.agent.presentation.component

import android.content.ClipData
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.aicode.R
import com.aicode.core.theme.Spacing
import compose.icons.FeatherIcons
import compose.icons.feathericons.Check
import compose.icons.feathericons.Copy
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Shared dimensions and palette for the message area (DSH flat document-flow style).
 *
 * Design baseline: the whole conversation area no longer stacks "one outlined card per paragraph", but is instead **one page of a document** -
 * assistant body text lies directly on the page background, a user message is a pale pill, a tool call is a flat row with a 1px hairline,
 * and only "content blocks" such as code/diffs are raised into white cards with an outline.
 *
 * All colours come from the current theme's semantic slots (no hardcoded hex), so the four presets + dark mode + Monet colour extraction
 * all adapt automatically; the tonal relationships in dark mode are obtained by layering `copy(alpha)` over theme surfaces, isomorphic with light mode.
 */
internal object ChatStyle {
    /** Hairline/outline width: the flat style's only means of separation. */
    val hairline = 1.dp

    /** Corner radius of the user pill. */
    val bubbleCorner = 14.dp

    /** Corner radius of code cards / diff cards. */
    val cardCorner = 10.dp

    /** Corner radius of small inline panels (command, result). */
    val panelCorner = 8.dp

    // ——— Pre-built shared Shapes ———
    // Creating a RoundedCornerShape(...) on the spot during composition builds a new CornerBasedShape (holding 4
    // CornerSize values) on every recomposition. During a fast fling, visible items recompose repeatedly, accumulating into thousands of allocations per second -> heap sawtooth -> GC pauses
    // and dropped frames. These radii are all compile-time constants and a Shape is immutable and stateless, so one instance can safely be shared globally.
    val bubbleShape = RoundedCornerShape(bubbleCorner)
    val cardShape = RoundedCornerShape(cardCorner)
    val panelShape = RoundedCornerShape(panelCorner)

    /** Minimum tool-row height: keeps it fingertip-tappable while the layout stays compact, not loose. */
    val toolRowMinHeight = 32.dp

    /** Code card header height. */
    val codeHeaderHeight = 36.dp

    /** Leading icon cell: the thinking row / tool row / tool group header share the same cell width, so the three rows' icons and following text align horizontally. */
    val rowIconSize = 16.dp
}

/** User message background: in light themes it is the theme container colour (the default blue is the pale blue of the reference image); in dark themes it deepens automatically. */
@Composable
internal fun chatUserBubbleColor(): Color = MaterialTheme.colorScheme.primaryContainer

/** User message text: dark text sitting on the pale pill, no longer white reversed-out text. */
@Composable
internal fun chatUserBubbleTextColor(): Color = MaterialTheme.colorScheme.onPrimaryContainer

/** 1px hairline: the flat style's only means of grouping. */
@Composable
internal fun chatHairlineColor(): Color = MaterialTheme.colorScheme.outlineVariant

/** Weak panel background (tool results, code area base): close to `#F9FAFB` in light themes. */
@Composable
internal fun chatMutedSurfaceColor(): Color =
    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)

/** Content card background (diff/code cards): in light themes a white base + hairline; in dark themes it drops one level to become the "paper surface". */
@Composable
internal fun chatCardSurfaceColor(): Color = MaterialTheme.colorScheme.surface

/** Horizontal 1px hairline filling the available width. */
@Composable
internal fun ChatHairline(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(ChatStyle.hairline)
            .background(chatHairlineColor())
    )
}

/** The small time / token / elapsed text below a message, uniformly 12sp in dimmed grey. */
@Composable
internal fun ChatMetaText(
    text: String,
    modifier: Modifier = Modifier,
    maxLines: Int = 1
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

private val CHAT_CLOCK_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** Timestamp below a bubble (e.g. `17:55`). */
internal fun formatClockTime(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(CHAT_CLOCK_FORMATTER)

/**
 * "Thinking" glyph: a vector atom/thinking icon (matching the user's reference image).
 * Loads the resource R.drawable.ic_thinking, its colour follows the theme-colour tint given by the caller, scaled proportionally to the requested size.
 */
@Composable
internal fun ThinkingGlyph(
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    iconSize: Dp = 16.dp
) {
    Icon(
        painter = painterResource(R.drawable.ic_thinking),
        contentDescription = null,
        tint = tint,
        modifier = modifier.size(iconSize)
    )
}

/**
 * Content card: white base + 1px hairline + an optional header (title + copy) and footer statistics.
 *
 * The block below "Write .dsh-write-test.txt" in the reference image is exactly this: the header row holds the file path and "Copy",
 * the body is monospace code/diff, and the footer gives a summary such as `+3 -0 · 1 file`.
 */
@Composable
internal fun ChatCodeCard(
    title: String?,
    copyText: String?,
    modifier: Modifier = Modifier,
    footer: String? = null,
    content: @Composable () -> Unit
) {
    val shape = ChatStyle.cardShape
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(chatCardSurfaceColor())
            .border(ChatStyle.hairline, chatHairlineColor(), shape)
    ) {
        if (!title.isNullOrBlank() || !copyText.isNullOrBlank()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = ChatStyle.codeHeaderHeight)
                    .padding(start = Spacing.sm, end = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title.orEmpty(),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (!copyText.isNullOrBlank()) {
                    ChatCopyAction(copyText = copyText)
                }
            }
            ChatHairline()
        }
        content()
        if (!footer.isNullOrBlank()) {
            ChatHairline()
            Text(
                text = footer,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs)
            )
        }
    }
}

/** Small inline monospace panel: secondary content blocks such as a tool's "command" / "result" that need no copy button. */
@Composable
internal fun ChatMonoPanel(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(ChatStyle.panelShape)
            .background(chatMutedSurfaceColor())
            .padding(horizontal = Spacing.sm, vertical = Spacing.sm)
    ) {
        content()
    }
}

/**
 * A finger drag may hand off to the outer scroller at the boundary; the fling inertia after release must not overshoot the inner window.
 */
@Composable
internal fun rememberBoundNestedScrollConnection(scrollState: ScrollState): NestedScrollConnection {
    return remember(scrollState) {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset = if (source == NestedScrollSource.SideEffect) Offset(0f, available.y) else Offset.Zero

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
                Velocity(0f, available.y)
        }
    }
}

/** Fallback for compatibility with old references. */
internal val InnerScrollConsumeRemainder = object : NestedScrollConnection {
    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity = available
}

/**
 * "Copy" text button in a card header: briefly changes to "Copied" after being tapped.
 *
 * Height is 32dp (hit area about 36dp including padding): this is a secondary action in a very dense message area,
 * staggered in level from the tool row's 40dp primary hit area, so the whole screen is not full of big 44dp buttons.
 */
@Composable
internal fun ChatCopyAction(
    copyText: String,
    modifier: Modifier = Modifier,
    label: String = stringResource(R.string.chat_copy)
) {
    var copied by remember(copyText) { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    Row(
        modifier = modifier
            .clip(ChatStyle.panelShape)
            .clickable {
                scope.launch {
                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("chat-copy", copyText)))
                    copied = true
                }
            }
            .heightIn(min = 32.dp)
            .padding(horizontal = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        Icon(
            imageVector = if (copied) FeatherIcons.Check else FeatherIcons.Copy,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(12.dp)
        )
        Text(
            text = if (copied) stringResource(R.string.chat_copied) else label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    if (copied) {
        LaunchedEffect(copied) {
            delay(1500)
            copied = false
        }
    }
}
