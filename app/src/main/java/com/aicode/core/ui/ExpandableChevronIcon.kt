package com.aicode.core.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aicode.core.theme.Brand
import compose.icons.FeatherIcons
import compose.icons.feathericons.ChevronDown
import compose.icons.feathericons.ChevronRight

/**
 * Rotation semantic styles for the collapse chevron:
 * - [UP_DOWN]: base orientation points down (ChevronDown), collapsed is 0°, expanded flips 180° to point up; used for in-card collapsing, thinking panels, top-bar boards, etc.;
 * - [RIGHT_DOWN]: base orientation points right (ChevronRight), collapsed is 0°, expanded rotates 90° clockwise to point down; used for whole-round execution processes, sidebar time groups, Git status groups, etc.
 */
enum class ChevronRotationStyle {
    UP_DOWN,
    RIGHT_DOWN,
}

private const val CHEVRON_ANIM_DURATION_MS = 200

/**
 * Unified collapse chevron component: encapsulates a smooth rotation animation with a unified duration/easing curve.
 */
@Composable
fun ExpandableChevronIcon(
    expanded: Boolean,
    modifier: Modifier = Modifier,
    style: ChevronRotationStyle = ChevronRotationStyle.UP_DOWN,
    size: Dp = 18.dp,
    tint: Color = Brand.IconGray,
    contentDescription: String? = null
) {
    val targetRotation = when (style) {
        ChevronRotationStyle.UP_DOWN -> if (expanded) 180f else 0f
        ChevronRotationStyle.RIGHT_DOWN -> if (expanded) 90f else 0f
    }
    val rotation by animateFloatAsState(
        targetValue = targetRotation,
        animationSpec = tween(durationMillis = CHEVRON_ANIM_DURATION_MS, easing = FastOutSlowInEasing),
        label = "chevron_rotation"
    )
    val baseIcon: ImageVector = when (style) {
        ChevronRotationStyle.UP_DOWN -> FeatherIcons.ChevronDown
        ChevronRotationStyle.RIGHT_DOWN -> FeatherIcons.ChevronRight
    }
    Icon(
        imageVector = baseIcon,
        contentDescription = contentDescription,
        tint = tint,
        modifier = modifier
            .size(size)
            .rotate(rotation)
    )
}
