package com.aicode.feature.agent.presentation.component

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Construction
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.aicode.core.theme.Brand
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import com.aicode.feature.agent.domain.session.SessionUseCase
import com.aicode.feature.agent.presentation.AgentUIMessage
import com.aicode.core.ui.ExpandableChevronIcon
import compose.icons.FeatherIcons
import compose.icons.feathericons.Check
import compose.icons.feathericons.ChevronDown
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.ChevronUp
import compose.icons.feathericons.Cpu
import compose.icons.feathericons.Database
import compose.icons.feathericons.Edit3
import compose.icons.feathericons.FileText
import compose.icons.feathericons.Search
import compose.icons.feathericons.Terminal
import compose.icons.feathericons.Tool
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.aicode.R

internal val DiffAddBg: Color
    @Composable get() = MaterialTheme.semanticColors.diffAddBg

internal val DiffAddText: Color
    @Composable get() = MaterialTheme.semanticColors.diffAdd

internal val DiffRemoveBg: Color
    @Composable get() = MaterialTheme.semanticColors.diffRemoveBg

internal val DiffRemoveText: Color
    @Composable get() = MaterialTheme.semanticColors.diffRemove

internal const val DIFF_COLLAPSE_THRESHOLD = 20
internal const val TOOL_SECTION_LINE_LIMIT = 20

/** Character limit for rendering static (non-live) tool output: the viewport height limit of 240dp already cannot show over-long output in full, but
 *  Text lays out and measures the full text (O number of characters) -- a card with 50KB of output scrolling into the viewport can take 10ms+ for a single layout,
 *  a direct source of composition spikes/dropped frames when quickly scrolling the chat history. Over the limit it is truncated for display; the full content goes to the original record. */
private const val TOOL_SECTION_CHAR_LIMIT = 16_000

/** Maximum height of the tool 'command / result' expanded window: beyond it, scrolling happens inside the window, no longer growing indefinitely. */
private val ToolSectionMaxHeight = 240.dp

/** Height of the fade-out mask at the bottom of the tool window (shown only when the content can still scroll further down). */
private val ToolSectionFadeHeight = 24.dp

/**
 * Tool message (DSH flat row): one line of 'tool icon + tool name + path + add/remove statistics + arrow', with a
 * 1px hairline above the row as a divider, no longer in an outlined card; tap to expand and view 'command / result', or for editFile / writeFile
 * expand into a diff card with a white background and outline (path + copy in the header, add/remove statistics in the footer).
 *
 * Status is no longer expressed by colour alone: the icon pulses while running, a warning icon is additionally hung on failure, and screen readers get separate status semantics.
 * When [liveOutput] is non-null it enters 'live output' mode: showing line-by-line accumulated output.
 *
 * The expanded state is controlled by [expandedOverride] + [onExpandedChange] (host-persisted, see
 * [com.aicode.feature.agent.presentation.AIAgentViewModel.toolExpansionOverrides]):
 * previously it was stored here with `remember(message.id)`; when an item scrolls out of the viewport and is recycled by the LazyColumn, or a full-screen route
 * disposes the whole chat composition, it would shrink back to the default state. null means the user has not manually toggled it yet -- at that point it is **collapsed by default**,
 * and all tools (including diff cards and todo cards) are treated alike; tap to open to see details.
 */
