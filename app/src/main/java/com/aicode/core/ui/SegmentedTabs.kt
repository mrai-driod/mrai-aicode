package com.aicode.core.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aicode.core.theme.semanticColors

/**
 * iOS-style segmented control: neutral gray track + white (raised gray in dark mode) thumb; when selected, the thumb springs elastically to the corresponding segment.
 *
 * It is not filled with the theme color, so it coordinates with the scanning-style list below, relying only on neutral colors and a light shadow to express hierarchy.
 * Shared as a single implementation between the top of the sidebar and the MCP edit dialog, keeping the switching feel consistent.
 */
@Composable
fun SegmentedTabs(
    selected: Int,
    labels: List<String>,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val trackColor = MaterialTheme.semanticColors.mutedSurface
    val thumbColor = if (dark) MaterialTheme.semanticColors.capsuleSurface else MaterialTheme.semanticColors.cardSurface

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(42.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(trackColor)
            .padding(3.dp)
    ) {
        val thumbWidth = maxWidth / labels.size
        // Selected thumb displacement: critically damped spring (no overshoot), the thumb slides smoothly into place when switching, rather than snapping hard.
        val thumbOffset by animateDpAsState(
            targetValue = thumbWidth * selected,
            animationSpec = spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow),
            label = "segmented-thumb"
        )
        Surface(
            modifier = Modifier
                .offset(x = thumbOffset)
                .width(thumbWidth)
                .fillMaxHeight(),
            shape = RoundedCornerShape(9.dp),
            color = thumbColor,
            shadowElevation = 2.dp,
            content = {}
        )
        Row(modifier = Modifier.fillMaxSize()) {
            labels.forEachIndexed { index, label ->
                val isSelected = selected == index
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(9.dp))
                        // Disable the click ripple: the selected thumb displacement is already feedback, and the dark shadow left by the ripple is instead jarring.
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { onSelect(index) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (isSelected) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
