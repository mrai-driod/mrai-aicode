package com.aicode.feature.git.presentation.component

import android.content.ClipData
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.aicode.core.ui.AdaptiveModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aicode.R
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.GitLanePalette
import com.aicode.core.theme.semanticColors
import com.aicode.feature.git.domain.model.GitFileChange
import com.aicode.feature.git.domain.model.GitGraph
import com.aicode.feature.git.domain.model.GitGraphRef
import com.aicode.feature.git.domain.model.GraphCommit
import com.aicode.feature.git.domain.model.GraphEdge
import compose.icons.FeatherIcons
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.Cloud
import compose.icons.feathericons.Copy
import compose.icons.feathericons.GitBranch
import compose.icons.feathericons.Tag
import compose.icons.feathericons.RotateCcw
import compose.icons.feathericons.Search
import compose.icons.feathericons.X

import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.graphics.RectangleShape
import com.aicode.core.ui.AppTextField
import com.aicode.core.ui.dialogTextFieldColors
import kotlinx.coroutines.launch

@Composable
internal fun LogTab(
    graph: GitGraph,
    commitFiles: Map<String, List<GitFileChange>>,
    loadingCommit: String?,
    graphLoadingMore: Boolean,
    listState: LazyListState,
    detailHash: String?,
    onOpenCommit: (String) -> Unit,
    onCloseCommit: () -> Unit,
    onFileDiff: (String, String) -> Unit,
    onLoadMore: () -> Unit,
    onCreateBranchAtCommit: (String, String) -> Unit = { _, _ -> },
    onCreateTagAtCommit: (String, String) -> Unit = { _, _ -> },
    onResetToCommit: (String, String) -> Unit = { _, _ -> }
) {
    val allCommits = graph.commits
    if (allCommits.isEmpty()) {
        EmptyState(stringResource(R.string.git_no_commits))
        return
    }

    var searchQuery by remember { mutableStateOf("") }
    val filteredCommits = remember(allCommits, searchQuery) {
        if (searchQuery.isBlank()) allCommits
        else {
            val q = searchQuery.trim().lowercase()
            allCommits.filter {
                it.message.lowercase().contains(q) ||
                    it.author.lowercase().contains(q) ||
                    it.shortHash.lowercase().contains(q)
            }
        }
    }
    val commits = filteredCommits

    // Lane palette: colours cycle by column index; the more branches, the richer the colours.
    val laneColors = rememberLaneColors(graph.maxLane + 1)
    // Edge list from each commit to its parents (grouped by commit index), for the Canvas to draw the connecting lines.
    val edgesByCommit = remember(graph) { groupEdgesByCommit(graph) }
    // Canvas width: one column per lane, plus left/right padding. For a linear history (no forks/merges) the lane width is tightened
    // to leave more room for the commit information; with forks each lane is 16dp. Nodes are automatically centred within their lane, so drawing needs no change.
    val laneWidth = if (graph.maxLane == 0) 10.dp else 16.dp
    val canvasWidth = laneWidth * (graph.maxLane + 1) + Spacing.xs * 2
    // Row height, used to compute the vertical span of the connecting lines (nodes are centred).
    val rowHeight = 72.dp
    // Prefetch the next page: when the list has scrolled close to the bottom (20 items remaining) and there are more commits, start a background fetch early, eliminating the wait when hitting the bottom.
    val shouldLoadMore by remember {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val totalItems = listState.layoutInfo.totalItemsCount
            totalItems > 0 && lastVisible >= totalItems - 20
        }
    }
    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore && graph.hasMore && !graphLoadingMore && searchQuery.isBlank()) onLoadMore()
    }
    val cardColor = MaterialTheme.semanticColors.cardSurface
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = stringResource(R.string.git_search_commits_hint),
                leadingIcon = {
                    Icon(FeatherIcons.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                },
                trailingIcon = if (searchQuery.isNotEmpty()) {
                    {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(FeatherIcons.X, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                    }
                } else null,
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
        SectionHeader(stringResource(R.string.git_commit_count, commits.size))
        // The scroll container fills the whole screen (consistent with the Status/Branches pages): the white card is part of the scrolling content —
        // when there is little content the card shrinks at the top; when scrolling, the whole card can scroll past the tab and be faded by the gradient mask,
        // and at the bottom the card's bottom edge stops above the tab (70dp of bottom padding).
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = Spacing.lg),
            state = listState,
            contentPadding = PaddingValues(bottom = 70.dp)
        ) {
            itemsIndexed(commits, key = { _, c -> c.hash }) { index, c ->
                val shape = when {
                    index == 0 && commits.size == 1 && (!graph.hasMore || searchQuery.isNotBlank()) -> RoundedCornerShape(Radius.lg)
                    index == 0 -> RoundedCornerShape(topStart = Radius.lg, topEnd = Radius.lg)
                    index == commits.lastIndex && (!graph.hasMore || searchQuery.isNotBlank()) -> RoundedCornerShape(bottomStart = Radius.lg, bottomEnd = Radius.lg)
                    else -> RectangleShape
                }
                Surface(
                    color = cardColor,
                    shape = shape,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val origIndex = allCommits.indexOfFirst { it.hash == c.hash }
                    GraphCommitRow(
                        commit = c,
                        lane = graph.lanes[c.hash] ?: 0,
                        edges = if (searchQuery.isBlank()) edgesByCommit[origIndex].orEmpty() else emptyList(),
                        activeTopLanes = if (searchQuery.isBlank()) graph.activeTopLanes[c.hash].orEmpty() else emptyList(),
                        activeBottomLanes = if (searchQuery.isBlank()) graph.activeBottomLanes[c.hash].orEmpty() else emptyList(),
                        laneColors = laneColors,
                        canvasWidth = if (searchQuery.isBlank()) canvasWidth else 0.dp,
                        laneWidth = if (searchQuery.isBlank()) laneWidth else 0.dp,
                        rowHeight = rowHeight,
                        refs = graph.refs[c.hash].orEmpty(),
                        isTopTerminal = origIndex == 0,
                        onOpen = { onOpenCommit(c.hash) }
                    )
                }
            }

            if (searchQuery.isBlank()) {
                item(key = "footer") {
                    Surface(
                        color = cardColor,
                        shape = RoundedCornerShape(bottomStart = Radius.lg, bottomEnd = Radius.lg),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = Spacing.md),
                            contentAlignment = Alignment.Center
                        ) {
                            if (graph.hasMore) {
                                if (graphLoadingMore) {
                                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                                } else {
                                    Text(
                                        stringResource(R.string.git_load_more_commits),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            } else {
                                Text(
                                    stringResource(R.string.git_no_more_commits),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Commit detail sheet: does not interrupt the list layout, and the lanes stay intact.
    detailHash?.let { hash ->
        val commit = graph.commits.find { it.hash == hash }
        if (commit != null) {
            CommitDetailSheet(
                commit = commit,
                files = commitFiles[hash],
                loading = loadingCommit == hash && commitFiles[hash] == null,
                onDismiss = onCloseCommit,
                onFileDiff = onFileDiff,
                onCreateBranch = { name -> onCreateBranchAtCommit(name, commit.hash) },
                onCreateTag = { name -> onCreateTagAtCommit(name, commit.hash) },
                onReset = { mode -> onResetToCommit(commit.hash, mode) }
            )
        }
    }
}

@Composable
private fun GraphCommitRow(
    commit: GraphCommit,
    lane: Int,
    edges: List<GraphEdge>,
    activeTopLanes: List<Int>,
    activeBottomLanes: List<Int>,
    laneColors: List<Color>,
    canvasWidth: Dp,
    laneWidth: Dp,
    rowHeight: Dp,
    refs: List<GitGraphRef>,
    /** The first row of the list (the newest commit): there are no commits above it, so no vertical line is drawn extending upward. */
    isTopTerminal: Boolean,
    onOpen: () -> Unit
) {
    val isMerge = commit.isMerge
    val nodeColor = laneColors.getOrElse(lane) { Color.Gray }
    Surface(
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .heightIn(min = rowHeight)
                .clickable(onClick = onOpen),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left topology-graph area: the Canvas draws nodes + connecting lines above and below.
            GraphCanvas(
                edges = edges,
                activeTopLanes = activeTopLanes,
                activeBottomLanes = activeBottomLanes,
                lane = lane,
                isMerge = isMerge,
                laneColors = laneColors,
                canvasWidth = canvasWidth,
                laneWidth = laneWidth,
                suppressTopLane = isTopTerminal,
                modifier = Modifier
                    .width(canvasWidth)
                    .fillMaxHeight()
            )
            // Right commit information. Vertical padding applies only to the content; the Canvas fills the whole row to keep lanes continuous across rows.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = Spacing.sm)
                    .padding(end = Spacing.lg)
            ) {
                // Ref label row (branch/tag pills).
                if (refs.isNotEmpty()) {
                    RefPills(refs = refs)
                    Spacer(Modifier.height(Spacing.xs))
                }
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        imageVector = FeatherIcons.ChevronRight,
                        contentDescription = stringResource(R.string.common_expand),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(Spacing.xs))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = commit.message,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(2.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                color = nodeColor.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(Radius.pill)
                            ) {
                                Text(
                                    text = commit.shortHash,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = nodeColor,
                                    modifier = Modifier.padding(horizontal = Spacing.sm, vertical = 2.dp),
                                    maxLines = 1
                                )
                            }
                            Spacer(Modifier.width(Spacing.sm))
                            Text(
                                text = commit.author,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = commit.date,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
        HorizontalDivider(
            color = MaterialTheme.semanticColors.subtleBorder,
            thickness = 0.5.dp,
            modifier = Modifier.padding(start = canvasWidth + Spacing.md)
        )
    }
}

/**
 * Topology graph Canvas: draws the current commit's node dot + branch connecting lines.
 *
 * Drawing order (later drawing covers earlier drawing, ensuring nodes sit on top of lines):
 * 1. Segmented vertical lines: combining [activeTopLanes] and [activeBottomLanes], for each lane draw, as needed, the full length (0→height),
 *    the upper half (0→centerY) or the lower half (centerY→height) as a vertical line, avoiding vertical lines hanging in mid-air at fork/merge nodes.
 * 2. Cross-column lines: draw a Bézier curve from the node centre to the target column in the lower half.
 * 3. Node dot: merge commits are drawn as a double ring, ordinary commits as a filled circle.
 */
@Composable
private fun GraphCanvas(
    edges: List<GraphEdge>,
    activeTopLanes: List<Int>,
    activeBottomLanes: List<Int>,
    lane: Int,
    isMerge: Boolean,
    laneColors: List<Color>,
    canvasWidth: Dp,
    laneWidth: Dp,
    /** First row (newest commit): do not draw the upper-half vertical line, to avoid it hanging above the node. */
    suppressTopLane: Boolean = false,
    modifier: Modifier = Modifier
) {
    val nodeColor = laneColors.getOrElse(lane) { Color.Gray }
    Canvas(modifier = modifier) {
        val lanePx = laneWidth.toPx()
        val padPx = Spacing.xs.toPx()
        val centerX = lane * lanePx + lanePx / 2f + padPx
        val centerY = size.height / 2f
        val stroke = 2.5.dp.toPx()

        // All relevant lanes (the union of top and bottom)
        val allLanes = (activeTopLanes + activeBottomLanes + lane).toSet()

        // Cross-column edges (both out-edges and in-edges) are drawn as Bézier curves in the lower half.
        // curveBotLanes records the columns whose lower-half vertical line is replaced by a curve: a column is excluded only when its upper half has no vertical line (not a through channel),
        // to avoid an isolated half segment; if the column's upper half already has a vertical line (the main line passes through), keep the lower-half vertical line so it runs through, with the curve drawn on top.
        val crossEdgeToLanes = edges.filter { it.fromLane != it.toLane }.map { it.toLane }.toSet()
        val curveBotLanes = crossEdgeToLanes.filterNot { it in activeTopLanes }.toSet()

        // 1. Segmented vertical-line drawing: precisely controls 0->centerY and centerY->height
        for (l in allLanes) {
            val inTop = l in activeTopLanes
            val inBot = l in activeBottomLanes && l !in curveBotLanes
            val color = laneColors.getOrElse(l) { Color.Gray }
            val x = l * lanePx + lanePx / 2f + padPx

            if (inTop && inBot) {
                // Runs through the whole row; the first row does not draw the upper half (no commits above).
                if (suppressTopLane) {
                    drawLine(
                        color = color,
                        start = Offset(x, centerY),
                        end = Offset(x, size.height),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round
                    )
                } else {
                    drawLine(
                        color = color,
                        start = Offset(x, 0f),
                        end = Offset(x, size.height),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round
                    )
                }
            } else if (inTop) {
                // Upper half only (terminates at the node, or the lower half turns into a diagonal curve); not drawn for the first row.
                if (!suppressTopLane) {
                    drawLine(
                        color = color,
                        start = Offset(x, 0f),
                        end = Offset(x, centerY),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round
                    )
                }
            } else if (inBot) {
                // Lower half only (newly forked/extended from this node)
                drawLine(
                    color = color,
                    start = Offset(x, centerY),
                    end = Offset(x, size.height),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round
                )
            }
        }

        // 2. Cross-column lines (all in the lower half): out-edges from the node centre → the bottom of the target column, in-edges from the bottom of the target column → the node centre
        for (edge in edges) {
            if (edge.fromLane == edge.toLane) continue
            val color = laneColors.getOrElse(edge.lane) { Color.Gray }
            val fromX = edge.fromLane * lanePx + lanePx / 2f + padPx
            val toX = edge.toLane * lanePx + lanePx / 2f + padPx
            val midY = centerY + (size.height - centerY) * 0.5f
            val path = Path().apply {
                if (edge.isMergeIn) {
                    // Merge in-edge: the parent side line curves in from the bottom of the target column to this node's centre
                    moveTo(toX, size.height)
                    cubicTo(
                        toX, midY,
                        fromX, midY,
                        fromX, centerY
                    )
                } else {
                    // Out-edge: this node forks from its centre towards the bottom of the target column
                    moveTo(fromX, centerY)
                    cubicTo(
                        fromX, midY,
                        toX, midY,
                        toX, size.height
                    )
                }
            }
            drawPath(
                path = path,
                color = color,
                style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }

        // 3. Node dot: merge commits are drawn as a double ring, ordinary commits as a filled circle.
        val nodeRadius = if (isMerge) 7.dp.toPx() else 5.dp.toPx()
        if (isMerge) {
            drawCircle(
                color = nodeColor,
                radius = nodeRadius,
                center = Offset(centerX, centerY),
                style = Stroke(width = 2.5.dp.toPx())
            )
            drawCircle(
                color = nodeColor,
                radius = nodeRadius / 2f,
                center = Offset(centerX, centerY)
            )
        } else {
            drawCircle(
                color = nodeColor,
                radius = nodeRadius,
                center = Offset(centerX, centerY)
            )
        }
    }
}

/** Commit ref label row: the current branch is highlighted in primary, the rest use their branch colours. */
@Composable
private fun RefPills(refs: List<GitGraphRef>) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        refs.forEach { ref ->
            val isCurrent = ref.isCurrent
            val bg = if (isCurrent) MaterialTheme.colorScheme.primary
                else if (ref.isBranch) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.tertiaryContainer
            val fg = if (isCurrent) MaterialTheme.colorScheme.onPrimary
                else if (ref.isBranch) MaterialTheme.colorScheme.onSecondaryContainer
                else MaterialTheme.colorScheme.onTertiaryContainer
            Surface(
                color = bg,
                shape = RoundedCornerShape(Radius.xs)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = Spacing.sm, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (ref.isRemote) FeatherIcons.Cloud else if (ref.isBranch) FeatherIcons.GitBranch else FeatherIcons.Tag,
                        contentDescription = null,
                        tint = fg,
                        modifier = Modifier.size(11.dp)
                    )
                    Spacer(Modifier.width(2.dp))
                    Text(
                        text = ref.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = fg,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/**
 * Lane palette: generates a set of highly distinguishable colours by column count, reused cyclically.
 * Colours are taken from common IDE branch palettes, moderate in lightness to suit light and dark themes.
 */
@Composable
private fun rememberLaneColors(count: Int): List<Color> {
    val palette = GitLanePalette.colors
    return remember(count) {
        (0 until count).map { palette[it % palette.size] }
    }
}

/**
 * Group [GitGraph.edges] by source commit index. [GitRepository.computeLanes] generates, in commit order,
 * `parents.size` edges for each commit (0 for a root commit), so edges are flat and ordered, and grouping by the cumulative parent count rebuilds the correspondence.
 * Returns a `Map<commit index, List<GraphEdge>>`, for [GraphCommitRow] to draw this row's lines.
 */
private fun groupEdgesByCommit(graph: GitGraph): Map<Int, List<GraphEdge>> {
    val result = mutableMapOf<Int, List<GraphEdge>>()
    var edgeIdx = 0
    graph.commits.forEachIndexed { commitIdx, commit ->
        // A root commit has no edges; a non-root commit has parents.size edges.
        val n = if (commit.parents.isEmpty()) 0 else commit.parents.size
        val list = mutableListOf<GraphEdge>()
        repeat(n) {
            if (edgeIdx < graph.edges.size) {
                list.add(graph.edges[edgeIdx++])
            }
        }
        result[commitIdx] = list
    }
    return result
}

@Composable
private fun CommitFilesSummary(count: Int, indent: Dp) {
    Text(
        text = stringResource(R.string.git_files_changed, count),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(start = indent, end = Spacing.lg, top = Spacing.sm, bottom = Spacing.xs)
    )
}

@Composable
private fun EmptyCommitFilesRow(indent: Dp) {
    Text(
        text = stringResource(R.string.git_no_files_changed),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(start = indent, end = Spacing.lg, top = Spacing.sm, bottom = Spacing.sm)
    )
}

@Composable
private fun LoadingFilesRow(indent: Dp) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = indent, end = Spacing.lg, top = Spacing.sm, bottom = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            strokeWidth = 2.dp
        )
        Spacer(Modifier.width(Spacing.sm))
        Text(
            text = stringResource(R.string.git_loading_files),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun CommitFileRow(file: GitFileChange, indent: Dp, onClick: () -> Unit = {}) {
    val fileName = file.path.substringAfterLast('/')
    val directory = file.path.substringBeforeLast('/', missingDelimiterValue = "")

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(start = indent, end = Spacing.lg, top = Spacing.xs, bottom = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatusChip(file.statusCode)
            Spacer(Modifier.width(Spacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = fileName,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (directory.isNotEmpty()) {
                    Text(
                        text = directory,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        HorizontalDivider(
            color = MaterialTheme.semanticColors.subtleBorder,
            thickness = 0.5.dp,
            modifier = Modifier.padding(start = indent + 44.dp)
        )
    }
}

/** Commit detail bottom sheet: full information + copy hash + changed file list (tap a file to open its diff). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CommitDetailSheet(
    commit: GraphCommit,
    files: List<GitFileChange>?,
    loading: Boolean,
    onDismiss: () -> Unit,
    onFileDiff: (String, String) -> Unit,
    onCreateBranch: (String) -> Unit = {},
    onCreateTag: (String) -> Unit = {},
    onReset: (String) -> Unit = {}
) {
    val sheetState = rememberModalBottomSheetState()
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    var showCreateBranchDialog by remember { mutableStateOf(false) }
    var showCreateTagDialog by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }
    var newBranchName by remember { mutableStateOf("") }
    var newTagName by remember { mutableStateOf("") }
    var resetMode by remember { mutableStateOf("mixed") }

    if (showCreateBranchDialog) {
        AlertDialog(
            onDismissRequest = { showCreateBranchDialog = false },
            title = { Text(stringResource(R.string.git_create_branch_from_commit)) },
            text = {
                AppTextField(
                    value = newBranchName,
                    onValueChange = { newBranchName = it },
                    label = stringResource(R.string.git_branch_name),
                    singleLine = true,
                    colors = dialogTextFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = newBranchName.trim()
                        if (name.isNotBlank()) {
                            showCreateBranchDialog = false
                            newBranchName = ""
                            onCreateBranch(name)
                            onDismiss()
                        }
                    },
                    enabled = newBranchName.isNotBlank()
                ) { Text(stringResource(R.string.git_action_create_branch)) }
            },
            dismissButton = {
                TextButton(onClick = { showCreateBranchDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    if (showCreateTagDialog) {
        AlertDialog(
            onDismissRequest = { showCreateTagDialog = false },
            title = { Text(stringResource(R.string.git_create_tag_at_commit)) },
            text = {
                AppTextField(
                    value = newTagName,
                    onValueChange = { newTagName = it },
                    label = stringResource(R.string.git_tag_name),
                    singleLine = true,
                    colors = dialogTextFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = newTagName.trim()
                        if (name.isNotBlank()) {
                            showCreateTagDialog = false
                            newTagName = ""
                            onCreateTag(name)
                        }
                    },
                    enabled = newTagName.isNotBlank()
                ) { Text(stringResource(R.string.git_action_create_tag)) }
            },
            dismissButton = {
                TextButton(onClick = { showCreateTagDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text(stringResource(R.string.git_reset_to_commit)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text(stringResource(R.string.git_reset_confirm, commit.shortHash))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { resetMode = "soft" },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        androidx.compose.material3.RadioButton(
                            selected = resetMode == "soft",
                            onClick = { resetMode = "soft" }
                        )
                        Spacer(Modifier.width(Spacing.xs))
                        Text(stringResource(R.string.git_reset_soft), style = MaterialTheme.typography.bodySmall)
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { resetMode = "mixed" },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        androidx.compose.material3.RadioButton(
                            selected = resetMode == "mixed",
                            onClick = { resetMode = "mixed" }
                        )
                        Spacer(Modifier.width(Spacing.xs))
                        Text(stringResource(R.string.git_reset_mixed), style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetDialog = false
                        onReset(resetMode)
                        onDismiss()
                    }
                ) { Text(stringResource(R.string.git_reset_to_commit), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    AdaptiveModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Text(
                text = commit.message,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (commit.body.isNotBlank()) {
                Text(
                    text = commit.body.trim(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = "${commit.author} · ${commit.date}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = commit.hash,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = {
                    scope.launch {
                        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("commit-short", commit.shortHash)))
                    }
                }) {
                    Text(stringResource(R.string.git_copy_short_hash), style = MaterialTheme.typography.labelMedium)
                }
                TextButton(onClick = {
                    scope.launch {
                        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("commit-full", commit.hash)))
                    }
                }) {
                    Text(stringResource(R.string.git_copy_full_hash), style = MaterialTheme.typography.labelMedium)
                }
            }

            // Deep-action button row: create a branch / tag / reset from this commit
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                FilledTonalButton(
                    onClick = { showCreateBranchDialog = true },
                    modifier = Modifier.weight(1f).height(36.dp),
                    shape = RoundedCornerShape(Radius.sm),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                ) {
                    Icon(FeatherIcons.GitBranch, contentDescription = null, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(Spacing.xs))
                    Text(stringResource(R.string.git_tab_branches), style = MaterialTheme.typography.labelSmall)
                }
                FilledTonalButton(
                    onClick = { showCreateTagDialog = true },
                    modifier = Modifier.weight(1f).height(36.dp),
                    shape = RoundedCornerShape(Radius.sm),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                ) {
                    Icon(FeatherIcons.Tag, contentDescription = null, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(Spacing.xs))
                    Text(stringResource(R.string.git_tags), style = MaterialTheme.typography.labelSmall)
                }
                FilledTonalButton(
                    onClick = { showResetDialog = true },
                    modifier = Modifier.weight(1f).height(36.dp),
                    shape = RoundedCornerShape(Radius.sm),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                ) {
                    Icon(FeatherIcons.RotateCcw, contentDescription = null, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(Spacing.xs))
                    Text("Reset", style = MaterialTheme.typography.labelSmall)
                }
            }

            if (commit.isMerge) {
                Text(
                    text = stringResource(R.string.git_merge_commit),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
            Spacer(Modifier.height(Spacing.xs))
            when {
                loading -> LoadingFilesRow(0.dp)
                files == null -> Unit
                files.isEmpty() -> EmptyCommitFilesRow(0.dp)
                else -> {
                    CommitFilesSummary(files.size, 0.dp)
                    files.forEach { file ->
                        CommitFileRow(file, indent = 0.dp, onClick = { onFileDiff(commit.hash, file.path) })
                    }
                }
            }
        }
    }
}
