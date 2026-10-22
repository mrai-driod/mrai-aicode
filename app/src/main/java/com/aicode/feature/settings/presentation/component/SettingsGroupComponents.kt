package com.aicode.feature.settings.presentation.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import com.aicode.core.ui.ChevronRotationStyle
import com.aicode.core.ui.ExpandableChevronIcon
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import compose.icons.FeatherIcons
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.Search

/**
 * Settings-page grouping components: light-grey background + white grouped rounded cards + indented dividers within the group + black line icons.
 *
 * Usage:
 * ```
 * SettingsGroupHeader("Group title")
 * SettingsGroup {
 *     SettingsRow(icon, title, onClick = { ... })
 *     SettingsDivider()
 *     SettingsRow(icon, title, trailing = { AppSwitch(...) })
 * }
 * ```
 */

/** Whether light mode is currently active (used to switch between light-grey / theme dark colour schemes accordingly). */
@Composable
internal fun settingsLightMode(): Boolean =
    MaterialTheme.colorScheme.background.luminance() > 0.5f

/** Collapsible group header: tap to expand/collapse, with a chevron on the right indicating the state. Consistent with the grouping on the tool authorisation page. */
@Composable
internal fun CollapsibleGroupHeader(
    text: String,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(start = Spacing.md, end = Spacing.sm, top = Spacing.sm, bottom = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        ExpandableChevronIcon(
            expanded = expanded,
            style = ChevronRotationStyle.RIGHT_DOWN,
            size = 16.dp,
            tint = MaterialTheme.semanticColors.subtleText
        )
    }
}

/** Settings page background: uniformly uses the semantically defined pageBackground. */
@Composable
internal fun settingsPageBackground(): Color =
    MaterialTheme.semanticColors.pageBackground

/** Group subheader: small grey text above the card, left-aligned. */
@Composable
internal fun SettingsGroupHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp),
        fontWeight = FontWeight.Normal,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = Spacing.md, top = Spacing.lg, bottom = Spacing.sm)
    )
}

/** White/dark grouped rounded-card container: arranged by rows inside, with rows separated by [SettingsDivider]. */
@Composable
internal fun SettingsGroup(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.semanticColors.cardSurface,
        shadowElevation = 0.dp
    ) {
        Column(content = content)
    }
}

/**
 * A single row within a group: line icon on the left + title, with an optional trailing component on the right (right arrow/switch/value).
 *
 * @param icon The icon on the left; if null, the title aligns with icon-less rows.
 * @param trailing The trailing content on the right (such as [com.aicode.core.ui.AppSwitch], a chevron).
 * @param onClick null means no click behaviour (such as a switch row); when non-null, a right arrow is automatically shown at the end of the row.
 */
@Composable
internal fun SettingsRow(
    icon: ImageVector? = null,
    title: String,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    subtitle: String? = null,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    val baseModifier = modifier
        .fillMaxWidth()
        .let { if (onClick != null && enabled) it.clickable { onClick() } else it }
        .padding(horizontal = Spacing.lg, vertical = 11.dp)
    Row(
        modifier = baseModifier.alpha(if (enabled) 1f else 0.5f),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(Spacing.md))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        trailing?.invoke(this)
        if (onClick != null && enabled) {
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

/** Divider between rows within a group: both the left and right ends are indented to align with the row content, and it is not shown on the last row. */
@Composable
internal fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = Spacing.lg),
        thickness = 0.5.dp,
        color = MaterialTheme.semanticColors.subtleBorder
    )
}

/** iOS-style search field: light-grey capsule background, no border, consistent with the settings-page grouping style.
 *  [trailing] is an optional trailing control (such as a clear button). */
@Composable
internal fun ModelSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null
) {
    val isLight = settingsLightMode()
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(50),
        color = MaterialTheme.semanticColors.capsuleSurface
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                FeatherIcons.Search,
                contentDescription = null,
                tint = MaterialTheme.semanticColors.subtleText,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(Spacing.sm))
            Box(modifier = Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.semanticColors.subtleText,
                        maxLines = 1
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    singleLine = true,
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            trailing?.invoke()
        }
    }
}
