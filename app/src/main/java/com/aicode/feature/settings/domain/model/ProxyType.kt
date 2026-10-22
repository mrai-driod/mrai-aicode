package com.aicode.feature.settings.domain.model

/**
 * Proxy protocol type: HTTP (CONNECT tunnel, can proxy all http/https traffic) or SOCKS5 (TCP-level forwarding).
 * Placed in the domain layer: referenced by both the global proxy (ProxySettingsRepository) and provider-level proxies (AIProviderConfig).
 */
enum class ProxyType {
    HTTP, SOCKS5
}
