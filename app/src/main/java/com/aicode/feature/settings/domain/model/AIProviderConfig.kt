package com.aicode.feature.settings.domain.model

data class AIProviderConfig(
    val id: String,
    val name: String,
    val type: ProviderType,
    val apiKey: String,
    /** Multi-key mode switch: when enabled, keys are taken in rotation from [apiKeys]; when disabled, only [apiKey] is used. */
    val multiKeyEnabled: Boolean = false,
    /** Candidate keys in multi-key mode (prioritised in list order, each entry carries its own scheduling weight). */
    val apiKeys: List<ProviderKey> = emptyList(),
    /** Multi-key selection strategy: sequential (switch only on failure) or round-robin (new sessions start in turn). */
    val keyRotationStrategy: KeyRotationStrategy = KeyRotationStrategy.SEQUENTIAL,
    /** Deprecated: the early "switch only after consecutive failures reach a threshold" threshold. Now hitting [keySwitchStatusCodes] switches immediately; kept only for compatibility with the old database column. */
    val keyFailoverThreshold: Int = 2,
    /** How many minutes a switched-away key cools down before it is included as a candidate again; 0 means no cooldown. */
    val keyCooldownMinutes: Int = 5,
    /** HTTP status codes that trigger automatic multi-key switching when hit; falls back to [DEFAULT_KEY_SWITCH_STATUS_CODES] when empty. */
    val keySwitchStatusCodes: List<Int> = emptyList(),
    val baseUrl: String,
    val defaultModel: String,
    /** List of usable models added for this provider (fetched or added manually). */
    val models: List<String> = emptyList(),
    /** The currently selected model in use; falls back to defaultModel when empty. */
    val selectedModel: String = defaultModel,
    val isEnabled: Boolean = true,
    val useFullUrl: Boolean = false,
    val useResponseApi: Boolean = false,
    /** Anthropic explicit cache breakpoints (cache_control). Only takes effect for the ANTHROPIC type, enabled by default. */
    val anthropicCacheBreakpoints: Boolean = true,
    /** Send prompt_cache_key on the Chat Completion path (shard routing). Only takes effect for the OPENAI type, disabled by default. */
    val openaiChatCacheKey: Boolean = false,
    /** Custom dashboard script path (located in ~/.aicode/scripts/, or an absolute path / custom command). */
    val dashboardScriptPath: String = "",
    /** Custom dashboard auto-refresh interval (minutes); 0 means refresh only on entry / manually; supports 1, 3, 5, 10, etc. Defaults to 5 minutes. */
    val dashboardRefreshInterval: Int = 5,
    /**
     * Custom request headers (header name -> value), fully overriding the same-named default headers on all requests for this provider.
     * Values support the placeholders `{{SESSION_ID}}` (session id) and `{{API_KEY}}` (the key actually used for this request),
     * which the provider adapter replaces before sending the request.
     */
    val customHeaders: Map<String, String> = emptyMap(),
    /** Provider list sort order; smaller comes first; -1 means unassigned (on save, takes max+1 and goes to the end). */
    val sortOrder: Int = -1,
    /** Configure a proxy separately for this provider (when disabled, follows the global proxy settings). */
    val proxyEnabled: Boolean = false,
    val proxyType: ProxyType = ProxyType.HTTP,
    val proxyHost: String = "",
    val proxyPort: Int = 0,
    val proxyUsername: String = "",
    val proxyPassword: String = "",
    /**
     * Custom dashboard (DIY) script parameters (key-value).
     * Injected as extra environment variables `AICODE_KEY_<KEY>` when the dashboard script runs,
     * values may reference provider configuration placeholders, such as `{{PROVIDER_API_KEY}}`, `{{BASE_URL}}`.
     */
    val scriptParams: Map<String, String> = emptyMap()
) {
    /** The model actually in effect: selectedModel first, then defaultModel. */
    val effectiveModel: String
        get() = selectedModel.ifBlank { defaultModel }

    /**
     * The list of keys actually usable. Uses [apiKeys] when multi-key mode is enabled and the list is non-empty, otherwise falls back to the single [apiKey],
     * so both "switch off" and "on but not filled in" land on the existing single-key behaviour.
     */
    val effectiveApiKeys: List<ProviderKey>
        get() = if (multiKeyEnabled && apiKeys.any { it.value.isNotBlank() }) {
            apiKeys.filter { it.value.isNotBlank() }
        } else {
            listOf(ProviderKey(apiKey)).filter { it.value.isNotBlank() }
        }

    /** Whether at least one usable key has been configured. */
    val hasUsableApiKey: Boolean get() = effectiveApiKeys.isNotEmpty()

    /** The switching status codes actually in effect: falls back to the default set when empty. */
    val effectiveKeySwitchStatusCodes: Set<Int>
        get() = keySwitchStatusCodes.filter { it in 100..599 }.toSet()
            .ifEmpty { DEFAULT_KEY_SWITCH_STATUS_CODES }

    /** Requests with no session context, such as fetching the model list / connectivity tests, use the first usable key. */
    val firstUsableApiKey: String get() = effectiveApiKeys.firstOrNull()?.value ?: ""
}

