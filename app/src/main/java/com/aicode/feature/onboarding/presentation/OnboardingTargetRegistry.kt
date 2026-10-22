package com.aicode.feature.onboarding.presentation

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import com.aicode.feature.onboarding.domain.OnboardingStep

/**
 * Registry of onboarding target components: centrally records the bounding rectangles of each step's highlighted component in the Root coordinate system.
 */
class OnboardingTargetRegistry {
    private val _targets = mutableStateMapOf<OnboardingStep, Rect>()
    val targets: Map<OnboardingStep, Rect> get() = _targets

    fun register(step: OnboardingStep, rect: Rect) {
        _targets[step] = rect
    }

    fun unregister(step: OnboardingStep) {
        _targets.remove(step)
    }

    fun targetFor(step: OnboardingStep): Rect? = _targets[step]
}

/**
 * Global CompositionLocal, so deeply nested components can report their highlight position without being aware of it.
 */
val LocalOnboardingTargetRegistry = compositionLocalOf<OnboardingTargetRegistry?> { null }

/**
 * Convenience Modifier: tags a target component and automatically reports its coordinate bounds once laid out.
 */
fun Modifier.onboardingTarget(step: OnboardingStep): Modifier = composed {
    val registry = LocalOnboardingTargetRegistry.current
    if (registry == null) {
        this
    } else {
        DisposableEffect(step) {
            onDispose {
                registry.unregister(step)
            }
        }
        this.onGloballyPositioned { coordinates ->
            if (coordinates.isAttached) {
                registry.register(step, coordinates.boundsInRoot())
            }
        }
    }
}
