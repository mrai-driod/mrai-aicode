package com.aicode.core.net

import android.content.Context
import com.aicode.feature.settings.data.repository.DEFAULT_NO_PROXY
import com.aicode.feature.settings.data.repository.ProviderProxyEntry
import com.aicode.feature.settings.data.repository.ProviderProxyRegistry
import com.aicode.feature.settings.data.repository.ProxyConfig
import com.aicode.feature.settings.data.repository.ProxySettingsRepository
import com.aicode.feature.settings.domain.model.ProxyType
import okhttp3.Authenticator
import okhttp3.Credentials
import okhttp3.Route
import java.io.IOException
import java.net.Authenticator.RequestorType
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.net.URLEncoder

/**
 * Global HTTP proxy entry point (stateless singleton, reads the latest SharedPreferences value each time, so changing settings takes effect for new connections).
 *
 * Integration:
 * - [applyGlobal] is called in Application.attachBaseContext (before any Hilt injection): it sets the dynamic
 *   [selector] as the JVM global default. OkHttpClient captures that reference when built, and HttpURLConnection queries the global selector
 *   every time it opens a connection; both call select() live to read the latest config, with no need to rebuild clients.
 * - OkHttp proxy authentication goes through [okHttpAuthenticator] (which must be explicitly attached when building the OkHttpClient, as OkHttp
 *   does not respond to 407 challenges by default); HttpURLConnection authentication goes through the global java.net.Authenticator
 *   (also set by [applyGlobal]).
 * - Commands inside the container (curl/git/npm/pip, etc.) get HTTP_PROXY/HTTPS_PROXY/ALL_PROXY injected via [proxyEnv]
 *   environment variables, which LinuxContainerEngine overlays into the container process environment.
 */
object AppProxy {

    @Volatile
    private var appContext: Context? = null

    /** Provider-level proxy registry (registered after DI injection): dispatches provider-specific proxies by target host. */
    @Volatile
    private var providerRegistry: ProviderProxyRegistry? = null

    /** Called in Application.attachBaseContext (before the first OkHttpClient build). */
    fun applyGlobal(context: Context) {
        // At the attachBaseContext stage getApplicationContext() may be null (the Application is not yet fully
        // bound), so the passed-in base is used directly (the Application itself is a valid Context) -- otherwise subsequent
        // config() cannot read the proxy settings and all requests silently connect directly (appearing as the proxy working inside the container but not on the app side).
        appContext = context.applicationContext ?: context
        ProxySelector.setDefault(selector)
        java.net.Authenticator.setDefault(httpUrlConnectionAuthenticator)
    }

    /** Registered by the DI layer after startup wiring (AIEditorApp.onCreate); after that the selector dispatches provider proxies by host. */
    fun registerProviderProxyRegistry(registry: ProviderProxyRegistry) {
        providerRegistry = registry
    }

    private fun config(): ProxyConfig {
        val ctx = appContext ?: return ProxyConfig()
        return ProxySettingsRepository.readConfig(ctx)
    }

    /** The proxy selected by one select call (provider-level takes precedence over global) and its credentials. */
    private data class SelectedProxy(
        val type: ProxyType, val host: String, val port: Int,
        val username: String, val password: String
    )

    /** Dispatches by target host+path: provider-level proxy first (the most specific path prefix wins), otherwise the global proxy; returns null when unavailable. */
    private fun selectProxy(host: String, path: String?): SelectedProxy? {
        providerRegistry?.findByUrl(host, path)?.takeIf { it.valid }?.let { e ->
            return SelectedProxy(e.type, e.host, e.port, e.username, e.password)
        }
        val cfg = config()
        if (!cfg.enabled || !cfg.valid) return null
        return SelectedProxy(cfg.type, cfg.host, cfg.port, cfg.username, cfg.password)
    }

    private fun proxyOf(sel: SelectedProxy): Proxy {
        val type = when (sel.type) {
            ProxyType.HTTP -> Proxy.Type.HTTP
            ProxyType.SOCKS5 -> Proxy.Type.SOCKS
        }
        return Proxy(type, InetSocketAddress(sel.host, sel.port))
    }

