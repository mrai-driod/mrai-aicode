package com.aicode.feature.agent.presentation.component

import android.content.ClipData
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aicode.R
import com.aicode.core.theme.Brand
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import com.aicode.core.ui.ChevronRotationStyle
import com.aicode.core.ui.ContentWidth
import com.aicode.core.ui.ExpandableChevronIcon
import com.aicode.feature.agent.presentation.AgentUIMessage
import com.aicode.feature.agent.presentation.hasVisibleContent
import com.aicode.feature.agent.presentation.MessageRole
import compose.icons.FeatherIcons
import compose.icons.feathericons.Check
import compose.icons.feathericons.ChevronDown
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.ChevronUp
import compose.icons.feathericons.Clock
import compose.icons.feathericons.Copy
import compose.icons.feathericons.Database
import compose.icons.feathericons.MoreHorizontal
import compose.icons.feathericons.RotateCcw
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Tool card entry duration (ms) and float-up start point: shift down slightly then fade in into place, using only the draw layer without affecting layout. */
private const val MESSAGE_ENTRY_ANIM_MS = 260
private val MESSAGE_ENTRY_RISE = 10.dp

/** Division result for one task turn: turn start point + all assistant messages within the turn (1 turn, n steps) + the turn-final one. */
private data class AgentTurn(
    val startMillis: Long,
    val assistantMessages: List<AgentUIMessage>,
    val endMessage: AgentUIMessage
)

/**
 * Token total within one task turn: the sum of input/output/cache hits of **every step** (every LLM call) within the turn.
 * Hung under the turn-final assistant message together with [computeTaskDurations], see [computeTurnUsage].
 */
internal data class TurnUsage(
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val cachedInputTokens: Int = 0
)

/**
 * Split messages into turns by 'user message -> before the next user message'.
 *
 * Turn-final judgement: the next message after it is a user message, or it is the last in the list and this turn has ended ([lastTurnFinished],
 * given by whether the agent is idle). A final turn still generating is not counted -- duration and usage only hold once this turn has finished work.
 *
 * The anchors/summaries inserted by context compaction fall within the turn (compaction happens before the request); if they took part in the division the turn start would be counted at the compaction
 * moment, so they are removed first.
 */
private fun splitTurns(messages: List<AgentUIMessage>, lastTurnFinished: Boolean): List<AgentTurn> {
    val turnMessages = messages.filter {
        !it.isCompactionMarker && !it.isContextSummary && !it.isCompactionFailure
    }
    if (turnMessages.isEmpty()) return emptyList()
    val turns = mutableListOf<AgentTurn>()
    var turnStart: Long? = null
    var assistants = mutableListOf<AgentUIMessage>()
    turnMessages.forEachIndexed { index, message ->
        when (message.role) {
            MessageRole.USER -> {
                turnStart = message.timestamp
                assistants = mutableListOf()
            }
            MessageRole.ASSISTANT -> {
                val start = turnStart ?: return@forEachIndexed
                assistants += message
                val isTurnEnd = if (index == turnMessages.lastIndex) {
                    lastTurnFinished
                } else {
                    turnMessages[index + 1].role == MessageRole.USER
                }
                if (isTurnEnd) {
                    turns += AgentTurn(start, assistants.toList(), message)
                    assistants = mutableListOf()
                }
            }
            MessageRole.TOOL -> Unit
        }
    }
    return turns
}

/**
 * Total duration of each task turn (milliseconds): the moment the turn-final assistant message is persisted - the moment that turn's user message was sent, i.e. the wall-clock time from the user pressing send
 * to the AI finishing work in this turn (including tool execution and time waiting for user authorisation). Returns 'message id -> duration';
 * only the turn-final assistant message has an entry.
 */
internal fun computeTaskDurations(
    messages: List<AgentUIMessage>,
    lastTurnFinished: Boolean
): Map<String, Long> = splitTurns(messages, lastTurnFinished)
    .filter { it.endMessage.timestamp > it.startMillis }
    .associate { it.endMessage.id to (it.endMessage.timestamp - it.startMillis) }

/**
 * Token total for each task turn: the sum of input/output/cache hits of **all steps** within the turn, returning 'message id -> [TurnUsage]';
 * likewise only the turn-final assistant message has an entry.
 *
 * The measure is 'how much this turn cost in total', not 'how much this step cost': a turn may call the model n times (tool loop);
 * showing it step by step would split one task's cost into fragments, and the intermediate steps' numbers are meaningless to the user.
 */
