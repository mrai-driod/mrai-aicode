package com.aicode.feature.settings.data.remote

import android.content.Context
import com.aicode.core.net.RepoDataFetcher
import com.aicode.core.util.FileLogger
import com.aicode.feature.settings.data.local.CustomModelMetadataStore
import com.aicode.feature.settings.data.repository.ContextWindowOverride
import com.aicode.feature.settings.data.repository.ContextWindowOverrideRepository
import com.aicode.feature.settings.domain.model.ModelMetadata
import com.aicode.feature.settings.domain.model.ProviderType
import com.aicode.feature.settings.domain.model.mergeModelMetadata
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ModelMetadataService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val customModelMetadataStore: CustomModelMetadataStore,
    private val contextWindowOverrideRepository: ContextWindowOverrideRepository
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Unified fetcher: model metadata is fetched from a separate data branch, to avoid daily machine-generated output polluting main. */
    private val repoFetcher = RepoDataFetcher(context, branch = MODELS_BRANCH)

    @Volatile
    private var cached: Catalog? = null

    @Volatile
    private var refreshAttemptedThisProcess = false

    suspend fun resolve(providerId: String, type: ProviderType, modelId: String): ModelMetadata =
        withContext(Dispatchers.IO) {
            val catalog = loadCatalog()
            val auto = findMetadata(catalog, type, modelId) ?: default(type, modelId)
            applyGlobalOverride(mergeCustom(providerId, modelId, auto))
        }

    suspend fun resolveAll(providerId: String, type: ProviderType, modelIds: List<String>): Map<String, ModelMetadata> =
        withContext(Dispatchers.IO) {
            val catalog = loadCatalog()
            val override = contextWindowOverrideRepository.getOverride()
            modelIds.associateWith { modelId ->
                val auto = findMetadata(catalog, type, modelId) ?: default(type, modelId)
                applyGlobalOverride(mergeCustom(providerId, modelId, auto), override)
            }
        }

    /**
     * Apply the "unified context window advanced setting": once enabled, ignore the model's own window values
     * and use the user-configured value uniformly for all models — so the auto-compaction threshold and output reservation are all calculated with the same ruler.
     *
     * When the input window is left empty it is set to null (rather than keeping the model's old value), leaving
     * [com.aicode.feature.settings.domain.model.ModelContextPolicy.effectiveInputBudget] to derive it from
     * "total window - output reservation"; otherwise the model's old cap would clamp the user's enlarged total window back down, making it have no effect.
     */
    private suspend fun applyGlobalOverride(
        meta: ModelMetadata,
        cached: ContextWindowOverride? = null
    ): ModelMetadata {
        val o = cached ?: contextWindowOverrideRepository.getOverride()
        if (!o.enabled) return meta
        return meta.copy(
            contextTokens = o.contextTokens,
            inputTokens = o.inputTokens,
            outputTokens = o.outputTokens
        )
    }

    /** Custom metadata takes priority over automatically resolved (fetched/built-in) results; the merge is skipped when providerId is empty (not associated with a configuration). */
    private suspend fun mergeCustom(providerId: String, modelId: String, auto: ModelMetadata): ModelMetadata {
        if (providerId.isBlank()) return auto
        val custom = customModelMetadataStore.get(providerId, modelId)
        return mergeModelMetadata(modelId, auto, custom)
    }

    /** Called uniformly at startup: refreshes the model catalog from this repository via the unified fetcher (12h cache, silent on failure). */
    suspend fun refreshFromNetworkIfStale() {
        if (refreshAttemptedThisProcess) return
        refreshAttemptedThisProcess = true
        withContext(Dispatchers.IO) {
            val result = runCatching { repoFetcher.fetch(MODELS_REPO_PATH) }.getOrNull()
            val body = when (result) {
                is RepoDataFetcher.FetchResult.Success -> result.content
                is RepoDataFetcher.FetchResult.FallbackDiskCache -> result.content
                else -> null
            } ?: return@withContext
            if (body.isBlank()) return@withContext
            runCatching { parseCatalog(json.parseToJsonElement(body)) }
                .onSuccess { cached = it }
                .onFailure { FileLogger.w(TAG, "Failed to parse model metadata", it) }
        }
    }

    /** Purely read-only chain: memory → this repository's disk cache → built-in assets → empty catalog (the caller falls back to defaults); never issues a network request. */
    private fun loadCatalog(): Catalog {
        cached?.let { return it }

        repoFetcher.readLocalCache(MODELS_REPO_PATH)?.let { body ->
            runCatching { parseCatalog(json.parseToJsonElement(body)) }.getOrNull()?.let {
                cached = it
                return it
            }
        }

        loadCatalogFromAssets()?.let {
            cached = it
            return it
        }

        return Catalog(emptyMap(), emptyMap())
    }

    private fun loadCatalogFromAssets(): Catalog? = runCatching {
        val body = context.assets.open(ASSET_FILE_NAME).bufferedReader().use { it.readText() }
        parseCatalog(json.parseToJsonElement(body))
    }.getOrNull()

    /** Fallback when no model matches in the catalog: uniformly treated as a text model, 128k input / 64k output. */
    private fun default(type: ProviderType, modelId: String): ModelMetadata = ModelMetadata(
        id = modelId,
        providerId = type.name.lowercase(),
        displayName = modelId,
        contextTokens = DEFAULT_CONTEXT_TOKENS,
        inputTokens = DEFAULT_CONTEXT_TOKENS,
        outputTokens = DEFAULT_OUTPUT_TOKENS,
        supportsTools = true,
        supportsVision = false,
        supportsReasoning = false,
        source = ModelMetadata.Source.INFERRED
    )

    companion object {
        /** Extract the values of the effort type from the models.dev reasoning_options array; returns an empty list when there is no effort entry. */
        fun parseReasoningOptions(reasoningOptions: JsonElement?): List<String> =
            reasoningOptions?.takeIf { it !is JsonNull }?.jsonArray
                ?.mapNotNull { it.jsonObject }
                ?.firstOrNull { it["type"]?.jsonPrimitive?.content == "effort" }
                ?.get("values")?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.content }
                .orEmpty()

        /**
         * The parsed model catalog: [byProvider] preserves the original id casing from the catalog; [lowerByProvider] has the same structure but with keys uniformly lowercased
         * (reusing the same batch of [ModelMetadata] instances), for case-insensitive matching.
         */
        internal data class Catalog(
            val byProvider: Map<String, Map<String, ModelMetadata>>,
            val lowerByProvider: Map<String, Map<String, ModelMetadata>>
        )

        internal fun parseCatalog(root: JsonElement): Catalog {
            val byProvider = root.jsonObject.mapValues { (providerId, providerEl) ->
                val models = providerEl.jsonObject["models"]?.jsonObject.orEmpty()
                models.mapValues { (_, modelEl) ->
                    val model = modelEl.jsonObject
                    val limit = model["limit"]?.jsonObject
                    val modalities = model["modalities"]?.jsonObject
                    val inputModalities = modalities?.get("input")?.jsonArray
                        ?.mapNotNull { it.jsonPrimitive.content }
                        .orEmpty()
                    val outputModalities = modalities?.get("output")?.jsonArray
                        ?.mapNotNull { it.jsonPrimitive.content }
                        .orEmpty()
                    val cost = model["cost"]?.jsonObject
                    val reasoningOptions = parseReasoningOptions(model["reasoning_options"])
                    ModelMetadata(
                        id = model["id"]?.jsonPrimitive?.content ?: "",
                        providerId = providerId,
                        displayName = model["name"]?.jsonPrimitive?.content ?: model["id"]?.jsonPrimitive?.content.orEmpty(),
                        contextTokens = limit?.get("context")?.jsonPrimitive?.intOrNull ?: 0,
                        inputTokens = limit?.get("input")?.jsonPrimitive?.intOrNull,
                        outputTokens = limit?.get("output")?.jsonPrimitive?.intOrNull,
                        supportsTools = model["tool_call"]?.jsonPrimitive?.booleanOrNull == true,
                        supportsVision = "image" in inputModalities || "video" in inputModalities || "pdf" in inputModalities,
                        // Image output capability: the models.dev modalities.output annotation, or a Nano Banana family id suffix fallback
                        // (so gemini-*-image models can still be recognised when the built-in snapshot lacks the output modality).
                        supportsImageOutput = "image" in outputModalities ||
                            (model["id"]?.jsonPrimitive?.content.orEmpty().endsWith("-image")),
                        supportsReasoning = model["reasoning"]?.jsonPrimitive?.booleanOrNull == true,
                        supportsCustomTemperature = model["temperature"]?.jsonPrimitive?.booleanOrNull == true,
                        reasoningEffortOptions = reasoningOptions.takeIf { it.isNotEmpty() },
                        inputCostUsdPerM = cost?.get("input")?.jsonPrimitive?.doubleOrNull,
                        outputCostUsdPerM = cost?.get("output")?.jsonPrimitive?.doubleOrNull,
                        cacheReadCostUsdPerM = cost?.get("cache_read")?.jsonPrimitive?.doubleOrNull,
                        cacheWriteCostUsdPerM = cost?.get("cache_write")?.jsonPrimitive?.doubleOrNull,
                        source = ModelMetadata.Source.MODELS_DEV
                    )
                }
            }
            return Catalog(
                byProvider = byProvider,
                lowerByProvider = byProvider.mapValues { (_, models) ->
                    models.mapKeys { (id, _) -> id.lowercase() }
                }
            )
        }

        /**
         * Model id matching: each round the full name (exact → case-insensitive) takes priority over known-suffix candidates, then retries by stripping the vendor prefix level by level;
         * only after all candidates miss is the last hyphen suffix removed once; every lookup prefers the provider first, then falls back to the whole catalog.
         */
        internal fun findMetadata(
            catalog: Catalog,
            type: ProviderType,
            modelId: String
        ): ModelMetadata? {
            val preferred = preferredProviders(type)
            val normalized = if (modelId.startsWith(MODELS_PREFIX, ignoreCase = true)) {
                modelId.substring(MODELS_PREFIX.length)
            } else {
                modelId
            }
            val names = listOf(normalized) + vendorStrippedCandidates(normalized)
            for (name in names) {
                lookup(catalog.byProvider, preferred, listOf(name))?.let { return it }
                lookup(catalog.lowerByProvider, preferred, listOf(name.lowercase()))?.let { return it }
                lookup(catalog.byProvider, preferred, strippedCandidates(name).drop(1))?.let { return it }
                lookup(catalog.lowerByProvider, preferred, strippedCandidates(name.lowercase()).drop(1))?.let { return it }
            }
            for (name in names) {
                val lastDash = name.lastIndexOf('-')
                if (lastDash <= name.lastIndexOf('/') + 1) continue
                val candidate = name.substring(0, lastDash)
                lookup(catalog.byProvider, preferred, listOf(candidate))?.let { return it }
                lookup(catalog.lowerByProvider, preferred, listOf(candidate.lowercase()))?.let { return it }
            }
            return null
        }

        private fun preferredProviders(type: ProviderType): List<String> = when (type) {
            ProviderType.OPENAI -> listOf(
                "openai", "openrouter", "deepseek", "groq", "xai", "mistral",
                "togetherai", "alibaba", "moonshot", "github-copilot"
            )
            ProviderType.ANTHROPIC -> listOf("anthropic", "google-vertex-anthropic")
            ProviderType.GEMINI -> listOf("google", "google-vertex")
        }

        /** Look up in [catalog] (key casing already aligned by the caller) in candidate order: prefer the provider first, then the same-named key across the whole catalog. */
        private fun lookup(
            catalog: Map<String, Map<String, ModelMetadata>>,
            preferred: List<String>,
            candidates: List<String>
        ): ModelMetadata? {
            for (candidate in candidates) {
                for (provider in preferred) {
                    catalog[provider]?.get(candidate)?.let { return it }
                }
                catalog.values.firstNotNullOfOrNull { models -> models[candidate] }?.let { return it }
            }
            return null
        }

        /**
         * Candidates after stripping the vendor prefix level by level (`z-ai/glm-5.3-flash` → `glm-5.3-flash`,
         * `@cf/zai-org/glm-5.3-flash` → `zai-org/glm-5.3-flash` → `glm-5.3-flash`).
         */
        private fun vendorStrippedCandidates(modelId: String): List<String> {
            val candidates = mutableListOf<String>()
            var current = modelId
            while (true) {
                val slash = current.indexOf('/')
                if (slash < 0 || slash == current.lastIndex) break
                current = current.substring(slash + 1)
                candidates.add(current)
            }
            return candidates
        }

        /** Generate matching candidates: the original id first, then iteratively strip common suffixes (-thinking/-preview/-high/-low and the parenthesised forms); multiple layers can be stripped consecutively. */
        private fun strippedCandidates(modelId: String): List<String> {
            val candidates = mutableListOf(modelId)
            var current = modelId
            var changed: Boolean
            do {
                changed = false
                for (suffix in MODEL_SUFFIXES) {
                    if (current.length > suffix.length && current.endsWith(suffix)) {
                        current = current.dropLast(suffix.length)
                        candidates.add(current)
                        changed = true
                        break
                    }
                }
            } while (changed)
            return candidates
        }

        const val TAG = "ModelMetadataService"
        const val ASSET_FILE_NAME = "api.official.json"

        /** The separate data branch carrying the full models.dev snapshot (synced daily by CI; main keeps only manual commits). */
        const val MODELS_BRANCH = "data"

        /** Repository-relative path in the unified fetcher: the full models.dev snapshot maintained by this repository (synced daily by CI to [MODELS_BRANCH]). */
        const val MODELS_REPO_PATH = "data/models.json"
        const val DEFAULT_CONTEXT_TOKENS = 128_000
        const val DEFAULT_OUTPUT_TOKENS = 64_000

        /** Gemini-style model id prefix (`models/gemini-2.5-pro`), stripped before matching, case-insensitive. */
        private const val MODELS_PREFIX = "models/"

        /** Fallback fuzzy matching: model id suffixes to try stripping in order. */
        private val MODEL_SUFFIXES = listOf(
            "-thinking", "-preview", "-high", "-low",
            "(thinking)", "(xhigh)", "(high)", "(low)"
        )
    }
}