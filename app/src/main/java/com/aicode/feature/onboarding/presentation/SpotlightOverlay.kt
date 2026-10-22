package com.aicode.feature.onboarding.presentation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aicode.R
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import com.aicode.feature.onboarding.domain.OnboardingStep
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Full-screen spotlight overlay for new-user onboarding (Spotlight Overlay).
 *
 * 1. A translucent scrim that matches the system style (40% in light mode / 52% in dark mode), without feeling oppressive;
 * 2. Hardware-accelerated rounded cutout (BlendMode.Clear) + smooth fade-in in place (never slides toward the target from the centre of the screen);
 * 3. The focused target's edge carries a very subtle breathing glow border;
 * 4. Simulated panels use the native [ModelLogoIcon] and app-level card hierarchy, avoiding a cartoonish, toy-like feel;
 * 5. A refined floating instruction card following Material 3 guidelines, without heavy black shadows, with a smooth breathing fade-in transition.
 */
@Composable
fun SpotlightOverlay(
    currentStep: OnboardingStep,
    targetRect: Rect?,
    onNext: () -> Unit,
    onSkip: () -> Unit,
    onTargetClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val cardMarginPx = with(density) { 16.dp.toPx() }
    val cardGapPx = with(density) { 12.dp.toPx() }

    // Breathing animation: produces a soft glow pulse around the highlight outline
    val infiniteTransition = rememberInfiniteTransition(label = "SpotlightPulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.50f,
        targetValue = 0.90f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "PulseAlpha"
    )

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val screenWidthPx = constraints.maxWidth.toFloat()
        val screenHeightPx = constraints.maxHeight.toFloat()

        val isSheetStep = currentStep == OnboardingStep.SIMULATE_FETCH_DIALOG ||
            currentStep == OnboardingStep.SIMULATE_CHOOSE_MODEL

        // Adaptively computes the best focus size and safe margins for the target's shape (small icon button vs wide component), preventing any out-of-bounds clipping
        val (optimalTargetRect, holeRadiusPx) = remember(targetRect, screenWidthPx, screenHeightPx, density, isSheetStep) {
            if (targetRect != null && !isSheetStep) {
                calculateOptimalHoleBounds(targetRect, screenWidthPx, screenHeightPx, density)
            } else {
                Rect.Zero to with(density) { 12.dp.toPx() }
            }
        }

        // State machine for the spotlight's position and opacity
        val holeLeft = remember { Animatable(0f) }
        val holeTop = remember { Animatable(0f) }
        val holeRight = remember { Animatable(0f) }
        val holeBottom = remember { Animatable(0f) }
        val spotlightAlpha = remember { Animatable(0f) }
        var isFirstTarget by remember { mutableStateOf(true) }
        var lastTargetRect by remember { mutableStateOf<Rect?>(null) }

        LaunchedEffect(optimalTargetRect, targetRect, isSheetStep) {
            if (isSheetStep || targetRect == null) {
                spotlightAlpha.animateTo(0f, animationSpec = tween(200))
            } else {
                val last = lastTargetRect
                val isBigJump = last == null ||
                    hypot(
                        (optimalTargetRect.center.x - last.center.x).toDouble(),
                        (optimalTargetRect.center.y - last.center.y).toDouble()
                    ) > with(density) { 240.dp.toPx() }

                if (isFirstTarget || isBigJump) {
                    // First appearance or a large jump across pages:
                    // Snap immediately to the new target position and fade in/expand at the target (eliminating the odd full-screen diagonal drag/pull animation)
                    holeLeft.snapTo(optimalTargetRect.left)
                    holeTop.snapTo(optimalTargetRect.top)
                    holeRight.snapTo(optimalTargetRect.right)
                    holeBottom.snapTo(optimalTargetRect.bottom)
                    isFirstTarget = false
                    spotlightAlpha.animateTo(1f, animationSpec = tween(260, easing = FastOutSlowInEasing))
                } else {
                    // Short-distance switch within the same area: smooth transition into place
                    launch { holeLeft.animateTo(optimalTargetRect.left, tween(300, easing = FastOutSlowInEasing)) }
                    launch { holeTop.animateTo(optimalTargetRect.top, tween(300, easing = FastOutSlowInEasing)) }
                    launch { holeRight.animateTo(optimalTargetRect.right, tween(300, easing = FastOutSlowInEasing)) }
                    launch { holeBottom.animateTo(optimalTargetRect.bottom, tween(300, easing = FastOutSlowInEasing)) }
                    if (spotlightAlpha.value < 1f) {
                        launch { spotlightAlpha.animateTo(1f, tween(200)) }
                    }
                }
                lastTargetRect = optimalTargetRect
            }
        }

        val isLight = MaterialTheme.colorScheme.background.luminance() > 0.5f
        val scrimColor = if (isLight) {
            Color.Black.copy(alpha = 0.40f)
        } else {
            Color.Black.copy(alpha = 0.52f)
        }

        val primaryColor = MaterialTheme.colorScheme.primary

        // 1. Full-screen Canvas: soft translucent scrim + cleared cutout + highlight glow border
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                .pointerInput(targetRect, optimalTargetRect, onTargetClick) {
                    detectTapGestures { offset ->
                        // If the tap lands inside the currently highlighted area, handle the highlighted target's behaviour first
                        if (targetRect != null && spotlightAlpha.value > 0.3f) {
                            val inHole = offset.x in optimalTargetRect.left..optimalTargetRect.right &&
                                offset.y in optimalTargetRect.top..optimalTargetRect.bottom
                            if (inHole) {
                                onTargetClick?.invoke() ?: onNext()
                                return@detectTapGestures
                            }
                        }
                        // Taps outside the scrim are intercepted and consumed, preventing accidental touches on elements behind it
                    }
                }
        ) {
            // Translucent scrim
            drawRect(color = scrimColor)

            if (spotlightAlpha.value > 0f) {
                val currentAlpha = spotlightAlpha.value
                val activeHoleLeft = holeLeft.value
                val activeHoleTop = holeTop.value
                val activeHoleWidth = holeRight.value - holeLeft.value
                val activeHoleHeight = holeBottom.value - holeTop.value

                if (activeHoleWidth > 0f && activeHoleHeight > 0f) {
                    // Cutout
                    drawRoundRect(
                        color = Color.Transparent,
                        topLeft = Offset(activeHoleLeft, activeHoleTop),
                        size = Size(activeHoleWidth, activeHoleHeight),
                        cornerRadius = CornerRadius(holeRadiusPx, holeRadiusPx),
                        blendMode = BlendMode.Clear
                    )
                    // Highlight focus outer border (solid glow)
                    drawRoundRect(
                        color = primaryColor.copy(alpha = pulseAlpha * currentAlpha),
                        topLeft = Offset(activeHoleLeft, activeHoleTop),
                        size = Size(activeHoleWidth, activeHoleHeight),
                        cornerRadius = CornerRadius(holeRadiusPx, holeRadiusPx),
                        style = Stroke(width = 1.5.dp.toPx())
                    )
                    // Outer halo sheen (twice the glow radius)
                    val glowPadding = 2.dp.toPx()
                    drawRoundRect(
                        color = primaryColor.copy(alpha = 0.20f * pulseAlpha * currentAlpha),
                        topLeft = Offset(activeHoleLeft - glowPadding, activeHoleTop - glowPadding),
                        size = Size(activeHoleWidth + glowPadding * 2, activeHoleHeight + glowPadding * 2),
                        cornerRadius = CornerRadius(holeRadiusPx + glowPadding, holeRadiusPx + glowPadding),
                        style = Stroke(width = 1.5.dp.toPx())
                    )
                }
            }
        }

        // 2. Instruction card: precisely anchored from its measured height, never overlapping the spotlight
        val cardWidthDp = 310.dp
        val cardWidthPx = with(density) { cardWidthDp.toPx() }
        var actualCardHeightPx by remember { mutableFloatStateOf(with(density) { 210.dp.toPx() }) }

        val targetCenterX = if (targetRect != null && spotlightAlpha.value > 0.1f) {
            (holeLeft.value + holeRight.value) / 2f
        } else {
            screenWidthPx * 0.5f
        }

        val cardLeftPx = if (isSheetStep) {
            (screenWidthPx - cardWidthPx) / 2f
        } else {
            (targetCenterX - cardWidthPx / 2f).coerceIn(
                cardMarginPx,
                (screenWidthPx - cardWidthPx - cardMarginPx).coerceAtLeast(cardMarginPx)
            )
        }

        val isBottomHalf = targetRect != null && ((holeTop.value + holeBottom.value) / 2f > screenHeightPx * 0.52f)
        val cardTopPx = if (isSheetStep) {
            // Bottom-sheet step: the sheet occupies the lower half of the screen, so the card floats centred in the safe area of the upper half and is never obscured by the sheet
            (screenHeightPx * 0.10f).coerceIn(
                cardMarginPx + with(density) { 36.dp.toPx() },
                (screenHeightPx * 0.38f - actualCardHeightPx).coerceAtLeast(cardMarginPx + with(density) { 36.dp.toPx() })
            )
        } else if (targetRect == null || spotlightAlpha.value < 0.1f) {
            screenHeightPx * 0.38f
        } else if (isBottomHalf) {
            // Target is in the lower half of the screen: the card sits above the target, with the card's bottom edge strictly keeping cardGapPx from the highlight's top edge
            (holeTop.value - cardGapPx - actualCardHeightPx).coerceAtLeast(cardMarginPx)
        } else {
            // Target is in the upper half of the screen: the card sits below the target, with the card's top edge strictly keeping cardGapPx from the highlight's bottom edge
            (holeBottom.value + cardGapPx).coerceAtMost(screenHeightPx - actualCardHeightPx - cardMarginPx)
        }

        val animCardX by animateFloatAsState(
            targetValue = cardLeftPx,
            animationSpec = tween(280, easing = FastOutSlowInEasing),
            label = "CardX"
        )
        val animCardY by animateFloatAsState(
            targetValue = cardTopPx,
            animationSpec = tween(280, easing = FastOutSlowInEasing),
            label = "CardY"
        )

        Box(
            modifier = Modifier
                .offset { IntOffset(animCardX.roundToInt(), animCardY.roundToInt()) }
                .onSizeChanged { size ->
                    if (size.height > 0) {
                        actualCardHeightPx = size.height.toFloat()
                    }
                }
        ) {
            AnimatedContent(
                targetState = currentStep,
                transitionSpec = {
                    (fadeIn(tween(200, delayMillis = 40)) + slideInVertically(tween(200)) { it / 8 })
                        .togetherWith(fadeOut(tween(130)))
                },
                label = "CardContentTransition"
            ) { step ->
                SpotlightCard(
                    step = step,
                    onNext = onNext,
                    onSkip = onSkip,
                    modifier = Modifier.widthIn(max = cardWidthDp)
                )
            }
        }
    }
}

