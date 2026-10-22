package com.aicode.feature.agent.presentation.component

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.aicode.core.ui.AdaptiveModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import com.aicode.core.ui.AppTextField
import com.aicode.core.ui.dialogTextFieldColors
import com.aicode.feature.onboarding.domain.OnboardingStep
import com.aicode.feature.onboarding.presentation.onboardingTarget
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import com.aicode.core.ui.SegmentedTabs
import com.aicode.core.ui.rememberImeBottomInset
import com.aicode.feature.settings.presentation.component.ModelSearchField
import com.aicode.feature.settings.presentation.component.SettingsDivider
import com.aicode.feature.settings.presentation.component.SettingsGroup
import com.aicode.feature.settings.presentation.component.SettingsGroupHeader
import com.aicode.feature.settings.presentation.component.SettingsRow
import com.aicode.feature.settings.presentation.component.settingsPageBackground
import com.aicode.feature.agent.domain.model.ChatSession
import com.aicode.feature.agent.presentation.AgentUIState
import com.aicode.feature.agent.presentation.ChatSearchHit
import com.aicode.feature.agent.presentation.ChatSearchState
import com.aicode.feature.agent.presentation.BrowseClipboard
import com.aicode.feature.agent.presentation.FileBrowseState
import com.aicode.feature.agent.presentation.FileTreeNode
import com.aicode.feature.workspace.domain.isValidFileEntryName
import com.aicode.core.ui.ChevronRotationStyle
import com.aicode.core.ui.ExpandableChevronIcon
import compose.icons.FeatherIcons
import compose.icons.feathericons.ChevronDown
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.CheckSquare
import compose.icons.feathericons.Clipboard
import compose.icons.feathericons.Copy
import compose.icons.feathericons.Download
import compose.icons.feathericons.Edit2
import compose.icons.feathericons.FilePlus
import compose.icons.feathericons.Folder
import compose.icons.feathericons.FolderPlus
import compose.icons.feathericons.Globe
import compose.icons.feathericons.RefreshCw
import compose.icons.feathericons.Settings
import compose.icons.feathericons.Trash2
import compose.icons.feathericons.X
import androidx.compose.ui.res.stringResource
import com.aicode.R
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * Drawer content: a Tab switcher at the top for "Sessions" / "Files", and a "Settings" entry card at the bottom.
 * Tab0 is the root session list, where rows of sessions with sub-agents can expand in place; Tab1 is the current workspace's file tree.
 */