@Composable
internal fun ToolMessageBody(
    message: AgentUIMessage,
    liveOutput: String? = null,
    expandedOverride: Boolean? = null,
    onExpandedChange: ((Boolean) -> Unit)? = null,
    onToggle: (() -> Unit)? = null
) {
    val streaming = liveOutput != null
    val running = message.isToolRunning(liveOutput)
    val edit = if (!running && !message.isError &&
        (message.toolName == "editFile" || message.toolName == "writeFile")
    ) {
        remember(message.id, message.content) { parseEditDiff(message.content) }
    } else null

    val resultText = if (!running) {
        remember(message.id, message.content) { formatToolResult(message.content) }
    } else null
    val argHint = remember(message.toolArgs) { toolArgHint(message.toolArgs) }
    val argsFull = remember(message.toolArgs) { formatToolArgs(message.toolArgs) }

    val todoData = if (message.toolName == "todo" && !running && !message.isError) {
        remember(message.id, message.content) { parseTodoResult(message.content) }
    } else null
    val webSearchData = if (message.toolName == "websearch" && !running && !message.isError) {
        remember(message.id, message.content) { parseWebSearchResult(message.content) }
    } else null
    // Background task/sub-agent completion notification: piggybacked in this tool result sent to the AI, and also shown permanently to the user.
    val notifications = if (!running) {
        remember(message.id, message.content) { parseToolNotifications(message.content) }
    } else emptyList()

    // While executing it can also be collapsed/expanded (e.g. when bash is flooding the screen, collapse it to just the title row), whether or not there is currently output; with no output the collapsed state has no content, but it stays clickable with a consistent arrow
    val hasLiveOutput = !liveOutput.isNullOrBlank()
    val expandable = streaming || (!running && (edit != null || !resultText.isNullOrBlank() || !argsFull.isNullOrBlank()
            || (todoData != null && todoData.items.isNotEmpty()) || webSearchData != null))
    // Tool calls are always collapsed by default (diff cards and todo cards are no exception): whether to see details is for the user to tap open;
    // once manually toggled (expandedOverride non-null) the user's choice prevails. The expanded state is kept by the host,
    // so scrolling out of the viewport or returning from another page no longer loses it.
    val effectiveExpanded = expandedOverride == true

    val toolLabel = message.toolName ?: stringResource(R.string.common_tool)
    // File-related tools: take the path from the structured diff or the tool arguments, and uniformly display as 'tool name + path + file name'
    val filePath = if (edit != null) {
        edit.path
    } else {
        remember(message.toolArgs) { extractFilePathArg(message.toolArgs) }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = ChatStyle.toolRowMinHeight)
                .then(
                    if (expandable) Modifier.clickable {
                        onExpandedChange?.invoke(!effectiveExpanded)
                        onToggle?.invoke()
                    } else Modifier
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ToolStatusIcon(running = running, isError = message.isError, toolName = message.toolName)
            Spacer(Modifier.width(Spacing.sm))
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!filePath.isNullOrBlank()) {
                    Text(
                        text = toolLabel,
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.width(Spacing.xs))
                    // Path segment (omissible) + file name segment (always complete, highest priority): monospace, consistent with the code card header
                    val monoLabel = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace
                    )
                    val pathDir = filePath.substringBeforeLast('/')
                    if (pathDir.isNotEmpty()) {
                        Text(
                            text = pathDir + "/",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = monoLabel,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }
                    Text(
                        text = filePath.substringAfterLast('/'),
                        color = MaterialTheme.colorScheme.onSurface,
                        style = monoLabel,
                        maxLines = 1
                    )
                } else {
                    Text(
                        text = toolLabel,
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!argHint.isNullOrBlank()) {
                        Spacer(Modifier.width(Spacing.sm))
                        Text(
                            text = argHint,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }
                }
            }
            if (running) {
                // For the running entry: the end of the row uses the unified ripple scene copy to explain 'what it is doing'. The tool name arrives before execution starts
                // (AgentEvent.ToolCallStarted), so there is no need to stay at a generic 'Running';
                // it is visible in the collapsed state too, without needing to expand to know it is running.
                Spacer(Modifier.width(Spacing.sm))
                RippleText(text = stringResource(toolRunningLabelRes(message.toolName)))
            }
            if (edit != null) {
                DiffStat(added = edit.added, removed = edit.removed)
                Spacer(Modifier.width(Spacing.sm))
            }
            if (todoData != null && todoData.total > 0) {
                Text(
                    text = "${todoData.completed}/${todoData.total}",
                    color = if (todoData.completed == todoData.total) DiffAddText
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.width(Spacing.sm))
            }
            if (expandable) {
                ExpandableChevronIcon(
                    expanded = effectiveExpanded,
                    contentDescription = if (effectiveExpanded) stringResource(R.string.common_collapse_action) else stringResource(R.string.common_expand),
                    tint = Brand.IconGray,
                    size = 18.dp
                )
            }
        }
        if (streaming) {
            // The expanded state is structurally the same as the persisted card: first 'command' (tool arguments), then 'result' (tail of live output);
            // the collapsed state keeps only the title row. 'Still running' is expressed by the ripple scene copy at the end of the title row, not repeated here.
            AnimatedVisibility(
                visible = effectiveExpanded,
                enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
            ) {
                Column {
                    if (!argsFull.isNullOrBlank()) {
                        Spacer(Modifier.height(Spacing.sm))
                        ToolSection(label = stringResource(R.string.tool_instruction), content = argsFull)
                    }
                    if (hasLiveOutput) {
                        val truncated = remember(liveOutput) { liveOutput.takeLastLines(TOOL_SECTION_LINE_LIMIT) }
                        Spacer(Modifier.height(Spacing.sm))
                        ToolSection(label = stringResource(R.string.tool_result), content = truncated, live = true)
                    }
                }
            }
        } else {
            AnimatedVisibility(
                visible = effectiveExpanded,
                enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
            ) {
                Column(
                    modifier = Modifier.pointerInput(message.id) {
                        detectDoubleTapToCollapse {
                            onExpandedChange?.invoke(false)
                            onToggle?.invoke()
                        }
                    }
                ) {
                if (todoData != null && todoData.items.isNotEmpty()) {
                    Spacer(Modifier.height(Spacing.xs))
                    TodoCard(items = todoData.items)
                } else if (webSearchData != null) {
                    Spacer(Modifier.height(Spacing.xs))
                    WebSearchResultCard(result = webSearchData)
                } else if (edit != null) {
                    // Diff card: the header gives the path and 'copy', the footer gives the add/remove statistics (the DSH white-background outlined card)
                    Spacer(Modifier.height(Spacing.xs))
                    ChatCodeCard(
                        title = edit.path.ifBlank { null },
                        copyText = edit.hunks.joinToString("\n") { it.diff },
                        footer = stringResource(
                            R.string.tool_changed_files_summary,
                            edit.added,
                            edit.removed,
                            if (edit.path.isBlank()) 0 else 1
                        )
                    ) {
                        Column {
                            edit.hunks.forEach { h -> DiffView(diff = h.diff, startLine = h.startLine) }
                            Spacer(Modifier.height(Spacing.xs))
                        }
                    }
                } else {
                    if (!argsFull.isNullOrBlank()) {
                        Spacer(Modifier.height(Spacing.sm))
                        ToolSection(label = stringResource(R.string.tool_instruction), content = argsFull)
                    }
                    if (!resultText.isNullOrBlank()) {
                        Spacer(Modifier.height(Spacing.sm))
                        ToolSection(label = stringResource(R.string.tool_result), content = resultText)
                    }
                }
                }
            }
        }
        if (notifications.isNotEmpty()) {
            Spacer(Modifier.height(Spacing.sm))
            notifications.forEach { ToolNotificationRow(it) }
        }
        // File list: permanently shown at the bottom of the message after the tool finishes, one file per line; tap to open with a system app.
        if (!running && message.attachments.isNotEmpty()) {
            val opener = LocalAttachmentOpener.current
            MessageAttachmentList(
                attachments = message.attachments,
                onClick = { opener.open(it) }
            )
        }
    }
}

