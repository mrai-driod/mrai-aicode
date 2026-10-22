package com.aicode.feature.settings.domain.model

/**
 * Custom dashboard query result.
 */
data class ProviderDashboardResult(
    val card: AdaptiveCardRoot = AdaptiveCardRoot(),
    val rawOutput: String = ""
)

/**
 * Custom dashboard state.
 */
sealed interface ProviderDashboardState {
    data object Idle : ProviderDashboardState
    data object Loading : ProviderDashboardState
    data class Success(val result: ProviderDashboardResult) : ProviderDashboardState
    data class Error(val message: String, val rawOutput: String = "") : ProviderDashboardState
}
