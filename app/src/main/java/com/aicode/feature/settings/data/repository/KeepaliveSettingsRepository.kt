
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

private val Context.keepaliveDataStore by preferencesDataStore(
    name = "keepalive_prefs",
    corruptionHandler = preferencesCorruptionHandler
)

/**
 * Persists the "background keepalive persistent notification" toggle. Off by default -- the user must enable it manually on the settings page and grant notification permission.
 *
 * When enabled, [com.aicode.AIEditorApp] observes this toggle and starts
 * the persistent foreground notification of [com.aicode.feature.terminal.domain.TerminalKeepaliveService],
 * keeping the App process alive in the background. DataStore usage matches [LogSettingsRepository].
 */
@Singleton
class KeepaliveSettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private companion object {
        val ENABLED_KEY = booleanPreferencesKey("keepalive_enabled")
    }

    /** Flow of the currently persisted toggle; falls back to false when unset (off by default, must be enabled manually). */
    val enabledFlow: Flow<Boolean> = context.keepaliveDataStore.data.map { it[ENABLED_KEY] ?: false }

    /** Writes the toggle. Actually starting/stopping the Service is handled centrally by [com.aicode.AIEditorApp] observing this flow. */
    suspend fun setEnabled(enabled: Boolean) {
        context.keepaliveDataStore.edit { it[ENABLED_KEY] = enabled }
    }

    /** Reads the current value once (for cold-start restore). */
    suspend fun isEnabled(): Boolean = enabledFlow.first()

    /** Backup snapshot: whether the keepalive toggle is currently enabled. */
    suspend fun snapshot(): Boolean = enabledFlow.first()

    /** Restores the keepalive toggle from a backup. */
    suspend fun restore(enabled: Boolean) = setEnabled(enabled)
}