internal data class ToolNotificationInfo(val summary: String, val succeeded: Boolean, val isMessage: Boolean = false)

/**
 * Extract piggybacked notifications (background task/sub-agent completion) from the `notifications` field at the top level of the tool result transport JSON.
 * This field is injected by [com.aicode.feature.agent.domain.workflow.StatefulAgentWorkflow] while the AI is busy.
 */
internal fun parseToolNotifications(raw: String): List<ToolNotificationInfo> {
    val array = parseToolTransport(raw.withoutToolStatusPrefix())?.get("notifications") as? JsonArray
        ?: return emptyList()
    return array.mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        val summary = (obj["summary"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: return@mapNotNull null
        val status = (obj["status"] as? JsonPrimitive)?.contentOrNull
        ToolNotificationInfo(
            summary = summary,
            succeeded = status == "completed",
            isMessage = status == "message"
        )
    }
}

/** Piggybacked notification hint bar at the bottom of a tool card: status dot + summary. */
@Composable
private fun ToolNotificationRow(info: ToolNotificationInfo) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(
                    when {
                        info.isMessage -> MaterialTheme.colorScheme.primary
                        info.succeeded -> MaterialTheme.semanticColors.success
                        else -> MaterialTheme.colorScheme.error
                    }
                )
        )
        Text(
            text = info.summary,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun String.takeLastLines(maxLines: Int): String {
    if (maxLines <= 0 || isEmpty()) return ""
    var seen = 0
    for (i in lastIndex downTo 0) {
        if (this[i] == '\n' && ++seen == maxLines) {
            return substring(i + 1)
        }
    }
    return this
}

/**
 * Double-tap collapse detection: detectTapGestures' awaitFirstDown defaults to requireUnconsumed=true,
 * and content in the expanded area wrapped in a SelectionContainer / clickable consumes the down event, causing the first tap to be ignored and
 * a double-tap never to trigger. Here the first tap does not require unconsumed; it only judges 'two quick taps in a row'.
 */
private suspend fun PointerInputScope.detectDoubleTapToCollapse(onDoubleTap: () -> Unit) {
    val viewConfig = viewConfiguration
    awaitEachGesture {
        val firstDown = awaitFirstDown(requireUnconsumed = false)
        // If significant displacement occurs between the two taps (the user is scrolling), abandon the double-tap judgement
        if (!awaitTapOrSwipe(firstDown.id, firstDown.position, viewConfig.touchSlop)) return@awaitEachGesture
        val secondDown = withTimeoutOrNull(viewConfig.doubleTapTimeoutMillis) {
            awaitFirstDown(requireUnconsumed = false)
        } ?: return@awaitEachGesture
        if (secondDown.uptimeMillis - firstDown.uptimeMillis < viewConfig.doubleTapMinTimeMillis) {
            return@awaitEachGesture
        }
        if ((secondDown.position - firstDown.position).getDistance() > viewConfig.touchSlop) {
            return@awaitEachGesture
        }
        if (!awaitTapOrSwipe(secondDown.id, secondDown.position, viewConfig.touchSlop)) return@awaitEachGesture
        onDoubleTap()
    }
}

/** Wait for the specified pointer to lift; if displacement exceeds slop meanwhile (a scroll occurred), return false. */
private suspend fun AwaitPointerEventScope.awaitTapOrSwipe(
    pointerId: PointerId,
    downPosition: Offset,
    slop: Float
): Boolean {
    while (true) {
        val event = awaitPointerEvent(PointerEventPass.Main)
        val change = event.changes.firstOrNull { it.id == pointerId } ?: continue
        if ((change.position - downPosition).getDistance() > slop) return false
        if (!change.pressed) return true
    }
}

/**
 * Tool status icon: gives a glyph by tool type (file / terminal / search / task...), coloured by status --
 * pulsing while running, the whole icon turns red on failure, and back to a neutral colour on success (a screen full of green would steal attention).
 *
 * Failure no longer hangs an extra warning icon: the icon cell at the start of the row keeps one glyph, and status is expressed only by colour and screen-reader semantics,
 * so that two icons do not appear in the same row and the start-of-row position does not jump with success/failure. Colour-blind users rely on [statusLabel] (screen reader) to tell success from failure.
 */
@Composable
internal fun ToolStatusIcon(running: Boolean, isError: Boolean, toolName: String?) {
    val tint = when {
        isError -> DiffRemoveText
        running -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val pulseAlpha = if (running) {
        val transition = rememberInfiniteTransition(label = "tool-status-pulse")
        transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.35f,
            animationSpec = infiniteRepeatable(animation = tween(650), repeatMode = RepeatMode.Reverse),
            label = "tool-status-pulse-alpha"
        ).value
    } else {
        1f
    }
    val statusLabel = stringResource(
        when {
            running -> R.string.chat_tool_running
            isError -> R.string.chat_tool_failed
            else -> R.string.chat_tool_succeeded
        }
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = toolIcon(toolName),
            contentDescription = statusLabel,
            tint = tint,
            modifier = Modifier
                // Same cell width as the reasoning row: only then do the two rows' icon centres and the left edge of the following text align
                .size(ChatStyle.rowIconSize)
                .graphicsLayer { alpha = pulseAlpha }
        )
    }
}

