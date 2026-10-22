package com.aicode.feature.agent.domain.prompt

import java.io.File

/**
 * `prompts.custom/` fragment resolution rules (pure filesystem logic, easy to unit test).
 *
 * The directory holds two kinds of fragments:
 * - Top-level `<two-digits>-<name>.md`: the number is the identity. If the number matches a built-in static fragment it is an override; otherwise it is a new fragment merged into the static baseline sorted by number.
 * - Other files (including the `agent/` subdirectory): override by exact same name, with no numeric identity.
 */
internal object PromptFragmentResolver {

    /** If this file exists, built-in prompts are fully disabled (main agent only). */
    const val DISABLE_BUILTIN_FILE = ".no-builtin"

    private val NUMBERED = Regex("""^(\d{2})-(.+)\.md$""")

    /** The numeric value of a top-level numbered fragment; returns null when there is no two-digit prefix. */
    fun parseNumber(fileName: String): Int? =
        NUMBERED.matchEntire(fileName)?.groupValues?.get(1)?.toInt()

    /**
     * Top-level `<two-digits>-<name>.md` files in the directory, in ascending numeric order.
     * When several files share a number, only the lexicographically first is kept to avoid unstable ordering.
     */
    fun numberedFragments(dir: File): List<Pair<Int, File>> {
        val files = dir.listFiles() ?: return emptyList()
        val byNumber = LinkedHashMap<Int, File>()
        files.filter { it.isFile }
            .sortedBy { it.name }
            .forEach { file ->
                val number = parseNumber(file.name) ?: return@forEach
                byNumber.putIfAbsent(number, file)
            }
        return byNumber.entries.sortedBy { it.key }.map { it.key to it.value }
    }

    /** Whether `.no-builtin` exists. */
    fun isBuiltinDisabled(dir: File): Boolean = File(dir, DISABLE_BUILTIN_FILE).isFile
}