/**
 * One candidate key in multi-key mode: value + scheduling weight.
 *
 * [weight] participates in round-robin scheduling: the larger it is, the more new sessions it is assigned; ≤ 0 means this key does not participate in scheduling for now (kept but not selected).
 */
data class ProviderKey(
    val value: String,
    val weight: Int = DEFAULT_KEY_WEIGHT
)

/** Scheduling weight default value and maximum. */
const val DEFAULT_KEY_WEIGHT: Int = 1
const val MAX_KEY_WEIGHT: Int = 1_000_000

/** Fields that must never contain whitespace (API key / URL / proxy host): strip whitespace in the middle as well. */
private fun String.stripAllWhitespace(): String = filterNot { it.isWhitespace() }

/** Fields that allow internal spaces (name / model name / UA / path / proxy account): remove line breaks and tabs, then trim both ends. */
private fun String.stripLineBreaks(): String =
    filterNot { it == '\n' || it == '\r' || it == '\t' }.trim()

/**
 * Sanitise a hand-typed/pasted provider configuration. A pasted API key often brings in line breaks or leading/trailing spaces; splicing it directly into the Authorization
 * header would be rejected by OkHttp (Unexpected char 0x0a in Authorization value); a baseUrl / proxy host containing whitespace would
 * produce an invalid URL. Sanitise once on both the write and read sides, so already-stored dirty data becomes usable again without a migration.
 */
fun AIProviderConfig.sanitized(): AIProviderConfig = copy(
    name = name.stripLineBreaks(),
    apiKey = apiKey.stripAllWhitespace(),
    apiKeys = apiKeys
        .map { it.copy(value = it.value.stripAllWhitespace(), weight = it.weight.coerceIn(0, MAX_KEY_WEIGHT)) }
        .filter { it.value.isNotEmpty() }
        .distinctBy { it.value },
    keySwitchStatusCodes = keySwitchStatusCodes.filter { it in 100..599 }.distinct(),
    baseUrl = baseUrl.stripAllWhitespace(),
    defaultModel = defaultModel.stripLineBreaks(),
    models = models.map { it.stripLineBreaks() }.filter { it.isNotEmpty() }.distinct(),
    selectedModel = selectedModel.stripLineBreaks(),
    dashboardScriptPath = dashboardScriptPath.stripLineBreaks(),
    customHeaders = customHeaders
        .mapKeys { (k, _) -> k.trim() }
        .mapValues { (_, v) -> v.stripLineBreaks() }
        .filterKeys { it.isNotEmpty() },
    proxyHost = proxyHost.stripAllWhitespace(),
    proxyUsername = proxyUsername.stripLineBreaks(),
    proxyPassword = proxyPassword.stripLineBreaks(),
    scriptParams = scriptParams
        .mapKeys { (k, _) -> k.trim() }
        .mapValues { (_, v) -> v.stripLineBreaks() }
        .filterKeys { it.isNotEmpty() }
)

enum class ProviderType {
    OPENAI, ANTHROPIC, GEMINI
}

/** HTTP status codes that trigger automatic multi-key switching by default: authentication (401), billing/quota (402), permission (403), rate limiting (429). */
val DEFAULT_KEY_SWITCH_STATUS_CODES: Set<Int> = setOf(401, 402, 403, 429)

enum class KeyRotationStrategy {
    /** Sequential: always use the first key that is not cooling down; move forward only after consecutive failures reach the threshold. */
    SEQUENTIAL,

    /** Round-robin: new sessions are assigned a starting key in turn; within the same session it still sticks to the same key to preserve the server-side prompt cache. */
    ROUND_ROBIN
}

fun defaultProviderApiPath(type: ProviderType): String = when (type) {
    ProviderType.ANTHROPIC -> "v1/messages"
    ProviderType.GEMINI -> "v1beta"
    else -> "v1/chat/completions"
}
