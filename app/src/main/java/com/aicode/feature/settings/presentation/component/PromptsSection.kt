package com.aicode.feature.settings.presentation.component

import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import com.aicode.core.ui.AdaptiveModalBottomSheet
import com.aicode.core.ui.AppSwitch
import com.aicode.core.ui.SwipeToDeleteRow
import com.aicode.feature.agent.domain.prompt.PromptFragment
import com.aicode.feature.agent.domain.prompt.PromptFragmentSource
import com.aicode.feature.settings.presentation.PromptsUiState
import compose.icons.FeatherIcons
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.FileText
import compose.icons.feathericons.Info
import compose.icons.feathericons.Plus
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * Edit target: distinguishes "create new" from "edit an existing number".
 *
 * - [number] = null: create a new numbered fragment
 * - Non-null: edit the fragment that ultimately takes effect for that number
 */
internal data class PromptEditTarget(val number: Int? = null)

/**
 * Prompts page: at the top are the "completely disable built-in prompts" switch and the help entry, and below are the effective fragments listed by number.
 *
 * Each row is a card (icon + number·name + summary + source badge); long-pressing the row content allows dragging to adjust the order,
 * tapping opens the detail preview (then edit from the top-right corner), and when the source is writable, swiping left deletes it.
 */
@Composable
internal fun PromptsSection(
    state: PromptsUiState,
    onOpenFragment: (PromptFragment) -> Unit,
    onDeleteFragment: (Int) -> Unit,
    onReorder: (List<PromptFragment>) -> Unit,
    onToggleBuiltinDisabled: (Boolean) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        SettingsGroup(
            modifier = Modifier
                .padding(horizontal = Spacing.lg)
                .padding(top = Spacing.sm)
        ) {
            SettingsRow(
                icon = null,
                title = stringResource(R.string.prompts_disable_builtin),
                subtitle = stringResource(R.string.prompts_disable_builtin_hint),
                trailing = {
                    AppSwitch(checked = state.builtinDisabled, onCheckedChange = onToggleBuiltinDisabled)
                }
            )
        }

        if (state.fragments.isEmpty()) {
            Text(
                text = stringResource(R.string.prompts_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.lg, vertical = 12.dp)
            )
        } else {
            PromptList(
                fragments = state.fragments,
                onOpenFragment = onOpenFragment,
                onDeleteFragment = onDeleteFragment,
                onReorder = onReorder
            )
        }
    }
}

/**
 * Fragment list. While dragging, only the local order is updated (nothing is written to disk); only on release is the final order handed to [onReorder] for persistence,
 * avoiding repeated disk writes + re-reads during dragging that would make the list jitter.
 */
@Composable
private fun PromptList(
    fragments: List<PromptFragment>,
    onOpenFragment: (PromptFragment) -> Unit,
    onDeleteFragment: (Int) -> Unit,
    onReorder: (List<PromptFragment>) -> Unit
) {
    var localFragments by remember { mutableStateOf(fragments) }
    LaunchedEffect(fragments) { localFragments = fragments }

    val listState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(listState) { from, to ->
        localFragments = localFragments.toMutableList().apply { add(to.index, removeAt(from.index)) }
    }
    val hapticFeedback = LocalHapticFeedback.current

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.lg)
            .padding(bottom = Spacing.xl),
        contentPadding = PaddingValues(top = Spacing.xs),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        itemsIndexed(
            items = localFragments,
            key = { index, fragment ->
                "${fragment.stableReorderKey}:${localFragments.take(index).count { it.stableReorderKey == fragment.stableReorderKey }}"
            }
        ) { index, fragment ->
            val rowKey = "${fragment.stableReorderKey}:${localFragments.take(index).count { it.stableReorderKey == fragment.stableReorderKey }}"
            ReorderableItem(state = reorderableState, key = rowKey) { isDragging ->
                val dragScale by animateFloatAsState(
                    targetValue = if (isDragging) 0.97f else 1f,
                    animationSpec = tween(durationMillis = if (isDragging) 120 else 220),
                    label = "promptDragScale"
                )
                val dragElevation by animateDpAsState(
                    targetValue = if (isDragging) 8.dp else 0.dp,
                    animationSpec = tween(durationMillis = if (isDragging) 120 else 220),
                    label = "promptDragElevation"
                )
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.semanticColors.cardSurface,
                    shadowElevation = dragElevation,
                    modifier = Modifier
                        .fillMaxWidth()
                        .zIndex(if (isDragging) 1f else 0f)
                        .graphicsLayer {
                            scaleX = dragScale
                            scaleY = dragScale
                        }
                ) {
                    PromptRow(
                        fragment = fragment,
                        onClick = { onOpenFragment(fragment) },
                        onDelete = { onDeleteFragment(fragment.number) },
                        deleteEnabled = fragment.editable,
                        dragModifier = Modifier.longPressDraggableHandle(
                            onDragStarted = {
                                hapticFeedback.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                            },
                            onDragStopped = {
                                hapticFeedback.performHapticFeedback(HapticFeedbackType.GestureEnd)
                                // Renumber immediately (slotted), consistent with the numbering after writing to disk, to avoid list jumping caused by key changes
                                val numbers = localFragments.map { it.number }.sorted()
                                val renumbered = localFragments.mapIndexed { index, item ->
                                    item.copy(number = numbers[index])
                                }
                                localFragments = renumbered
                                onReorder(renumbered)
                            }
                        )
                    )
                }
            }
        }
    }
}

