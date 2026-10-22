package com.aicode.feature.settings.data.repository

import com.aicode.feature.settings.data.local.CustomModelMetadataStore
import com.aicode.feature.settings.domain.model.AIProviderConfig
import com.aicode.feature.settings.domain.model.ModelMetadata
import com.aicode.feature.settings.domain.model.ProviderType
import com.aicode.feature.settings.domain.repository.AIProviderRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Mrai fork: seed the built-in free providers on first run so the app works
 * with zero setup — upstream ships with no usable model until the user pastes
 * an API key. Seeding is insert-only: once a provider row exists it is never
 * touched again, so user edits and deletions are respected (a deleted built-in
 * is re-added only while its row is absent at a later cold start).
 *
 * Both providers are keyless; their stored API key is the literal placeholder
 * [KEYLESS], which satisfies the "provider must have a key" checks while the
 * underlying endpoints ignore it.
 */
@Singleton
class BuiltInProviderSeeder @Inject constructor(
    private val providerRepository: AIProviderRepository,
    private val defaultModelSettings: DefaultModelSettingsRepository,
    private val customModelMetadataStore: CustomModelMetadataStore
) {

    suspend fun seedIfNeeded() {
        if (providerRepository.getProviderById(MRAI_ID) == null) {
            providerRepository.saveProvider(mraiAiConfig())
        }
        if (providerRepository.getProviderById(OPENCODE_FREE_ID) == null) {
            providerRepository.saveProvider(openCodeFreeConfig())
        }
        // Make Mrai AI the out-of-box default only when the user has not
        // already chosen a default provider themselves.
        if (defaultModelSettings.getDefaultProviderId().isBlank()) {
            defaultModelSettings.setDefaultModel(MRAI_ID, MRAI_MODEL)
        }
        seedModelMetadata()
    }

    /**
     * Pin capability metadata for the built-in models (insert-only, same rule
     * as the providers: a user's own Settings override is never stomped).
     *
     * Why: these ids are not in the models.dev catalog, so they resolve via
     * the unknown-model fallback — tools on, vision off. That fallback happens
     * to be right for most of them, but pinning it explicitly protects the
     * built-ins from a future catalog refresh mis-matching an id by suffix,
     * and it unlocks vision for the one genuinely multimodal model in the
     * free list. Verified against the live endpoints (Oct 22, 2026):
     * Pollinations' `openai` model accepts tool calls but rejects image
     * input, so Mrai AI stays vision-off (the app then swaps attached images
     * for a placeholder instead of failing the whole request).
     */
    private suspend fun seedModelMetadata() {
        seedMetadata(MRAI_ID, MRAI_MODEL, supportsVision = false)
        for (model in OPENCODE_FREE_MODELS) {
            seedMetadata(
                OPENCODE_FREE_ID, model,
                supportsVision = model in VISION_MODELS
            )
        }
    }

    private suspend fun seedMetadata(providerId: String, model: String, supportsVision: Boolean) {
        if (customModelMetadataStore.get(providerId, model) != null) return
        customModelMetadataStore.put(
            providerId, model,
            ModelMetadata(
                id = model,
                // 0 keeps the auto-resolved window (unknown models get 128k).
                contextTokens = 0,
                supportsTools = true,
                supportsVision = supportsVision
            )
        )
    }

    private fun mraiAiConfig() = AIProviderConfig(
        id = MRAI_ID,
        name = "Mrai AI",
        type = ProviderType.OPENAI,
        apiKey = KEYLESS,
        baseUrl = "https://text.pollinations.ai/openai",
        defaultModel = MRAI_MODEL,
        models = listOf(MRAI_MODEL),
        selectedModel = MRAI_MODEL,
        // Pollinations answers at the base URL itself (Buddy's exact shape).
        useFullUrl = true
    )

    private fun openCodeFreeConfig() = AIProviderConfig(
        id = OPENCODE_FREE_ID,
        name = "OpenCode Free",
        type = ProviderType.OPENAI,
        apiKey = KEYLESS,
        baseUrl = "https://opencode.ai/zen/v1",
        defaultModel = OPENCODE_FREE_MODELS.first(),
        models = OPENCODE_FREE_MODELS,
        selectedModel = OPENCODE_FREE_MODELS.first()
    )

    companion object {
        const val MRAI_ID = "mrai"
        const val MRAI_MODEL = "mrai-auto"
        const val OPENCODE_FREE_ID = "opencode-free"
        const val KEYLESS = "keyless"

        /**
         * Vision-capable models in the OpenCode Free list, per models.dev
         * input modalities (checked Oct 22, 2026) — all six also support
         * tool calls, so they stay full agent models with images on.
         */
        val VISION_MODELS = setOf(
            "mimo-v2.6-flash-free",
            "nemotron-3.5-super-vl-free",
            "longcat-2.5-preview-free",
            "deepseek-v4.1-flash-free",
            "glm-5.3-flash-free",
            "minimax-m3.1-flash-free"
        )

        /** OpenCode Zen's free tier, as listed by its live /models catalog. */
        val OPENCODE_FREE_MODELS = listOf(
            "big-pickle",
            "beam-free",
            "flint-40b-free",
            "mimo-v2.6-flash-free",
            "nemotron-3.5-super-vl-free",
            "nemotron-3.5-lightning-free",
            "jev-1.13-free",
            "longcat-2.5-preview-free",
            "deepseek-v4.1-flash-free",
            "glm-5.3-flash-free",
            "minimax-m3.1-flash-free"
        )
    }
}
