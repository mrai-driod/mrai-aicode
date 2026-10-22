package com.aicode.feature.agent.domain.subagent

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Subagent event types. The first four are lifecycle events; the last two are message send/receive events, which only deliver content and do not affect the active set.
 */
enum class SubAgentEventType {
    SPAWNED, COMPLETED, FAILED, STOPPED,

    /** The main session sent a message to a subagent (the sender is the parent session). */
    MESSAGE_FROM_PARENT,

    /** A subagent sent a message to the main session (the sender is the sub session). */
    MESSAGE_FROM_SUB
}

/**
 * A subagent lifecycle event.
 *
 * @property subSessionId the subagent session id.
 * @property parentSessionId the parent session id (parentId in the sub-session record).
 * @property type the event type.
 * @property detail additional detail: for SPAWNED it is the task instruction; for COMPLETED/FAILED it is the subagent's final output / error message;
 *   for MESSAGE_FROM_PARENT / MESSAGE_FROM_SUB it is the message body.
 */
data class SubAgentEvent(
    val subSessionId: String,
    val parentSessionId: String,
    val type: SubAgentEventType,
    val detail: String = ""
)

/**
 * Subagent event bus: TaskTool emits SPAWNED (the subagent has been created), and after the ViewModel collects it, it automatically
 * starts the AI workflow on the sub session; when the sub session's workflow ends, the ViewModel emits COMPLETED/FAILED,
 * and the parent session injects a background notification from that (analogous to the terminal's notify=true async callback).
 *
 * It also maintains the set of active subagent session ids, for TaskTool to query the concurrency limit (at most 5 running).
 */
@Singleton
class SubAgentEventBus @Inject constructor() {
    private val _events = MutableSharedFlow<SubAgentEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<SubAgentEvent> = _events.asSharedFlow()

    /** The set of currently active (running) subagent session ids. */
    private val _activeSubSessionIds = MutableStateFlow<Set<String>>(emptySet())
    val activeSubSessionIds: StateFlow<Set<String>> = _activeSubSessionIds.asStateFlow()

    /** The number of running subagents. */
    val activeCount: Int get() = _activeSubSessionIds.value.size

    /** Whether the concurrency limit has been reached. */
    val isFull: Boolean get() = activeCount >= MAX_RUNNING

    /**
     * Removes a subagent directly from the active set without broadcasting an event; returns whether it was active before.
     *
     * This is the path taken when the user manually stops or deletes a running sub session in the UI: it must not go through [emit],
     * because after the ViewModel receives a STOPPED event it would call stopAgentSession back, forming an infinite loop.
     * The return value also distinguishes "manually terminated by the user" from "already handled by TaskTool" (the latter returns false),
     * avoiding duplicate notification delivery to the parent agent.
     */
    fun release(subSessionId: String): Boolean {
        val current = _activeSubSessionIds.value
        if (subSessionId !in current) return false
        _activeSubSessionIds.value = current - subSessionId
        return true
    }

    fun emit(event: SubAgentEvent) {
        // Maintain the active set synchronously
        when (event.type) {
            SubAgentEventType.SPAWNED -> {
                _activeSubSessionIds.value = _activeSubSessionIds.value + event.subSessionId
            }
            SubAgentEventType.COMPLETED, SubAgentEventType.FAILED, SubAgentEventType.STOPPED -> {
                _activeSubSessionIds.value = _activeSubSessionIds.value - event.subSessionId
            }
            SubAgentEventType.MESSAGE_FROM_PARENT, SubAgentEventType.MESSAGE_FROM_SUB -> {
                // Message send/receive does not change the running state, so the active set stays unchanged.
            }
        }
        _events.tryEmit(event)
    }

    companion object {
        /** Cap on subagents running at the same time. This is the authoritative value for the check; the tool layer only references it and does not keep its own copy. */
        const val MAX_RUNNING = 5
    }
}