package com.aicode.feature.settings.data.local

import android.content.Context
import com.aicode.core.net.RepoDataFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Display preset for a built-in/dynamic provider: contains name/type/baseUrl plus recommendation and promotion extension properties. */
@Serializable
data class ProviderPreset(
    val id: String,
    val name: String,
    /** Protocol type: OPENAI / ANTHROPIC / GEMINI. */
    val type: String,
    /** Base URL, auto-filled when selected, can be changed on the edit page. */
    val baseUrl: String,
    /** When true, [baseUrl] is the complete request URL and is used as-is (no API path is appended). */
    val useFullUrl: Boolean = false,
    /** List of associated model ids (optional; when selected, imported as available models). */
    val models: List<String> = emptyList(),
    /** Whether recommended/pinned to the top */
    val isRecommended: Boolean = false,
    /** Badge/tag text (e.g. "Recommended", "Sponsored") */
    val badge: String? = null,
    /** Provider official website or promotional sign-up link */
    val websiteUrl: String? = null,
    /** Feature/benefit tag list (e.g. ["Sign up and get 10 yuan"]) */
    val tags: List<String> = emptyList()
)

/**
 * Library of official and custom provider presets:
 * It uses a dual-track mechanism:
 * 1. At startup it is pre-warmed asynchronously in the background ([refreshFromNetworkIfStale]), written to disk and updating the in-memory [cached];
 * 2. Every UI read ([loadOfficial]) synchronously reads from "memory -> downloaded file on disk -> built-in assets",
 *    never initiates a network request, and returns directly within milliseconds.
 */
object ProviderPresetLibrary {
    const val PROVIDERS_ASSET_FILE_NAME = "providers.json"
    const val REMOTE_PROVIDERS_PATH = "data/providers.json"

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var cached: List<ProviderPreset>? = null

    @Volatile
    private var refreshAttemptedThisProcess = false

    /**
     * For use only during the App startup phase (AIEditorApp), called asynchronously in a background coroutine:
     * it goes through RepoDataFetcher to probe/fetch the latest configuration and persist it, failing completely silently.
     */
    suspend fun refreshFromNetworkIfStale(context: Context) = withContext(Dispatchers.IO) {
        if (refreshAttemptedThisProcess) return@withContext
        refreshAttemptedThisProcess = true

        val fetcher = RepoDataFetcher(context)
        val remoteResult = runCatching { fetcher.fetch(REMOTE_PROVIDERS_PATH) }.getOrNull()
        val remoteContent = when (remoteResult) {
            is RepoDataFetcher.FetchResult.Success -> remoteResult.content
            is RepoDataFetcher.FetchResult.FallbackDiskCache -> remoteResult.content
            else -> null
        }

        if (!remoteContent.isNullOrBlank()) {
            val parsedRemote = runCatching {
                json.decodeFromString<List<ProviderPreset>>(remoteContent)
            }.getOrNull()
            if (!parsedRemote.isNullOrEmpty()) {
                val sorted = parsedRemote.sortedWith(
                    compareByDescending<ProviderPreset> { it.isRecommended }
                        .thenBy { it.name.lowercase() }
                )
                cached = sorted
            }
        }
    }

    /**
     * Fast synchronous read dedicated to the UI:
     * Order: in-memory cache -> downloaded file on disk -> built-in assets file.
     * Purely local IO, never sends any network request, and returns within seconds.
     */
    fun loadOfficial(context: Context): List<ProviderPreset> {
        cached?.let { return it }

        // 1. Try to read the local disk cache file directly (written by RepoDataFetcher)
        val diskContent = RepoDataFetcher(context).readLocalCache(REMOTE_PROVIDERS_PATH)
        if (!diskContent.isNullOrBlank()) {
            val parsed = runCatching {
                json.decodeFromString<List<ProviderPreset>>(diskContent)
            }.getOrNull()
            if (!parsed.isNullOrEmpty()) {
                val sorted = parsed.sortedWith(
                    compareByDescending<ProviderPreset> { it.isRecommended }
                        .thenBy { it.name.lowercase() }
                )
                cached = sorted
                return sorted
            }
        }

        // 2. Fall back to reading the built-in assets (guaranteed to exist, guaranteed to return instantly)
        val fallback = loadFromAssets(context)
        cached = fallback
        return fallback
    }

    private fun loadFromAssets(context: Context): List<ProviderPreset> = runCatching {
        val text = context.assets.open(PROVIDERS_ASSET_FILE_NAME).bufferedReader().use { it.readText() }
        val list = json.decodeFromString<List<ProviderPreset>>(text)
        list.sortedWith(
            compareByDescending<ProviderPreset> { it.isRecommended }
                .thenBy { it.name.lowercase() }
        )
    }.getOrDefault(emptyList())
}