/** Tool name glyph mapping: keys match the registered names in [com.aicode.feature.agent.domain.tool.ToolRegistry] (comparison ignores case). */
private fun toolIcon(toolName: String?): ImageVector = when (toolName?.lowercase()) {
    "editfile", "writefile", "generateimage" -> FeatherIcons.Edit3
    "readfile", "list", "sendfile", "viewimage" -> FeatherIcons.FileText
    "search", "websearch", "webfetch", "browser" -> FeatherIcons.Search
    "bash", "terminal" -> FeatherIcons.Terminal
    "todo" -> FeatherIcons.Check
    "task" -> FeatherIcons.Cpu
    "memory" -> FeatherIcons.Database
    else -> FeatherIcons.Tool
}

/**
 * Scene copy while a tool is executing: groups tool names into a user-glanceable 'what it is doing', for the unified busy indicator
 * ([AgentBusyIndicator] / [RippleText]) to use.
 *
 * Keys match [toolIcon] (tool registered names, ignoring case). The grouping principle is the 'user perspective' rather than the 'implementation perspective':
 * reading a file and listing a directory both count as 'Reading files', web search and fetching a web page both count as 'Searching online'. The tool name arrives before execution
 * starts (`AgentEvent.ToolCallStarted`), so the copy can follow the actual action rather than staying at 'Thinking'.
 * MCP dynamically registered tools, custom tools and other ungrouped ones all fall back to [R.string.chat_status_calling_tool].
 */
