package com.aicode.feature.settings.presentation.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.core.ui.AppSwitch
import com.aicode.core.ui.AppTextField
import com.aicode.feature.settings.presentation.ContextWindowOverrideUiState
import compose.icons.FeatherIcons
import compose.icons.feathericons.AlertTriangle
import compose.icons.feathericons.Info

/**
 * "Unified context window advanced settings" sub-page.
 *
 * When enabled, all models ignore the context window in their own metadata and uniformly use the value configured on this page --
 * for scenarios where gateway/self-hosted model metadata is inaccurate (e.g. an old 8k model reported as 128k, causing over-long request errors).
 *
 * Required-field rule: the total context window and output window must be positive integers; otherwise the toggle automatically returns to off (the storage layer has the same fallback).
 * The input window is optional; if left empty, it is derived by [com.aicode.feature.settings.domain.model.ModelContextPolicy] as
 * "total window - output reserve".
 */
@Composable
internal fun ContextWindowSection(
    state: ContextWindowOverrideUiState,
    onToggleEnabled: (Boolean) -> Unit,
    onSave: (contextTokens: Int, inputTokens: Int?, outputTokens: Int?) -> Unit
) {
    var contextText by remember(state.contextTokens) {
        mutableStateOf(state.contextTokens.takeIf { it > 0 }?.toString() ?: "")
    }
    var inputText by remember(state.inputTokens) {
        mutableStateOf(state.inputTokens?.takeIf { it > 0 }?.toString() ?: "")
    }
    var outputText by remember(state.outputTokens) {
        mutableStateOf(state.outputTokens?.takeIf { it > 0 }?.toString() ?: "")
    }

    fun parsed(text: String): Int? = text.trim().toIntOrNull()?.takeIf { it > 0 }

    val contextValue = parsed(contextText)
    val inputValue = parsed(inputText)
    val outputValue = parsed(outputText)
    // Saving is not allowed when a required field is missing: the save action automatically turns the toggle off accordingly, avoiding an "on but with no value" state.
    val canSave = contextValue != null && outputValue != null

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg)
            .padding(bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        SettingsGroupHeader(text = stringResource(R.string.ctx_window_group))
        SettingsGroup {
            SettingsRow(
                icon = FeatherIcons.Info,
                title = stringResource(R.string.ctx_window_enable),
                subtitle = stringResource(R.string.ctx_window_enable_desc),
                trailing = {
                    AppSwitch(
                        checked = state.enabled,
                        onCheckedChange = onToggleEnabled
                    )
                }
            )
        }

        Text(
            text = stringResource(R.string.ctx_window_note_required),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = Spacing.md)
        )

        SettingsGroupHeader(text = stringResource(R.string.ctx_window_values_group))
        SettingsGroup {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                FieldLabel(
                    text = stringResource(R.string.ctx_window_total),
                    required = true
                )
                AppTextField(
                    value = contextText,
                    onValueChange = { contextText = it.filter(Char::isDigit) },
                    placeholder = stringResource(R.string.ctx_window_total_hint),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                FieldLabel(
                    text = stringResource(R.string.ctx_window_input),
                    required = false
                )
                AppTextField(
                    value = inputText,
                    onValueChange = { inputText = it.filter(Char::isDigit) },
                    placeholder = stringResource(R.string.ctx_window_input_hint),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                FieldLabel(
                    text = stringResource(R.string.ctx_window_output),
                    required = true
                )
                AppTextField(
                    value = outputText,
                    onValueChange = { outputText = it.filter(Char::isDigit) },
                    placeholder = stringResource(R.string.ctx_window_output_hint),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(4.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (canSave) {
                                Modifier.saveButton { onSave(contextValue!!, inputValue, outputValue!!) }
                            } else {
                                Modifier
                            }
                        )
                        .padding(vertical = 10.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (canSave) FeatherIcons.Info else FeatherIcons.AlertTriangle,
                        contentDescription = null,
                        tint = if (canSave) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(Spacing.md))
                    Text(
                        text = stringResource(
                            if (canSave) R.string.ctx_window_save else R.string.ctx_window_save_disabled
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (canSave) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        }
                    )
                }
            }
        }

        if (state.enabled) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = 4.dp)
            ) {
                Text(
                    text = stringResource(R.string.ctx_window_active_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

/** Field title + required asterisk. */
@Composable
private fun FieldLabel(text: String, required: Boolean) {
    Row {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface
        )
        if (required) {
            Text(
                text = " *",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

/** Click area of the save row: extracted so the gesture is only rebuilt when canSave changes. */
private fun Modifier.saveButton(onClick: () -> Unit): Modifier =
    this.clickable(onClick = onClick)
