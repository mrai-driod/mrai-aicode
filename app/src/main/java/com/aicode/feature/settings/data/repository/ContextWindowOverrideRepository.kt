package com.aicode.feature.settings.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.aicode.core.datastore.preferencesCorruptionHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.contextWindowOverrideDataStore by preferencesDataStore(
    name = "context_window_override_prefs",
    corruptionHandler = preferencesCorruptionHandler
)

/**
 * Persistence for the global "unified context window value".
 *
 * When enabled, each model's own context window (models.dev / built-in / custom) is ignored and all models uniformly use the value configured here
 * to determine the "usable input budget" and "output reserve", which in turn decides when auto-compaction triggers; when disabled, each model's own value applies again.
 *
 * Required-field contract: the total context window and output window are required (guaranteed by the business layer) — missing either is treated as an incomplete configuration,
 * and the upper layer automatically turns the switch off accordingly, avoiding the ambiguous state of "switch on but no effective value". The input window is optional;
 * leaving it empty means input limit = total window - output reserve (computed by [com.aicode.feature.settings.domain.model.ModelContextPolicy]).
 */
@Singleton
class ContextWindowOverrideRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object Keys {
        val ENABLED = booleanPreferencesKey("override_enabled")
        val CONTEXT_TOKENS = intPreferencesKey("context_tokens")
        val INPUT_TOKENS = intPreferencesKey("input_tokens")
        val OUTPUT_TOKENS = intPreferencesKey("output_tokens")
    }

    /**
     * Flow of the current snapshot. The single outlet for the UI and policy layers:
     * when the switch is off, [ContextWindowOverride.enabled] is false (values are still retained so they can be refilled when re-enabled).
     */
    val overrideFlow: Flow<ContextWindowOverride> =
        context.contextWindowOverrideDataStore.data.map { prefs -> prefs.toOverride() }

    /** Reads the current snapshot once (for cold reads by the policy layer). */
    suspend fun getOverride(): ContextWindowOverride =
        context.contextWindowOverrideDataStore.data.first().toOverride()

    /**
     * Writes the configuration and decides whether it is actually enabled.
     *
     * @return The enabled state actually persisted: forced off if any required field (total context, output) is missing or non-positive.
     *   Callers (the UI) can use the return value directly to refresh the switch state without re-checking themselves.
     */
    suspend fun save(contextTokens: Int, inputTokens: Int?, outputTokens: Int?): Boolean {
        val valid = contextTokens > 0 && (outputTokens ?: 0) > 0
        context.contextWindowOverrideDataStore.edit { prefs ->
            prefs[Keys.CONTEXT_TOKENS] = contextTokens.coerceAtLeast(0)
            if (inputTokens != null && inputTokens > 0) {
                prefs[Keys.INPUT_TOKENS] = inputTokens
            } else {
                prefs.remove(Keys.INPUT_TOKENS)
            }
            if (outputTokens != null && outputTokens > 0) {
                prefs[Keys.OUTPUT_TOKENS] = outputTokens
            } else {
                prefs.remove(Keys.OUTPUT_TOKENS)
            }
            prefs[Keys.ENABLED] = valid
        }
        return valid
    }

    /** Turns the switch off, returning to each model's own context window; entered values are retained for re-enabling. */
    suspend fun setEnabled(enabled: Boolean) {
        if (enabled) {
            // Self-check before re-enabling: only allow turning on if required fields are still complete
            val current = getOverride()
            if (current.contextTokens <= 0 || (current.outputTokens ?: 0) <= 0) return
        }
        context.contextWindowOverrideDataStore.edit { prefs ->
            prefs[Keys.ENABLED] = enabled
        }
    }

    private fun Preferences.toOverride(): ContextWindowOverride {
        val enabled = this[Keys.ENABLED] ?: false
        val contextTokens = this[Keys.CONTEXT_TOKENS] ?: 0
        val inputTokens = this[Keys.INPUT_TOKENS]?.takeIf { it > 0 }
        val outputTokens = this[Keys.OUTPUT_TOKENS]?.takeIf { it > 0 }
        // Storage-layer guard: if required fields are incomplete, it does not take effect even if ENABLED was written as true, avoiding a "on but valueless" state.
        val effective = enabled && contextTokens > 0 && (outputTokens ?: 0) > 0
        return ContextWindowOverride(effective, contextTokens, inputTokens, outputTokens)
    }
}

/**
 * Snapshot of the unified context window value.
 *
 * @param enabled Whether it is in effect (true only when required fields are satisfied).
 * @param contextTokens Total context window.
 * @param inputTokens Input limit; null means the policy layer derives it as "total window - output reserve".
 * @param outputTokens Output limit.
 */
data class ContextWindowOverride(
    val enabled: Boolean,
    val contextTokens: Int,
    val inputTokens: Int?,
    val outputTokens: Int?
)