/**
 * A high-quality instruction card that matches the app's developer-tool style.
 */
@Composable
private fun SpotlightCard(
    step: OnboardingStep,
    onNext: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(Radius.lg),
        color = MaterialTheme.semanticColors.cardSurface,
        tonalElevation = 1.dp,
        shadowElevation = 2.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.lg)
        ) {
            // Step badge: minimalist pill capsule
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(Radius.pill),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
                ) {
                    Text(
                        text = stringResource(
                            R.string.onboarding_step_badge,
                            step.stepIndex,
                            OnboardingStep.totalSteps
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = Spacing.sm, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(Spacing.sm))

            // Step title
            Text(
                text = stringResource(step.titleRes),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(Spacing.xs))

            // Step description
            Text(
                text = stringResource(step.descRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 20.sp
            )

            Spacer(modifier = Modifier.height(Spacing.lg))

            // Bottom action area: Skip + Next/Done
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = onSkip,
                    contentPadding = PaddingValues(horizontal = Spacing.sm, vertical = 0.dp)
                ) {
                    Text(
                        text = stringResource(R.string.onboarding_skip),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelLarge
                    )
                }

                Button(
                    onClick = onNext,
                    shape = RoundedCornerShape(Radius.md),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    modifier = Modifier.height(38.dp),
                    contentPadding = PaddingValues(horizontal = Spacing.lg, vertical = 0.dp)
                ) {
                    Text(
                        text = stringResource(if (step.isLastStep) R.string.onboarding_done else R.string.onboarding_next),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

/**
 * Computes the optimal focus cutout region from the target element's bounds in the root view, combined with the screen bounds and the component's characteristics.
 *
 * 1. Compact small targets (e.g. an IconButton top-bar button, a model switch key): focus symmetrically on their geometric visual centre,
 *    avoiding the large block that would be clipped against the edges if padding were added on top of the 48dp interactive hot area;
 * 2. Wide large targets (input boxes, settings rows): keep the outer padding and clamp against the screen edges to prevent overflow;
 * 3. A safe margin [minEdgeMarginPx] is left at the screen edges, ensuring the rounded corners and breathing outline are 100% fully visible and never clipped out of bounds.
 */
private fun calculateOptimalHoleBounds(
    target: Rect,
    screenWidth: Float,
    screenHeight: Float,
    density: androidx.compose.ui.unit.Density
): Pair<Rect, Float> {
    val smallTargetThresholdPx = with(density) { 56.dp.toPx() }
    val minEdgeMarginPx = with(density) { 6.dp.toPx() }

    val isSmallTarget = target.width <= smallTargetThresholdPx && target.height <= smallTargetThresholdPx

    return if (isSmallTarget) {
        val targetSizePx = with(density) { 42.dp.toPx() }
        val halfSize = targetSizePx / 2f
        val centerX = target.center.x
        val centerY = target.center.y

        // Ensure it never goes beyond the screen edge, keeping minEdgeMarginPx
        val left = (centerX - halfSize).coerceIn(minEdgeMarginPx, (screenWidth - minEdgeMarginPx - targetSizePx).coerceAtLeast(minEdgeMarginPx))
        val top = (centerY - halfSize).coerceIn(minEdgeMarginPx, (screenHeight - minEdgeMarginPx - targetSizePx).coerceAtLeast(minEdgeMarginPx))
        val rect = Rect(left, top, left + targetSizePx, top + targetSizePx)
        val cornerRadiusPx = with(density) { 10.dp.toPx() }
        rect to cornerRadiusPx
    } else {
        val paddingPx = with(density) { 4.dp.toPx() }
        val left = (target.left - paddingPx).coerceAtLeast(minEdgeMarginPx)
        val top = (target.top - paddingPx).coerceAtLeast(minEdgeMarginPx)
        val right = (target.right + paddingPx).coerceAtMost(screenWidth - minEdgeMarginPx).coerceAtLeast(left)
        val bottom = (target.bottom + paddingPx).coerceAtMost(screenHeight - minEdgeMarginPx).coerceAtLeast(top)
        val rect = Rect(left, top, right, bottom)
        val cornerRadiusPx = with(density) { 12.dp.toPx() }
        rect to cornerRadiusPx
    }
}
