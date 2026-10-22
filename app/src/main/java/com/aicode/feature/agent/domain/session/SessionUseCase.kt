package com.aicode.feature.agent.domain.session

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.data.local.dao.AgentMessageDao
import com.aicode.feature.agent.data.local.dao.ChatSessionDao
import com.aicode.feature.agent.data.local.entity.ChatSessionEntity
import com.aicode.feature.agent.domain.model.AgentMode
import com.aicode.feature.agent.domain.model.ReasoningEffort
import com.aicode.feature.agent.presentation.MessageRole
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SessionUseCase @Inject constructor(
    private val chatSessionDao: ChatSessionDao,
    private val agentMessageDao: AgentMessageDao
) {
    companion object {
        private const val TAG = "SessionUseCase"
        const val TITLE_MAX = 20
        /** Tool placeholder-row prefix: marks an orphan that is "running, result not yet returned"; the UI and replay identify it by this. */
        const val PENDING_TOOL_MARKER = "[running]"
        /** Legacy-version emoji prefix; cold-start cleanup and replay still need to recognise it. */
        const val LEGACY_PENDING_TOOL_MARKER = "\u23F3"
        /** Legacy-version stopped emoji prefix; kept for compatibility when the UI strips result text. */
        const val LEGACY_STOPPED_TOOL_MARKER = "\u23F9"
        const val INTERRUPTED_TOOL_TEXT = "Execution interrupted (app was closed)"
    }

    /** Cold-start cleanup: if a tool was executing when the previous process was killed, its placeholder row would show "running" forever. */
    suspend fun initColdStartCleanup() {
        runCatching {
            val n = listOf(PENDING_TOOL_MARKER, LEGACY_PENDING_TOOL_MARKER).sumOf { marker ->
                agentMessageDao.markPendingToolsInterrupted(
                    toolRole = MessageRole.TOOL.name,
                    pendingPrefix = "$marker%",
                    interruptedContent = INTERRUPTED_TOOL_TEXT
                )
            }
            if (n > 0) FileLogger.i(TAG, "Cold-start cleanup: $n leftover \"running\" tool rows marked as interrupted")
        }.onFailure { FileLogger.e(TAG, "Failed to reclaim leftover running tool rows", it) }
    }

    fun newSessionEntity(
        workspacePath: String,
        providerId: String? = null,
        model: String? = null,
        reasoningEffort: String = ReasoningEffort.MEDIUM.name
    ): ChatSessionEntity {
        val now = System.currentTimeMillis()
        return ChatSessionEntity(
            id = UUID.randomUUID().toString(),
            title = "New Session",
            workspacePath = workspacePath,
            createdAt = now,
            updatedAt = now,
            providerId = providerId,
            model = model,
            reasoningEffort = reasoningEffort
        )
    }

    fun deriveTitle(request: String): String {
        val clean = request.trim().replace(Regex("\\s+"), " ")
        return if (clean.length <= TITLE_MAX) clean.ifBlank { "New Conversation" }
        else clean.take(TITLE_MAX) + "…"
    }

    /** Deletes a session and all its sub-agent sessions (messages + session records deleted together). Returns the list of deleted session ids. */
    suspend fun deleteSession(id: String): List<String> {
        val deleted = mutableListOf(id)
        // Recursively collect child sessions (v1 has only one level, so a loop suffices)
        chatSessionDao.getSubSessionsByParentOnce(id).forEach { child ->
            agentMessageDao.deleteBySession(child.id)
            chatSessionDao.delete(child.id)
            deleted.add(child.id)
        }
        agentMessageDao.deleteBySession(id)
        chatSessionDao.delete(id)
        return deleted
    }

    /** Deletes all sessions under a workspace (including sub-agent sessions) and their messages; returns the number of sessions deleted. */
    suspend fun deleteSessionsByWorkspace(workspacePath: String): Int {
        val sessions = chatSessionDao.getAllSessionsByWorkspaceOnce(workspacePath)
        if (sessions.isEmpty()) return 0
        sessions.forEach { session -> agentMessageDao.deleteBySession(session.id) }
        chatSessionDao.deleteByWorkspace(workspacePath)
        return sessions.size
    }

    suspend fun getFirstSessionOfWorkspace(workspacePath: String): ChatSessionEntity? {
        return chatSessionDao.getRootSessionsByWorkspaceOnce(workspacePath).firstOrNull()
    }

    /** The most recently updated root session in the workspace (ignoring pinned), used to enter the most recent session at startup. */
    suspend fun getMostRecentSessionOfWorkspace(workspacePath: String): ChatSessionEntity? {
        return chatSessionDao.getLatestRootSessionByWorkspace(workspacePath)
    }

    /** Reclaims surplus empty sessions (never sent a message) under a workspace, keeping [keepId]; ensures the list holds at most one empty session. */
    suspend fun recycleEmptySessions(workspacePath: String, keepId: String? = null): Int {
        var count = 0
        chatSessionDao.getRootSessionsByWorkspaceOnce(workspacePath).forEach { session ->
            if (session.id != keepId && !agentMessageDao.hasMessages(session.id)) {
                deleteSession(session.id)
                count++
            }
        }
        return count
    }

    /**
     * Creates a sub-agent session, inheriting the parent session's provider/model/reasoningEffort/workspacePath.
     * @param title Child session title (derived from the task description)
     * @param parentId Parent session id
     * @param subagentType Sub-agent type id (custom agent name, or the default general sub-agent identifier)
     * @param providerId Overrides the provider; null means inherit from the parent session
     * @param model Overrides the model; null means inherit from the parent session
     * @param reasoningEffort Overrides the reasoning effort; null means inherit from the parent session
     * @param mode Overrides the run mode; null means inherit from the parent session
     */
    fun newSubSessionEntity(
        title: String,
        parentId: String,
        parent: ChatSessionEntity,
        subagentType: String,
        providerId: String? = null,
        model: String? = null,
        reasoningEffort: String? = null,
        mode: AgentMode? = null
    ): ChatSessionEntity {
        val now = System.currentTimeMillis()
        return ChatSessionEntity(
            id = UUID.randomUUID().toString(),
            title = title,
            workspacePath = parent.workspacePath,
            createdAt = now,
            updatedAt = now,
            // By default inherits the parent session's mode: the permission engine decides write blocking by session mode, and defaulting to BUILD would let a sub-agent dispatched in PLAN
            // mode bypass the read-only restriction, while conversely a sub-agent dispatched in another mode would pop up authorisation prompts and disturb the user.
            // A custom sub-agent can override this default with the mode from its definition.
            mode = mode?.name ?: parent.mode,
            providerId = providerId ?: parent.providerId,
            model = model ?: parent.model,
            reasoningEffort = reasoningEffort ?: parent.reasoningEffort,
            parentId = parentId,
            subagentType = subagentType
        )
    }

    suspend fun upsertSession(entity: ChatSessionEntity) {
        chatSessionDao.upsert(entity)
    }

    suspend fun updateTitle(sessionId: String, title: String) {
        chatSessionDao.updateTitle(sessionId, title)
    }

    suspend fun updatePinned(sessionId: String, pinned: Boolean) {
        chatSessionDao.updatePinned(sessionId, pinned)
    }

    suspend fun touch(sessionId: String, timestamp: Long) {
        chatSessionDao.touch(sessionId, timestamp)
    }

    suspend fun getSessionById(id: String): ChatSessionEntity? {
        return chatSessionDao.getById(id)
    }

    suspend fun updateMode(sessionId: String, mode: String) {
        val s = chatSessionDao.getById(sessionId) ?: return
        val newMode = runCatching { AgentMode.valueOf(mode) }.getOrNull() ?: return
        val currentMode = runCatching { AgentMode.valueOf(s.mode) }.getOrNull()
        // When the user enters PLAN manually, likewise record the mode before entering, and restore it when leaving PLAN (same semantics as AI self-switching).
        val modeBeforePlan = when {
            newMode == AgentMode.PLAN && currentMode != AgentMode.PLAN -> currentMode?.name
            newMode == AgentMode.PLAN -> s.modeBeforePlan
            else -> null
        }
        chatSessionDao.upsert(s.copy(mode = newMode.name, modeBeforePlan = modeBeforePlan))
    }

    suspend fun updateProviderModel(sessionId: String, providerId: String?, model: String?) {
        chatSessionDao.updateProviderModel(sessionId, providerId, model)
    }

    suspend fun updateReasoningEffort(sessionId: String, effort: String) {
        chatSessionDao.updateReasoningEffort(sessionId, effort)
    }

    suspend fun isSessionEmpty(sessionId: String): Boolean {
        return !agentMessageDao.hasMessages(sessionId)
    }
}
