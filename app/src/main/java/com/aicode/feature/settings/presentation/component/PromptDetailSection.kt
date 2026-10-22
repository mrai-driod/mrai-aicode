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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.feature.agent.domain.prompt.PromptFragment
import com.aicode.feature.agent.domain.prompt.PromptFragmentSource
import com.aicode.feature.agent.presentation.component.MarkdownContent

/**
 * Prompt fragment detail page: read-only preview — number/name/source, summary, and body (Markdown).
 *
 * To change the content, use the edit button in the top bar to open [PromptEditorScreen].
 */
@Composable
internal fun PromptDetailSection(fragment: PromptFragment) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg)
            .padding(bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        SettingsGroup {
            InfoRow(
                label = stringResource(R.string.prompts_field_number),
                value = "%02d".format(fragment.number)
            )
            SettingsDivider()
            InfoRow(
                label = stringResource(R.string.prompts_field_title),
                value = fragment.title
            )
            SettingsDivider()
            InfoRow(
                label = stringResource(R.string.subagent_editor_scope),
                value = stringResource(
                    when (fragment.source) {
                        PromptFragmentSource.PROJECT -> R.string.prompts_source_project
                        PromptFragmentSource.GLOBAL -> R.string.prompts_source_global
                        PromptFragmentSource.LOCAL -> R.string.prompts_source_local
                        PromptFragmentSource.BUILTIN -> R.string.prompts_source_builtin
                    }
                )
            )
        }

        if (fragment.description.isNotBlank()) {
            SettingsGroupHeader(text = stringResource(R.string.skills_summary))
            SettingsGroup {
                Text(
                    text = fragment.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.lg, vertical = 12.dp)
                )
            }
        }

        SettingsGroupHeader(text = stringResource(R.string.prompts_field_content))
        SettingsGroup {
            MarkdownContent(
                text = fragment.body,
                color = MaterialTheme.colorScheme.onSurface,
                lazyScroll = true,
                modifier = Modifier
                    .fillMaxWidth()
                    // Body card height limit: long text lazy-loads and scrolls inside the card (the outer page can still keep scrolling), avoiding jank from fully rendering an oversized md.
                    .heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.62f)
                    .padding(horizontal = Spacing.lg, vertical = 12.dp)
            )
        }
    }
}

/** Key-value row: label on the left, value on the right; the value wraps when it is too long. */
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