internal fun computeTurnUsage(
    messages: List<AgentUIMessage>,
    lastTurnFinished: Boolean
): Map<String, TurnUsage> = splitTurns(messages, lastTurnFinished).associate { turn ->
    turn.endMessage.id to TurnUsage(
        inputTokens = turn.assistantMessages.sumOf { it.inputTokens },
        outputTokens = turn.assistantMessages.sumOf { it.outputTokens },
        cachedInputTokens = turn.assistantMessages.sumOf { it.cachedInputTokens }
    )
}

/** Task duration formatting: under 1 minute shows `12s`, under 1 hour shows `2:05`, longer shows `1:02:05`. */
internal fun formatTaskDuration(millis: Long): String {
    val totalSeconds = ((millis + 500) / 1000).coerceAtLeast(1)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    val paddedSeconds = seconds.toString().padStart(2, '0')
    return when {
        hours > 0 -> "$hours:${minutes.toString().padStart(2, '0')}:$paddedSeconds"
        minutes > 0 -> "$minutes:$paddedSeconds"
        else -> "${seconds}s"
    }
}

/**
 * Cache hit rate of a single message: cache-hit input / total input (inputTokens includes the cache-hit portion), on the same basis as the Token statistics on the settings page.
 * Returns null when there are no input statistics or no cache was hit this time (no placeholder, to avoid mis-showing 'the channel does not report cache data' as a 0% hit).
 */
internal fun formatCacheHitRate(inputTokens: Int, cachedInputTokens: Int): String? {
    if (inputTokens <= 0 || cachedInputTokens <= 0) return null
    val rate = (cachedInputTokens * 100.0 / inputTokens).coerceAtMost(100.0)
    return "${rate.roundToInt()}%"
}