@StringRes
internal fun toolRunningLabelRes(toolName: String?): Int = when (toolName?.lowercase()) {
    "editfile", "writefile" -> R.string.chat_status_editing_file
    "readfile", "list", "sendfile", "viewimage" -> R.string.chat_status_reading_file
    "search", "websearch", "webfetch", "browser" -> R.string.chat_status_searching_web
    "bash", "terminal" -> R.string.chat_status_running_command
    "generateimage" -> R.string.chat_status_generating_image
    "todo" -> R.string.chat_status_updating_todo
    "task" -> R.string.chat_status_starting_subagent
    "memory" -> R.string.chat_status_reading_memory
    else -> R.string.chat_status_calling_tool
}

/**
 * Whether a tool is still executing: live output is in progress, or the result is still a pending placeholder.
 * [AIChatPanel] reuses this when judging whether a tool-call group should stay expanded, avoiding the two judgements drifting apart.
 */
internal fun AgentUIMessage.isToolRunning(liveOutput: String?): Boolean =
    liveOutput != null ||
        content.startsWith(SessionUseCase.PENDING_TOOL_MARKER) ||
        content.startsWith(SessionUseCase.LEGACY_PENDING_TOOL_MARKER)

/**
 * 'N tool calls' group header: DSH folds consecutive tool calls in a task turn into one row, **collapsed by default**,
 * tap to manually expand/collapse (it does not auto-pop open while running either).
 * Layout aligned with the reasoning row: tool icon on the left (hammer/construction) + call count in the middle + collapse arrow.
 *
 * When [running] is true, the 'N tool calls' row copy itself gets the ripple highlight, indicating this batch of calls is still running.
 * No separate three bouncing dots are hung, and the scene copy is not repeated here ('Editing file' and similar refer to **which specific tool**,
 * given by each tool row's running state after expanding, see [ToolMessageBody]).
 */
@Composable
internal fun ToolCallGroupHeader(
    count: Int,
    running: Boolean,
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
                // Accessibility: whether tapping this row will expand or collapse can be read out by a screen reader (the expanded state itself is expressed by the count text)
                onClickLabel = stringResource(
                    if (expanded) R.string.common_collapse_action else R.string.common_expand
                ),
                onClick = onToggle
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Outlined.Construction,
            contentDescription = null,
            tint = Brand.IconGray,
            modifier = Modifier.size(ChatStyle.rowIconSize)
        )
        Spacer(Modifier.width(Spacing.sm))
        // The weight hangs on the outer placeholder, and the ripple is drawn over the text's own size: the band sweeps only over the words 'N tool calls',
        // and does not crawl slowly across the row's blank space (see Modifier.rippleHighlight).
        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            RippleText(
                text = stringResource(R.string.chat_tool_calls_count, count),
                highlight = if (running) Color.White else null
            )
        }
        ExpandableChevronIcon(
            expanded = expanded,
            contentDescription = if (expanded) stringResource(R.string.common_collapse_action) else stringResource(R.string.common_expand),
            tint = Brand.IconGray,
            size = 18.dp
        )
    }
}

