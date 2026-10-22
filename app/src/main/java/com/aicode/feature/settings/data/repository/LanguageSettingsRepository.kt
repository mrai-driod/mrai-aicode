package com.aicode.feature.settings.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aicode.core.datastore.preferencesCorruptionHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.languageDataStore by preferencesDataStore(
    name = "language_prefs",
    corruptionHandler = preferencesCorruptionHandler
)

private const val PREFS_NAME = "language_prefs_sync"
private const val PREFS_KEY = "language_tag"

/** Mrai fork default UI/agent language (used when no explicit choice is stored). */
const val DEFAULT_LANGUAGE_TAG = "en"

/**
 * Persists the app language chosen by the user.
 *
 * The stored value is a BCP-47 language tag (such as "zh" or "en"); null means follow the system.
 * On a switch it writes the synchronous prefs, which [com.aicode.MainActivity] reads in attachBaseContext and applies via recreate.
 */
@Singleton
class LanguageSettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private companion object {
        val LANGUAGE_KEY = stringPreferencesKey("language_tag")
    }

    private val syncPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val languageFlow: Flow<String?> = context.languageDataStore.data.map { prefs ->
        prefs[LANGUAGE_KEY]
    }

    suspend fun setLanguage(tag: String?) {
        context.languageDataStore.edit {
            if (tag == null) it.remove(LANGUAGE_KEY) else it[LANGUAGE_KEY] = tag
        }
        syncPrefs.edit().apply {
            if (tag == null) remove(PREFS_KEY) else putString(PREFS_KEY, tag)
        }.apply()
    }

    /** Mrai fork: English is the default language when the user has not picked one. */
    suspend fun snapshot(): String? = languageFlow.first() ?: DEFAULT_LANGUAGE_TAG

    /** Reads the language tag synchronously, for attachBaseContext to call before the Activity is created (when Hilt has not been injected yet). */
    fun getLanguageSync(): String? = syncPrefs.getString(PREFS_KEY, null) ?: DEFAULT_LANGUAGE_TAG

    suspend fun restore(value: String?) {
        context.languageDataStore.edit {
            if (value == null) it.remove(LANGUAGE_KEY) else it[LANGUAGE_KEY] = value
        }
    }
}
