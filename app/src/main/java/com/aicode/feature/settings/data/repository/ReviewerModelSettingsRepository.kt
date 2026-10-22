package com.aicode.feature.settings.data.repository

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import com.aicode.core.datastore.preferencesCorruptionHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

private val Context.reviewerModelDataStore by preferencesDataStore(
    name = "reviewer_model_prefs",
    corruptionHandler = preferencesCorruptionHandler
)

/**
 * Persists the "AI command reviewer model" selection (two strings: providerId + model).
 *
 * In "autonomous execution" mode, each command awaiting authorization is first independently reviewed by the reviewer model; when no dedicated model is configured (providerId is empty),
 * it follows the current chat model. DataStore usage is consistent with TitleModelSettingsRepository.
 */
@Singleton
class ReviewerModelSettingsRepository @Inject constructor(
    @ApplicationContext context: Context
) : ModelSelectionSettingsRepository(
    context.reviewerModelDataStore, "reviewer_provider_id", "reviewer_model"
) {

    /** Writes the reviewer-specific model (setting an empty string is equivalent to [clear]). */
    suspend fun setReviewerModel(providerId: String, model: String) = setSelection(providerId, model)

    /** Clears the configuration — falls back to "follow the current chat model". */
    suspend fun clear() = clearSelection()

    /** Reads the current reviewer-specific providerId once (for cold reads). */
    suspend fun getReviewerProviderId(): String = readProviderId()

    /** Reads the current reviewer-specific model once (for cold reads). */
    suspend fun getReviewerModel(): String = readModel()
}
