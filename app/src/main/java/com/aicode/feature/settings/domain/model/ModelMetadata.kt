package com.aicode.feature.settings.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class ModelMetadata(
    val id: String,
    val providerId: String? = null,
    val displayName: String = id,
    val contextTokens: Int,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val supportsTools: Boolean = false,
    val supportsVision: Boolean = false,
    val supportsReasoning: Boolean = false,
    /**
     * models.dev `temperature`: whether this model accepts a custom temperature.
     * false (including missing metadata) means the server has fixed the sampling temperature, so requests must not include this field --
     * models such as the kimi-k3 / gpt-5 families will return a direct 400 if it is included.
     */
    val supportsCustomTemperature: Boolean = false,
    val modelType: ModelType = ModelType.CHAT,
    val supportsImageOutput: Boolean = false,
    val source: Source = Source.INFERRED,
    /** models.dev reasoning effort levels (values of the effort type in reasoning_options, e.g. ["low","medium","high"]); null/empty = no levels (reasoning effort selection is not shown). */
    val reasoningEffortOptions: List<String>? = null,
    /** models.dev cost: input unit price (USD/1M tokens). */
    val inputCostUsdPerM: Double? = null,
    /** models.dev cost: output unit price (USD/1M tokens). */
    val outputCostUsdPerM: Double? = null,
    /** models.dev cost: cache read unit price (USD/1M tokens). */
    val cacheReadCostUsdPerM: Double? = null,
    /** models.dev cost: cache write unit price (USD/1M tokens), usually higher than the normal input price. */
    val cacheWriteCostUsdPerM: Double? = null
) {
    enum class ModelType { CHAT, EMBEDDING }

    enum class Source {
        MODELS_DEV,
        INFERRED
    }
}

/**
 * Key for the model metadata cache.
 * Metadata cannot be indexed by model name alone: custom prices are stored by "provider ID:model name", and different channel types produce different
 * automatic matching results for the same model name, so indexing by model name alone would cross-contaminate entries (taking another channel's price/capabilities).
 */
fun modelMetadataKey(providerId: String, model: String): String = "$providerId:$model"

/**
 * Merges custom metadata with automatically resolved (fetched/built-in) metadata, with custom values taking priority; when a window is not filled in, the automatic value is kept.
 * When [base] is empty, constructs fallback metadata (window 0, all capabilities false).
 */
fun mergeModelMetadata(
    model: String,
    base: ModelMetadata?,
    custom: ModelMetadata?
): ModelMetadata {
    val a = base ?: ModelMetadata(
        id = model,
        displayName = model,
        contextTokens = 0,
        inputTokens = null,
        outputTokens = null
    )
    val c = custom ?: return a
    return a.copy(
        modelType = c.modelType,
        supportsVision = c.supportsVision,
        supportsImageOutput = c.supportsImageOutput,
        supportsTools = c.supportsTools,
        supportsReasoning = c.supportsReasoning,
        contextTokens = c.contextTokens.takeIf { it > 0 } ?: a.contextTokens,
        inputTokens = c.inputTokens ?: a.inputTokens,
        outputTokens = c.outputTokens ?: a.outputTokens,
        inputCostUsdPerM = c.inputCostUsdPerM ?: a.inputCostUsdPerM,
        outputCostUsdPerM = c.outputCostUsdPerM ?: a.outputCostUsdPerM,
        cacheReadCostUsdPerM = c.cacheReadCostUsdPerM ?: a.cacheReadCostUsdPerM,
        cacheWriteCostUsdPerM = c.cacheWriteCostUsdPerM ?: a.cacheWriteCostUsdPerM
    )
}

