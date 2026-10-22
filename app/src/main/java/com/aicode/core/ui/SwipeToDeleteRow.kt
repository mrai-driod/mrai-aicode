package com.aicode.core.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aicode.R
import com.aicode.core.theme.semanticColors
import compose.icons.FeatherIcons
import compose.icons.feathericons.Trash2
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * Project-wide unified swipe-left-to-delete row container: the underlying red delete button is fixed at the right end and is revealed as you swipe left (with scale/opacity gradients),
 * the surface content slides left as a whole; when swiped open, tapping the surface retracts it.
 *
 * Gesture and animation parameters are kept consistent project-wide (reveal width -112dp, button limit 104dp, rebound spring parameters); changing them here takes effect globally.
 *
 * @param onDelete Triggered after tapping the delete button (retracts first, then calls back). Whether to show a secondary confirmation is decided by the caller.
 * @param onClick Click callback when the surface is not swiped open; null means the whole row is not clickable.
 * @param deleteEnabled When false, the delete button can be revealed but tapping it has no effect (e.g. an image whose download has not completed cannot be deleted).
 * @param content Surface row content; the background is provided by this component, and the padding is supplied by the caller's own content (consistent with existing usage on each page).
 */
@Composable
fun SwipeToDeleteRow(
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    deleteEnabled: Boolean = true,
    content: @Composable () -> Unit
) {
    val rowBackground = MaterialTheme.semanticColors.cardSurface
    val density = LocalDensity.current
    val revealPx = remember(density) { with(density) { -112.dp.toPx() } }
    val offsetX = remember { Animatable(0f) }
    val coroutineScope = rememberCoroutineScope()

    val revealedWidthDp = with(density) { (-offsetX.value).toDp().coerceAtLeast(0.dp) }
    val maxButtonWidth = 104.dp
    val buttonWidth = if (revealedWidthDp > 8.dp) (revealedWidthDp - 8.dp).coerceAtMost(maxButtonWidth) else 0.dp
    val progress = (buttonWidth / maxButtonWidth).coerceIn(0f, 1f)
    // When deletion is not allowed, the button is gray, distinguishing it from the deletable state
    val buttonBg = if (deleteEnabled) Color(0xFFEF4444) else Color(0xFF9CA3AF)
    val buttonBorder = if (deleteEnabled) Color(0xFFF87171) else Color(0xFFD1D5DB)

    Box(modifier = modifier.fillMaxWidth()) {
        // Underlying delete button: revealed as you swipe, with scale and opacity following the progress
        Row(
            modifier = Modifier
                .matchParentSize()
                .padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (buttonWidth > 0.dp) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(buttonWidth)
                        .graphicsLayer {
                            alpha = (progress * 1.2f).coerceIn(0f, 1f)
                            scaleX = (0.4f + 0.6f * progress).coerceIn(0f, 1f)
                            scaleY = (0.7f + 0.3f * progress).coerceIn(0f, 1f)
                        }
                        .clip(RoundedCornerShape(10.dp))
                        .background(buttonBg)
                        .border(1.dp, buttonBorder, RoundedCornerShape(10.dp))
                        .clickable(enabled = deleteEnabled) {
                            coroutineScope.launch {
                                offsetX.animateTo(0f)
                                onDelete()
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        modifier = Modifier.requiredWidth(104.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = FeatherIcons.Trash2,
                            contentDescription = stringResource(R.string.common_delete),
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.common_delete),
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp
                            ),
                            color = Color.White
                        )
                    }
                }
            }
        }

        // Surface grouping row: gesture rebound and slide expansion; in the swiped-open state, a tap retracts it first and does not trigger onClick
        Row(
            modifier = Modifier
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .fillMaxWidth()
                .background(rowBackground)
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = {
                            coroutineScope.launch { offsetX.stop() }
                        },
                        onDragEnd = {
                            coroutineScope.launch {
                                if (offsetX.value < revealPx / 2) {
                                    offsetX.animateTo(
                                        targetValue = revealPx,
                                        animationSpec = spring(
                                            dampingRatio = Spring.DampingRatioLowBouncy,
                                            stiffness = Spring.StiffnessMediumLow
                                        )
                                    )
                                } else {
                                    offsetX.animateTo(
                                        targetValue = 0f,
                                        animationSpec = spring(
                                            dampingRatio = Spring.DampingRatioNoBouncy,
                                            stiffness = Spring.StiffnessMedium
                                        )
                                    )
                                }
                            }
                        },
                        onDragCancel = {
                            coroutineScope.launch {
                                offsetX.animateTo(0f)
                            }
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            coroutineScope.launch {
                                val newOffset = (offsetX.value + dragAmount).coerceIn(revealPx * 1.15f, 0f)
                                offsetX.snapTo(newOffset)
                            }
                        }
                    )
                }
                .then(
                    if (onClick != null) {
                        Modifier.clickable {
                            if (offsetX.value < -10f) {
                                coroutineScope.launch {
                                    offsetX.animateTo(0f, spring(stiffness = Spring.StiffnessMedium))
                                }
                            } else {
                                onClick()
                            }
                        }
                    } else {
                        Modifier
                    }
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            content()
        }
    }
}
