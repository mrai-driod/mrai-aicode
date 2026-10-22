package com.aicode.feature.settings.presentation.component

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import com.aicode.core.ui.SwipeToDeleteRow
import com.aicode.feature.settings.domain.model.AIProviderConfig
import compose.icons.FeatherIcons
import compose.icons.feathericons.ChevronRight
import androidx.compose.ui.res.stringResource
import com.aicode.R
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * Provider sub-page: list + empty-state hint. Adding/editing is triggered by the top bar "+" and by tapping, via [ProviderEditorScreen]; swipe left to delete.
 * Each row is a group of rounded cards; long-pressing the content area adjusts the provider order (persisted to sortOrder).
 */
@Composable
internal fun ProvidersSection(
    providers: List<AIProviderConfig>,
    onEdit: (AIProviderConfig) -> Unit,
    onDelete: (AIProviderConfig) -> Unit,
    onReorder: (fromIndex: Int, toIndex: Int) -> Unit
) {
    if (providers.isEmpty()) {
        EmptyHint(stringResource(R.string.providers_empty))
        return
    }
    val lazyListState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        onReorder(from.index, to.index)
    }
    val hapticFeedback = LocalHapticFeedback.current
    LazyColumn(
        state = lazyListState,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.lg)
            .padding(bottom = Spacing.xl),
        contentPadding = PaddingValues(top = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        itemsIndexed(
            items = providers,
            key = { _, provider -> provider.id }
        ) { _, provider ->
            ReorderableItem(
                state = reorderableState,
                key = provider.id
            ) { isDragging ->
                // While dragging, the card shrinks slightly (matching the rikkahub interaction), transitions smoothly, and gets raised shadow + higher z-order so it does not cover neighboring cards
                val dragScale by animateFloatAsState(
                    targetValue = if (isDragging) 0.95f else 1f,
                    animationSpec = tween(durationMillis = if (isDragging) 120 else 220),
                    label = "providerDragScale"
                )
                val dragElevation by animateDpAsState(
                    targetValue = if (isDragging) 8.dp else 0.dp,
                    animationSpec = tween(durationMillis = if (isDragging) 120 else 220),
                    label = "providerDragElevation"
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
                    ProviderItem(
                        provider = provider,
                        onEdit = { onEdit(provider) },
                        onDelete = { onDelete(provider) },
                        dragModifier = Modifier.longPressDraggableHandle(
                            onDragStarted = {
                                hapticFeedback.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                            },
                            onDragStopped = {
                                hapticFeedback.performHapticFeedback(HapticFeedbackType.GestureEnd)
                            }
                        )
                    )
                }
            }
        }
    }
}

/** Centered empty-state hint. */
@Composable
internal fun EmptyHint(text: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(Spacing.xl),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Provider row: brand logo on the left (automatically identified from the provider name),
 * two lines in the middle (name / type + model-count pills), status pill + arrow on the right.
 * Tapping the whole row opens editing, swiping left reveals the delete button; long-pressing the left information area adjusts the order.
 */
@Composable
fun ProviderItem(
    provider: AIProviderConfig,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    dragModifier: Modifier = Modifier
) {
    val sortDescription = stringResource(R.string.provider_sort_long_press)
    // Status color matches MCP rows: enabled uses theme tertiary (green tint), disabled uses outline (gray).
    val statusColor = if (provider.isEnabled) MaterialTheme.semanticColors.success else MaterialTheme.colorScheme.outline

    SwipeToDeleteRow(
        onDelete = onDelete,
        onClick = onEdit
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .then(dragModifier)
                    .semantics {
                        contentDescription = sortDescription
                    },
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left brand logo container
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(8.dp)
                        )
                ) {
                    ProviderLogoIcon(
                        provider = provider,
                        size = 22.dp,
                        modifier = Modifier.size(22.dp).align(Alignment.Center)
                    )
                }

                Spacer(modifier = Modifier.width(Spacing.md))

                // Middle: name / type + model count
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = provider.name,
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        McpPill(
                            text = providerTypeLabel(provider.type),
                            textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            backgroundColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                        )
                        McpPill(
                            text = stringResource(R.string.provider_models_count_tag, provider.models.size),
                            textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            backgroundColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(Spacing.sm))

            // Enabled/disabled status pill + right arrow
            McpPill(
                text = stringResource(if (provider.isEnabled) R.string.common_enabled else R.string.common_disabled),
                textColor = statusColor,
                backgroundColor = statusColor.copy(alpha = 0.12f)
            )
            Spacer(Modifier.width(Spacing.xs))
            Icon(
                imageVector = FeatherIcons.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.semanticColors.subtleText,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
