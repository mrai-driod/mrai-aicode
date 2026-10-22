package com.aicode.feature.agent.domain.tool

import com.aicode.feature.agent.domain.permission.PermissionChoice
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Singleton
class ToolPermissionManager @Inject constructor() {
    /**
     * All pending authorization requests, stored in insertion order (key = request id). With multiple sessions in parallel, each session can have one request suspended,
     * without blocking the others -- so the sidebar can light up all sessions awaiting authorization at the same time. Permission requests within one session are handled serially by the workflow,
     * so a session appears at most once in this table.
     */
    private val pending = LinkedHashMap<String, Entry>()
    private val lock = Any()

    /** For dialog display: the single request to show now (the earliest one entered); once resolved, it automatically switches to the next. */
    private val _pendingRequest = MutableStateFlow<PendingToolPermission?>(null)
    val pendingRequest: StateFlow<PendingToolPermission?> = _pendingRequest.asStateFlow()

    /** For the sidebar: the set of session ids currently awaiting authorization. */
    private val _awaitingSessionIds = MutableStateFlow<Set<String>>(emptySet())
    val awaitingSessionIds: StateFlow<Set<String>> = _awaitingSessionIds.asStateFlow()

    /** Suspends waiting for the user's choice in the dialog (deny / this time / always). Different sessions can suspend in parallel without blocking each other. */
    suspend fun awaitApproval(request: PendingToolPermission): PermissionChoice {
        val decision = CompletableDeferred<PermissionChoice>()
        synchronized(lock) {
            pending[request.id] = Entry(request, decision)
            publish()
        }
        try {
            return decision.await()
        } finally {
            synchronized(lock) {
                pending.remove(request.id)
                publish()
            }
        }
    }

    /** The UI passes back the user's choice, waking the suspended request with the matching id. */
    fun resolve(id: String, choice: PermissionChoice) {
        val entry = synchronized(lock) { pending[id] } ?: return
        entry.decision.complete(choice)
    }

    /** Gets a session's current pending request (used when stopAgent stops a single session, to avoid taking another session's dialog by mistake). */
    fun pendingForSession(sessionId: String): PendingToolPermission? =
        synchronized(lock) { pending.values.firstOrNull { it.request.sessionId == sessionId }?.request }

    /** Must be called while holding the lock: projects the internal table to the two public StateFlows. */
    private fun publish() {
        _pendingRequest.value = pending.values.firstOrNull()?.request
        _awaitingSessionIds.value = pending.values.mapNotNull { it.request.sessionId.ifBlank { null } }.toSet()
    }

    private class Entry(
        val request: PendingToolPermission,
        val decision: CompletableDeferred<PermissionChoice>
    )
}
