package com.aicode.feature.settings.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "ai_providers")
data class AIProviderEntity(
    @PrimaryKey val id: String,
    val name: String,
    val type: String,
    /** Stored in plaintext in Room, on the same basis as the git token; will be handled together when unified encryption is introduced later. */
    val apiKey: String,
    /** Multi-Key mode switch; when off, only [apiKey] is used. */
    val multiKeyEnabled: Boolean = false,
    /** Multi-Key candidate list, persisted newline-separated (same as [models]). */
    val apiKeys: String = "",
    /** Multi-Key selection strategy: SEQUENTIAL / ROUND_ROBIN. */
    val keyRotationStrategy: String = "SEQUENTIAL",
    /** Deprecated: the old threshold for "switch only after consecutive failures reach the threshold"; the column is kept for compatibility with old databases (SQLite 3.18 has no DROP COLUMN). */
    val keyFailoverThreshold: Int = 2,
    /** Cooldown in minutes for a Key that has been switched away from; 0 means no cooldown. */
    val keyCooldownMinutes: Int = 5,
    /** HTTP status codes that trigger automatic multi-Key switching on a match, persisted comma-separated; an empty string means the default values are used. */
    val keySwitchStatusCodes: String = "",
    val baseUrl: String,
    val defaultModel: String,
    /** List of available models, persisted newline-separated. */
    val models: String = "",
    /** Currently selected model; when empty, falls back to defaultModel. */
    val selectedModel: String = "",
    val isEnabled: Boolean = true,
    val useFullUrl: Boolean = false,
    val useResponseApi: Boolean = false,
    /** Anthropic explicit cache breakpoints (cache_control). Only used for the ANTHROPIC type, enabled by default. */
    val anthropicCacheBreakpoints: Boolean = true,
    /** Send prompt_cache_key on the Chat Completion path (shard routing). Only used for the OPENAI type, disabled by default (the official API does not accept this field). */
    val openaiChatCacheKey: Boolean = false,
    /** Custom dashboard script path. The column name keeps the historical naming for compatibility with released databases. */
    @ColumnInfo(name = "balanceScriptPath")
    val dashboardScriptPath: String = "",
    /** Custom dashboard auto-refresh interval (minutes). Defaults to 5 minutes. The column name keeps the historical naming for compatibility with released databases. */
    @ColumnInfo(name = "balanceRefreshInterval")
    val dashboardRefreshInterval: Int = 5,
    /**
     * Deprecated: the UA override column superseded by [customHeaders]. The field is kept for compatibility with old databases —
     * the physical column added by migration 40 cannot be deleted (SQLite 3.18 has no DROP COLUMN), and if the entity were missing a column, Room's
     * post-migration schema validation would fail (the column set is compared exactly, so one extra column crashes it).
     */
    val userAgent: String = "",
    /** Custom request headers (a JSON-encoded Map<header name, value>, empty is ""), completely overriding default headers with the same name. */
    val customHeaders: String = "",
    /** Sort order in the provider list; smaller values come first; assigned max+1 when newly created. */
    val sortOrder: Int = 0,
    /** Configure a proxy specifically for this provider (when off, follows the global proxy settings). */
    val proxyEnabled: Boolean = false,
    val proxyType: String = "HTTP",
    val proxyHost: String = "",
    val proxyPort: Int = 0,
    val proxyUsername: String = "",
    val proxyPassword: String = "",
    /** Custom dashboard (DIY) script parameters (a JSON-encoded Map<String, String>, empty is ""). */
    val scriptParams: String = ""
)
