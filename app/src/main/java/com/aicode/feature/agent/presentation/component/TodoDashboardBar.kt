package com.aicode.feature.agent.presentation.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aicode.R
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.core.ui.GlassSurface
import com.aicode.feature.agent.domain.model.TodoItem
import com.aicode.feature.agent.domain.model.TodoStatus
import com.aicode.core.ui.ExpandableChevronIcon
import com.styropyr0.prismal.sources.PrismalGlassLayer
import compose.icons.FeatherIcons
import compose.icons.feathericons.CheckSquare
import compose.icons.feathericons.ChevronDown
import compose.icons.feathericons.ChevronUp

/**
 * Persistent to-do panel (two forms):
 * - Above the message bar: a plain card embedded in the input-area column (the default, pre-existing behaviour);
 * - Below the title bar: a floating glass overlay (enabled by passing [glassBackdrop]), sharing the same background sampling layer as the title bar.
 *
 * Common traits:
 * - Shown only when the current session has to-do items;
 * - Can collapse to a compact single-line summary and expand to show the full list;
 * - Remembers each session's expanded/collapsed state;
 * - Can be forcibly collapsed in sync when a dialog/keyboard overlays it.
 */
@Composable
fun TodoDashboardBar(
    items: List<TodoItem>,
    sessionId: String,
    modifier: Modifier = Modifier,
    forceCollapse: Boolean = false,
    onExpandedChange: (Boolean) -> Unit = {},
    /** Background sampling layer; when non-null the container switches to liquid glass (the below-title-bar overlay form). */
    glassBackdrop: PrismalGlassLayer? = null,
    /** Background luminance (0..1), glass mode only. */
    glassLuminance: () -> Float = { 0.5f },
    /** Foreground colour on the glass: adapts to the background luminance; when Unspecified, falls back to palette colours. */
    glassContentColor: Color = Color.Unspecified
) {
    if (items.isEmpty()) return

    // Remember the expanded state per session; new sessions start collapsed
    var isExpanded by rememberSaveable(sessionId) { mutableStateOf(false) }
    val effectiveExpanded = isExpanded && !forceCollapse

    LaunchedEffect(effectiveExpanded) {
        onExpandedChange(effectiveExpanded)
    }

    val totalCount = items.size
    val completedCount = items.count { it.status == TodoStatus.COMPLETED }
    val inProgressItem = items.firstOrNull { it.status == TodoStatus.IN_PROGRESS }

    val hasGlass = glassBackdrop != null
    val titleColor = if (hasGlass && glassContentColor != Color.Unspecified) {
        glassContentColor
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val secondaryColor = if (hasGlass && glassContentColor != Color.Unspecified) {
        glassContentColor.copy(alpha = 0.72f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    val body: @Composable () -> Unit = {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md, vertical = 8.dp)
        ) {
            // Single-line title bar (tap to toggle collapse/expand)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radius.sm))
                    .clickable { isExpanded = !isExpanded }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = FeatherIcons.CheckSquare,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(Modifier.width(Spacing.xs))
                Text(
                    text = stringResource(R.string.todo_dashboard_title),
                    style = MaterialTheme.typography.titleSmall.copy(fontSize = 13.sp),
                    fontWeight = FontWeight.Bold,
                    color = titleColor
                )
                Spacer(Modifier.width(Spacing.sm))

                // Progress pill or a brief description of the task currently in progress
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = if (completedCount == totalCount && totalCount > 0) {
                        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
                    } else {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                    }
                ) {
                    Text(
                        text = if (completedCount == totalCount && totalCount > 0) {
                            stringResource(R.string.todo_dashboard_all_done)
                        } else {
                            stringResource(R.string.todo_dashboard_progress, completedCount, totalCount)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = secondaryColor,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }

                if (!effectiveExpanded && inProgressItem != null) {
                    Spacer(Modifier.width(Spacing.xs))
                    Text(
                        text = inProgressItem.subject,
                        style = MaterialTheme.typography.bodySmall,
                        color = secondaryColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }

                ExpandableChevronIcon(
                    expanded = effectiveExpanded,
                    contentDescription = if (effectiveExpanded) {
                        stringResource(R.string.common_collapse_action)
                    } else {
                        stringResource(R.string.common_expand)
                    },
                    tint = secondaryColor,
                    size = 16.dp
                )
            }

            // When expanded, shows the to-do list (smoothly fades in while expanding, rolls up and fades out)
            AnimatedVisibility(
                visible = effectiveExpanded,
                enter = fadeIn(tween(180)) + expandVertically(tween(220)),
                exit = fadeOut(tween(140)) + shrinkVertically(tween(180))
            ) {
                Column {
                    Spacer(Modifier.height(Spacing.xs))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items.forEach { todo ->
                            TodoItemRow(item = todo.toParsedItem())
                        }
                    }
                }
            }
        }
    }

    if (glassBackdrop != null) {
        // Below-title-bar overlay: a liquid-glass container with the same corner radius and sampling layer as the title bar
        GlassSurface(
            backdrop = glassBackdrop,
            modifier = modifier
                .fillMaxWidth()
                .padding(bottom = Spacing.xs),
            cornerRadius = 20.dp,
            blurRadius = 8.dp,
            tintAlpha = 0.10f,
            luminance = glassLuminance
        ) {
            body()
        }
    } else {
        // Above the message bar: a plain card (keeping the pre-rework look)
        Surface(
            modifier = modifier
                .fillMaxWidth()
                .padding(bottom = Spacing.xs),
            shape = RoundedCornerShape(Radius.lg),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            body()
        }
    }
}

private fun TodoItem.toParsedItem(): ParsedTodoItem = ParsedTodoItem(
    id = id,
    subject = subject,
    description = description,
    status = status.name.lowercase(),
    priority = priority,
    order = order
)
