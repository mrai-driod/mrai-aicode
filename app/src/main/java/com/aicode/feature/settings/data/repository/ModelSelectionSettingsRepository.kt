package com.aicode.feature.settings.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Shared persistence implementation for dedicated model selection: the two strings providerId + model are stored in a separate DataStore.
 *
 * The three uses (vision / compression / title summary) have identical structure and differ only in DataStore file and key, hence this base class;
 * each subclass declares its own `preferencesDataStore(name=...)` delegate and passes its key names, with unified method semantics:
 * an empty providerId (not configured) means "follow the current chat model".
 */
abstract class ModelSelectionSettingsRepository(
    private val dataStore: DataStore<Preferences>,
    providerIdKeyName: String,
    modelKeyName: String
) {
    private val providerIdKey = stringPreferencesKey(providerIdKeyName)
    private val modelKey = stringPreferencesKey(modelKeyName)

    /** Flow of the currently persisted dedicated providerId; empty string when unset (= follow the chat model). */
    val providerIdFlow: Flow<String> = dataStore.data.map { it[providerIdKey] ?: "" }

    /** Flow of the currently persisted dedicated model; empty string when unset. */
    val modelFlow: Flow<String> = dataStore.data.map { it[modelKey] ?: "" }

    /** Writes the dedicated model (setting empty strings is equivalent to [clearSelection]). */
    protected suspend fun setSelection(providerId: String, model: String) {
        dataStore.edit {
            it[providerIdKey] = providerId
            it[modelKey] = model
        }
    }

    /** Clears the configuration, falling back to "follow the current chat model". */
    protected suspend fun clearSelection() {
        dataStore.edit {
            it.remove(providerIdKey)
            it.remove(modelKey)
        }
    }

    /** Reads the current dedicated providerId once (for cold reads). */
    protected suspend fun readProviderId(): String = providerIdFlow.first()

    /** Reads the current dedicated model once (for cold reads). */
    protected suspend fun readModel(): String = modelFlow.first()
}
