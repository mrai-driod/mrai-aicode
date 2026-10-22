package com.aicode.feature.settings.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Model default reasoning effort" memory: key = "providerId:model", value = ReasoningEffort.name.
 * Written when the user switches reasoning effort for a model; when a new session is bound to that model, it initialises the session level from it,
 * providing per-model level memory (similar to how opencode stores the selected variant per model).
 */
@Singleton
class ModelReasoningEffortRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Reads a model's default level name; returns null if none was remembered. */
    fun get(providerId: String, model: String): String? =
        prefs.getString(key(providerId, model), null)

    /** Remembers a model's default level name. */
    fun set(providerId: String, model: String, effortName: String) {
        prefs.edit().putString(key(providerId, model), effortName).apply()
    }

    private fun key(providerId: String, model: String) = "$providerId:$model"

    private companion object {
        const val PREFS_NAME = "model_reasoning_effort_prefs"
    }
}