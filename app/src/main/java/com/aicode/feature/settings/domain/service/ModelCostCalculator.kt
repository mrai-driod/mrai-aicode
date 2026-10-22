package com.aicode.feature.settings.domain.service

import com.aicode.feature.settings.data.remote.ModelMetadataService
import com.aicode.feature.settings.domain.model.ProviderType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * LLM invocation cost estimation. Unit prices are resolved via [ModelMetadataService] with a three-level fallback of "provider custom metadata -> models.dev -> built-in",
 * so the provider (providerId + type) must be included; calculating by model alone cannot retrieve provider-custom unit prices.
 */
@Singleton
class ModelCostCalculator @Inject constructor(
    private val modelMetadataService: ModelMetadataService
) {
    /**
     * Estimated cost (USD) for a set of token usage; returns null when the model has no unit price.
     * When the cache-read price is missing, it is estimated at [CACHE_READ_DISCOUNT] times the input price; when the cache-write price is missing, it is estimated at [CACHE_WRITE_MARKUP] times the input price.
     */
    suspend fun costUsd(
        providerId: String,
        providerType: ProviderType,
        model: String,
        inputTokens: Long,
        cachedInputTokens: Long,
        outputTokens: Long,
        cacheCreationTokens: Long = 0
    ): Double? {
        val meta = modelMetadataService.resolve(providerId, providerType, model)
        val inputPrice = meta.inputCostUsdPerM ?: return null
        val outputPrice = meta.outputCostUsdPerM ?: 0.0
        val cachePrice = meta.cacheReadCostUsdPerM ?: inputPrice * CACHE_READ_DISCOUNT
        val cacheWritePrice = meta.cacheWriteCostUsdPerM ?: inputPrice * CACHE_WRITE_MARKUP
        val uncached = (inputTokens - cachedInputTokens - cacheCreationTokens).coerceAtLeast(0)
        return (uncached * inputPrice + cachedInputTokens * cachePrice +
            cacheCreationTokens * cacheWritePrice + outputTokens * outputPrice) / 1_000_000.0
    }

    companion object {
        /** Discount applied to the input price to estimate the cost when the cache-read price is missing. */
        const val CACHE_READ_DISCOUNT = 0.1

        /** Multiplier relative to the input price when the cache-write unit price is missing (Anthropic's official value is 1.25x). */
        const val CACHE_WRITE_MARKUP = 1.25
    }
}
