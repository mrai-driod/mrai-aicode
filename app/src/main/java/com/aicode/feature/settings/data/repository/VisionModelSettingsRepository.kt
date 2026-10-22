package com.aicode.feature.settings.data.repository

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import com.aicode.core.datastore.preferencesCorruptionHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

private val Context.visionModelDataStore by preferencesDataStore(
    name = "vision_model_prefs",
    corruptionHandler = preferencesCorruptionHandler
)

/**
 * Persists the "vision model" selection (two strings: providerId + model).
 *
 * Image viewing (viewImage) follows the current chat model by default; once the user specifies a dedicated model here, the vision session uses that model.
 * The model's vision capability is not validated; on invocation failure, the error message is returned verbatim as the tool result.
 * DataStore usage is consistent with [KeepaliveSettingsRepository] / [LogSettingsRepository].
 */
@Singleton
class VisionModelSettingsRepository @Inject constructor(
    @ApplicationContext context: Context
) : ModelSelectionSettingsRepository(
    context.visionModelDataStore, "vision_provider_id", "vision_model"
) {

    /** Writes the vision-specific model (setting an empty string is equivalent to [clear]). */
    suspend fun setVisionModel(providerId: String, model: String) = setSelection(providerId, model)

    /** Clears the configuration — falls back to "follow the current chat model". */
    suspend fun clear() = clearSelection()

    /** Reads the current vision-specific providerId once (for cold reads). */
    suspend fun getVisionProviderId(): String = readProviderId()

    /** Reads the current vision-specific model once (for cold reads). */
    suspend fun getVisionModel(): String = readModel()
}
