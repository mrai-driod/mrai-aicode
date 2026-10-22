package com.aicode.feature.settings.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.aicode.core.datastore.preferencesCorruptionHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.agentSoundDataStore by preferencesDataStore(
    name = "agent_sound_prefs",
    corruptionHandler = preferencesCorruptionHandler
)

/**
 * Persists the "agent completion sound" toggle. Off by default — the user must enable it manually on the app permissions page.
 * When enabled, the system notification sound plays whenever the agent completes a conversation turn while the App is not in the foreground. DataStore usage is consistent with
 * [KeepaliveSettingsRepository].
 */
@Singleton
class AgentSoundSettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private companion object {
        val ENABLED_KEY = booleanPreferencesKey("agent_sound_enabled")
    }

    /** The currently persisted toggle flow; falls back to false when unset (off by default). */
    val enabledFlow: Flow<Boolean> = context.agentSoundDataStore.data.map { it[ENABLED_KEY] ?: false }

    /** Writes the toggle. */
    suspend fun setEnabled(enabled: Boolean) {
        context.agentSoundDataStore.edit { it[ENABLED_KEY] = enabled }
    }

    /** Reads the current value once (used to decide whether to play the sound when the agent completes). */
    suspend fun isEnabled(): Boolean = enabledFlow.first()

    /** Backup snapshot: whether the completion-sound toggle is currently enabled. */
    suspend fun snapshot(): Boolean = enabledFlow.first()

    /** Restores the completion-sound toggle from a backup. */
    suspend fun restore(enabled: Boolean) = setEnabled(enabled)
}