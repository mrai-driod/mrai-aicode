package com.aicode.feature.agent.domain.skill

/**
 * Formats the list of enabled skills into a list injected into the system prompt, with a total budget and staged degradation.
 *
 * Benchmarks (why a budget is necessary):
 * - Codex (codex-rs/ext/skills): the skill list takes about 2% of the context (hard cap 10000 tokens); when over budget it first drops
 *   descriptions, degrading to name-only entries, then omits the remainder entirely and states the omitted count in the prompt, so the bulk is left for the actual conversation.
 * - Claude Code: about 1% context budget, with a per-description cap of 250 characters.
 * Measured on this device (evidence from before the improvement): a plain list of 118 skills was 15441 characters, 53.2% of a 29526-character system prompt,
 * with no total constraint at all; in the same statistics, out of 456 messages / 239 tool calls, loadSkill calls were 0:
 * the model simply ignores a half-screen list, which is worse than a compressed list + forceful wording + a per-turn relevance reminder combined.
 *
 * Degradation order (the budget unit is characters; entries greedily take space in the given order, whose determinism is guaranteed by the caller):
 * 1. Full `- name: description` entries;
 * 2. Once full entries no longer fit, the remaining skills degrade to name-only `- name` entries (preserving discoverability);
 * 3. If even name-only entries do not fit, they are omitted, and the omitted count and the self-service lookup path are stated at the end.
 */
object SkillListFormatter {

    /**
     * Character budget for the list. Takes a conservative value between Claude Code's 1% and Codex's 2%: converted against a mainstream 128K context,
     * 1% is about 4000 characters and 2% is about 8000 characters (mixed Chinese/English), and the upper value is taken; after name-only degradation the actual use is usually below this.
     * To adjust it, change only this value; the formatting logic and tests are decoupled from it.
     */
    const val DEFAULT_CHAR_BUDGET = 8_000

    /** Length allowance for the omission notice line: reserved so the notice itself does not push the list over budget. */
    private const val NOTICE_SLACK = 240

    /** Placeholder used when there is no description, keeping the structure of every list line consistent. */
    private const val NO_DESC = "(No description)"

    /**
     * Formats the skill list; returns "" when [skills] is empty.
     * The output length does not exceed [charBudget] (including the omission notice line), unless a single name by itself exceeds the remaining budget:
     * in that case the entry is not listed at all rather than breaking the budget cap.
     */
    fun format(skills: List<Skill>, charBudget: Int = DEFAULT_CHAR_BUDGET): String {
        if (skills.isEmpty()) return ""
        if (charBudget <= 0) return ""

        val limit = (charBudget - NOTICE_SLACK).coerceAtLeast(0)
        val full = StringBuilder()
        val namesOnly = StringBuilder()
        var used = 0
        var omitted = 0
        var descPhaseDone = false

        for (skill in skills) {
            val name = skill.name.trim()
            if (name.isEmpty()) continue
            val fullLine = "- $name: ${skill.description.trim().ifEmpty { NO_DESC }}"
            if (!descPhaseDone) {
                val cost = fullLine.length + 1 // +1 = the trailing newline
                if (used + cost <= limit) {
                    full.appendLine(fullLine)
                    used += cost
                    continue
                }
                descPhaseDone = true
            }
            val nameLine = "- $name"
            val nameCost = nameLine.length + 1
            if (used + nameCost <= limit) {
                namesOnly.appendLine(nameLine)
                used += nameCost
            } else {
                omitted++
            }
        }

        // When not even one entry fits (budget smaller than NOTICE_SLACK), only the notice line remains, which is still better than an empty list.
        if (full.isEmpty() && namesOnly.isEmpty() && omitted == 0) return ""

        return buildString {
            if (full.isNotEmpty()) append(full.trimEnd())
            if (namesOnly.isNotEmpty()) {
                if (isNotEmpty()) append('\n')
                append(namesOnly.trimEnd())
            }
            if (omitted > 0) {
                if (isNotEmpty()) append('\n')
                append(omissionNotice(omitted, charBudget))
            }
        }.trim()
    }

    /** Omission notice: tells the model how many were left out, where to see the full list, and that unlisted skills can also be attempted by name. */
    private fun omissionNotice(omitted: Int, charBudget: Int): String =
        "... $omitted more skills are not listed here because of the prompt budget (limit $charBudget characters); " +
            "see the full list in ~/.aicode/skills/ and <project root>/.aicode/skills/. " +
            "If one fits the task, load it by name with loadSkill."
}
