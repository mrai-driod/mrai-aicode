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

private val Context.screenOnDataStore by preferencesDataStore(
    name = "screen_on_prefs",
    corruptionHandler = preferencesCorruptionHandler
)

/**
 * Persists the "keep screen on" toggle. Off by default.
 *
 * When enabled, [com.aicode.MainActivity] observes this toggle and adds
 * [android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON] to the window. This flag only constrains the screen while the App is in the foreground,
 * and is automatically invalidated by the system when the App moves to the background, so it does not need to be released manually like a wakeLock.
 * DataStore usage is consistent with [KeepaliveSettingsRepository].
 */
@Singleton
class ScreenOnSettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private companion object {
        val ENABLED_KEY = booleanPreferencesKey("screen_on_enabled")
    }

    /** The currently persisted toggle flow; falls back to false when unset (off by default, must be enabled manually). */
    val enabledFlow: Flow<Boolean> = context.screenOnDataStore.data.map { it[ENABLED_KEY] ?: false }

    /** Writes the toggle. Adding/removing the window flag is handled uniformly by [com.aicode.MainActivity] observing this flow. */
    suspend fun setEnabled(enabled: Boolean) {
        context.screenOnDataStore.edit { it[ENABLED_KEY] = enabled }
    }

    /** Backup snapshot: whether the keep-screen-on toggle is currently enabled. */
    suspend fun snapshot(): Boolean = enabledFlow.first()

    /** Restores the keep-screen-on toggle from a backup. */
    suspend fun restore(enabled: Boolean) = setEnabled(enabled)
}