@Composable
fun ChatDrawerContent(
    sessions: List<ChatSession>,
    currentSessionId: String?,
    agentStates: Map<String, AgentUIState>,
    awaitingPermissionSessionIds: Set<String> = emptySet(),
    onSelect: (ChatSession) -> Unit,
    onDelete: (ChatSession) -> Unit,
    onDeleteSessions: ((Set<String>) -> Unit)? = null,
    onRename: (ChatSession, String) -> Unit,
    onTogglePin: (ChatSession) -> Unit,
    onExport: (ChatSession) -> Unit,
    subSessionsByParent: Map<String, List<ChatSession>> = emptyMap(),
    browseState: FileBrowseState,
    expandedPaths: Set<String>,
    expandingPath: String? = null,
    fileOpPaths: Set<String> = emptySet(),
    clipboard: BrowseClipboard? = null,
    pasteConflict: Pair<String, String>? = null,
    onToggleExpand: (String) -> Unit,
    onOpenFile: (String) -> Unit,
    onRefreshBrowse: () -> Unit,
    onCreateFile: (String, String) -> Unit,
    onCreateFolder: (String, String) -> Unit,
    onRenameEntry: (String, String) -> Unit,
    onDeleteEntry: (String) -> Unit,
    onCopyEntry: (String, String) -> Unit,
    onCutEntry: (String, String) -> Unit,
    onPasteEntry: (String, (Boolean) -> Unit) -> Unit,
    onPasteOverwrite: () -> Unit,
    onCancelPasteOverwrite: () -> Unit,
    onClearClipboard: () -> Unit,
    onNavigateToBrowser: (() -> Unit)? = null,
    onNavigateToSettings: () -> Unit,
    searchQuery: String,
    searchState: ChatSearchState,
    onSearchQueryChange: (String) -> Unit,
    onClearSearch: () -> Unit,
    onOpenSearchHit: (ChatSearchHit) -> Unit,
    modifier: Modifier = Modifier
) {
    // Tab and expansion state go into saveable: on large screens, after the drawer collapses the whole subtree leaves composition (see MainActivity's
    // SaveableStateProvider), so storing them with remember would reset to the "Sessions" tab every time you return to the chat page.
    var selectedTab by rememberSaveable { mutableStateOf(0) }
    var isSelectionMode by rememberSaveable { mutableStateOf(false) }
    val selectedSessionIds = remember { mutableStateListOf<String>() }
    var showBatchDeleteConfirm by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<ChatSession?>(null) }
    var pendingRename by remember { mutableStateOf<ChatSession?>(null) }
    var menuSession by remember { mutableStateOf<ChatSession?>(null) }
    val listState = rememberLazyListState()

    BackHandler(enabled = isSelectionMode) {
        isSelectionMode = false
        selectedSessionIds.clear()
    }

    // Tapping a session / reopening the drawer keeps the previous scroll position; it scrolls back to the top only when the same session's last-reply time changes (a message sent/received).
    var lastTouched by remember { mutableStateOf<Pair<String?, Long?>?>(null) }
    val currentUpdatedAt = sessions.firstOrNull { it.id == currentSessionId }?.updatedAt
    LaunchedEffect(currentSessionId, currentUpdatedAt) {
        val cur = currentSessionId to currentUpdatedAt
        val prev = lastTouched
        lastTouched = cur
        if (prev != null && prev.first == cur.first && prev.second != cur.second) {
            listState.scrollToItem(0)
        }
    }

    val imeInset = rememberImeBottomInset()
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(settingsPageBackground())
            .windowInsetsPadding(
                WindowInsets.systemBars.only(
                    WindowInsetsSides.Horizontal + WindowInsetsSides.Top
                )
            )
            .padding(horizontal = Spacing.lg)
            .padding(bottom = imeInset + Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        // Top Tab switcher
        SegmentedTabs(
            selected = selectedTab,
            labels = listOf(
                stringResource(R.string.subagent_tab_sessions),
                stringResource(R.string.drawer_tab_files)
            ),
            onSelect = { selectedTab = it }
        )

        // The chat-history search box sits directly below the Tab bar, visible only on the "Sessions" tab.
        if (selectedTab == 0 && isSelectionMode) {
            Surface(
                color = MaterialTheme.semanticColors.cardSurface,
                shape = RoundedCornerShape(Radius.mdLarge),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.md, vertical = Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            isSelectionMode = false
                            selectedSessionIds.clear()
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = FeatherIcons.X,
                            contentDescription = stringResource(R.string.common_cancel),
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Spacer(Modifier.width(Spacing.xs))
                    Text(
                        text = stringResource(R.string.chat_sessions_selected_count, selectedSessionIds.size),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    val allSessionIds: List<String> = remember(sessions, subSessionsByParent) {
                        val ids = mutableListOf<String>()
                        sessions.forEach { s ->
                            ids.add(s.id)
                            subSessionsByParent[s.id]?.forEach { sub -> ids.add(sub.id) }
                        }
                        ids
                    }
                    val allSelected = allSessionIds.isNotEmpty() && selectedSessionIds.size == allSessionIds.size
                    TextButton(onClick = {
                        if (allSelected) {
                            selectedSessionIds.clear()
                        } else {
                            selectedSessionIds.clear()
                            selectedSessionIds.addAll(allSessionIds)
                        }
                    }) {
                        Text(stringResource(if (allSelected) R.string.provider_models_deselect_all else R.string.provider_models_select_all))
                    }
                    IconButton(
                        onClick = { showBatchDeleteConfirm = true },
                        enabled = selectedSessionIds.isNotEmpty(),
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = FeatherIcons.Trash2,
                            contentDescription = stringResource(R.string.chat_batch_delete_title),
                            modifier = Modifier.size(18.dp),
                            tint = if (selectedSessionIds.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                        )
                    }
                }
            }
        } else if (selectedTab == 0) {
            ChatSearchField(
                query = searchQuery,
                onQueryChange = onSearchQueryChange,
                onClear = onClearSearch
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            when (selectedTab) {
                0 -> SessionListTab(
                    sessions = sessions,
                    currentSessionId = currentSessionId,
                    agentStates = agentStates,
                    awaitingPermissionSessionIds = awaitingPermissionSessionIds,
                    subSessionsByParent = subSessionsByParent,
                    listState = listState,
                    searchQuery = searchQuery,
                    searchState = searchState,
                    selectionMode = isSelectionMode,
                    selectedSessionIds = selectedSessionIds,
                    onToggleSelectSession = { s ->
                        if (s.id in selectedSessionIds) {
                            selectedSessionIds.remove(s.id)
                        } else {
                            selectedSessionIds.add(s.id)
                        }
                    },
                    onSelect = onSelect,
                    onLongClick = { menuSession = it },
                    onOpenSearchHit = onOpenSearchHit
                )
                1 -> FileBrowserTab(
                    state = browseState,
                    expandedPaths = expandedPaths,
                    expandingPath = expandingPath,
                    fileOpPaths = fileOpPaths,
                    clipboard = clipboard,
                    onToggleExpand = onToggleExpand,
                    onOpenFile = onOpenFile,
                    onRefresh = onRefreshBrowse,
                    onCreateFile = onCreateFile,
                    onCreateFolder = onCreateFolder,
                    onRenameEntry = onRenameEntry,
                    onDeleteEntry = onDeleteEntry,
                    onCopyEntry = onCopyEntry,
                    onCutEntry = onCutEntry,
                    onPasteEntry = onPasteEntry,
                    onClearClipboard = onClearClipboard
                )
            }
        }

        SettingsGroup {
            if (onNavigateToBrowser != null) {
                SettingsRow(
                    icon = FeatherIcons.Globe,
                    title = stringResource(R.string.browser_title),
                    onClick = onNavigateToBrowser
                )
                SettingsDivider()
            }
            SettingsRow(
                icon = FeatherIcons.Settings,
                title = stringResource(R.string.chat_settings),
                onClick = onNavigateToSettings,
                modifier = Modifier.onboardingTarget(OnboardingStep.ENTER_SETTINGS)
            )
        }
    }

    pasteConflict?.let { (_, targetPath) ->
        AlertDialog(
            onDismissRequest = onCancelPasteOverwrite,
            title = { Text(stringResource(R.string.file_browser_paste_conflict_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.file_browser_paste_conflict_message,
                        targetPath.substringAfterLast('/')
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = onPasteOverwrite) { Text(stringResource(R.string.common_overwrite)) }
            },
            dismissButton = {
                TextButton(onClick = onCancelPasteOverwrite) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    pendingDelete?.let { session ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.chat_delete_session)) },
            text = { Text(stringResource(R.string.chat_delete_session_confirm, session.title)) },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(session)
                    pendingDelete = null
                }) { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    menuSession?.let { session ->
        SessionActionSheet(
            session = session,
            onTogglePin = {
                menuSession = null
                onTogglePin(session)
            },
            onRename = {
                menuSession = null
                pendingRename = session
            },
            onExport = {
                menuSession = null
                onExport(session)
            },
            onBatchSelect = {
                menuSession = null
                isSelectionMode = true
                selectedSessionIds.clear()
                selectedSessionIds.add(session.id)
            },
            onDelete = {
                menuSession = null
                pendingDelete = session
            },
            onDismiss = { menuSession = null }
        )
    }

    if (showBatchDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showBatchDeleteConfirm = false },
            title = { Text(stringResource(R.string.chat_batch_delete_title)) },
            text = {
                Text(stringResource(R.string.chat_batch_delete_confirm, selectedSessionIds.size))
            },
            confirmButton = {
                TextButton(onClick = {
                    showBatchDeleteConfirm = false
                    val toDelete = selectedSessionIds.toSet()
                    selectedSessionIds.clear()
                    isSelectionMode = false
                    if (onDeleteSessions != null) {
                        onDeleteSessions(toDelete)
                    } else {
                        toDelete.forEach { sid: String ->
                            sessions.firstOrNull { it.id == sid }?.let { onDelete(it) }
                        }
                    }
                }) {
                    Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showBatchDeleteConfirm = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    pendingRename?.let { session ->
        var renameText by remember(session.id) { mutableStateOf(session.title) }
        AlertDialog(
            onDismissRequest = { pendingRename = null },
            title = { Text(stringResource(R.string.chat_rename_session)) },
            text = {
                AppTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    label = stringResource(R.string.chat_session_name),
                    modifier = Modifier.fillMaxWidth(),
                    colors = dialogTextFieldColors()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onRename(session, renameText)
                        pendingRename = null
                    },
                    enabled = renameText.isNotBlank() && renameText != session.title
                ) { Text(stringResource(R.string.common_rename)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRename = null }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }
}

/**
 * Tab0: session list. When the search query is non-empty, the list area switches to cross-session search results.
 */
@Composable
private fun SessionListTab(
    sessions: List<ChatSession>,
    currentSessionId: String?,
    agentStates: Map<String, AgentUIState>,
    awaitingPermissionSessionIds: Set<String>,
    subSessionsByParent: Map<String, List<ChatSession>>,
    listState: LazyListState,
    searchQuery: String,
    searchState: ChatSearchState,
    selectionMode: Boolean = false,
    selectedSessionIds: List<String> = emptyList(),
    onToggleSelectSession: (ChatSession) -> Unit = {},
    onSelect: (ChatSession) -> Unit,
    onLongClick: (ChatSession) -> Unit,
    onOpenSearchHit: (ChatSearchHit) -> Unit
) {
    if (searchQuery.isBlank()) {
        SessionListContent(
            sessions = sessions,
            currentSessionId = currentSessionId,
            agentStates = agentStates,
            awaitingPermissionSessionIds = awaitingPermissionSessionIds,
            subSessionsByParent = subSessionsByParent,
            listState = listState,
            selectionMode = selectionMode,
            selectedSessionIds = selectedSessionIds,
            onToggleSelectSession = onToggleSelectSession,
            onSelect = onSelect,
            onLongClick = onLongClick
        )
    } else {
        ChatSearchResults(state = searchState, onOpenHit = onOpenSearchHit)
    }
}

/** The Tab0 session list body (grouped by last reply time). Rows of sessions with sub-agents have an expand arrow at the end; when expanded, the sub-agents are listed indented below. */
@Composable
private fun SessionListContent(
    sessions: List<ChatSession>,
    currentSessionId: String?,
    agentStates: Map<String, AgentUIState>,
    awaitingPermissionSessionIds: Set<String>,
    subSessionsByParent: Map<String, List<ChatSession>>,
    listState: LazyListState,
    selectionMode: Boolean = false,
    selectedSessionIds: List<String> = emptyList(),
    onToggleSelectSession: (ChatSession) -> Unit = {},
    onSelect: (ChatSession) -> Unit,
    onLongClick: (ChatSession) -> Unit
) {
    if (sessions.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                stringResource(R.string.chat_no_sessions_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.md)
            )
        }
        return
    }
    var expandedIds by rememberSaveable(
        stateSaver = listSaver<Set<String>, String>(
            save = { it.toList() },
            restore = { it.toSet() }
        )
    ) { mutableStateOf(emptySet<String>()) }
    val groups = remember(sessions) {
        val now = System.currentTimeMillis()
        val pinned = sessions.filter { it.isPinned }
        val unpinned = sessions.filterNot { it.isPinned }
        buildList {
            if (pinned.isNotEmpty()) add(SessionGroup("pinned", pinned))
            addAll(buildSessionGroups(unpinned, now))
        }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize()
    ) {
        // Giving each group one item and rendering the whole group with a forEach inside it would make the LazyColumn lose its laziness:
        // a group like "Last 30 days" can hold hundreds of sessions, so a single item would have to compose hundreds of rows. Group headers and sessions each get their own item.
        groups.forEach { group ->
            item(key = "group-${group.groupKey}", contentType = "group-header") {
                SettingsGroupHeader(
                    text = sessionGroupLabel(group.groupKey, group.sessions.first())
                )
            }
            itemsIndexed(
                items = group.sessions,
                key = { _, session -> session.id },
                contentType = { _, _ -> "session" }
            ) { index, session ->
                Column {
                    if (index > 0) {
                        if (group.groupKey == "pinned") Spacer(Modifier.height(Spacing.sm)) else SettingsDivider()
                    }
                    val state = agentStates[session.id]
                    val isExecuting = state is AgentUIState.Loading || state is AgentUIState.Streaming
                    val subSessions = subSessionsByParent[session.id].orEmpty()
                    val expanded = session.id in expandedIds
                    ChatSessionRow(
                        session = session,
                        selected = if (selectionMode) false else session.id == currentSessionId,
                        isExecuting = isExecuting,
                        awaitingPermission = session.id in awaitingPermissionSessionIds,
                        pinned = session.isPinned,
                        selectionMode = selectionMode,
                        checked = session.id in selectedSessionIds,
                        onCheckedChange = { onToggleSelectSession(session) },
                        onClick = {
                            if (selectionMode) {
                                onToggleSelectSession(session)
                            } else {
                                onSelect(session)
                            }
                        },
                        onLongClick = {
                            if (selectionMode) {
                                onToggleSelectSession(session)
                            } else {
                                onLongClick(session)
                            }
                        },
                        trailing = if (subSessions.isEmpty()) null else {
                            {
                                SubAgentExpandToggle(
                                    expanded = expanded,
                                    count = subSessions.size,
                                    onToggle = {
                                        expandedIds = if (expanded) {
                                            expandedIds - session.id
                                        } else {
                                            expandedIds + session.id
                                        }
                                    }
                                )
                            }
                        }
                    )
                    AnimatedVisibility(
                        visible = expanded,
                        enter = expandVertically(expandFrom = androidx.compose.ui.Alignment.Top) + fadeIn(),
                        exit = shrinkVertically(shrinkTowards = androidx.compose.ui.Alignment.Top) + fadeOut()
                    ) {
                        Column {
                            subSessions.forEach { sub ->
                                val subState = agentStates[sub.id]
                                Row(modifier = Modifier.padding(start = Spacing.lg)) {
                                    ChatSessionRow(
                                        session = sub,
                                        selected = if (selectionMode) false else sub.id == currentSessionId,
                                        isExecuting = subState is AgentUIState.Loading ||
                                            subState is AgentUIState.Streaming,
                                        awaitingPermission = sub.id in awaitingPermissionSessionIds,
                                        pinned = false,
                                        selectionMode = selectionMode,
                                        checked = sub.id in selectedSessionIds,
                                        onCheckedChange = { onToggleSelectSession(sub) },
                                        onClick = {
                                            if (selectionMode) {
                                                onToggleSelectSession(sub)
                                            } else {
                                                onSelect(sub)
                                            }
                                        },
                                        onLongClick = {
                                            if (selectionMode) {
                                                onToggleSelectSession(sub)
                                            } else {
                                                onLongClick(sub)
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Search box at the bottom of Tab0: reuses the Settings page's pill search box, with a clear button when non-empty. */
@Composable
private fun ChatSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit
) {
    ModelSearchField(
        query = query,
        onQueryChange = onQueryChange,
        placeholder = stringResource(R.string.chat_search_hint),
        modifier = Modifier.fillMaxWidth(),
        trailing = if (query.isEmpty()) null else {
            {
                IconButton(onClick = onClear, modifier = Modifier.size(24.dp)) {
                    Icon(
                        imageVector = FeatherIcons.X,
                        contentDescription = stringResource(R.string.chat_search_clear),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    )
}

/** Search results area: three states - loading / no results / hit list. */
@Composable
private fun ChatSearchResults(
    state: ChatSearchState,
    onOpenHit: (ChatSearchHit) -> Unit
) {
    when {
        state.hits.isEmpty() && state.loading -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        state.hits.isEmpty() -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.chat_search_no_result),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.md)
                )
            }
        }
        else -> {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(state.hits, key = { it.messageId }) { hit ->
                    ChatSearchResultRow(
                        hit = hit,
                        query = state.query,
                        onClick = { onOpenHit(hit) }
                    )
                    SettingsDivider()
                }
            }
        }
    }
}

/** A single search result row: session title + hit snippet (query highlighted); tap to jump to it and position on it. */
@Composable
private fun ChatSearchResultRow(
    hit: ChatSearchHit,
    query: String,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm)
    ) {
        Text(
            text = hit.sessionTitle,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = highlightSnippet(
                snippet = hit.snippet,
                query = query,
                highlight = SpanStyle(
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** Marks every occurrence of the matched term in the snippet with a highlight style. */
private fun highlightSnippet(snippet: String, query: String, highlight: SpanStyle): AnnotatedString =
    buildAnnotatedString {
        append(snippet)
        if (query.isBlank()) return@buildAnnotatedString
        var start = 0
        while (true) {
            val index = snippet.indexOf(query, start, ignoreCase = true)
            if (index < 0) break
            addStyle(highlight, index, index + query.length)
            start = index + query.length
        }
    }

/** Sub-agent expand toggle at the end of a session row: shows the count and an arrow, consumes its own clicks, and does not trigger selection of the whole row. */
@Composable
private fun SubAgentExpandToggle(
    expanded: Boolean,
    count: Int,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.pill))
            .clickable(onClick = onToggle)
            .padding(horizontal = Spacing.sm, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        ExpandableChevronIcon(
            expanded = expanded,
            style = ChevronRotationStyle.RIGHT_DOWN,
            contentDescription = stringResource(
                if (expanded) R.string.drawer_collapse_subagents else R.string.drawer_expand_subagents
            ),
            size = 16.dp,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Tab1: the current workspace's file tree. Expands in place from the workspace root, with indentation showing the hierarchy;
 * every row shares the same horizontal scroll, so when a path is too deep you can swipe sideways to see the full name instead of it being truncated with an ellipsis.
 * Creating a file/folder is started from the long-press menu of a directory row (including the workspace root).
 */
@Composable
private fun FileBrowserTab(
    state: FileBrowseState,
    expandedPaths: Set<String>,
    expandingPath: String?,
    fileOpPaths: Set<String>,
    clipboard: BrowseClipboard?,
    onToggleExpand: (String) -> Unit,
    onOpenFile: (String) -> Unit,
    onRefresh: () -> Unit,
    onCreateFile: (String, String) -> Unit,
    onCreateFolder: (String, String) -> Unit,
    onRenameEntry: (String, String) -> Unit,
    onDeleteEntry: (String) -> Unit,
    onCopyEntry: (String, String) -> Unit,
    onCutEntry: (String, String) -> Unit,
    onPasteEntry: (String, (Boolean) -> Unit) -> Unit,
    onClearClipboard: () -> Unit
) {
    var creating by remember { mutableStateOf<CreateTarget?>(null) }
    var menuNode by remember { mutableStateOf<FileTreeNode?>(null) }
    var pendingRename by remember { mutableStateOf<FileTreeNode?>(null) }
    var pendingDelete by remember { mutableStateOf<FileTreeNode?>(null) }
    val hScroll = rememberScrollState()

    Box(modifier = Modifier.fillMaxSize()) {
        when (state) {
            is FileBrowseState.Loading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            }

            is FileBrowseState.Error -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = state.detail ?: stringResource(R.string.file_browser_error),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = Spacing.md)
                )
            }

            is FileBrowseState.Success -> {
                val listState = rememberLazyListState()
                val textMeasurer = rememberTextMeasurer()
                val textStyle = MaterialTheme.typography.bodyMedium
                val density = LocalDensity.current
                // Pre-compute the content width of the widest row (fixed overhead + indentation + text) so all rows take a uniform width,
                // making the whole tree pan horizontally as one (rather than each row scrolling on its own).
                // Measuring row by row in a large directory would run thousands of text measurements synchronously during composition; instead, first use "indentation + character-width weighting"
                // to pick out the widest row and run one real measurement on it only. The estimate is imprecise, but it is only used here to leave enough width for horizontal scrolling.
                val maxContentPx = remember(state.nodes, textStyle) {
                    with(density) {
                        val widest = state.nodes.maxByOrNull { node ->
                            val label = if (node.isRoot) WORKSPACE_LABEL else node.entry.name
                            node.depth * 4 + label.sumOf { ch -> if (ch.code > 0x2E80) 2 else 1 }
                        } ?: return@with 0
                        val label = if (widest.isRoot) WORKSPACE_LABEL else widest.entry.name
                        val textW = textMeasurer.measure(label, textStyle).size.width
                        (FILE_TREE_ROW_OVERHEAD.toPx() + (Spacing.lg * widest.depth).toPx() + textW).toInt()
                    }
                }
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val rowWidthPx = maxOf(constraints.maxWidth, maxContentPx)
                    val rowWidth = with(density) { rowWidthPx.toDp() }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxHeight()
                            .horizontalScroll(hScroll)
                    ) {
                        items(state.nodes, key = { it.path }) { node ->
                            FileTreeRow(
                                node = node,
                                rowWidth = rowWidth,
                                expanding = node.entry.isDirectory && node.path == expandingPath,
                                busy = node.path in fileOpPaths,
                                onClick = {
                                    if (node.entry.isDirectory) onToggleExpand(node.path)
                                    else onOpenFile(node.path)
                                },
                                onLongClick = { menuNode = node },
                                modifier = Modifier.animateItem(
                                    fadeInSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
                                    fadeOutSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
                                    placementSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing)
                                )
                            )
                        }
                    }
                }
            }
        }

        // Action row in the top-right corner: clipboard indicator (with a background and border, distinguishing it from the background-less refresh icon) + refresh button.
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(Spacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            clipboard?.let { clip ->
                ClipboardIndicator(
                    clipboard = clip,
                    onClear = onClearClipboard,
                    modifier = Modifier.padding(end = Spacing.xs)
                )
            }
            if (fileOpPaths.isNotEmpty()) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(end = Spacing.xs).size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            IconButton(
                onClick = onRefresh,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = FeatherIcons.RefreshCw,
                    contentDescription = stringResource(R.string.file_browser_refresh),
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    creating?.let { target ->
        FileNameInputDialog(
            title = stringResource(
                if (target.kind == CreateKind.FILE) R.string.file_browser_new_file
                else R.string.file_browser_new_folder
            ),
            confirmLabel = stringResource(R.string.common_create),
            initialName = "",
            onConfirm = { name ->
                if (target.kind == CreateKind.FILE) onCreateFile(target.parent, name)
                else onCreateFolder(target.parent, name)
                creating = null
            },
            onDismiss = { creating = null }
        )
    }

    menuNode?.let { node ->
        FileTreeActionSheet(
            node = node,
            clipboard = clipboard,
            onNewFile = {
                menuNode = null
                if (!node.isRoot && node.path !in expandedPaths) onToggleExpand(node.path)
                creating = CreateTarget(node.path, CreateKind.FILE)
            },
            onNewFolder = {
                menuNode = null
                if (!node.isRoot && node.path !in expandedPaths) onToggleExpand(node.path)
                creating = CreateTarget(node.path, CreateKind.FOLDER)
            },
            onRename = {
                menuNode = null
                pendingRename = node
            },
            onDelete = {
                menuNode = null
                pendingDelete = node
            },
            onCopy = {
                menuNode = null
                onCopyEntry(node.path, node.entry.name)
            },
            onCut = {
                menuNode = null
                onCutEntry(node.path, node.entry.name)
            },
            onPasteHere = {
                val target = node.path
                menuNode = null
                onPasteEntry(target) { /* Whether to show a hint is decided by the caller; stay silent here */ }
            },
            onDismiss = { menuNode = null }
        )
    }

    pendingRename?.let { node ->
        FileNameInputDialog(
            title = stringResource(R.string.common_rename),
            confirmLabel = stringResource(R.string.common_rename),
            initialName = node.entry.name,
            onConfirm = { name ->
                onRenameEntry(node.path, name)
                pendingRename = null
            },
            onDismiss = { pendingRename = null }
        )
    }

    pendingDelete?.let { node ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.common_delete)) },
            text = {
                Text(
                    stringResource(
                        if (node.entry.isDirectory) {
                            R.string.file_browser_delete_folder_confirm
                        } else {
                            R.string.file_browser_delete_file_confirm
                        },
                        node.entry.name
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteEntry(node.path)
                    pendingDelete = null
                }) { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }
}

/** The type of the new object; decides whether confirmation calls create-file or create-folder. */
private enum class CreateKind { FILE, FOLDER }

/** New-object target: in which directory ([parent]) to create it, and whether it is a file or a folder ([kind]). */
private data class CreateTarget(val parent: String, val kind: CreateKind)

/** Name-input dialog shared by create / rename: confirmation is disabled when the name is invalid or identical to the original name. */
@Composable
private fun FileNameInputDialog(
    title: String,
    confirmLabel: String,
    initialName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            AppTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = stringResource(R.string.file_browser_name_label),
                modifier = Modifier.fillMaxWidth(),
                colors = dialogTextFieldColors()
            )
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name) },
                enabled = isValidFileEntryName(name) && name.trim() != initialName
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}

/** Clipboard indicator: a theme-coloured icon next to the refresh button in the top-right corner, showing the copy/cut state; tap to clear. */
@Composable
private fun ClipboardIndicator(
    clipboard: BrowseClipboard,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    IconButton(
        onClick = onClear,
        modifier = modifier.size(32.dp)
    ) {
        Icon(
            imageVector = if (clipboard.isCut) Icons.Filled.ContentCut else FeatherIcons.Copy,
            contentDescription = stringResource(
                if (clipboard.isCut) R.string.file_browser_clipboard_cut
                else R.string.file_browser_clipboard_copy
            ),
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.primary
        )
    }
}

/** Action menu shown on long-press of a file-tree node: directories (including the workspace root) can create entries; non-root nodes can be copied/cut/renamed/deleted;
 * when the clipboard is non-empty, a directory offers "Paste here". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileTreeActionSheet(
    node: FileTreeNode,
    clipboard: BrowseClipboard?,
    onNewFile: () -> Unit,
    onNewFolder: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onPasteHere: () -> Unit,
    onDismiss: () -> Unit
) {
    AdaptiveModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = Spacing.xl)
        ) {
            Text(
                text = if (node.isRoot) WORKSPACE_LABEL else node.entry.name,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(horizontal = Spacing.lg)
                    .padding(bottom = Spacing.md)
            )
            // Clipboard non-empty and the current node is a directory / the root: offer "Paste here" first.
            if (clipboard != null && node.entry.isDirectory) {
                SheetActionRow(
                    icon = FeatherIcons.Clipboard,
                    label = stringResource(R.string.file_browser_paste_here),
                    tint = MaterialTheme.colorScheme.primary,
                    onClick = onPasteHere
                )
            }
            if (node.entry.isDirectory) {
                SheetActionRow(
                    icon = FeatherIcons.FilePlus,
                    label = stringResource(R.string.file_browser_new_file),
                    tint = MaterialTheme.colorScheme.onSurface,
                    onClick = onNewFile
                )
                SheetActionRow(
                    icon = FeatherIcons.FolderPlus,
                    label = stringResource(R.string.file_browser_new_folder),
                    tint = MaterialTheme.colorScheme.onSurface,
                    onClick = onNewFolder
                )
            }
            if (!node.isRoot) {
                SheetActionRow(
                    icon = FeatherIcons.Copy,
                    label = stringResource(R.string.common_copy),
                    tint = MaterialTheme.colorScheme.onSurface,
                    onClick = onCopy
                )
                SheetActionRow(
                    icon = Icons.Filled.ContentCut,
                    label = stringResource(R.string.common_cut),
                    tint = MaterialTheme.colorScheme.onSurface,
                    onClick = onCut
                )
                SheetActionRow(
                    icon = FeatherIcons.Edit2,
                    label = stringResource(R.string.common_rename),
                    tint = MaterialTheme.colorScheme.onSurface,
                    onClick = onRename
                )
                SheetActionRow(
                    icon = FeatherIcons.Trash2,
                    label = stringResource(R.string.common_delete),
                    tint = MaterialTheme.colorScheme.error,
                    onClick = onDelete
                )
            }
        }
    }
}

/** A single file-tree row: indented by [FileTreeNode.depth], directories carry an expand arrow.
 * All rows take a uniform [rowWidth] (working with the outer horizontalScroll), so the whole tree pans together when swiping horizontally,
 * and names are neither truncated nor wrapped. Colouring priority: read failure > .gitignore match (orange) > dotfile (dimmed) > normal. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileTreeRow(
    node: FileTreeNode,
    rowWidth: Dp,
    expanding: Boolean,
    busy: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Add a minimum delay to the expanding loading state: listing a directory in local mode is extremely fast, so avoid a spinner flashing on every expansion.
    var showExpanding by remember { mutableStateOf(false) }
    LaunchedEffect(expanding) {
        if (expanding) {
            delay(180)
            showExpanding = true
        } else {
            showExpanding = false
        }
    }
    val isDotfile = !node.isRoot && node.entry.name.startsWith(".")
    val decorationColor: Color? = when {
        node.hasError -> MaterialTheme.colorScheme.error
        node.ignored -> FileTreeIgnoredColor
        isDotfile -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
        else -> null
    }
    Row(
        modifier = modifier
            .width(rowWidth)
            .clip(RoundedCornerShape(10.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = Spacing.xs + 2.dp)
            .padding(start = Spacing.sm, end = Spacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Spacer(Modifier.width(Spacing.lg * node.depth))
        if (node.entry.isDirectory) {
            if (showExpanding) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                ExpandableChevronIcon(
                    expanded = node.isExpanded,
                    style = ChevronRotationStyle.RIGHT_DOWN,
                    contentDescription = stringResource(
                        if (node.isExpanded) R.string.file_browser_collapse else R.string.file_browser_expand
                    ),
                    size = 16.dp,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            Spacer(Modifier.width(16.dp))
        }
        Spacer(Modifier.width(Spacing.xs))
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary
            )
        } else {
            FileTreeIcon(node = node, decorationColor = decorationColor)
        }
        Spacer(Modifier.width(Spacing.sm))
        Text(
            text = if (node.isRoot) WORKSPACE_LABEL else node.entry.name,
            style = MaterialTheme.typography.bodyMedium,
            color = decorationColor ?: MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            softWrap = false
        )
    }
}

/** Type icon for a file-tree row: folders use a folder icon, files pick a monochrome linear icon by extension. When [decorationColor] is non-null everything is tinted uniformly (.gitignore/dotfile dimming). */
@Composable
private fun FileTreeIcon(node: FileTreeNode, decorationColor: Color?) {
    val vector = if (node.entry.isDirectory) {
        FeatherIcons.Folder
    } else {
        fileTypeIconFor(node.entry.name)
    }
    Icon(
        imageVector = vector,
        contentDescription = null,
        modifier = Modifier.size(20.dp),
        tint = decorationColor
            ?: if (node.entry.isDirectory) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** Dimming colour for .gitignore-matched entries (orange, VSCode-style). */
private val FileTreeIgnoredColor = Color(0xFFCC8844)

/** Display name of the file-tree root node, corresponding to the container path `~/workspace`. */
private const val WORKSPACE_LABEL = "workspace"

/** Fixed width overhead of a file-tree row excluding indentation and text (start 8 + arrow 16 + 4 + icon 20 + 8 + end 12), used to pre-compute the uniform row width for the whole tree. */
private val FILE_TREE_ROW_OVERHEAD = 68.dp


/**
 * Action menu shown on long-press of a session row: pin / rename / export / delete. The bottom-sheet style follows the git branch's RefActionSheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionActionSheet(
    session: ChatSession,
    onTogglePin: () -> Unit,
    onRename: () -> Unit,
    onExport: () -> Unit,
    onBatchSelect: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()
    AdaptiveModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = Spacing.xl)
        ) {
            Text(
                text = session.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(horizontal = Spacing.lg)
                    .padding(bottom = Spacing.md)
            )
            SheetActionRow(
                icon = Icons.Outlined.PushPin,
                label = stringResource(if (session.isPinned) R.string.chat_unpin_session else R.string.chat_pin_session),
                tint = MaterialTheme.colorScheme.onSurface,
                onClick = {
                    onDismiss()
                    onTogglePin()
                }
            )
            SheetActionRow(
                icon = FeatherIcons.Edit2,
                label = stringResource(R.string.common_rename),
                tint = MaterialTheme.colorScheme.onSurface,
                onClick = {
                    onDismiss()
                    onRename()
                }
            )
            SheetActionRow(
                icon = FeatherIcons.Download,
                label = stringResource(R.string.chat_export_session),
                tint = MaterialTheme.colorScheme.onSurface,
                onClick = {
                    onDismiss()
                    onExport()
                }
            )
            SheetActionRow(
                icon = FeatherIcons.CheckSquare,
                label = stringResource(R.string.chat_batch_select),
                tint = MaterialTheme.colorScheme.onSurface,
                onClick = {
                    onDismiss()
                    onBatchSelect()
                }
            )
            SheetActionRow(
                icon = FeatherIcons.Trash2,
                label = stringResource(R.string.common_delete),
                tint = MaterialTheme.colorScheme.error,
                onClick = {
                    onDismiss()
                    onDelete()
                }
            )
        }
    }
}

@Composable
private fun SheetActionRow(
    icon: ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit
) {
    Surface(onClick = onClick, color = Color.Transparent) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = Spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = tint
            )
            Spacer(Modifier.width(Spacing.lg))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = tint
            )
        }
    }
}

/** Drawer session group: sessions within the same time group (Today / Yesterday / Within 7 days / Within 30 days / Month), sorted by last reply time descending. */
internal data class SessionGroup(
    val groupKey: String,
    val sessions: List<ChatSession>
)

/**
 * Grouping of a session list sorted by last reply time (updatedAt) descending: Today / Yesterday / Within 7 days / Within 30 days / earlier by month.
 */
internal fun buildSessionGroups(sessions: List<ChatSession>, now: Long): List<SessionGroup> {
    val groups = mutableListOf<SessionGroup>()
    for (session in sessions) {
        val groupKey = sessionGroupKey(session.updatedAt, now)
        val lastIndex = groups.lastIndex
        if (lastIndex >= 0 && groups[lastIndex].groupKey == groupKey) {
            groups[lastIndex] = groups[lastIndex].copy(sessions = groups[lastIndex].sessions + session)
        } else {
            groups += SessionGroup(groupKey, listOf(session))
        }
    }
    return groups
}

/** Returns the group key a session belongs to; month groups are ISO year-month (e.g. 2026-05), the rest are fixed literals. */
internal fun sessionGroupKey(updatedAt: Long, now: Long): String {
    val zone = ZoneId.systemDefault()
    val day = Instant.ofEpochMilli(updatedAt).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val days = ChronoUnit.DAYS.between(day, today)
    return when {
        days <= 0L -> "today"
        days == 1L -> "yesterday"
        days <= 7L -> "7d"
        days <= 30L -> "30d"
        else -> YearMonth.from(day).toString()
    }
}

@Composable
private fun sessionGroupLabel(groupKey: String, anchorSession: ChatSession): String = when (groupKey) {
    "pinned" -> stringResource(R.string.session_group_pinned)
    "today" -> stringResource(R.string.session_group_today)
    "yesterday" -> stringResource(R.string.session_group_yesterday)
    "7d" -> stringResource(R.string.session_group_last_7_days)
    "30d" -> stringResource(R.string.session_group_last_30_days)
    else -> SimpleDateFormat(
        stringResource(R.string.session_group_month_format),
        Locale.getDefault()
    ).format(Date(anchorSession.updatedAt))
}