/**
 * The "+" pop-up in the top-right corner: two entries (add fragment / help).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PromptsAddSheet(
    onDismiss: () -> Unit,
    onAddFragment: () -> Unit,
    onHelp: () -> Unit
) {
    AdaptiveModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            SettingsGroupHeader(text = stringResource(R.string.prompts_add_sheet_title))
            SettingsGroup {
                SettingsRow(
                    icon = FeatherIcons.Plus,
                    title = stringResource(R.string.prompts_add_prompt),
                    subtitle = stringResource(R.string.prompts_add_prompt_hint),
                    onClick = onAddFragment
                )
                SettingsDivider()
                SettingsRow(
                    icon = FeatherIcons.Info,
                    title = stringResource(R.string.prompts_help),
                    subtitle = stringResource(R.string.prompts_help_hint),
                    onClick = onHelp
                )
            }
        }
    }
}

/** A single fragment row: icon + number·name + summary + source badge + arrow; long-press the content area to drag and reorder, swipe left to delete. */
@Composable
private fun PromptRow(
    fragment: PromptFragment,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    deleteEnabled: Boolean,
    dragModifier: Modifier
) {
    val sortDescription = stringResource(R.string.prompts_sort_long_press)
    val row: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = Spacing.lg, end = Spacing.xs, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .then(dragModifier)
                    .semantics { contentDescription = sortDescription },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = FeatherIcons.FileText,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(Spacing.md))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "%02d · %s".format(fragment.number, fragment.title),
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (fragment.description.isNotBlank()) {
                        Text(
                            text = fragment.description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(Spacing.sm))
            SourceBadge(fragment.source)
            Spacer(modifier = Modifier.width(Spacing.xs))
            Icon(
                imageVector = FeatherIcons.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.semanticColors.subtleText,
                modifier = Modifier.size(18.dp)
            )
        }
    }

    SwipeToDeleteRow(onDelete = onDelete, onClick = onClick, deleteEnabled = deleteEnabled) { row() }
}

/** Source badge: shows at a glance which layer the content for that number comes from. */
@Composable
private fun SourceBadge(source: PromptFragmentSource) {
    val color = when (source) {
        PromptFragmentSource.PROJECT -> MaterialTheme.colorScheme.primary
        PromptFragmentSource.GLOBAL -> MaterialTheme.colorScheme.tertiary
        PromptFragmentSource.LOCAL -> MaterialTheme.colorScheme.secondary
        PromptFragmentSource.BUILTIN -> MaterialTheme.semanticColors.subtleText
    }
    Text(
        text = stringResource(
            when (source) {
                PromptFragmentSource.PROJECT -> R.string.prompts_source_project
                PromptFragmentSource.GLOBAL -> R.string.prompts_source_global
                PromptFragmentSource.LOCAL -> R.string.prompts_source_local
                PromptFragmentSource.BUILTIN -> R.string.prompts_source_builtin
            }
        ),
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}
