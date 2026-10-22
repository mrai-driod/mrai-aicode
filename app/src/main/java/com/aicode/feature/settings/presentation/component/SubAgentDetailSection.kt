package com.aicode.feature.settings.presentation.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.core.ui.AppSwitch
import com.aicode.feature.agent.domain.subagent.InjectPart
import com.aicode.feature.agent.presentation.component.MarkdownContent
import com.aicode.feature.agent.presentation.component.MarkdownRenderCache
import com.aicode.feature.settings.presentation.SubAgentUiEntry

/**
 * Sub-agent detail page: grouped cards — an "enabled" toggle row, a "summary" description card, a "configuration" (model/tool set/injected items/definition file) key-value card,
 * and an "agent prompt" body card (Markdown rendering). To change the configuration, use the edit button in the top bar.
 */
@Composable
internal fun SubAgentDetailSection(
    entry: SubAgentUiEntry,
    onToggle: (Boolean) -> Unit,
    cache: MarkdownRenderCache? = null
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg)
            .padding(bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        SettingsGroup {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.subagent_enable),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.subagent_enable_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                AppSwitch(
                    checked = !entry.disabled,
                    onCheckedChange = onToggle
                )
            }
        }

        SettingsGroupHeader(text = stringResource(R.string.skills_summary))
        SettingsGroup {
            Text(
                text = entry.description.ifBlank { stringResource(R.string.mcp_no_description) },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = 12.dp)
            )
        }

        SettingsGroup {
            InfoRow(
                label = stringResource(R.string.subagent_model),
                value = modelText(entry)
            )
            SettingsDivider()
            InfoRow(
                label = stringResource(R.string.subagent_editor_mode),
                value = modeText(entry)
            )
            SettingsDivider()
            InfoRow(
                label = stringResource(R.string.subagent_tools),
                value = toolsText(entry)
            )
            SettingsDivider()
            InfoRow(
                label = stringResource(R.string.subagent_inject),
                value = injectText(entry.inject)
            )
            entry.filePath?.let { path ->
                SettingsDivider()
                InfoRow(
                    label = stringResource(R.string.subagent_source_file),
                    value = path
                )
            }
        }

        SettingsGroupHeader(text = stringResource(R.string.subagent_prompt))
        SettingsGroup {
            MarkdownContent(
                text = entry.prompt.ifBlank { stringResource(R.string.mcp_no_description) },
                color = MaterialTheme.colorScheme.onSurface,
                cache = cache,
                lazyScroll = true,
                modifier = Modifier
                    .fillMaxWidth()
                    // Body card height limit: long text lazy-scrolls inside the card (the outer page can still continue scrolling), avoiding jank from fully rendering a very large md file.
                    .heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.62f)
                    .padding(horizontal = Spacing.lg, vertical = 12.dp)
            )
        }
    }
}

/** Key-value row: label on the left, value on the right; wraps when the value is too long. */
@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = 12.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.width(Spacing.md))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun modelText(entry: SubAgentUiEntry): String {
    val parts = listOfNotNull(entry.providerId, entry.model, entry.reasoningEffort)
    return if (parts.isEmpty()) stringResource(R.string.subagent_inherit_parent) else parts.joinToString(" / ")
}

@Composable
private fun modeText(entry: SubAgentUiEntry): String =
    entry.mode?.name ?: stringResource(R.string.subagent_inherit_parent)

@Composable
private fun toolsText(entry: SubAgentUiEntry): String {
    val parts = buildList {
        if (entry.allowedTools.isNotEmpty()) {
            add(stringResource(R.string.subagent_tools_allow, entry.allowedTools.joinToString(", ")))
        }
        if (entry.disallowedTools.isNotEmpty()) {
            add(stringResource(R.string.subagent_tools_deny, entry.disallowedTools.joinToString(", ")))
        }
    }
    return if (parts.isEmpty()) stringResource(R.string.subagent_all_tools) else parts.joinToString("\n")
}

@Composable
private fun injectText(inject: Set<InjectPart>): String =
    if (inject.isEmpty()) {
        stringResource(R.string.subagent_inject_none)
    } else {
        // Shows the frontmatter syntax directly, so users can copy it into hand-written definition files.
        InjectPart.entries.filter { it in inject }.joinToString(", ") { it.token }
    }
