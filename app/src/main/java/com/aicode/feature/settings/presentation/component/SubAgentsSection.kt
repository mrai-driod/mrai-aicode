package com.aicode.feature.settings.presentation.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aicode.R
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import com.aicode.core.ui.SwipeToDeleteRow
import com.aicode.feature.agent.domain.subagent.AgentDefinitionScope
import com.aicode.feature.settings.presentation.SubAgentUiEntry
import compose.icons.FeatherIcons
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.Users

/**
 * Sub-agent secondary page: a collapsible grouped list consistent with "Skills" -- the two groups "Current project / Global" are each collapsible,
 * with one sub-agent per row (icon + name + description + model tag), swipe to delete, tap a row to open details.
 * The create entry point is the "+" in the top bar; the enable toggle and edit entry are on the detail page.
 */
@Composable
internal fun SubAgentsSection(
    projectName: String?,
    entries: List<SubAgentUiEntry>,
    onDelete: (SubAgentUiEntry) -> Unit,
    onOpenDetail: (SubAgentUiEntry) -> Unit
) {
    val projectAgents = entries.filter { it.scope == AgentDefinitionScope.PROJECT }
    val globalAgents = entries.filter { it.scope == AgentDefinitionScope.GLOBAL }

    if (entries.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = Spacing.xl, vertical = 48.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.md)
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(Radius.lg)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        FeatherIcons.Users,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = stringResource(R.string.subagents_empty),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(R.string.subagents_empty_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
        return
    }

    var projectExpanded by rememberSaveable { mutableStateOf(true) }
    var globalExpanded by rememberSaveable { mutableStateOf(true) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg)
            .padding(bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        CollapsibleGroupHeader(
            text = if (projectName != null) {
                stringResource(R.string.perm_current_project, projectName)
            } else {
                stringResource(R.string.perm_current_project_none)
            },
            expanded = projectExpanded,
            onToggle = { projectExpanded = !projectExpanded }
        )
        AnimatedVisibility(visible = projectExpanded) {
            SettingsGroup {
                if (projectAgents.isEmpty()) {
                    SubAgentEmptyHint(stringResource(R.string.subagents_no_project))
                } else {
                    projectAgents.forEachIndexed { index, entry ->
                        if (index > 0) SettingsDivider()
                        SubAgentRow(
                            entry = entry,
                            onDelete = { onDelete(entry) },
                            onClick = { onOpenDetail(entry) }
                        )
                    }
                }
            }
        }

        CollapsibleGroupHeader(
            text = stringResource(R.string.perm_global),
            expanded = globalExpanded,
            onToggle = { globalExpanded = !globalExpanded }
        )
        AnimatedVisibility(visible = globalExpanded) {
            SettingsGroup {
                if (globalAgents.isEmpty()) {
                    SubAgentEmptyHint(stringResource(R.string.subagents_no_global))
                } else {
                    globalAgents.forEachIndexed { index, entry ->
                        if (index > 0) SettingsDivider()
                        SubAgentRow(
                            entry = entry,
                            onDelete = { onDelete(entry) },
                            onClick = { onOpenDetail(entry) }
                        )
                    }
                }
            }
        }
    }
}

/** Single sub-agent row: icon + name/description + model tag + right chevron; swipe to delete, tap the row to open details. */
@Composable
private fun SubAgentRow(
    entry: SubAgentUiEntry,
    onDelete: () -> Unit,
    onClick: () -> Unit
) {
    val rowBackground = MaterialTheme.semanticColors.cardSurface

    SwipeToDeleteRow(onDelete = onDelete, onClick = onClick) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(rowBackground)
                .padding(start = Spacing.lg, end = Spacing.xs, top = 11.dp, bottom = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = FeatherIcons.Users,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(Spacing.md))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = entry.name,
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    entry.model?.let { model ->
                        McpPill(
                            text = model,
                            textColor = MaterialTheme.colorScheme.tertiary,
                            backgroundColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f)
                        )
                    }
                    // Only show the tag when disabled: enabled-by-default is the norm, and tagging every row would bury the point.
                    if (entry.disabled) {
                        McpPill(
                            text = stringResource(R.string.common_disabled),
                            textColor = MaterialTheme.colorScheme.outline,
                            backgroundColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)
                        )
                    }
                }
                Text(
                    text = entry.description.ifBlank { stringResource(R.string.mcp_no_description) },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(Spacing.sm))

            Icon(
                imageVector = FeatherIcons.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.semanticColors.subtleText,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/** Empty state inside a group: one line of gray text, aligned with row content. */
@Composable
private fun SubAgentEmptyHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Spacing.lg, vertical = 12.dp)
    )
}
