package com.aicode.feature.settings.data.repository

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

private val Context.imageGenModelDataStore by preferencesDataStore(name = "image_gen_model_prefs")

/**
 * Persists the "image generation model" selection (two strings: providerId + model).
 *
 * Image generation (generateImage) must use a model that supports image output (such as OpenAI's gpt-image-1 /
 * dall-e-3); it does not follow the current chat model — chat models usually have no image output capability. When not configured, the image generation tool
 * returns an error message with setup guidance.
 * DataStore usage is consistent with [VisionModelSettingsRepository].
 */
@Singleton
class ImageGenModelSettingsRepository @Inject constructor(
    @ApplicationContext context: Context
) : ModelSelectionSettingsRepository(
    context.imageGenModelDataStore, "image_gen_provider_id", "image_gen_model"
) {

    /** Writes the image-generation-specific model (setting an empty string is equivalent to [clear]). */
    suspend fun setImageGenModel(providerId: String, model: String) = setSelection(providerId, model)

    /** Clears the configuration — the image generation model returns to the unconfigured state. */
    suspend fun clear() = clearSelection()

    /** Reads the current image-generation-specific providerId once (for cold reads). */
    suspend fun getImageGenProviderId(): String = readProviderId()

    /** Reads the current image-generation-specific model once (for cold reads). */
    suspend fun getImageGenModel(): String = readModel()
}