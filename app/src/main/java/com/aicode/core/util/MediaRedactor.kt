package com.aicode.core.util

/**
 * Log media redaction: before writing to disk, replaces oversized base64 (images, etc.) with an omission marker to prevent a single line of several MB from blowing up the log file.
 *
 * Three layers of coverage:
 * 1. `data:<mime>;base64,...` inline data URL (common in image-generation/screenshot tool results);
 * 2. base64 values under known field names (`data` / `base64Data` / `image_data`) (matched in both Gson pretty and SSE compact forms);
 * 3. Fallback: a run of >=1024 consecutive base64 characters anywhere -- catches it regardless of the field name, at the cost of also omitting very long alphanumeric text (rare, acceptable).
 *
 * Thresholds are 256/1024: real image base64 is at least a few hundred characters, and normal text almost never has 256 consecutive base64 characters.
 */
object MediaRedactor {

    private val DATA_URL_REGEX =
        Regex("data:[A-Za-z0-9.+-]+/[A-Za-z0-9.+-]+;base64,([A-Za-z0-9+/=_-]{256,})")
    private val BASE64_FIELD_REGEX =
        Regex("\"(base64Data|data|image_data)\"\\s*:\\s*\"([A-Za-z0-9+/=_-]{256,})\"")
    private val LONG_BASE64_REGEX = Regex("[A-Za-z0-9+/=_-]{1024,}")

    fun redact(text: String): String {
        if (text.isBlank()) return text
        val afterDataUrl = DATA_URL_REGEX.replace(text) { m ->
            "data:[base64 omitted: ${m.groupValues[1].length} chars]"
        }
        val afterField = BASE64_FIELD_REGEX.replace(afterDataUrl) { m ->
            "\"${m.groupValues[1]}\": \"[base64 omitted: ${m.groupValues[2].length} chars]\""
        }
        return LONG_BASE64_REGEX.replace(afterField) { m ->
            "[base64 omitted: ${m.value.length} chars]"
        }
    }
}
