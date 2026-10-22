package com.aicode.feature.agent.presentation.component

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import com.aicode.feature.agent.domain.model.ChatSession
import compose.icons.FeatherIcons

/**
 * A single session row: tap to select, long-press for the action menu (pin / rename / export / delete). Reused by the drawer's history list.
 * Pinned sessions show a pale-blue background (primaryContainer).
 * [trailing] is for extra trailing controls (such as the sub-agent expand arrow); its own clicks must not trigger selection of the whole row.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatSessionRow(
    session: ChatSession,
    selected: Boolean,
    isExecuting: Boolean = false,
    awaitingPermission: Boolean = false,
    pinned: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
    selectionMode: Boolean = false,
    checked: Boolean = false,
    onCheckedChange: ((Boolean) -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (pinned) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(horizontal = Spacing.lg, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selectionMode) {
            Checkbox(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = CheckboxDefaults.colors(
                    checkedColor = MaterialTheme.colorScheme.primary,
                    uncheckedColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
            Spacer(Modifier.width(Spacing.xs))
        }
        if (awaitingPermission) {
            // Awaiting user permission: a solid orange dot, taking priority over the green executing pulse, signalling that this session is paused pending action.
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.semanticColors.warning)
            )
            Spacer(Modifier.width(Spacing.md))
        } else if (isExecuting) {
            val transition = rememberInfiniteTransition(label = "tool-status-dot")
            val alpha by transition.animateFloat(
                initialValue = 1f,
                targetValue = 0.25f,
                animationSpec = infiniteRepeatable(animation = tween(650), repeatMode = RepeatMode.Reverse),
                label = "tool-status-dot-alpha"
            )
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.semanticColors.success.copy(alpha = alpha))
            )
            Spacer(Modifier.width(Spacing.md))
        }
        Text(
            text = session.title,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        trailing?.invoke()
    }
}
