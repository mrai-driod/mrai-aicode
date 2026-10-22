package com.aicode.core.util

/**
 * List of languages supported by the App.
 *
 * To add a new language, simply append an entry to this list and create the corresponding res/values-xx/strings.xml,
 * and update res/xml/locales_config.xml accordingly; no other code needs to change.
 *
 * @property tag BCP-47 language tag.
 * @property displayName The language's autonym (written in the language itself), used for display in the language selection list.
 */
data class SupportedLanguage(
    val tag: String,
    val displayName: String
)

object LanguageRegistry {

    /** Placeholder value for following the system; not a real language tag. */
    const val FOLLOW_SYSTEM = ""

    val languages: List<SupportedLanguage> = listOf(
        SupportedLanguage("zh", "中文"),
        SupportedLanguage("en", "English")
    )

    /** Whether the tag is a supported real language (excluding [FOLLOW_SYSTEM]). */
    fun isSupported(tag: String): Boolean = languages.any { it.tag == tag }
}
