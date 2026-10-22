package com.aicode.feature.onboarding.domain

import com.aicode.feature.onboarding.data.OnboardingStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Onboarding coordinator: connects the UI state machine [OnboardingStateHolder] with persistence.
 */
class OnboardingCoordinator(
    private val stateHolder: OnboardingStateHolder,
    private val onPersist: suspend (OnboardingStatus) -> Unit,
    private val scope: CoroutineScope
) {
    /** Launch decision for onboarding: activates only when not completed / still in progress. */
    fun initialize(status: OnboardingStatus) {
        if (status == OnboardingStatus.IN_PROGRESS) {
            stateHolder.start()
        }
    }

    /** Advances to the next step (or completes). */
    fun nextStep() {
        stateHolder.nextStep()
        val current = stateHolder.state.value
        scope.launch { onPersist(current.status) }
    }

    /** Goes back or jumps to the specified step (e.g. falling back to the triggering button when a dialog closes). */
    fun goToStep(targetStep: OnboardingStep) {
        stateHolder.goToStep(targetStep)
        val current = stateHolder.state.value
        scope.launch { onPersist(current.status) }
    }

    /** The user actively skips onboarding. */
    fun skip() {
        stateHolder.skip()
        scope.launch { onPersist(OnboardingStatus.SKIPPED) }
    }

    /** The user completes onboarding. */
    fun complete() {
        stateHolder.completeNow()
        scope.launch { onPersist(OnboardingStatus.COMPLETED) }
    }

    /** Resets and restarts onboarding (triggered from Settings). */
    fun resetAndStart() {
        stateHolder.start()
        scope.launch { onPersist(OnboardingStatus.IN_PROGRESS) }
    }
}
