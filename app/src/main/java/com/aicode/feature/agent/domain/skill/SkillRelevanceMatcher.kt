package com.aicode.feature.agent.domain.skill

/**
 * Each turn, matches "possibly relevant" skills against the user's input and generates a short reminder appended to the end of this turn's latest user message.
 *
 * Benchmark: Claude Code injects "Skills relevant to your task:" to the model every turn; the list only appears
 * once in the system prompt and the model forgets it as it works, so pushing the few most relevant skills, names and descriptions together, in front of it at the decision point (this turn's request)
 * is a key reason its skill invocation rate is high. Codex reaches the same effect with a list budget + forceful wording.
 *
 * Matching is a purely local heuristic and does not aim for completeness: the [MIN_SCORE] threshold ensures a skill only counts as relevant when several trigger terms hit;
 * the reminder text itself says to ignore the section if none applies, so the cost of a false positive is just a short ignorable piece of text.
 *
 * Trigger term extraction (the same rules on both the user-text side and the skill side):
 * - Chinese: two consecutive Han characters form a bigram;
 * - English/digits: words of length >= 3 (lowercased), such as root, adb, ssh, pdf, apk.
 * A hit on a skill name is weighted double: if the name hits, it is basically that skill.
 */
object SkillRelevanceMatcher {

    /** Maximum number of skills listed in the reminder: 3 is enough to cover the vast majority of tasks; any more becomes another ignored list. */
    const val MAX_HINT_SKILLS = 3

    /** Score threshold: only remind when at least two trigger terms hit (or one skill name hits), to avoid false positives everywhere. */
    const val MIN_SCORE = 2

    /** Maximum length of a single description in the reminder, so relevant skills themselves do not bloat this turn's request. */
    private const val HINT_DESC_CHARS = 120

    private val LATIN_TOKEN = Regex("[A-Za-z0-9][A-Za-z0-9_.+-]{2,}")

    /** Han character ranges (Basic + Extension A). */
    private fun isCjk(c: Char): Boolean = c.code in 0x4E00..0x9FFF || c.code in 0x3400..0x4DBF

    /**
     * Generates the reminder text; returns null when there is no hit / no skills / the user triggered a slash command with `/skill-name`
     * (the slash command's skill body is already injected as this turn's instruction, so there is no need to remind about loadSkill again).
     */
    fun buildHint(skills: List<Skill>, userText: String): String? {
        if (skills.isEmpty() || userText.isBlank()) return null
        if (userText.trimStart().startsWith("/")) return null

        val userTerms = extractTerms(userText)
        if (userTerms.isEmpty()) return null

        val scored = skills.mapNotNull { skill ->
            val score = score(skill, userTerms)
            if (score >= MIN_SCORE) skill to score else null
        }
        if (scored.isEmpty()) return null

        val lines = scored
            .sortedByDescending { it.second }
            .take(MAX_HINT_SKILLS)
            .joinToString("\n") { (skill, _) -> "- ${skill.name}: ${truncate(skill.description)}" }

        return "[System reminder] The following skills may be relevant to this turn's task: if one fits, you must first call loadSkill to load its body and follow it this turn, before starting to act; if none applies, ignore this section.\n$lines"
    }

    /** Chinese bigrams + English/digit words of length >= 3. */
    private fun extractTerms(text: String): Set<String> {
        val terms = LinkedHashSet<String>()
        LATIN_TOKEN.findAll(text).forEach { terms.add(it.value.lowercase()) }
        var i = 0
        while (i < text.length - 1) {
            if (isCjk(text[i]) && isCjk(text[i + 1])) {
                terms.add(text.substring(i, i + 2))
                i += 1 // Sliding window: take adjacent bigrams too, to hit consecutive words
            } else {
                i += 1
            }
        }
        return terms
    }

    /** Skill name hit x2 + description hit x1. */
    private fun score(skill: Skill, userTerms: Set<String>): Int {
        var s = 0
        for (token in nameTerms(skill.name)) {
            if (token in userTerms) s += 2
        }
        for (token in extractTerms(skill.description)) {
            if (token in userTerms) s += 1
        }
        return s
    }

    /** Splits a skill name into terms: English/digit tokens (>= 3 characters), and the whole Chinese run split into bigrams, aligned with the user side. */
    private fun nameTerms(name: String): Set<String> {
        val terms = LinkedHashSet<String>()
        LATIN_TOKEN.findAll(name).forEach { terms.add(it.value.lowercase()) }
        var i = 0
        while (i < name.length - 1) {
            if (isCjk(name[i]) && isCjk(name[i + 1])) {
                terms.add(name.substring(i, i + 2))
                i += 1
            } else {
                i += 1
            }
        }
        return terms
    }

    private fun truncate(description: String): String {
        val d = description.trim().ifEmpty { "(No description)" }
        return if (d.length <= HINT_DESC_CHARS) d else d.take(HINT_DESC_CHARS) + "…"
    }
}