@Composable
internal fun AgentMessageItem(
    message: AgentUIMessage,
    /** Whether it is the newest message in the whole session (only it carries 'copy / more'). User messages are not subject to this limit: every one permanently carries
     *  this row of buttons, so you can copy or rewind what you sent at any time; assistant messages only carry it on the newest one, avoiding historical replies having a row of
     *  duplicate buttons hanging below them and fragmenting the chat history. Timestamps, usage and duration are information and are not controlled by it. */
    showActions: Boolean = false,
    liveOutput: String? = null,
    markdownCache: MarkdownRenderCache? = null,
    onRewindClick: ((String) -> Unit)? = null,
    onMoreClick: ((AgentUIMessage) -> Unit)? = null,
    onToolToggle: (() -> Unit)? = null,
    /** Persisted override of the tool row's expanded state (null = not yet manually toggled, take the default by content type); see [ToolMessageBody]. */
    toolExpandedOverride: Boolean? = null,
    /** Report the new state back when a tool row is manually expanded/collapsed, for the upper layer to persist. */
    onToolExpandedChange: ((Boolean) -> Unit)? = null,
    /** Outer padding of this item's content: when a tool-call group is expanded, [AIChatPanel] passes in an indent to distinguish levels. */
    contentPadding: PaddingValues = PaddingValues(0.dp),
    /** Total duration of this task turn (ms): non-null only on the turn-final assistant message, see [computeTaskDurations]. */
    taskDurationMs: Long? = null,
    /** Token total of this task turn (sum over 1 turn, n steps): non-null only on the turn-final assistant message, see [computeTurnUsage]. */
    turnUsage: TurnUsage? = null,
    /** Entry animation delay for a new message (ms): null means a historical message is shown directly; non-null means on first composition it fades in and expands after a delay. */
    entryDelayMs: Long? = null,
    /** Chunked rendering of a long message: when non-null, the body MarkdownContent renders only that fragment.
     *  Chunks join seamlessly (the first chunk carries reasoning, the last chunk carries the action row and bottom rounded corners); the copy button still copies the whole message.content. */
    contentSlice: String? = null,
    /** Whether it is the first chunk of a chunked message (renders the reasoning block, top rounded corners); always true for non-chunked messages. */
    isChunkHeader: Boolean = true,
    /** Whether to render this message's own reasoning block. Default true; when whole-turn folding extracts reasoning as a separate process item, pass false to avoid duplication. */
    reasoningVisible: Boolean = true,
    /** Reasoning process duration (ms): the actual reasoning duration recorded by the upper layer. */
    reasoningDurationMs: Long? = null,
    /** Whether it is the last chunk of a chunked message (renders the action row, bottom rounded corners, and the spacing to the next list item); always true for non-chunked messages. */
    isChunkFooter: Boolean = true,
) {
    if (message.isCompactionMarker) {
        // Compaction internal anchors no longer render a divider: the summary card already provides compaction feedback, avoiding duplication with the card.
        return
    }

    if (message.isContextSummary) {
        CompactionSummaryCard(message, markdownCache)
        return
    }

    if (message.isCompactionFailure) {
        CompactionFailureCard(message)
        return
    }

    if (message.isBackgroundNotification) {
        BackgroundNotificationBar(message)
        return
    }

    val hasReasoning = message.role == MessageRole.ASSISTANT && !message.reasoning.isNullOrEmpty()
    val hasContent = message.content.hasVisibleContent()
    val hasAttachments = message.attachments.isNotEmpty()
    // Images emitted directly by the model are persisted as attachments; a pure-image message has no body or reasoning, and likewise must be shown (cannot return early).
    if (message.role == MessageRole.ASSISTANT && !hasContent && !hasReasoning && !hasAttachments) return

    val isUser = message.role == MessageRole.USER
    // Chunked messages: leave only one paragraph gap between chunks, visually still one continuous passage of body text (under the DSH flat document flow there is no longer an outline seam).
    val chunked = contentSlice != null
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    // The user bubble expands with the text, at most to the same width as the AI bubble (message column width - padding on both sides of the list).
    // On large screens the message column is already width-limited and centred, so the bubble cap narrows with it and must not be computed from the whole screen width.
    val maxUserBubbleWidth = remember(screenWidthDp) {
        minOf(screenWidthDp.dp, ContentWidth.readable) - Spacing.lg * 2
    }
    var copied by remember { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val copyScope = rememberCoroutineScope()

    // Tool card entry: only played for messages newly appended during this browsing session (entryDelayMs non-null);
    // history, returning from another page, and item recycling/remounting all show directly (who should enter is decided by MessageEntryScheduler).
    // Entry only changes alpha and translationY (effective in the draw phase); the card height is in place from the frame it is inserted:
    // with AnimatedVisibility a not-yet-entered card would occupy no space at all, and every card entering would make the list height jump once,
    // which combined with bottom-following scrollToItem is a series of jitters -- and that is exactly what this animation was meant to eliminate.
    // Now the list height changes only once, when a message is inserted, and afterwards the animation never touches layout.
    var entered by rememberSaveable(message.id) { mutableStateOf(entryDelayMs == null) }
    LaunchedEffect(message.id) {
        if (entered) return@LaunchedEffect
        val delayMs = entryDelayMs ?: return@LaunchedEffect
        if (delayMs > 0) delay(delayMs)
        entered = true
    }
    val entryProgress by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(durationMillis = MESSAGE_ENTRY_ANIM_MS, easing = LinearOutSlowInEasing),
        label = "tool-entry"
    )

    // Over-long assistant messages are split by AIChatPanel into multiple bounded items (chunking) for rendering; no height-limited inner scrolling is done here any more;
    // each chunk is treated as a segment of an ordinary message: the body is rendered by chunk, reasoning only on the first chunk, the action row only on the last chunk.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // Level indent of group members (see the contentPadding comment): added at the outermost layer, so the card's own divider hairline and
            // 'command / result' panels indent with it, avoiding the impression that the group header and member rows start at the same position.
            .padding(contentPadding)
            // The LazyColumn no longer uses a uniform spacedBy: the last chunk (or a non-chunked message) carries its own spacing to the next item,
            // adjacent chunks join with zero spacing seamlessly, and a whole long reply still looks like one continuous passage.
            // Under the flat document flow there is no bubble border behind body text, so compact arrangement distinguishes 'turns'.
            .padding(bottom = if (isChunkFooter) Spacing.sm else 0.dp),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        if (hasReasoning && isChunkHeader && reasoningVisible) {
            // Reasoning is collapsed by default: the collapsed row takes only one line (showing the first line of the reasoning); tap to open the full text manually
            ReasoningBubble(
                text = message.reasoning.orEmpty(),
                cache = markdownCache,
                durationMs = reasoningDurationMs
            )
        }
        if (hasContent || hasAttachments || message.role != MessageRole.ASSISTANT) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                // Assistant messages left-aligned, user messages right-aligned
                horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
            ) {
                if (hasContent || message.role == MessageRole.TOOL) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (message.role == MessageRole.TOOL) {
                            // The tool row carries its own 1px hairline and status icon; this only handles the entry animation (only changes the draw layer, not layout)
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .graphicsLayer {
                                        alpha = entryProgress
                                        translationY = (1f - entryProgress) * MESSAGE_ENTRY_RISE.toPx()
                                    }
                            ) {
                                ToolMessageBody(
                                    message = message,
                                    liveOutput = liveOutput,
                                    expandedOverride = toolExpandedOverride,
                                    onExpandedChange = onToolExpandedChange,
                                    onToggle = onToolToggle
                                )
                            }
                        } else if (isUser) {
                            // User message: right-aligned light-coloured pill + dark text (no longer inverting the whole block to theme colour).
                            Surface(
                                shape = ChatStyle.bubbleShape,
                                color = chatUserBubbleColor(),
                                modifier = Modifier.widthIn(max = maxUserBubbleWidth)
                            ) {
                                SelectionContainer {
                                    CompositionLocalProvider(
                                        LocalTextSelectionColors provides TextSelectionColors(
                                            handleColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                            backgroundColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.24f),
                                        )
                                    ) {
                                        Text(
                                            text = message.content,
                                            color = chatUserBubbleTextColor(),
                                            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp),
                                            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm)
                                        )
                                    }
                                }
                            }
                        } else {
                            // Assistant body: no container, laid directly on the page background (document flow). Between chunks only one paragraph gap is left,
                            // so the whole message still looks like one continuous passage of body text.
                            SelectionContainer {
                                CompositionLocalProvider(
                                    LocalTextSelectionColors provides TextSelectionColors(
                                        handleColor = MaterialTheme.colorScheme.primary,
                                        backgroundColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.32f),
                                    )
                                ) {
                                    MarkdownContent(
                                        text = contentSlice ?: message.content,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.fillMaxWidth(),
                                        cache = markdownCache,
                                    )
                                }
                            }
                        }
                    }
                }
                if ((isUser || message.role == MessageRole.ASSISTANT) && hasAttachments) {
                    MessageAttachmentList(attachments = message.attachments)
                }
                // Metadata row below the bubble (not shown for tool messages): user messages permanently carry 'time + copy/rewind/more' on every one,
                // assistant messages only carry it on the newest one in the whole session, avoiding every reply having a row of buttons hanging below it and fragmenting the chat history.
                // The order is fixed as 'statistics (usage/cache/duration) -> copy -> more options': information first, action entry points tucked at the end of the row.
                //
                // Usage and duration are hung on the turn-final assistant message by **this turn's total** (sum of all calls over 1 turn, n steps):
                // showing step by step would split one task's cost into fragments, and the intermediate steps' numbers are meaningless to the user.
                val tokenStats = turnUsage
                    ?.takeIf { it.inputTokens > 0 || it.outputTokens > 0 }
                    ?.let {
                        val inStr = formatTokenCount(it.inputTokens.toLong())
                        val outStr = formatTokenCount(it.outputTokens.toLong())
                        "↑$inStr ↓$outStr"
                    }
                val cacheHitRate = turnUsage?.let {
                    formatCacheHitRate(it.inputTokens, it.cachedInputTokens)
                }
                val durationText = taskDurationMs?.let { formatTaskDuration(it) }
                val hasMeta = isUser || tokenStats != null || cacheHitRate != null || durationText != null
                val actionsVisible = isUser || showActions
                if ((hasContent || hasAttachments) && message.role != MessageRole.TOOL && isChunkFooter &&
                    (actionsVisible || hasMeta)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val iconTint = MaterialTheme.colorScheme.onSurfaceVariant
                        // Only add a gap if content has already been laid out, otherwise the first element of this row would be indented by a space for no reason
                        var emitted = false
                        // User message: the timestamp goes before the copy button (aligned with the '17:55 ⧉' below the bubble in the reference image)
                        if (isUser) {
                            ChatMetaText(text = formatClockTime(message.timestamp))
                            emitted = true
                        }
                        val copyButton: @Composable () -> Unit = {
                            MessageActionIconButton(
                                icon = if (copied) FeatherIcons.Check else FeatherIcons.Copy,
                                contentDescription = if (copied) stringResource(R.string.chat_copied) else stringResource(R.string.chat_copy),
                                tint = iconTint,
                                onClick = {
                                    copyScope.launch {
                                        clipboard.setClipEntry(
                                            ClipEntry(ClipData.newPlainText("message", message.content))
                                        )
                                        copied = true
                                    }
                                }
                            )
                        }
                        // User message: copy immediately follows the timestamp, the rewind button after it (keeping the original order)
                        if (isUser && actionsVisible) {
                            if (emitted) Spacer(Modifier.width(Spacing.xs))
                            if (hasContent) copyButton()
                            if (onRewindClick != null) {
                                MessageActionIconButton(
                                    icon = FeatherIcons.RotateCcw,
                                    contentDescription = stringResource(R.string.checkpoint_rewind_title),
                                    tint = iconTint,
                                    onClick = { onRewindClick(message.id) }
                                )
                            }
                            emitted = true
                        }
                        if (tokenStats != null) {
                            if (emitted) Spacer(Modifier.width(Spacing.sm))
                            ChatMetaText(text = tokenStats)
                            emitted = true
                        }
                        if (cacheHitRate != null) {
                            if (emitted) Spacer(Modifier.width(Spacing.sm))
                            Icon(
                                FeatherIcons.Database,
                                contentDescription = stringResource(R.string.chat_cache_hit_rate, cacheHitRate),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(Modifier.width(2.dp))
                            ChatMetaText(text = cacheHitRate)
                            emitted = true
                        }
                        if (durationText != null) {
                            if (emitted) Spacer(Modifier.width(Spacing.sm))
                            Icon(
                                FeatherIcons.Clock,
                                contentDescription = stringResource(R.string.chat_task_duration, durationText),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(Modifier.width(2.dp))
                            ChatMetaText(text = durationText)
                            emitted = true
                        }
                        // The assistant message's copy button goes to the left of 'more options' (after the statistics)
                        if (!isUser && actionsVisible && hasContent) {
                            if (emitted) Spacer(Modifier.width(Spacing.xs))
                            copyButton()
                            emitted = true
                        }
                        // 'More options' goes **last** in this row: in assistant messages it follows the copy button (information first,
                        // then the action entry point); user messages have no statistics item, so it naturally follows the rewind button, with spacing consistent with the button group.
                        if (actionsVisible && onMoreClick != null) {
                            if (emitted) Spacer(Modifier.width(Spacing.xs))
                            MessageActionIconButton(
                                icon = FeatherIcons.MoreHorizontal,
                                contentDescription = stringResource(R.string.chat_more_options),
                                tint = iconTint,
                                onClick = { onMoreClick(message) }
                            )
                        }
                    }
                    // Restore the icon 1.5s after a successful copy
                    if (copied) {
                        LaunchedEffect(copied) {
                            delay(1500)
                            copied = false
                        }
                    }
                }
            }
        }
    }
}