    /**
     * Dynamic ProxySelector: each select() reads the latest config.
     * Dispatches by target host: if a provider-level proxy matches, use the provider config, otherwise the global config;
     * returns a direct connection when not enabled / config incomplete / NO_PROXY matches.
     */
    val selector: ProxySelector = object : ProxySelector() {
        override fun select(uri: URI): List<Proxy> {
            val cfg = config()
            val host = uri.host ?: return listOf(Proxy.NO_PROXY)
            if (isNoProxy(host, uri.port, cfg.noProxy)) return listOf(Proxy.NO_PROXY)
            val sel = selectProxy(host, uri.path) ?: return listOf(Proxy.NO_PROXY)
            return listOf(proxyOf(sel))
        }

        override fun connectFailed(uri: URI, sa: SocketAddress, ioe: IOException) = Unit
    }

    /** For attaching when building an OkHttpClient: responds with Basic authentication when the proxy returns 407, dispatching credentials by target host (provider first). */
    val okHttpAuthenticator: Authenticator = object : Authenticator {
        override fun authenticate(route: Route?, response: okhttp3.Response): okhttp3.Request? {
            if (response.code != 407) return null
            val url = response.request.url
            providerRegistry?.findByUrl(url.host, url.encodedPath)?.takeIf { it.username.isNotBlank() }?.let { e ->
                return response.request.newBuilder()
                    .header("Proxy-Authorization", Credentials.basic(e.username, e.password))
                    .build()
            }
            val cfg = config()
            if (!cfg.enabled || cfg.username.isBlank()) return null
            return response.request.newBuilder()
                .header("Proxy-Authorization", Credentials.basic(cfg.username, cfg.password))
                .build()
        }
    }

