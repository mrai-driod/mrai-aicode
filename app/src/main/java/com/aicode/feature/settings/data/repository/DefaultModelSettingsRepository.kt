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

private val Context.defaultModelDataStore by preferencesDataStore(
    name = "default_model_prefs",
    corruptionHandler = preferencesCorruptionHandler
)

/**
 * Persists the default model for new sessions (the two strings providerId + model).
 *
 * When the user switches the model in an empty session, it is written here in addition to being bound to the current session; new sessions created afterwards
 * bind to that model by default (see AIAgentViewModel.createSession). When unset, new sessions bind to nothing
 * and fall back to the global active provider. DataStore usage matches VisionModelSettingsRepository.
 */
@Singleton
class DefaultModelSettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private companion object {
        val PROVIDER_ID_KEY = stringPreferencesKey("default_provider_id")
        val MODEL_KEY = stringPreferencesKey("default_model")
    }

    /** Flow of the currently persisted default providerId for new sessions; empty string when unset. */
    val providerIdFlow: Flow<String> = context.defaultModelDataStore.data.map { it[PROVIDER_ID_KEY] ?: "" }

    /** Flow of the currently persisted default model for new sessions; empty string when unset. */
    val modelFlow: Flow<String> = context.defaultModelDataStore.data.map { it[MODEL_KEY] ?: "" }

    /** Writes the default model for new sessions (setting empty strings is equivalent to [clear]). */
    suspend fun setDefaultModel(providerId: String, model: String) {
        context.defaultModelDataStore.edit {
            it[PROVIDER_ID_KEY] = providerId
            it[MODEL_KEY] = model
        }
    }

    /** Clears the configuration; new sessions fall back to the global active provider. */
    suspend fun clear() {
        context.defaultModelDataStore.edit {
            it.remove(PROVIDER_ID_KEY)
            it.remove(MODEL_KEY)
        }
    }

    /** Reads the current default providerId for new sessions once (for cold reads). */
    suspend fun getDefaultProviderId(): String = providerIdFlow.first()

    /** Reads the current default model for new sessions once (for cold reads). */
    suspend fun getDefaultModel(): String = modelFlow.first()
}