/**
 * Whole-turn task folding header (outermost layer): one line of 'Running / Completed in Xs + arrow'; tap to expand/collapse the whole turn's process.
 *
 * The collapsed state uses a right arrow (ChevronRight), the expanded state a down arrow (ChevronDown) -- matching the reference image;
 * while running ([running]) it only shows 'Running'; after completion it shows this turn's duration. Style aligned with [ToolCallGroupHeader].
 */
@Composable
internal fun TurnCollapseHeader(
    running: Boolean,
    durationMs: Long?,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = ChatStyle.toolRowMinHeight)
            .clip(ChatStyle.panelShape)
            .clickable(
                onClickLabel = stringResource(
                    if (expanded) R.string.common_collapse_action else R.string.common_expand
                ),
                onClick = onToggle
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (running) {
                stringResource(R.string.chat_turn_running)
            } else {
                stringResource(R.string.chat_turn_completed, durationMs?.let { formatTaskDuration(it) } ?: "")
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.width(Spacing.xs))
        ExpandableChevronIcon(
            expanded = expanded,
            style = ChevronRotationStyle.RIGHT_DOWN,
            contentDescription = if (expanded) stringResource(R.string.common_collapse_action) else stringResource(R.string.common_expand),
            tint = Brand.IconGray,
            size = 18.dp
        )
    }
}

