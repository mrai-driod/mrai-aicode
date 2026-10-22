package com.aicode.feature.settings.presentation.component

import android.content.ClipData
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.aicode.core.ui.AdaptiveModalBottomSheet
import androidx.compose.material3.Surface
import com.aicode.core.ui.AppSwitch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aicode.R
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.LogLevelColors
import com.aicode.core.theme.semanticColors
import com.aicode.core.util.LogLevel
import com.aicode.feature.settings.presentation.LogViewerUiState
import compose.icons.FeatherIcons
import compose.icons.feathericons.Check
import compose.icons.feathericons.Copy
import compose.icons.feathericons.FileText
import compose.icons.feathericons.MessageSquare
import compose.icons.feathericons.X
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Combined log page: log level (tap to open the bottom sheet) + log file switching + search/copy + structured color-coded log list.
 * Page layout matches the settings page grouped style; log lines render logcat-style (level badge + time + tag + message).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LogSection(
    current: LogLevel,
    onSelect: (LogLevel) -> Unit,
    state: LogViewerUiState,
    onSelectFile: (String) -> Unit,
    onClearFilter: () -> Unit,
    onRefresh: () -> Unit
) {
    var showLevelSheet by remember { mutableStateOf(false) }
    var showFileSheet by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    val entries = remember(state.content) { parseLogLines(state.content) }
    val filtered = remember(entries, query) {
        if (query.isBlank()) entries
        else entries.filter { it.raw.contains(query, ignoreCase = true) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        // ── Log level + log file: two rows in one card ──
        SettingsGroup {
            SettingsRow(
                icon = FeatherIcons.FileText,
                title = stringResource(R.string.log_level),
                onClick = { showLevelSheet = true },
                trailing = {
                    Surface(
                        shape = RoundedCornerShape(Radius.pill),
                        color = logLevelColor(current).copy(alpha = 0.15f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .background(logLevelColor(current), RoundedCornerShape(Radius.pill))
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = current.name,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Medium,
                                color = logLevelColor(current)
                            )
                        }
                    }
                }
            )
            SettingsDivider()
            SettingsRow(
                title = stringResource(R.string.log_files),
                subtitle = state.selectedFileName
                    ?.removePrefix("log-")
                    ?.removeSuffix(".txt")
                    ?: stringResource(R.string.settings_log_no_files),
                onClick = { showFileSheet = true },
                enabled = state.files.isNotEmpty()
            )
        }

        // ── Search + copy ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ModelSearchField(
                query = query,
                onQueryChange = { query = it },
                placeholder = stringResource(R.string.log_search_hint),
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(Spacing.sm))
            CopyLogButton(
                text = filtered.joinToString("\n") { it.raw },
                enabled = filtered.isNotEmpty()
            )
        }

        // ── MCP filter badge (shown when entered from the MCP dialog, clearable)──
        state.filterServerName?.let { server ->
            Surface(
                shape = RoundedCornerShape(Radius.pill),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
            ) {
                Row(
                    modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.log_mcp_prefix, server),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    IconButton(
                        onClick = onClearFilter,
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            FeatherIcons.X,
                            contentDescription = stringResource(R.string.log_clear_filter),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }

        // ── Log content ──
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            shape = RoundedCornerShape(Radius.md),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            LogContent(
                state = state,
                entries = filtered,
                searchActive = query.isNotBlank(),
                onRefresh = onRefresh
            )
        }
    }

    if (showLevelSheet) {
        LogLevelSheet(
            current = current,
            onSelect = {
                onSelect(it)
                showLevelSheet = false
            },
            onDismiss = { showLevelSheet = false }
        )
    }

    if (showFileSheet) {
        LogFileSheet(
            files = state.files,
            selectedFileName = state.selectedFileName,
            onSelect = {
                onSelectFile(it)
                showFileSheet = false
            },
            onDismiss = { showFileSheet = false }
        )
    }
}

