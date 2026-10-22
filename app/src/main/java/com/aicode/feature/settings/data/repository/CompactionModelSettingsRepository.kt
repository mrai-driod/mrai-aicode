package com.aicode.feature.settings.data.repository

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import com.aicode.core.datastore.preferencesCorruptionHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

private val Context.compactionModelDataStore by preferencesDataStore(
    name = "compaction_model_prefs",
    corruptionHandler = preferencesCorruptionHandler
)

/**
 * Persists the "compaction-specific model" selection (two strings: providerId + model).
 *
 * Context compaction follows the current chat model by default; once the user specifies a dedicated model here,
 * the compaction turn temporarily switches to that dedicated model to send the summary request, then restores the chat model afterwards.
 * An empty providerId (not configured) is treated as "follow the current chat model".
 */
@Singleton
class CompactionModelSettingsRepository @Inject constructor(
    @ApplicationContext context: Context
) : ModelSelectionSettingsRepository(
    context.compactionModelDataStore, "compaction_provider_id", "compaction_model"
) {

    /** Writes the compaction-specific model (setting an empty string is equivalent to [clear]). */
    suspend fun setCompactionModel(providerId: String, model: String) = setSelection(providerId, model)

    /** Clears the configuration — falls back to "follow the current chat model". */
    suspend fun clear() = clearSelection()

    /** Reads the current compaction-specific providerId once (for cold reads). */
    suspend fun getCompactionProviderId(): String = readProviderId()

    /** Reads the current compaction-specific model once (for cold reads). */
    suspend fun getCompactionModel(): String = readModel()
}
