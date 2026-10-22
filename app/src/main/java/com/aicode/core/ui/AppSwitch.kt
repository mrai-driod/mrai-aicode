package com.aicode.core.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * Globally unified refined compact switch component:
 * - Compact size (42.dp × 24.dp), leaving behind the bulky heaviness of the Material 3 default Switch;
 * - Pure white circular thumb with a subtle shadow + silky smooth translation animation;
 * - Light mode uses brand blue / soft light gray track; dark mode uses bright blue / dark gray-blue track.
 */
@Composable
fun AppSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() }
) {
    val isLight = MaterialTheme.colorScheme.background.luminance() > 0.5f

    // Track color
    val targetTrackColor = when {
        checked -> MaterialTheme.colorScheme.primary
        isLight -> Color(0xFFE2E8F0)
        else -> Color(0xFF334155)
    }
    val animatedTrackColor by animateColorAsState(
        targetValue = targetTrackColor,
        animationSpec = tween(durationMillis = 200),
        label = "switchTrackColor"
    )

    // Thumb color
    val thumbColor = if (isLight) Color.White else Color(0xFFF8FAFC)

    // Thumb offset (track width 42dp, inner padding 2dp, thumb 20dp, offset range 2dp -> 20dp)
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) 20.dp else 2.dp,
        animationSpec = spring(
            dampingRatio = 0.8f,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "switchThumbOffset"
    )

    val toggleModifier = if (onCheckedChange != null) {
        Modifier.toggleable(
            value = checked,
            enabled = enabled,
            role = Role.Switch,
            interactionSource = interactionSource,
            indication = null,
            onValueChange = onCheckedChange
        )
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .alpha(if (enabled) 1f else 0.4f)
            .then(toggleModifier)
            .size(width = 42.dp, height = 24.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(animatedTrackColor),
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .offset(x = thumbOffset)
                .size(20.dp)
                .shadow(elevation = 2.dp, shape = CircleShape)
                .background(thumbColor, CircleShape)
        )
    }
}
