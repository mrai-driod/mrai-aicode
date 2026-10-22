-- v56 ended up with two schemas: databases freshly installed before a8d5c5df lack the physical userAgent column, while earlier ones (including releases <=52,
-- which had it added by migration 40) have the column. Both kinds can report version 56, so the onOpen identity check can no longer distinguish them.
-- Bump to 57 and rebuild ai_providers so both kinds converge on the same column set. SQLite 3.18 has no ADD COLUMN IF NOT EXISTS,
-- so adding the column would raise a duplicate column error on databases that already have it; rebuild unconditionally instead (userAgent filled with an empty string).
ALTER TABLE `ai_providers` RENAME TO `ai_providers_old`;

CREATE TABLE `ai_providers` (
    `id` TEXT NOT NULL,
    `name` TEXT NOT NULL,
    `type` TEXT NOT NULL,
    `apiKey` TEXT NOT NULL,
    `multiKeyEnabled` INTEGER NOT NULL,
    `apiKeys` TEXT NOT NULL,
    `keyRotationStrategy` TEXT NOT NULL,
    `keyFailoverThreshold` INTEGER NOT NULL,
    `keyCooldownMinutes` INTEGER NOT NULL,
    `keySwitchStatusCodes` TEXT NOT NULL,
    `baseUrl` TEXT NOT NULL,
    `defaultModel` TEXT NOT NULL,
    `models` TEXT NOT NULL,
    `selectedModel` TEXT NOT NULL,
    `isEnabled` INTEGER NOT NULL,
    `useFullUrl` INTEGER NOT NULL,
    `useResponseApi` INTEGER NOT NULL,
    `anthropicCacheBreakpoints` INTEGER NOT NULL,
    `openaiChatCacheKey` INTEGER NOT NULL,
    `balanceScriptPath` TEXT NOT NULL,
    `balanceRefreshInterval` INTEGER NOT NULL,
    `userAgent` TEXT NOT NULL,
    `customHeaders` TEXT NOT NULL,
    `sortOrder` INTEGER NOT NULL,
    `proxyEnabled` INTEGER NOT NULL,
    `proxyType` TEXT NOT NULL,
    `proxyHost` TEXT NOT NULL,
    `proxyPort` INTEGER NOT NULL,
    `proxyUsername` TEXT NOT NULL,
    `proxyPassword` TEXT NOT NULL,
    `scriptParams` TEXT NOT NULL,
    PRIMARY KEY(`id`)
);

INSERT INTO `ai_providers` (
    `id`, `name`, `type`, `apiKey`, `multiKeyEnabled`, `apiKeys`, `keyRotationStrategy`,
    `keyFailoverThreshold`, `keyCooldownMinutes`, `keySwitchStatusCodes`, `baseUrl`, `defaultModel`,
    `models`, `selectedModel`, `isEnabled`, `useFullUrl`, `useResponseApi`, `anthropicCacheBreakpoints`,
    `openaiChatCacheKey`, `balanceScriptPath`, `balanceRefreshInterval`, `userAgent`, `customHeaders`,
    `sortOrder`, `proxyEnabled`, `proxyType`, `proxyHost`, `proxyPort`, `proxyUsername`, `proxyPassword`,
    `scriptParams`
)
SELECT
    `id`, `name`, `type`, `apiKey`, `multiKeyEnabled`, `apiKeys`, `keyRotationStrategy`,
    `keyFailoverThreshold`, `keyCooldownMinutes`, `keySwitchStatusCodes`, `baseUrl`, `defaultModel`,
    `models`, `selectedModel`, `isEnabled`, `useFullUrl`, `useResponseApi`, `anthropicCacheBreakpoints`,
    `openaiChatCacheKey`, `balanceScriptPath`, `balanceRefreshInterval`, '', `customHeaders`,
    `sortOrder`, `proxyEnabled`, `proxyType`, `proxyHost`, `proxyPort`, `proxyUsername`, `proxyPassword`,
    `scriptParams`
FROM `ai_providers_old`;

DROP TABLE `ai_providers_old`;