@Composable
private fun MessageActionIconButton(
    icon: ImageVector,
    contentDescription: String,
    tint: Color,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(24.dp),
        colors = IconButtonDefaults.iconButtonColors(contentColor = tint),
    ) {
        Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(14.dp))
    }
}

/**
 * Lightweight hint bar for a background task completion notification: not shown as an ordinary user bubble, only in a compact strip form telling the user
 * which background command ended and whether it succeeded. Extract the <status>/<summary> fields from the notification text.
 */
@Composable
private fun BackgroundNotificationBar(message: AgentUIMessage) {
    val content = message.content
    val statuses = Regex("<status>(.*?)</status>")
        .findAll(content).map { it.groupValues.getOrNull(1)?.trim()?.lowercase() }.filterNotNull().toList()
    val summaries = Regex("<summary>(.*?)</summary>")
        .findAll(content).map { it.groupValues.getOrNull(1)?.trim() }.filterNotNull().toList()
    val dotColor = when {
        // A status of message means an inter-agent message (not a failure), using the primary colour; anything else not completed is treated as a failure.
        statuses.any { it != "completed" && it != "message" } -> MaterialTheme.colorScheme.error
        statuses.any { it == "message" } -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.semanticColors.success
    }
    val label = when {
        summaries.size <= 1 -> summaries.firstOrNull() ?: stringResource(R.string.chat_bg_command_done)
        else -> {
            // The summary original text is generated by the domain layer (the same copy is also fed to the AI); do not split the string by Chinese markers here to extract a task name --
            // changing the copy or switching to an English UI would break that parsing. With multiple entries, only report the count and the failure count.
            val failedCount = statuses.count { it != "completed" }
            if (failedCount > 0) {
                stringResource(R.string.chat_bg_commands_partial_failed, summaries.size, failedCount)
            } else {
                stringResource(R.string.chat_bg_commands_done, summaries.size)
            }
        }
    }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xs)) {
        ChatHairline()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = ChatStyle.toolRowMinHeight),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Text(
                text = label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Context compaction success row: collapsed by default to 'dot + Context compacted + arrow'; tap to expand and view the full summary.
 * Flat row + faint background panel, structurally the same as a tool row, using only the dot colour to distinguish the event type.
 */
@Composable
private fun CompactionSummaryCard(message: AgentUIMessage, markdownCache: MarkdownRenderCache?) {
    var expanded by remember(message.id) { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xs)) {
        ChatHairline()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(ChatStyle.panelShape)
                .background(chatMutedSurfaceColor())
                .clickable { expanded = !expanded }
                .padding(horizontal = Spacing.sm, vertical = Spacing.sm)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                )
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    text = stringResource(R.string.chat_context_compressed),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                ExpandableChevronIcon(
                    expanded = expanded,
                    contentDescription = if (expanded) stringResource(R.string.common_collapse_action) else stringResource(R.string.common_expand),
                    tint = Brand.IconGray,
                    size = 16.dp
                )
            }
            if (expanded && message.content.hasVisibleContent()) {
                Spacer(Modifier.height(Spacing.xs))
                MarkdownContent(
                    text = message.content,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth(),
                    cache = markdownCache
                )
            }
        }
    }
}

