package com.aicode.feature.agent.domain.tool.mode

import com.aicode.feature.agent.data.local.dao.ChatSessionDao
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Manages the suspend/resume of the plan review flow:
 * When the AI switches from PLAN mode back to BUILD mode, the workflow calls [awaitApproval] to suspend,
 * and resumes once the user has decided in the plan review panel in the UI.
 */
@Singleton
class PlanApprovalManager @Inject constructor(
    private val chatSessionDao: ChatSessionDao
) {
    private val _pendingApproval = MutableStateFlow<PlanApprovalRequest?>(null)
    val pendingApproval: StateFlow<PlanApprovalRequest?> = _pendingApproval.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO)
    private var currentDecision: CompletableDeferred<PlanApprovalChoice>? = null
    private var currentSessionId: String? = null

    /** Suspends waiting for the user's decision in the plan review panel. */
    suspend fun awaitApproval(reason: String, sessionId: String? = null): PlanApprovalChoice {
        val decision = CompletableDeferred<PlanApprovalChoice>()
        currentDecision = decision
        currentSessionId = sessionId
        _pendingApproval.value = PlanApprovalRequest(reason = reason)

        try {
            return decision.await()
        } catch (e: CancellationException) {
            // The user did not approve the switch (a cancellation path such as tapping "Stop"): PlanModeTool has already written the target mode to the DB;
            // cancellation means it was not approved, so it must be rolled back, otherwise the mode would switch over without approval.
            rollbackModeToPlan(sessionId)
            throw e
        } finally {
            currentDecision = null
            currentSessionId = null
            _pendingApproval.value = null
        }
    }

    /** The UI passes back the user's choice, waking the suspended [awaitApproval]. */
    fun resolve(choice: PlanApprovalChoice) {
        _pendingApproval.value = null

        // When the user rejects, roll the mode in the database back to PLAN (PlanModeTool has already written the target mode)
        if (choice == PlanApprovalChoice.REFINE) {
            rollbackModeToPlan(currentSessionId)
        }

        currentDecision?.complete(choice)
    }

    /** Rolls the session mode back to PLAN (PlanModeTool wrote the target mode in advance; restore it when not approved). */
    private fun rollbackModeToPlan(sessionId: String?) {
        val sid = sessionId ?: return
        scope.launch {
            val entity = chatSessionDao.getById(sid)
            if (entity != null && entity.mode != "PLAN") {
                chatSessionDao.upsert(entity.copy(mode = "PLAN"))
            }
        }
    }
}

data class PlanApprovalRequest(val reason: String)

enum class PlanApprovalChoice {
    /** Approve the plan and switch to BUILD mode to execute */
    APPROVE,
    /** Reject and stay in PLAN mode to continue giving feedback */
    REFINE
}
