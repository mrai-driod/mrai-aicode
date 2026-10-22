package com.aicode.feature.agent.domain.notification

import com.aicode.feature.agent.domain.model.AgentMode
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/** The source type of a pending notification. */
enum class AgentNotificationKind { BACKGROUND_TASK, SUBAGENT, AGENT_MESSAGE, MODE_CHANGE, USER_MESSAGE }

/**
 * How an async task ended. [STOPPED] and [FAILED] must be distinguished: being manually terminated by the user is not an execution error,
 * and conflating them would make the AI treat a human interruption as a fault to investigate or retry.
 */
enum class NotificationOutcome { COMPLETED, FAILED, STOPPED }

/**
 * One async completion notification pending delivery to the AI (background terminal command / subagent).
 *
 * @property sourceId the terminal tabId or subagent session id; the AI uses it to call terminal(read) / task(read) for the full result.
 * @property detail supplementary end reason: the error message when a subagent fails, or the explanation when it was terminated; null on normal completion.
 * @property message the message body for [AgentNotificationKind.AGENT_MESSAGE] / [AgentNotificationKind.USER_MESSAGE].
 * @property fromParent the direction of [AgentNotificationKind.AGENT_MESSAGE]: true means the sender is the main session (the recipient is a subagent),
 *   false means the sender is a subagent (the recipient is the main session). Used by the Formatter to generate the corresponding reply hint.
 * @property newMode the target mode of [AgentNotificationKind.MODE_CHANGE]: the new mode after the user switched during work.
 * @property seq the monotonic sequence number assigned by [AgentNotificationCenter], for a precise ack after peek; 0 when not yet enqueued.
 */
data class PendingNotification(
    val kind: AgentNotificationKind,
    val sourceId: String,
    val title: String,
    val outcome: NotificationOutcome,
    val command: String? = null,
    val exitCode: Int? = null,
    val tailOutput: String? = null,
    val detail: String? = null,
    val message: String? = null,
    val fromParent: Boolean = false,
    val newMode: AgentMode? = null,
    val seq: Long = 0
)

/**
 * Session-level pending notification queue: background task and subagent completion events are enqueued first while the AI is busy, and delivered by one of two paths.
 *
 * - **Piggyback** (preferred): after each batch of tool executions, the [StatefulAgentWorkflow][com.aicode.feature.agent.domain.workflow.StatefulAgentWorkflow]
 *   peeks the notifications and injects them into the tool results, delivering them immediately within this turn and saving one "wait for this turn to end, then start another" LLM round trip.
 * - **Fallback**: when the AI calls no tools in an entire turn, the ViewModel calls [drain] in this turn's finally and triggers a new turn with them as a system notification message.
 *
 * Enqueueing comes from the main thread (the ViewModel receiving Flow events) and peek/ack come from the IO coroutine of tool execution, so all operations are locked.
 * [peek] and [ack] are two separate steps rather than a direct take: a notification only counts as delivered once the tool result has truly been persisted (ToolCallFinished has been emitted);
 * if cancelled midway, the notification stays in the queue for the next batch of tools or the fallback path.
 */
@Singleton
class AgentNotificationCenter @Inject constructor() {

    private val lock = Any()
    private val pending = mutableMapOf<String, MutableList<PendingNotification>>()
    private val seqGenerator = AtomicLong(0)

    /** Enqueues a notification and returns its assigned sequence number. */
    fun enqueue(sessionId: String, item: PendingNotification): Long {
        val seq = seqGenerator.incrementAndGet()
        synchronized(lock) {
            pending.getOrPut(sessionId) { mutableListOf() }.add(item.copy(seq = seq))
        }
        return seq
    }

    /** Reads all currently pending notifications for this session (without removing them). */
    fun peek(sessionId: String): List<PendingNotification> = synchronized(lock) {
        pending[sessionId]?.toList() ?: emptyList()
    }

    /** Confirms delivery: removes by sequence number; entries enqueued after the peek are unaffected. */
    fun ack(sessionId: String, seqs: Collection<Long>) {
        if (seqs.isEmpty()) return
        synchronized(lock) {
            val list = pending[sessionId] ?: return
            list.removeAll { it.seq in seqs }
            if (list.isEmpty()) pending.remove(sessionId)
        }
    }

    /** Atomically takes all pending notifications for this session. */
    fun drain(sessionId: String): List<PendingNotification> = synchronized(lock) {
        pending.remove(sessionId) ?: emptyList()
    }

    fun pendingCount(sessionId: String): Int = synchronized(lock) {
        pending[sessionId]?.size ?: 0
    }

    fun clear(sessionId: String) {
        synchronized(lock) { pending.remove(sessionId) }
    }

    fun clearAll() {
        synchronized(lock) { pending.clear() }
    }
}
