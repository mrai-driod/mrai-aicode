package com.aicode.feature.agent.domain.model

/**
 * The working mode of the Agent (aligned with the three-part Build / Plan / Ask of Claude Code):
 * - [BUILD] Build: can read and write files and execute commands; whether an authorization popup appears is decided by permission settings such as "authorization tier + high-risk rules",
 *   and the mode itself no longer has a built-in allow policy (the original AUTO "allow everything" semantics are carried by the "full permissions" tier).
 * - [PLAN] Plan: read-only exploration + outputting an implementation plan, only entering build execution after approval by the plan review panel.
 * - [ASK] Ask: read-only question answering, forbidding all write operations, and not entering the plan execution flow (aligned with the Ask of Claude Code).
 */
enum class AgentMode {
    BUILD, // Build mode (default): allows all authorized operations; see ToolPermissionPolicyEngine for the exact authorization rules
    PLAN,  // Plan mode, intercepts modification operations, mainly read-only/exploration
    ASK    // Ask mode, read-only question answering; write operations are physically intercepted, and the AI is not allowed to switch to plan mode on its own
}

/**
 * A single independent chat session. Messages belong to a session via sessionId; switching sessions switches the chat history.
 */
data class ChatSession(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val workspacePath: String = "",
    val mode: AgentMode = AgentMode.BUILD,
    /** The mode before entering PLAN (BUILD or ASK); restored when leaving PLAN, null is treated as BUILD. */
    val modeBeforePlan: AgentMode? = null,
    val reasoningEffort: ReasoningEffort = ReasoningEffort.MEDIUM,
    val providerId: String? = null,
    val model: String? = null,
    val totalInputTokens: Int = 0,
    val totalOutputTokens: Int = 0,
    val lastInputTokens: Int = 0,
    val isPinned: Boolean = false,
    /** Sub-agent session: the parent session id; null means an ordinary root session. */
    val parentId: String? = null,
    /** Sub-agent session: the type of the spawned sub-agent (e.g. coder / researcher); null means an ordinary root session. */
    val subagentType: String? = null
)