/**
 * Context compaction failure row: collapsed by default to 'dot + Compaction failed + first line of the reason + arrow'; tap to expand and view the full reason.
 * A flat row structurally the same as a tool row, distinguished by an error-colour dot.
 */
@Composable
private fun CompactionFailureCard(message: AgentUIMessage) {
    var expanded by remember(message.id) { mutableStateOf(false) }
    val reason = message.content.ifBlank { stringResource(R.string.chat_compaction_failed) }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xs)) {
        ChatHairline()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(ChatStyle.panelShape)
                .background(chatMutedSurfaceColor())
                .clickable { expanded = !expanded }
                .padding(horizontal = Spacing.sm, vertical = Spacing.sm)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.error)
                )
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    text = stringResource(R.string.chat_compaction_failed),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium
                )
                if (!expanded) {
                    Spacer(Modifier.width(Spacing.sm))
                    Text(
                        text = reason.replace("\n", " ").trim(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                ExpandableChevronIcon(
                    expanded = expanded,
                    contentDescription = if (expanded) stringResource(R.string.common_collapse_action) else stringResource(R.string.common_expand),
                    tint = Brand.IconGray,
                    size = 16.dp
                )
            }
            if (expanded && reason.isNotBlank()) {
                Spacer(Modifier.height(Spacing.xs))
                ChatMonoPanel {
                    Text(
                        text = reason,
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                    )
                }
            }
        }
    }
}
