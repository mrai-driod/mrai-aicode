package com.aicode.feature.onboarding.data

import android.content.Context
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.onboardingDataStore by preferencesDataStore(name = "onboarding_prefs")

/** Persisted state for the first-launch onboarding flow. */
enum class OnboardingStatus(val persisted: String) {
    IN_PROGRESS("in_progress"),
    COMPLETED("completed"),
    SKIPPED("skipped");

    companion object {
        fun fromPersisted(value: String?): OnboardingStatus? =
            entries.firstOrNull { it.persisted == value }
    }
}

/**
 * Persistence for first-launch onboarding progress: a single key records the three states.
 *
 * Written on every step advance, so if the process is killed and restarted it returns to the current step; completion/skip is written only once, when the state actually transitions.
 * Style aligned with [ThemeSettingsRepository] (the same DataStore pattern).
 */
@Singleton
class OnboardingRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private companion object {
        val ONBOARDING_STATUS_KEY = stringPreferencesKey("onboarding_status")
    }

    /** The current onboarding state; null on a fresh install (never written), which callers treat as IN_PROGRESS. */
    val statusFlow: Flow<OnboardingStatus?> =
        context.onboardingDataStore.data.map { OnboardingStatus.fromPersisted(it[ONBOARDING_STATUS_KEY]) }

    /** A snapshot of the current state, for a one-off read during the launch decision. */
    suspend fun status(): OnboardingStatus? = statusFlow.first()

    suspend fun markStatus(status: OnboardingStatus) {
        context.onboardingDataStore.edit { it[ONBOARDING_STATUS_KEY] = status.persisted }
    }

    /** Completes onboarding; called after a real completion event, and writes only once if the state is not already completed. */
    suspend fun complete() {
        val current = status()
        if (current != OnboardingStatus.COMPLETED) markStatus(OnboardingStatus.COMPLETED)
    }

    /** Skips onboarding; called when the user taps skip or on a forced reset. */
    suspend fun skip() {
        val current = status()
        if (current != OnboardingStatus.SKIPPED) markStatus(OnboardingStatus.SKIPPED)
    }

    /** Resets to in-progress (used by the "Re-run onboarding" option in Settings). */
    suspend fun resetToInProgress() {
        val current = status()
        if (current != OnboardingStatus.IN_PROGRESS) markStatus(OnboardingStatus.IN_PROGRESS)
    }
}