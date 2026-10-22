package com.aicode.feature.settings.presentation.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.aicode.core.ui.AdaptiveModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.core.util.LanguageRegistry
import compose.icons.FeatherIcons
import compose.icons.feathericons.Check

/**
 * Language selection BottomSheet dialog: styled after the session long-press menu.
 *
 * @param currentTag the current language tag; null means follow the system.
 * @param onSelect selection callback; the argument is the tag (null means follow the system).
 * @param onDismiss closes the dialog.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LanguageSelectionSheet(
    currentTag: String?,
    onSelect: (String?) -> Unit,
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
                text = stringResource(R.string.settings_language),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(horizontal = Spacing.lg)
                    .padding(bottom = Spacing.md)
            )

            // Follow system
            val followSystemSelected = currentTag.isNullOrBlank()
            Surface(
                onClick = {
                    onDismiss()
                    onSelect(null)
                },
                color = Color.Transparent
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.language_follow_system),
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (followSystemSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        fontWeight = if (followSystemSelected) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.weight(1f)
                    )
                    if (followSystemSelected) {
                        Icon(
                            imageVector = FeatherIcons.Check,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // List of registered languages
            LanguageRegistry.languages.forEach { lang ->
                val selected = currentTag == lang.tag
                Surface(
                    onClick = {
                        onDismiss()
                        onSelect(lang.tag)
                    },
                    color = Color.Transparent
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = lang.displayName,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.weight(1f)
                        )
                        if (selected) {
                            Icon(
                                imageVector = FeatherIcons.Check,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }
}
