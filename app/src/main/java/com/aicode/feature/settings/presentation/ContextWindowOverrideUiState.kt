package com.aicode.feature.settings.presentation

/**
 * UI snapshot of the "Context window unified advanced settings" page.
 *
 * [enabled] is the state actually in effect — it is always false while required fields (total context window, output window) are incomplete,
 * and both the storage layer and the business layer have a fallback, so the UI does not need to judge this itself.
 */
data class ContextWindowOverrideUiState(
    val enabled: Boolean = false,
    val contextTokens: Int = 0,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null
)