/** Log content card: loading / error / empty states / summary row + structured color-coded list; when live scrolling is on, silently refreshes every 3 seconds and follows the latest logs. */
@Composable
private fun LogContent(
    state: LogViewerUiState,
    entries: List<LogEntry>,
    searchActive: Boolean,
    onRefresh: () -> Unit
) {
    when {
        state.loading -> Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
        }

        state.error != null -> Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(Spacing.lg),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = state.error,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
        }

        entries.isEmpty() -> Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(
                    if (searchActive || state.filterServerName != null) {
                        R.string.log_no_match
                    } else {
                        R.string.settings_log_no_files
                    }
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        else -> {
            val listState = rememberLazyListState()
            val clipboard = LocalClipboard.current
            val scope = rememberCoroutineScope()
            var copiedIndex by remember { mutableStateOf<Int?>(null) }
            var menuIndex by remember { mutableStateOf<Int?>(null) }
            var followLatest by remember { mutableStateOf(true) }

            // Live scrolling: when on, silently refreshes every 3 seconds and follows the latest logs
            LaunchedEffect(followLatest, state.selectedFileName, state.filterServerName) {
                if (followLatest) {
                    while (true) {
                        delay(3000)
                        onRefresh()
                    }
                }
            }

            LaunchedEffect(entries.size, state.selectedFileName, state.filterServerName, followLatest) {
                if (followLatest && entries.isNotEmpty()) listState.scrollToItem(entries.lastIndex)
            }
            LaunchedEffect(copiedIndex) {
                if (copiedIndex != null) {
                    delay(1500)
                    copiedIndex = null
                }
            }

            fun copyLog(text: String) {
                scope.launch {
                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("log", text)))
                }
            }

            Column(modifier = Modifier.fillMaxSize()) {
                val context = LocalContext.current
                val summary = if (searchActive) {
                    context.getString(R.string.log_search_results, entries.size)
                } else if (state.totalLines > state.shownLines) {
                    context.getString(R.string.log_show_last_lines, entries.size, state.totalLines)
                } else {
                    context.getString(R.string.log_show_lines, entries.size)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = stringResource(R.string.log_follow_latest),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(Spacing.xs))
                    AppSwitch(
                        checked = followLatest,
                        onCheckedChange = { followLatest = it }
                    )
                }
                HorizontalDivider(
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize()
                ) {
                    itemsIndexed(entries) { index, entry ->
                        LogLine(
                            entry = entry,
                            copied = copiedIndex == index,
                            onClick = {
                                copyLog(entry.raw)
                                copiedIndex = index
                            },
                            onLongClick = { menuIndex = index }
                        )
                    }
                }
            }

            // Long-press custom copy: this line / this line plus stack / message content
            menuIndex?.let { index ->
                val entry = entries.getOrNull(index) ?: return@let
                val stackLines = entries.drop(index + 1).takeWhile { it.level == null }
                LogLineActionsSheet(
                    hasStack = stackLines.isNotEmpty(),
                    onCopyLine = {
                        copyLog(entry.raw)
                        copiedIndex = index
                        menuIndex = null
                    },
                    onCopyWithStack = {
                        copyLog((listOf(entry) + stackLines).joinToString("\n") { it.raw })
                        copiedIndex = index
                        menuIndex = null
                    },
                    onCopyMessage = {
                        copyLog(entry.message)
                        copiedIndex = index
                        menuIndex = null
                    },
                    onDismiss = { menuIndex = null }
                )
            }
        }
    }
}

/** Single log line: level badge (fixed-width aligned) + time/tag/message in separate colors; non-log lines (stack continuations / file-header markers) are gray for the whole line. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LogLine(
    entry: LogEntry,
    copied: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = Spacing.md, vertical = 3.dp),
        verticalAlignment = Alignment.Top
    ) {
        if (entry.level != null) {
            LogBadge(level = entry.level)
        } else {
            Spacer(Modifier.width(56.dp))
        }
        Spacer(Modifier.width(Spacing.sm))
        Text(
            text = entry.displayText(),
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 16.sp
            ),
            color = if (entry.level == null) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.weight(1f)
        )
        if (copied) {
            Icon(
                FeatherIcons.Check,
                contentDescription = stringResource(R.string.log_copied),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(start = Spacing.xs, top = 2.dp)
                    .size(14.dp)
            )
        }
    }
}

/** Level badge: fixed width, light level-color background + dark text, logcat style. */
@Composable
private fun LogBadge(level: LogLevel) {
    val color = logLevelColor(level)
    Box(
        modifier = Modifier
            .width(56.dp)
            .background(color.copy(alpha = 0.14f), RoundedCornerShape(4.dp))
            .padding(vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = level.name,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold
            ),
            color = color,
            maxLines = 1
        )
    }
}

/** Copy button: capsule style aligned with the search field; briefly switches to a checkmark after copying. */
@Composable
private fun CopyLogButton(text: String, enabled: Boolean) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }
    val idleColor = MaterialTheme.semanticColors.capsuleSurface
    Surface(
        onClick = {
            if (enabled) {
                scope.launch {
                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("log", text)))
                    copied = true
                }
            }
        },
        shape = RoundedCornerShape(50),
        color = when {
            copied -> MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
            enabled -> idleColor
            else -> idleColor.copy(alpha = 0.5f)
        }
    ) {
        Icon(
            imageVector = if (copied) FeatherIcons.Check else FeatherIcons.Copy,
            contentDescription = stringResource(if (copied) R.string.log_copied else R.string.log_copy),
            tint = if (copied) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(12.dp)
                .size(18.dp)
        )
    }
}

