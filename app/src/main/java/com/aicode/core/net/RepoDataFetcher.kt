package com.aicode.core.net

import android.content.Context
import com.aicode.core.util.FileLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Generic component for fetching remote repository data:
 * Fetches JSON/text static data specifically from this repository (or a branch/Tag) (such as provider presets, model lists, etc.).
 *
 * Node scheduling and high-availability strategy:
 * 1. Prefer public acceleration nodes such as the Fastly CDN / jsDelivr (fast direct access in China, no proxy needed);
 * 2. Degrade in order to other CDN mirrors;
 * 3. Finally fall back to a direct connection to the GitHub raw origin;
 * 4. Built-in persistent local disk cache (TTL) and lightweight ETag / If-None-Match conditional requests (avoids mindless repeat downloads);
 * 5. When the network is completely down, gracefully fall back to existing local disk cache or bundled assets.
 */
class RepoDataFetcher(
    private val context: Context,
    private val owner: String = DEFAULT_OWNER,
    private val repo: String = DEFAULT_REPO,
    private val branch: String = DEFAULT_BRANCH,
    private val defaultMaxAgeMs: Long = DEFAULT_CACHE_MAX_AGE_MS,
    private val client: OkHttpClient = DEFAULT_CLIENT
) {

    /**
     * Fetch result for a single data file
     */
    sealed class FetchResult {
        /** Fetched successfully from the network, or a 304 hit a valid cache */
        data class Success(val content: String, val fromCache: Boolean) : FetchResult()
        /** Network request failed but the local disk cache was read successfully */
        data class FallbackDiskCache(val content: String, val error: Throwable) : FetchResult()
        /** Complete failure (no local cache available) */
        data class Failure(val error: Throwable) : FetchResult()
    }

    /**
     * Fetches data: if the local cache has not expired it is used directly; if expired, nodes are probed and fetched in priority order, supporting 304 and disk fallback.
     *
     * @param pathInRepo Path relative to the repository, such as "data/providers.json"
     * @param maxAgeMs Cache lifetime (milliseconds); if not passed it uses [defaultMaxAgeMs] set by the constructor; passing 0L skips the local freshness check and forces a network reconciliation
     */
    suspend fun fetch(
        pathInRepo: String,
        maxAgeMs: Long = defaultMaxAgeMs
    ): FetchResult = withContext(Dispatchers.IO) {
        val cleanPath = pathInRepo.trim().removePrefix("/")
        val cacheFile = getCacheFile(cleanPath)
        val etagFile = getEtagFile(cleanPath)

        val now = System.currentTimeMillis()
        val cachedContent = if (cacheFile.isFile) runCatching { cacheFile.readText(Charsets.UTF_8) }.getOrNull() else null
        val isCacheFresh = cachedContent != null && (now - cacheFile.lastModified() < maxAgeMs)

        // 1. Local cache is still fresh, return it directly
        if (isCacheFresh) {
            return@withContext FetchResult.Success(cachedContent!!, fromCache = true)
        }

        val cachedEtag = if (etagFile.isFile) runCatching { etagFile.readText(Charsets.UTF_8).trim() }.getOrNull() else null
        val candidateUrls = buildCandidateUrls(cleanPath)

        var lastError: Throwable? = null

        // 2. Iterate the candidate nodes in order
        for (url in candidateUrls) {
            try {
                val reqBuilder = Request.Builder()
                    .url(url)
                    .header("User-Agent", "aicode-android")
                    .get()

                if (!cachedEtag.isNullOrBlank()) {
                    reqBuilder.header("If-None-Match", cachedEtag)
                }

                client.newCall(reqBuilder.build()).execute().use { response ->
                    when {
                        // 304 not modified: the original cache is still valid, update the file modification time and return directly
                        response.code == 304 && cachedContent != null -> {
                            cacheFile.setLastModified(now)
                            return@withContext FetchResult.Success(cachedContent, fromCache = true)
                        }

                        response.isSuccessful -> {
                            val body = response.body?.string().orEmpty()
                            if (body.isNotBlank()) {
                                // Write to the disk cache
                                runCatching {
                                    cacheFile.parentFile?.mkdirs()
                                    cacheFile.writeText(body, Charsets.UTF_8)
                                    val newEtag = response.header("ETag")
                                    if (!newEtag.isNullOrBlank()) {
                                        etagFile.writeText(newEtag.trim(), Charsets.UTF_8)
                                    } else {
                                        etagFile.delete()
                                    }
                                }
                                return@withContext FetchResult.Success(body, fromCache = false)
                            }
                        }

                        else -> {
                            val errCode = response.code
                            FileLogger.d(TAG, "Node request failed url=$url code=$errCode")
                        }
                    }
                }
            } catch (e: Throwable) {
                lastError = e
                FileLogger.d(TAG, "Node connection error url=$url: ${e.message}")
            }
        }

        // 3. When all nodes are unavailable: if there is an old cache on disk, degrade to using the old cache
        if (cachedContent != null) {
            FileLogger.w(TAG, "All network nodes failed to fetch, degrading to old disk cache: $cleanPath", lastError)
            return@withContext FetchResult.FallbackDiskCache(
                cachedContent,
                lastError ?: IllegalStateException("All remote endpoints failed")
            )
        }

        // 4. Complete failure
        FetchResult.Failure(lastError ?: IllegalStateException("Failed to fetch $cleanPath from all sources"))
    }

    /**
     * Builds the candidate node URL list:
     * 1. jsDelivr (Fastly CDN accelerated)
     * 2. jsDelivr (general CDN)
     * 3. raw.githubusercontent.com (official GitHub direct fallback)
     */
    fun buildCandidateUrls(path: String): List<String> = listOf(
        // Fastly-accelerated jsDelivr node
        "https://fastly.jsdelivr.net/gh/$owner/$repo@$branch/$path",
        // General CDN mirror
        "https://cdn.jsdelivr.net/gh/$owner/$repo@$branch/$path",
        // jsDelivr GCore node
        "https://gcore.jsdelivr.net/gh/$owner/$repo@$branch/$path",
        // GitHub origin fallback
        "https://raw.githubusercontent.com/$owner/$repo/$branch/$path"
    )

    /**
     * Synchronously reads the disk cache (no network): returns the text if present, otherwise null.
     * For "pure read-only paths" to reuse the unified cache directory, avoiding callers hardcoding cache paths.
     */
    fun readLocalCache(pathInRepo: String): String? {
        val file = getCacheFile(pathInRepo.trim().removePrefix("/"))
        return if (file.isFile) runCatching { file.readText(Charsets.UTF_8) }.getOrNull() else null
    }

    fun clearCache(pathInRepo: String? = null) {
        val dir = File(context.filesDir, CACHE_DIR_NAME)
        if (!dir.exists()) return
        if (pathInRepo == null) {
            dir.deleteRecursively()
        } else {
            val cleanPath = pathInRepo.trim().removePrefix("/")
            getCacheFile(cleanPath).delete()
            getEtagFile(cleanPath).delete()
        }
    }

    private fun getCacheFile(path: String): File {
        val safeName = path.replace('/', '_')
        return File(File(context.filesDir, CACHE_DIR_NAME), safeName)
    }

    private fun getEtagFile(path: String): File {
        val safeName = path.replace('/', '_') + ".etag"
        return File(File(context.filesDir, CACHE_DIR_NAME), safeName)
    }

    companion object {
        private const val TAG = "RepoDataFetcher"
        private const val CACHE_DIR_NAME = "repo_data_cache"

        const val DEFAULT_OWNER = "mrai-driod"
        const val DEFAULT_REPO = "mrai-aicode"
        const val DEFAULT_BRANCH = "main"

        /** Default cache 12 hours */
        const val DEFAULT_CACHE_MAX_AGE_MS = 12 * 60 * 60 * 1000L

        val DEFAULT_CLIENT: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .proxyAuthenticator(AppProxy.okHttpAuthenticator)
                .build()
        }
    }
}