    /** Global java.net.Authenticator: all java.net-level proxy authentication goes through here (HttpURLConnection's 407,
     *  and all SOCKS5 handshakes via `Socket(proxy)` -- including OkHttp's SOCKS connections).
     *
     *  Android's SocksSocketImpl (unlike OpenJDK) does not read the java.net.socks.username/password
     *  system properties; SOCKS5 username/password authentication only goes through this callback, and it calls the six-argument requestPasswordAuthentication overload without RequestorType
     *  (getRequestorType() is null), so
     *  the SOCKS5 branch must not check requestorType, nor rely on a global switch (it must be testable even when the testProxy switch is off).
     *  At this point [getRequestingHost] gives "the proxy server address currently being connected to", and matching by host against it
     *  returns the corresponding credentials from the provider/global config; when null is returned Android falls back to user.name + an empty password,
     *  which will inevitably fail against proxies that require authentication.
     *  HTTP: only responds with the global config (HttpURLConnection has no callers left in the project; kept defensively).
     */
    private val httpUrlConnectionAuthenticator: java.net.Authenticator =
        object : java.net.Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication? {
                val protocol = getRequestingProtocol()?.lowercase()
                if (protocol == "socks5") {
                    val proxyHost = getRequestingHost()
                        ?: getRequestingSite()?.hostAddress
                        ?: return null
                    providerRegistry?.findByProxyHost(proxyHost)?.let { e ->
                        if (e.username.isNotBlank()) {
                            return PasswordAuthentication(e.username, e.password.toCharArray())
                        }
                    }
                    val cfg = config()
                    if (cfg.type == ProxyType.SOCKS5 && cfg.username.isNotBlank() &&
                        cfg.host.equals(proxyHost, ignoreCase = true)
                    ) {
                        return PasswordAuthentication(cfg.username, cfg.password.toCharArray())
                    }
                    return null
                }
                if (requestorType != RequestorType.PROXY) return null
                if (protocol != "http" && protocol != "https") return null
                val cfg = config()
                if (!cfg.enabled || cfg.username.isBlank()) return null
                return PasswordAuthentication(cfg.username, cfg.password.toCharArray())
            }
        }

    /** Proxy environment variables for processes inside the container; returns an empty map (direct connection) when not enabled or config is incomplete. */
    fun proxyEnv(context: Context): Map<String, String> {
        val cfg = ProxySettingsRepository.readConfig(context)
        if (!cfg.enabled || !cfg.valid) return emptyMap()
        val noProxy = cfg.noProxy.ifBlank { DEFAULT_NO_PROXY }
        return when (cfg.type) {
            ProxyType.HTTP -> {
                val proxyUrl = "http://${authority(cfg)}"
                mapOf(
                    "HTTP_PROXY" to proxyUrl,
                    "HTTPS_PROXY" to proxyUrl,
                    "ALL_PROXY" to proxyUrl,
                    "NO_PROXY" to noProxy
                )
            }
            ProxyType.SOCKS5 -> {
                // socks5h: DNS resolution also goes through the proxy (prevents DNS leaks). Only sets ALL_PROXY and not
                // HTTP_PROXY/HTTPS_PROXY -- otherwise curl would preferentially use the http_proxy direct-style proxy protocol for http/https targets
                // to connect to the SOCKS port and fail.
                val proxyUrl = "socks5h://${authority(cfg)}"
                mapOf(
                    "ALL_PROXY" to proxyUrl,
                    "NO_PROXY" to noProxy
                )
            }
        }
    }

    /** The host:port part of the proxy URL; when a username is present, appends userinfo (percent-encoded). */
    private fun authority(cfg: ProxyConfig): String =
        if (cfg.username.isBlank()) {
            "${cfg.host}:${cfg.port}"
        } else {
            "${encodeUserInfo(cfg.username)}:${encodeUserInfo(cfg.password)}@${cfg.host}:${cfg.port}"
        }

    /** NO_PROXY matching: comma-separated, with semantics matching curl (container side) -- `*` means all direct;
     *  `example.com` / `.example.com` match the host itself and subdomains; `*.example.com` matches subdomains only;
     *  `host:port` requires the port to match; IPv4/IPv6 CIDR ranges (such as 10.0.0.0/8, fd00::/8) match by prefix;
     *  case-insensitive; when the config is empty it falls back to the default (consistent with container injection). */
    private fun isNoProxy(host: String, port: Int, noProxy: String): Boolean {
        val list = noProxy.ifBlank { DEFAULT_NO_PROXY }
        val h = host.lowercase()
        return list.split(",").any { e ->
            val entry = e.trim().lowercase()
            when {
                entry.isEmpty() -> false
                entry == "*" -> true
                else -> {
                    val (hostPart, entryPort) = splitEntryHostPort(entry)
                    (entryPort == null || port == entryPort) && when {
                        hostPart.contains('/') -> cidrMatch(h, hostPart)
                        hostPart.startsWith("*.") -> h.endsWith("." + hostPart.removePrefix("*."))
                        else -> {
                            val hp = hostPart.removePrefix(".")
                            h == hp || h.endsWith(".$hp")
                        }
                    }
                }
            }
        }
    }

    /** Splits an entry's host and optional port: `[::1]:1080`, `example.com:8080` split off the port;
     *  a pure IPv6 literal (more than one colon) is treated as having no port, avoiding mistaking `::1` for host+port. */
    private fun splitEntryHostPort(entry: String): Pair<String, Int?> {
        if (entry.startsWith("[")) {
            val close = entry.indexOf(']')
            if (close > 0) {
                val port = entry.substring(close + 1).removePrefix(":").toIntOrNull()
                return entry.substring(1, close) to port
            }
        }
        if (entry.count { it == ':' } > 1) return entry to null
        val colon = entry.indexOf(':')
        if (colon >= 0) {
            val p = entry.substring(colon + 1).toIntOrNull()
            if (p != null) return entry.substring(0, colon) to p
        }
        return entry to null
    }

    /** CIDR prefix matching. Only accepts IP literals (no DNS resolution -- select() is on the network path);
     *  returns false if either side is invalid or v4/v6 are mixed. */
    private fun cidrMatch(host: String, cidr: String): Boolean {
        val slash = cidr.indexOf('/')
        if (slash <= 0) return false
        val prefix = cidr.substring(slash + 1).toIntOrNull() ?: return false
        val hostBytes = parseLiteralIp(host) ?: return false
        val netBytes = parseLiteralIp(cidr.substring(0, slash)) ?: return false
        if (hostBytes.size != netBytes.size || prefix !in 0..hostBytes.size * 8) return false
        var i = prefix / 8
        while (i > 0) {
            i--
            if (hostBytes[i] != netBytes[i]) return false
        }
        val rem = prefix % 8
        if (rem == 0) return true
        val mask = (0xFF shl (8 - rem)) and 0xFF
        return (hostBytes[prefix / 8].toInt() and mask) == (netBytes[prefix / 8].toInt() and mask)
    }

    private fun parseLiteralIp(s: String): ByteArray? = try {
        val isLiteral = if (s.contains(':')) {
            s.none { !it.isDigit() && it !in "abcdefABCDEF:." }
        } else {
            val parts = s.split(".")
            parts.size == 4 && parts.all { it.isNotEmpty() && it.length <= 3 && it.all(Char::isDigit) }
        }
        if (isLiteral) java.net.InetAddress.getByName(s)?.address else null
    } catch (e: Exception) {
        null
    }

    /** Percent-encodes userinfo (the subset allowed by RFC 3986 userinfo), avoiding passwords containing @ : and similar characters breaking URL parsing. */
    private fun encodeUserInfo(value: String): String {
        val sb = StringBuilder()
        for (ch in value) {
            if (ch.isLetterOrDigit() || ch in "-._~!$&'()*+,;=") {
                sb.append(ch)
            } else {
                sb.append(URLEncoder.encode(ch.toString(), "UTF-8"))
            }
        }
        return sb.toString()
    }

    // ── Proxy connectivity test ───────────────────────────────────────────

    /** Proxy test result. */
    data class ProxyTestResult(val ok: Boolean, val message: String)

    /** Probe URL: fixed to the connectivity endpoint on the Google search domain (returns 204, directly reachable in China),
     *  to test whether the proxy actually forwards traffic. No third-party IP lookup service is used: avoids sending privacy data out (the exit IP
     *  does not leave this device), and a 204 response does not depend on a probe service's text format. */
    private const val PROBE_URL = "https://www.google.com/generate_204"
    private const val TEST_TIMEOUT_SECONDS = 8L

    /**
     * Sends one probe request with the given config (unaffected by the global switch, so current form values can be tested directly).
     * [probeUrl] is the test target URL; if blank it falls back to the default;
     * HTTP proxy authentication uses 407 Basic; SOCKS5 authentication is dispatched by host via the global Authenticator (see above).
     */
    suspend fun testProxy(context: Context, cfg: ProxyConfig, probeUrl: String = PROBE_URL): ProxyTestResult =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            if (cfg.host.isBlank() || cfg.port !in 1..65535) {
                return@withContext ProxyTestResult(false, context.getString(com.aicode.R.string.proxy_test_invalid_config))
            }
            probe(context, SelectedProxy(cfg.type, cfg.host, cfg.port, cfg.username, cfg.password), probeUrl)
        }

    private suspend fun probe(context: Context, sel: SelectedProxy, probeUrl: String): ProxyTestResult {
        val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(TEST_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(TEST_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(TEST_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
            .proxy(proxyOf(sel))
            .proxyAuthenticator(okHttpAuthenticator)
            .build()
        return try {
            client.newCall(okhttp3.Request.Builder().url(probeUrl.ifBlank { PROBE_URL }).build()).execute().use { resp ->
                val body = resp.body?.string().orEmpty().trim()
                if (resp.isSuccessful) {
                    ProxyTestResult(true, context.getString(com.aicode.R.string.proxy_test_ok, resp.code))
                } else {
                    ProxyTestResult(false, context.getString(com.aicode.R.string.proxy_test_http_error, resp.code, body.take(120)))
                }
            }
        } catch (e: Exception) {
            ProxyTestResult(false, context.getString(com.aicode.R.string.proxy_test_connect_failed, e.message ?: ""))
        }
    }
}
