package com.aicode.feature.onboarding.domain

import com.aicode.feature.onboarding.data.OnboardingStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * UI runtime state for the focused new-user onboarding flow.
 */
data class OnboardingUiState(
    val active: Boolean = false,
    val step: OnboardingStep = OnboardingStep.OPEN_SIDEBAR,
    val status: OnboardingStatus = OnboardingStatus.IN_PROGRESS
)

/**
 * State machine for new-user onboarding (pure in-memory state; cross-process persistence is handled by OnboardingRepository).
 */
class OnboardingStateHolder {
    private val _state = MutableStateFlow(OnboardingUiState())
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    /** Starts onboarding: begins from the first step, [OnboardingStep.OPEN_SIDEBAR]. */
    fun start() {
        _state.value = OnboardingUiState(
            active = true,
            step = OnboardingStep.OPEN_SIDEBAR,
            status = OnboardingStatus.IN_PROGRESS
        )
    }

    /** Moves forward to the next step; if this is the last step, completes onboarding. */
    fun nextStep() {
        if (!_state.value.active) return
        val current = _state.value.step
        val next = current.next()
        if (next == null) {
            completeNow()
        } else {
            _state.value = _state.value.copy(step = next)
        }
    }

    /** Goes back or jumps to the specified onboarding step. */
    fun goToStep(targetStep: OnboardingStep) {
        if (!_state.value.active) return
        _state.value = _state.value.copy(step = targetStep)
    }

    /** Skips onboarding. */
    fun skip() {
        _state.value = OnboardingUiState(
            active = false,
            step = _state.value.step,
            status = OnboardingStatus.SKIPPED
        )
    }

    /** Completes onboarding. */
    fun completeNow() {
        _state.value = OnboardingUiState(
            active = false,
            step = _state.value.step,
            status = OnboardingStatus.COMPLETED
        )
    }

    /** Whether onboarding is currently in progress. */
    val isActive: Boolean get() = _state.value.active
}

/** Returns the step after the current one, or null if already at the last step. */
internal fun OnboardingStep.next(): OnboardingStep? {
    val nextIndex = ordinal + 1
    return if (nextIndex < OnboardingStep.all.size) OnboardingStep.all[nextIndex] else null
}
