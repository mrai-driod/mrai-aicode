package com.aicode.feature.settings.data.repository

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import com.aicode.core.datastore.preferencesCorruptionHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

private val Context.titleModelDataStore by preferencesDataStore(
    name = "title_model_prefs",
    corruptionHandler = preferencesCorruptionHandler
)

/**
 * Persists the selection of the dedicated title-summary model (the two strings providerId + model).
 *
 * Title generation for new sessions follows the current chat model by default; once the user specifies a dedicated model here,
 * title generation temporarily switches to that dedicated model to send the request and restores afterwards. An empty providerId (not configured) means "follow the current chat model".
 */
@Singleton
class TitleModelSettingsRepository @Inject constructor(
    @ApplicationContext context: Context
) : ModelSelectionSettingsRepository(
    context.titleModelDataStore, "title_provider_id", "title_model"
) {

    /** Writes the dedicated title-summary model (setting empty strings is equivalent to [clear]). */
    suspend fun setTitleModel(providerId: String, model: String) = setSelection(providerId, model)

    /** Clears the configuration, falling back to "follow the current chat model". */
    suspend fun clear() = clearSelection()

    /** Reads the current dedicated title-summary providerId once (for cold reads). */
    suspend fun getTitleProviderId(): String = readProviderId()

    /** Reads the current dedicated title-summary model once (for cold reads). */
    suspend fun getTitleModel(): String = readModel()
}
