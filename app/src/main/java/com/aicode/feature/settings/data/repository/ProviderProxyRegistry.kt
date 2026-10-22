package com.aicode.feature.settings.data.repository

import com.aicode.core.util.FileLogger
import com.aicode.feature.settings.domain.model.AIProviderConfig
import com.aicode.feature.settings.domain.model.ProxyType
import com.aicode.feature.settings.domain.repository.AIProviderRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Provider-level proxy entry: obtained from [ProviderProxyRegistry] by looking up the target URL. */
data class ProviderProxyEntry(
    val providerId: String,
    val type: ProxyType,
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
    /** The path prefix of baseUrl (normalised: trailing slash removed; the root path is an empty string, matching all requests to that host). */
    val basePath: String = ""
) {
    val valid: Boolean get() = host.isNotBlank() && port in 1..65535
}

/**
 * Provider-level proxy registry: indexes providers that have a dedicated proxy configured, by the host + path prefix of their baseUrl.
 *
 * Each time AppProxy's global dynamic ProxySelector calls select(uri), it first looks up this table by the target host+path —
 * on a hit it uses that provider's proxy, otherwise it falls back to the global proxy. Providers on the same host with different paths (different URLs)
 * can each be dispatched precisely; only entries with exactly the same host+path cannot be distinguished (the first configured one is kept, and a warning is logged).
 */
@Singleton
class ProviderProxyRegistry @Inject constructor(
    aiProviderRepository: AIProviderRepository
) {
    // Application-level scope: the singleton's lifetime matches the App process (it dies when the process exits), so no external cancellation is needed.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** host (lowercase) → list of proxy entries for that host; an empty map when nothing is configured. */
    @Volatile
    private var byHost: Map<String, List<ProviderProxyEntry>> = emptyMap()

    init {
        scope.launch {
            aiProviderRepository.getAllProviders().collectLatest { providers ->
                val entries = providers
                    .filter { it.proxyEnabled && it.proxyHost.isNotBlank() && it.proxyPort in 1..65535 }
                    .mapNotNull { entry ->
                        providerHostAndPath(entry.baseUrl)?.let { (host, path) -> host to entry.toEntry(path) }
                    }
                // Multiple providers with exactly the same host+path cannot be distinguished at the proxy layer: keep the first configured one and log it.
                entries.groupBy { it.first }.filter { it.value.size > 1 }.forEach { (key, pairs) ->
                    FileLogger.w(
                        TAG,
                        "Multiple providers share $key; proxy configuration conflict, only the first configured one takes effect: ${pairs.joinToString { p -> p.second.providerId }}"
                    )
                }
                byHost = entries.groupBy({ it.first }, { it.second })
                FileLogger.d(TAG, "Provider proxy table refreshed: ${byHost.size} hosts, ${entries.size} entries")
            }
        }
    }

    /**
     * Looks up by the host + path prefix of the target URL. Path matching rules: the entry's basePath is empty (the root path,
     * matching all requests to that host), the request path equals basePath, or the request path starts with basePath + "/".
     * When multiple entries match, the one with the longest basePath wins (most specific first); among duplicate entries of the same length, the first configured one wins.
     */
    fun findByUrl(host: String, path: String?): ProviderProxyEntry? {
        val candidates = byHost[host.lowercase()] ?: return null
        val reqPath = path?.takeIf { it.isNotBlank() } ?: "/"
        val matched = candidates.filter { e ->
            val base = e.basePath
            base.isEmpty() || reqPath == base || reqPath.startsWith("$base/")
        }
        if (matched.size <= 1) return matched.firstOrNull()
        return matched.maxByOrNull { it.basePath.length }
    }

    /** Looks up by proxy server address (rather than target host): during SOCKS5 handshake authentication, credentials are matched against the proxy used for the connection. */
    fun findByProxyHost(proxyHost: String): ProviderProxyEntry? =
        byHost.values.flatten().firstOrNull { it.host.equals(proxyHost, ignoreCase = true) && it.username.isNotBlank() }

    private fun AIProviderConfig.toEntry(basePath: String) = ProviderProxyEntry(
        providerId = id,
        type = proxyType,
        host = proxyHost,
        port = proxyPort,
        username = proxyUsername,
        password = proxyPassword,
        basePath = basePath
    )

    private fun providerHostAndPath(baseUrl: String): Pair<String, String>? =
        runCatching {
            val uri = java.net.URI(baseUrl.trim())
            val host = uri.host?.lowercase()?.takeIf { it.isNotBlank() } ?: return@runCatching null
            val path = uri.path?.trimEnd('/') ?: ""
            host to path
        }.getOrNull()

    private companion object {
        const val TAG = "ProviderProxyRegistry"
    }
}
