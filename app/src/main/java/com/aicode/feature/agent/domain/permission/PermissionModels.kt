package com.aicode.feature.agent.domain.permission

import kotlinx.serialization.Serializable

/**
 * The scope of a tool authorization rule. The scope is not stored on the rule object itself; it is implied by
 * which file the rule lives in: the project file (`.aicode/permissions.json`) or the global file.
 * "Promote to global" in the management UI means deleting it from the project file and adding it to the global file.
 */
enum class PermissionScope { PROJECT, GLOBAL }

/**
 * The direction of a rule's decision. The dialog's "Always allow" only ever produces [ALLOW]; [DENY] is only
 * provided by the permission config file (the deny list). During evaluation, DENY takes precedence over ALLOW
 * across scopes (safety first: a global deny can block a project allow).
 */
enum class PermissionDecision { ALLOW, DENY }

/**
 * The user's choice in the authorization dialog.
 * - [REJECT]: reject this call; do not remember.
 * - [ONCE]: allow this call only; do not remember.
 * - [ALWAYS]: allow and remember as a rule (the dialog path always writes to the current project scope).
 */
enum class PermissionChoice { REJECT, ONCE, ALWAYS }

/**
 * A single tool authorization rule.
 *
 * @param toolName The tool this applies to, e.g. `Bash`, `writeFile`.
 * @param pattern The match pattern. For shell commands this is a "command prefix", matched by token prefix:
 *   subcommand dispatchers are recorded as `git pull` (matches only `git pull ...`, not `git clone`), while
 *   ordinary programs are recorded as `cat`/`ls` (matching all of their calls);
 *   for non-shell tools it is the wildcard `*` (whole-tool match).
 * @param decision The decision direction; defaults to [PermissionDecision.ALLOW].
 */
@Serializable
data class PermissionRule(
    val toolName: String,
    val pattern: String,
    val decision: PermissionDecision = PermissionDecision.ALLOW
) {
    companion object {
        /** The whole-tool match pattern for non-shell tools. */
        const val WHOLE_TOOL = "*"

        /** Parses a PermissionRule from a compact-format string.
         *  Format examples:
         *  - `"Bash"` → toolName=Bash, pattern=*, ALLOW
         *  - `"Bash(git pull)"` → toolName=Bash, pattern="git pull", ALLOW
         */
        fun fromCompact(compact: String, decision: PermissionDecision = PermissionDecision.ALLOW): PermissionRule {
            val parenIdx = compact.indexOf('(')
            return if (parenIdx >= 0 && compact.endsWith(')')) {
                val toolName = compact.substring(0, parenIdx)
                val pattern = compact.substring(parenIdx + 1, compact.length - 1)
                PermissionRule(toolName, pattern, decision)
            } else {
                PermissionRule(compact, WHOLE_TOOL, decision)
            }
        }
    }

    /** Converts to a compact-format string.
     *  Examples:
     *  - toolName=Bash, pattern=* → `"Bash"`
     *  - toolName=Bash, pattern="git pull" → `"Bash(git pull)"`
     */
    fun toCompact(): String =
        if (pattern == WHOLE_TOOL) toolName else "$toolName($pattern)"
}

// ── Permission file top-level structure ──────────────────────────────────────────────

/**
 * The top-level structure of the permission rules JSON file.
 *
 * Example:
 * ```json
 * {
 *   "permissions": {
 *     "allow": ["Bash(git pull)", "writeFile"],
 *     "deny": ["Bash(rm -rf /)"]
 *   }
 * }
 * ```
 */
@Serializable
data class PermissionFile(
    val permissions: PermissionSection = PermissionSection()
) {
    @Serializable
    data class PermissionSection(
        val allow: List<String> = emptyList(),
        val deny: List<String> = emptyList()
    )

    companion object {
        val EMPTY = PermissionFile()
    }
}

// Conversion between PermissionFile and List<PermissionRule>

/** Parses a rule list from a [PermissionFile]. */
fun PermissionFile.toRuleList(): List<PermissionRule> =
    permissions.allow.map { PermissionRule.fromCompact(it, PermissionDecision.ALLOW) } +
            permissions.deny.map { PermissionRule.fromCompact(it, PermissionDecision.DENY) }

/** Builds a [PermissionFile] from a rule list. */
fun List<PermissionRule>.toPermissionFile(): PermissionFile {
    val (allow, deny) = partition { it.decision == PermissionDecision.ALLOW }
    return PermissionFile(
        permissions = PermissionFile.PermissionSection(
            allow = allow.map { it.toCompact() },
            deny = deny.map { it.toCompact() }
        )
    )
}