/** A titled content block in the expanded area (such as 'command' or 'result'): a faint-background monospace small panel; beyond the height limit it scrolls inside the window. */
@Composable
internal fun ToolSection(label: String, content: String, live: Boolean = false) {
    Text(
        text = label,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold
    )
    Spacer(Modifier.height(2.dp))

    val scrollState = rememberScrollState()
    var followLive by remember { mutableStateOf(true) }
    var displayedContent by remember { mutableStateOf(content) }
    LaunchedEffect(scrollState, live) {
        if (!live) return@LaunchedEffect
        scrollState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) followLive = false
        }
    }
    LaunchedEffect(scrollState, live) {
        if (!live) return@LaunchedEffect
        snapshotFlow { scrollState.isScrollInProgress to scrollState.canScrollForward }
            .collect { (scrolling, canScrollForward) ->
                if (!scrolling && !canScrollForward) followLive = true
            }
    }
    LaunchedEffect(content, live, followLive, scrollState.isScrollInProgress) {
        if (!live || (followLive && !scrollState.isScrollInProgress)) displayedContent = content
    }
    val fadeColor = MaterialTheme.colorScheme.background
    // Static over-long output truncation (see TOOL_SECTION_CHAR_LIMIT); live is controlled by the caller with takeLastLines.
    val renderContent = remember(content, live) {
        if (!live && content.length > TOOL_SECTION_CHAR_LIMIT) {
            content.take(TOOL_SECTION_CHAR_LIMIT) + "\n... (output too long, truncated for display)"
        } else {
            content
        }
    }
    Box {
        ChatMonoPanel(
            modifier = Modifier
                .heightIn(max = ToolSectionMaxHeight)
                .nestedScroll(rememberBoundNestedScrollConnection(scrollState))
                .verticalScroll(scrollState)
        ) {
            SelectionContainer {
                Text(
                    text = if (live) displayedContent else renderContent,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace
                    )
                )
            }
        }
        // Bottom fade: only overlay a layer when it can still scroll further down, hinting 'there is more below'
        if (scrollState.canScrollForward) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(ToolSectionFadeHeight)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, fadeColor)))
            )
        }
    }
}

