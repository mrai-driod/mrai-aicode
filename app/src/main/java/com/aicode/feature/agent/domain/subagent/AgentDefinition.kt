package com.aicode.feature.agent.domain.subagent

import com.aicode.feature.agent.domain.model.AgentMode

/** The source scope of a subagent definition: global (shared across projects) or project-level (only takes effect in the current workspace, can be tracked by git). */
enum class AgentDefinitionScope { GLOBAL, PROJECT }

/**
 * Injectable fragments of a subagent's system prompt. When `inject` is omitted, [AgentDefinition.DEFAULT_INJECT] is used.
 *
 * BASE and MAIN_RULES are not enforced as mutually exclusive; if both are written, both are injected (MAIN_RULES first).
 */
enum class InjectPart(val token: String) {
    /** The streamlined baseline dedicated to subagents (`agent/subagent-base.md`): tool usage, path conventions, safety boundaries. */
    BASE("base"),

    /** The main agent's full static-rules baseline (all fragments `00` to `70`); used when a subagent must behave exactly like the main agent. */
    MAIN_RULES("mainRules"),

    /** The list of available skills (the subagent can loadSkill). */
    SKILLS("skills"),

    /** The summary list of global + project memories. */
    MEMORY("memory"),

    /** Project rules (AGENTS.md / CLAUDE.md). */
    PROJECT_RULES("projectRules");

    companion object {
        /** Lenient spelling mapping for the frontmatter: ignores case and hyphen/underscore differences. */
        fun fromToken(token: String): InjectPart? {
            val normalized = token.trim().lowercase().replace("-", "").replace("_", "")
            return when (normalized) {
                "base" -> BASE
                "mainrules" -> MAIN_RULES
                "skills", "skill" -> SKILLS
                "memory", "memories" -> MEMORY
                "projectrules", "projectrule" -> PROJECT_RULES
                else -> null
            }
        }
    }
}

/**
 * A custom subagent definition, from the frontmatter + body of `agents/<name>.md`.
 *
 * @param name unique identifier; `task(agent="<name>")` dispatches with it
 * @param description when this subagent should be dispatched; injected into the main agent's prompt for it to choose from
 * @param providerId the bound provider id; null means inherit from the parent session
 * @param model the bound model name; null means inherit from the parent session
 * @param reasoningEffort reasoning effort (low/medium/high); null means inherit from the parent session
 * @param mode run mode (build/plan/auto); null means inherit from the parent session
 * @param allowedTools tool allowlist; empty means inherit the full tool set
 * @param disallowedTools tool denylist, applied before the allowlist
 * @param inject the prompt fragments to inject
 * @param prompt the agent's own system prompt (the body)
 * @param filePath container path of the definition file, for display and deletion on the settings page
 */
data class AgentDefinition(
    val name: String,
    val description: String,
    val providerId: String? = null,
    val model: String? = null,
    val reasoningEffort: String? = null,
    val mode: AgentMode? = null,
    val allowedTools: List<String> = emptyList(),
    val disallowedTools: List<String> = emptyList(),
    val inject: Set<InjectPart> = DEFAULT_INJECT,
    val prompt: String,
    val filePath: String? = null
) {
    /**
     * Trims the tool name set by the allowlist/denylist. `task` is always removed: a subagent cannot dispatch nested subagents.
     *
     * The denylist applies first, then the result is intersected with the allowlist; wildcards of the form `mcp__server__*` are supported,
     * so all tools of an MCP server can be banned at once.
     */
    fun filterToolNames(all: List<String>): List<String> {
        val afterDeny = all.filterNot { name -> disallowedTools.any { matches(it, name) } }
        val afterAllow = if (allowedTools.isEmpty()) {
            afterDeny
        } else {
            afterDeny.filter { name -> allowedTools.any { matches(it, name) } }
        }
        return afterAllow.filterNot { it == NESTED_TOOL }
    }

    private fun matches(pattern: String, toolName: String): Boolean {
        val p = pattern.trim()
        if (p.isEmpty()) return false
        if (p.endsWith("*")) return toolName.startsWith(p.dropLast(1), ignoreCase = true)
        return toolName.equals(p, ignoreCase = true)
    }

    companion object {
        /** The default injections when `inject` is omitted: streamlined baseline + skills + memory + project rules. */
        val DEFAULT_INJECT: Set<InjectPart> = setOf(
            InjectPart.BASE,
            InjectPart.SKILLS,
            InjectPart.MEMORY,
            InjectPart.PROJECT_RULES
        )

        /** Subagents cannot dispatch nested subagents, so their tool set always excludes this tool. */
        const val NESTED_TOOL = "task"

        /** A tool only subagents can use (subagent-to-main messaging); the main session's tool set excludes it. */
        const val PARENT_MESSAGE_TOOL = "messageParent"
    }
}

/** An active subagent definition entry: the definition itself + its source scope, so the UI can label it "Global/Project". */
data class AgentDefinitionEntry(
    val definition: AgentDefinition,
    val scope: AgentDefinitionScope
)

/** Snapshot of the form submitted when creating/editing a subagent on the settings page. */
data class AgentDefinitionForm(
    val name: String,
    val description: String,
    val providerId: String? = null,
    val model: String? = null,
    val reasoningEffort: String? = null,
    val mode: AgentMode? = null,
    val allowedTools: List<String> = emptyList(),
    val disallowedTools: List<String> = emptyList(),
    val inject: Set<InjectPart> = AgentDefinition.DEFAULT_INJECT,
    val prompt: String = ""
)

/** Reason a subagent definition failed to save. */
enum class AgentSaveError {
    /** The name is empty, too long, or contains characters that cannot be used in a file name. */
    INVALID_NAME,

    /** The prompt body is empty: the parser discards such a definition outright, so saving it would be unusable anyway. */
    EMPTY_PROMPT,

    /** A subagent with the same name already exists in the same scope. */
    NAME_CONFLICT,

    /** Writing to disk failed. */
    IO_FAILED
}
