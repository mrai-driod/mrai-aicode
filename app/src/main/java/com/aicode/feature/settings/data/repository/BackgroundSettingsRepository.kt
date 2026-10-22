package com.aicode.feature.settings.data.repository

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aicode.core.datastore.preferencesCorruptionHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private val Context.backgroundDataStore by preferencesDataStore(
    name = "background_prefs",
    corruptionHandler = preferencesCorruptionHandler
)

/**
 * Global custom background image: the image file is copied into the app's private directory for persistence (not relying on content URI permission),
 * and the opacity and image path are stored in DataStore. A null path means no background is set.
 */
@Singleton
class BackgroundSettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    companion object {
        const val DEFAULT_ALPHA = 0.15f
        /** Upper opacity limit in top-level watermark mode: exceeding it would cover text and affect readability. */
        const val MAX_ALPHA = 0.2f
        /** Minimum opacity: 0 means fully transparent (background invisible). */
        const val MIN_ALPHA = 0f
        private val IMAGE_PATH_KEY = stringPreferencesKey("background_image_path")
        private val ALPHA_KEY = floatPreferencesKey("background_alpha")

        /** Linearly maps a UI percentage (0~100) to the actual opacity (0~MAX_ALPHA). */
        fun sliderToAlpha(percent: Float): Float =
            MAX_ALPHA * (percent / 100f).coerceIn(0f, 1f)

        /** Maps the actual opacity back to a UI percentage (0~100). */
        fun alphaToSlider(alpha: Float): Float =
            (alpha / MAX_ALPHA).coerceIn(0f, 1f) * 100f
    }

    /** Absolute path of the current background image file; null means not set. */
    val imagePathFlow: Flow<String?> = context.backgroundDataStore.data.map { it[IMAGE_PATH_KEY] }

    /** Background image opacity (0.05~0.2; the top-level watermark keeps text readable). */
    val alphaFlow: Flow<Float> = context.backgroundDataStore.data.map {
        (it[ALPHA_KEY] ?: DEFAULT_ALPHA).coerceIn(MIN_ALPHA, MAX_ALPHA)
    }

    /**
     * Selects a new background image: copies [uri] into the private backgrounds/ directory and then replaces the old image.
     * The new file is written successfully first, then DataStore is updated; on failure the old background remains unchanged.
     */
    suspend fun setBackgroundImage(uri: Uri) {
        val extension = extensionFor(uri)
        val target = File(context.filesDir, "backgrounds/bg_${System.currentTimeMillis()}.$extension")
        target.parentFile?.mkdirs()
        val copied = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                target.length() > 0
            }.getOrDefault(false)
        }
        if (!copied) return
        val oldPath = context.backgroundDataStore.data.first()[IMAGE_PATH_KEY]
        context.backgroundDataStore.edit { it[IMAGE_PATH_KEY] = target.absolutePath }
        oldPath?.let { runCatching { File(it).delete() } }
    }

    /** Removes the background: deletes the image file and clears the path. */
    suspend fun clearBackground() {
        val oldPath = context.backgroundDataStore.data.first()[IMAGE_PATH_KEY]
        context.backgroundDataStore.edit { it.remove(IMAGE_PATH_KEY) }
        oldPath?.let { runCatching { File(it).delete() } }
    }

    suspend fun setBackgroundAlpha(alpha: Float) {
        context.backgroundDataStore.edit {
            it[ALPHA_KEY] = alpha.coerceIn(MIN_ALPHA, MAX_ALPHA)
        }
    }

    private fun extensionFor(uri: Uri): String = when (context.contentResolver.getType(uri)) {
        "image/png" -> "png"
        "image/webp" -> "webp"
        else -> "jpg"
    }
}