/** Add/remove statistics pill: green '+N' and red '-M'. */
@Composable
internal fun DiffStat(added: Int, removed: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (added > 0) {
            Text(
                text = "+$added",
                color = DiffAddText,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
        if (added > 0 && removed > 0) Spacer(Modifier.width(Spacing.xs))
        if (removed > 0) {
            Text(
                text = "−$removed",
                color = DiffRemoveText,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/**
 * Coloured line-level diff view
 */
@Composable
internal fun DiffView(diff: String, startLine: Int) {
    val lines = remember(diff) { diff.split("\n") }
    val collapsible = lines.size > DIFF_COLLAPSE_THRESHOLD
    var expanded by remember(diff) { mutableStateOf(false) }
    val visibleLines = if (collapsible && !expanded) lines.take(DIFF_COLLAPSE_THRESHOLD) else lines

    val mono = MaterialTheme.typography.bodySmall.copy(
        fontFamily = FontFamily.Monospace
    )
    val removeCount = lines.count { it.startsWith("-") }
    val addCount = lines.count { it.startsWith("+") }
    val maxLineNo = startLine + lines.size - removeCount - addCount + maxOf(removeCount, addCount)
    val gutterChars = maxOf(2, maxLineNo.toString().length)
    val gutterColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)

    Column(modifier = Modifier.fillMaxWidth()) {
        // Horizontal scroll container on the outside, text selection on the inside: scroll gestures take priority,
        // avoiding the SelectionContainer occasionally stealing left/right swipes (once selection mode is active, dragging is consumed by selecting words).
        // The background colour and rounded corners are provided by the outer ChatCodeCard; this only handles line rendering and horizontal scrolling.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
        ) {
            SelectionContainer {
                Column {
                    var oldLineNo = startLine
                    var newLineNo = startLine
                    visibleLines.forEach { line ->
                        val marker = line.firstOrNull()
                        val (bg, fg) = when (marker) {
                            '+' -> DiffAddBg to DiffAddText
                            '-' -> DiffRemoveBg to DiffRemoveText
                            else -> Color.Transparent to MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        val lineNo = when (marker) {
                            '-' -> oldLineNo++
                            '+' -> newLineNo++
                            else -> { val n = newLineNo; oldLineNo++; newLineNo++; n }
                        }
                        val gutter = lineNo.toString().padStart(gutterChars)
                        val styled = buildAnnotatedString {
                            withStyle(SpanStyle(color = gutterColor)) {
                                append(gutter)
                                append("  ")
                            }
                            withStyle(SpanStyle(color = fg)) {
                                append(line.ifEmpty { " " })
                            }
                        }
                        Text(
                            text = styled,
                            style = mono,
                            softWrap = false,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(bg)
                                .padding(horizontal = Spacing.sm, vertical = 1.dp)
                        )
                    }
                }
            }
        }
        if (collapsible) {
            DiffExpandToggle(
                expanded = expanded,
                hiddenCount = lines.size - DIFF_COLLAPSE_THRESHOLD,
                onToggle = { expanded = !expanded }
            )
        }
    }
}

/** Footer toggle for a long diff */
@Composable
internal fun DiffExpandToggle(expanded: Boolean, hiddenCount: Int, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.sm))
            .clickable(onClick = onToggle)
            .padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        ExpandableChevronIcon(
            expanded = expanded,
            contentDescription = if (expanded) stringResource(R.string.common_collapse_action) else stringResource(R.string.common_expand),
            tint = Brand.IconGray,
            size = 16.dp
        )
        Spacer(Modifier.width(Spacing.xs))
        Text(
            text = if (expanded) stringResource(R.string.common_collapse_action) else stringResource(R.string.tool_expand_remaining, hiddenCount),
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

/** Diff fragment of a single editFile edit. */
internal data class EditHunk(val startLine: Int, val diff: String)

/** Structured diff parsed from an editFile result */
internal data class EditDiff(
    val path: String,
    val added: Int,
    val removed: Int,
    val hunks: List<EditHunk>
)

/**
 * Parse the structured diff for editFile / writeFile from persisted TOOL content
 */
internal fun parseEditDiff(content: String): EditDiff? {
    val dataObj = extractToolDataObject(content)
    if (dataObj != null) {
        return parseEditDiffObject(dataObj)
    }

    val start = content.indexOf('{')
    val end = content.lastIndexOf('}')
    if (start < 0 || end <= start) return null
    return runCatching {
        parseEditDiffObject(Json.parseToJsonElement(content.substring(start, end + 1)).jsonObject)
    }.getOrNull()
}

private fun parseEditDiffObject(obj: JsonObject): EditDiff? {
    val path = obj["path"]?.jsonPrimitive?.contentOrNull ?: ""
    val added = obj["added_lines"]?.jsonPrimitive?.intOrNull ?: 0
    val removed = obj["removed_lines"]?.jsonPrimitive?.intOrNull ?: 0

    val hunks = obj["hunks"]?.jsonArray?.mapNotNull { el ->
        val ho = el.jsonObject
        val d = ho["diff"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        EditHunk(startLine = ho["start_line"]?.jsonPrimitive?.intOrNull ?: 1, diff = d)
    } ?: run {
        val d = obj["diff"]?.jsonPrimitive?.contentOrNull ?: return null
        listOf(EditHunk(startLine = obj["start_line"]?.jsonPrimitive?.intOrNull ?: 1, diff = d))
    }
    if (hunks.isEmpty()) return null
    return EditDiff(path = path, added = added, removed = removed, hunks = hunks)
}

/**
 * Clean a persisted raw tool result into readable text
 */
internal fun formatToolResult(raw: String): String {
    val s = raw.withoutToolStatusPrefix()
    parseToolTransport(s)?.let { obj ->
        return when (obj["status"]?.jsonPrimitive?.contentOrNull) {
            "error" -> obj["message"]?.jsonPrimitive?.contentOrNull ?: s
            "success", "partial" -> formatToolData(obj["data"]) ?: s
            else -> s
        }
    }

    when {
        s.startsWith("Error(") -> {
            val msgIdx = s.indexOf("message=")
            if (msgIdx >= 0) {
                var body = s.substring(msgIdx + "message=".length)
                val codeIdx = body.lastIndexOf(", code=")
                body = if (codeIdx >= 0) body.substring(0, codeIdx) else body.removeSuffix(")")
                return body.trim()
            }
        }
        s.startsWith("Success(data=") -> {
            val inner = s.removePrefix("Success(data=").removeSuffix(")")
            return formatJsonData(inner) ?: inner.trim()
        }
        s.startsWith("Partial(data=") -> {
            var inner = s.removePrefix("Partial(data=")
            val msgIdx = inner.lastIndexOf(", message=")
            inner = if (msgIdx >= 0) inner.substring(0, msgIdx) else inner.removeSuffix(")")
            return formatJsonData(inner) ?: inner.trim()
        }
    }
    return s
}

private fun parseToolTransport(raw: String): JsonObject? {
    return runCatching {
        val obj = Json.parseToJsonElement(raw.trim()).jsonObject
        if (obj["status"] != null) obj else null
    }.getOrNull()
}

private fun extractToolDataObject(raw: String): JsonObject? {
    return (parseToolTransport(raw)?.get("data") as? JsonObject)
}

private fun formatToolData(data: JsonElement?): String? {
    return when (data) {
        is JsonPrimitive -> data.contentOrNull ?: data.toString()
        is JsonObject -> {
            val main = data["content"] ?: data["output"] ?: data["stdout"] ?: data["text"]
            val mainStr = (main as? JsonPrimitive)?.contentOrNull
            mainStr ?: data.entries.joinToString("\n") { (k, v) ->
                val vv = (v as? JsonPrimitive)?.contentOrNull ?: v.toString()
                "$k: $vv"
            }
        }
        null -> null
        else -> data.toString()
    }
}

internal fun String.withoutToolStatusPrefix(): String = trim()
    .removePrefix(SessionUseCase.LEGACY_STOPPED_TOOL_MARKER)
    .removePrefix(SessionUseCase.LEGACY_PENDING_TOOL_MARKER)
    .removePrefix(SessionUseCase.PENDING_TOOL_MARKER)
    .trim()

/**
 * Render the JsonElement text in `data=` as a readable result
 */
internal fun formatJsonData(jsonStr: String): String? = runCatching {
    when (val el = Json.parseToJsonElement(jsonStr.trim())) {
        is JsonPrimitive -> formatToolData(el) ?: jsonStr.trim()
        is JsonObject -> formatToolData(el) ?: jsonStr.trim()
        else -> jsonStr.trim()
    }
}.getOrNull()

/** List the incoming argument JSON as `key: value` lines */
internal fun formatToolArgs(argsJson: String?): String? {
    if (argsJson.isNullOrBlank()) return null
    return runCatching {
        val obj = Json.parseToJsonElement(argsJson).jsonObject
        if (obj.isEmpty()) return null
        obj.entries.joinToString("\n") { (k, v) ->
            val vv = (v as? JsonPrimitive)?.contentOrNull ?: v.toString()
            "$k: $vv"
        }
    }.getOrNull() ?: argsJson.trim()
}

/** Inline argument summary in the title row */
internal fun toolArgHint(argsJson: String?): String? {
    if (argsJson.isNullOrBlank()) return null
    return runCatching {
        val obj = Json.parseToJsonElement(argsJson).jsonObject
        val preferred = listOf("command", "cmd", "path", "file_path", "file", "query", "pattern", "url", "name")
        val v = preferred.firstNotNullOfOrNull { obj[it] } ?: obj.values.firstOrNull()
        val str = (v as? JsonPrimitive)?.contentOrNull ?: v?.toString()
        str?.replace("\n", " ")?.trim()?.takeIf { it.isNotEmpty() }
    }.getOrNull()
}

/** Extract the file path from tool argument JSON (the path argument of readFile/editFile/writeFile). */
private fun extractFilePathArg(argsJson: String?): String? {
    if (argsJson.isNullOrBlank()) return null
    return runCatching {
        val obj = Json.parseToJsonElement(argsJson).jsonObject
        listOf("path", "file_path", "file").firstNotNullOfOrNull { key ->
            (obj[key] as? JsonPrimitive)?.contentOrNull
        }
    }.getOrNull()
}