/** Log level bottom sheet: 6-level single-choice list, checkmark on the right of the selected item, file location note at the bottom. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogLevelSheet(
    current: LogLevel,
    onSelect: (LogLevel) -> Unit,
    onDismiss: () -> Unit
) {
    AdaptiveModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg)
        ) {
            Text(
                text = stringResource(R.string.log_level),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(R.string.log_level_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.xs)
            )
            Spacer(Modifier.height(Spacing.sm))
            LogLevel.entries.forEach { level ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(level) }
                        .padding(vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(logLevelColor(level), RoundedCornerShape(Radius.pill))
                    )
                    Spacer(Modifier.width(Spacing.md))
                    Text(
                        text = level.name,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    if (level == current) {
                        Icon(
                            FeatherIcons.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(Spacing.sm))
            Text(
                text = stringResource(R.string.log_file_location),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                modifier = Modifier.padding(bottom = Spacing.xl)
            )
        }
    }
}

/** Log file bottom sheet: scrollable date list to choose from, checkmark on the right of the selected item. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogFileSheet(
    files: List<String>,
    selectedFileName: String?,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AdaptiveModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = stringResource(R.string.log_files),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(Spacing.sm))
            files.forEach { fileName ->
                val selected = fileName == selectedFileName
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(fileName) }
                        .padding(vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = fileName.removePrefix("log-").removeSuffix(".txt"),
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier.weight(1f)
                    )
                    if (selected) {
                        Icon(
                            FeatherIcons.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(Spacing.xl))
        }
    }
}

/** Log line long-press actions sheet: custom copy scope (this line / this line plus stack / message only). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogLineActionsSheet(
    hasStack: Boolean,
    onCopyLine: () -> Unit,
    onCopyWithStack: () -> Unit,
    onCopyMessage: () -> Unit,
    onDismiss: () -> Unit
) {
    AdaptiveModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg)
        ) {
            Text(
                text = stringResource(R.string.log_copy_menu_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(Spacing.sm))
            LogActionRow(
                icon = FeatherIcons.Copy,
                title = stringResource(R.string.log_copy_line),
                onClick = onCopyLine
            )
            if (hasStack) {
                LogActionRow(
                    icon = FeatherIcons.FileText,
                    title = stringResource(R.string.log_copy_with_stack),
                    onClick = onCopyWithStack
                )
            }
            LogActionRow(
                icon = FeatherIcons.MessageSquare,
                title = stringResource(R.string.log_copy_message),
                onClick = onCopyMessage
            )
            Spacer(Modifier.height(Spacing.xl))
        }
    }
}

/** Actions sheet row: icon on the left + title, whole row clickable. */
@Composable
private fun LogActionRow(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(Spacing.md))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/** Level theme color: uniformly uses the LogLevelColors palette. */
private fun logLevelColor(level: LogLevel): Color = when (level) {
    LogLevel.VERBOSE -> LogLevelColors.Verbose
    LogLevel.DEBUG -> LogLevelColors.Debug
    LogLevel.INFO -> LogLevelColors.Info
    LogLevel.WARN -> LogLevelColors.Warn
    LogLevel.ERROR -> LogLevelColors.Error
    LogLevel.NONE -> LogLevelColors.None
}

/** One parsed log entry. A null level means a non-standard log line (stack continuation, file-header reset marker, etc.). */
private data class LogEntry(
    val level: LogLevel?,
    val time: String?,
    val tag: String?,
    val message: String,
    val raw: String
)

/** Matches the on-disk format of [com.aicode.core.util.FileLogger]: `yyyy-MM-dd HH:mm:ss.SSS LEVEL [TAG] message`. */
private val LOG_LINE_REGEX = Regex(
    """^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}) (VERBOSE|DEBUG|INFO|WARN|ERROR)(?: \[([^\]]*)\])? (.*)$"""
)

/** Parses log text into a list of lines: standard log lines are split into level/time/tag/message, other lines are kept as-is (stack continuations, reset markers). */
private fun parseLogLines(content: String): List<LogEntry> {
    val result = ArrayList<LogEntry>()
    for (line in content.lineSequence()) {
        val match = LOG_LINE_REGEX.matchEntire(line)
        if (match != null) {
            result.add(
                LogEntry(
                    level = LogLevel.valueOf(match.groupValues[2]),
                    time = match.groupValues[1],
                    tag = match.groupValues[3].takeIf { it.isNotEmpty() },
                    message = match.groupValues[4],
                    raw = line
                )
            )
        } else {
            result.add(LogEntry(level = null, time = null, tag = null, message = line, raw = line))
        }
    }
    return result
}

/** Log line display text: time in gray, tag in blue-gray, message in theme color; non-standard lines are shown as-is for the whole line. */
private fun LogEntry.displayText(): AnnotatedString {
    val level = level ?: return AnnotatedString(raw)
    return buildAnnotatedString {
        withStyle(SpanStyle(color = Color(0xFF9CA3AF))) {
            append(time ?: "")
            append(" ")
        }
        if (tag != null) {
            withStyle(SpanStyle(color = Color(0xFF94A3B8))) {
                append("[$tag] ")
            }
        }
        append(message)
    }
}
