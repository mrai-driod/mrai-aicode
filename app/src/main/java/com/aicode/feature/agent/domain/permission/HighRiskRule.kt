package com.aicode.feature.agent.domain.permission

import kotlinx.serialization.Serializable

/**
 * High-risk interception rule: under the "Rule-based interception" level, a shell command that hits any enabled
 * rule must be escalated to manual confirmation (ASK).
 *
 * How it differs from authorization rules ([PermissionRule]): authorization rules come from remembered
 * "always allow/reject" choices and decide allow/deny; high-risk rules are preset by the system, editable by
 * the user, and their decision direction is "a human must take a look" — they carry no allow semantics.
 *
 * Matching modes:
 * - [HighRiskMatchType.PREFIX]: after the command is split into top-level segments, any segment hitting the
 *   token prefix matches (reusing [ShellCommandParser.matches], e.g. `rm -rf` matches `rm -rf /data` but not `rm -rfd`);
 * - [HighRiskMatchType.REGEX]: the regex is applied to the whole command line, and also tried once against each
 *   top-level segment (joined back to its original form), so `^`/`$` anchors also work at segment level
 *   (e.g. `curl … | sh` pipes, writing to a block device).
 */
@Serializable
data class HighRiskRule(
    /** Stable id: preset rules start with `preset.`; user-created rules are generated from a timestamp. */
    val id: String,
    val pattern: String,
    val matchType: HighRiskMatchType = HighRiskMatchType.PREFIX,
    /** Category name (UI grouping badge), e.g. "Deletion & erasure". */
    val category: String,
    /** Human-readable description of the hit, shown in the dialog and the list. */
    val description: String = "",
    val enabled: Boolean = true,
    /** Whether this is a system preset: presets can be brought back via "Restore defaults"; if the user deletes a preset and then restores, it returns in the enabled state. */
    val builtin: Boolean = true
)

enum class HighRiskMatchType { PREFIX, REGEX }

/** High-risk rule matching: returns the first enabled rule that hits, or null if none hits. */
object HighRiskRuleMatcher {

    fun matchAny(rules: List<HighRiskRule>, command: String): HighRiskRule? {
        val analysis = ShellCommandParser.analyze(command)
        for (rule in rules) {
            if (!rule.enabled) continue
            when (rule.matchType) {
                HighRiskMatchType.PREFIX -> {
                    if (analysis.segments.any { ShellCommandParser.matches(rule.pattern, it) }) return rule
                }
                HighRiskMatchType.REGEX -> {
                    val regex = runCatching { Regex(rule.pattern) }.getOrNull() ?: continue
                    if (regex.containsMatchIn(command)) return rule
                    if (analysis.segments.any { regex.containsMatchIn(it.joinToString(" ")) }) return rule
                }
            }
        }
        return null
    }
}
