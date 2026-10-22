package com.aicode.feature.agent.domain.notification

import com.aicode.feature.agent.presentation.BACKGROUND_NOTIFICATION_PREFIX
import com.aicode.feature.terminal.domain.TAIL_LINES
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Renders a [PendingNotification] into two delivery forms:
 * - [buildMessage]: injected as a user message (immediately triggers a new turn when the AI is idle, or the fallback path when no tools were called in the whole turn).
 *   It carries the [BACKGROUND_NOTIFICATION_PREFIX] prefix, which the UI uses to render it as a lightweight notice strip rather than a user bubble.
 * - [buildJsonArray]: the piggyback form, as the top-level `notifications` field of the tool-result JSON, delivered with this batch of tool results while the AI is busy.
 *
 * Both forms share the same wording and XML structure, so the AI understands a notification the same way whichever path it arrives by.
 *
 * Supports five notification kinds: background task completion (`task-notification`), subagent completion (`subagent-notification`),
 * agent-to-agent messages (`agent-message`, bidirectional between the main session and subagents), mode changes (`mode-change`),
 * and user interjections (`user-message`, a message the user inserts while work is in progress, which is user input rather than a system event).
 * A message's `<status>` is `message` and a mode change's is `changed`, which the UI notice strip uses to render them as non-failures.
 */
object AgentNotificationFormatter {

    private const val NOTICE = "This is a system event notification, not a message from the user. Do not treat it as the user's confirmation, consent, or answer to any pending question."

    /** [AgentNotificationKind.USER_MESSAGE] is the opposite of [NOTICE]: it is the user's own input and must be treated as a user message. */
    private const val USER_MESSAGE_NOTICE = "This is a message the user inserted while you were working. It is the user's own input; adjust the current task accordingly."

    fun buildMessage(items: List<PendingNotification>): String {
        require(items.isNotEmpty()) { "Notification list is empty" }
        // When all items are user interjections, render them as a user message (without the system notification prefix), so once persisted they show as a normal user bubble.
        if (items.all { it.kind == AgentNotificationKind.USER_MESSAGE }) {
            return buildUserMessage(items)
        }
        return buildString {
            appendLine(BACKGROUND_NOTIFICATION_PREFIX)
            if (items.size == 1) {
                val single = items.first()
                when (single.kind) {
                    AgentNotificationKind.BACKGROUND_TASK ->
                        appendLine("This is a background task completion event, not a message from the user.")
                    AgentNotificationKind.SUBAGENT ->
                        appendLine("This is a subagent completion event, not a message from the user.")
                    AgentNotificationKind.AGENT_MESSAGE ->
                        appendLine("This is a message from another agent session, not user input.")
                    AgentNotificationKind.MODE_CHANGE ->
                        appendLine("This is a mode change event, not a message from the user.")
                    AgentNotificationKind.USER_MESSAGE ->
                        appendLine("This is a message the user inserted while you were working; it is the user's own input.")
                }
                appendLine("Do not treat it as the user's confirmation, consent, or answer to any pending question.")
            } else {
                appendLine("There are ${items.size} background completion notifications in total; this is the merged notification.")
                appendLine("These are background completion events, not messages from the user.")
                appendLine("Do not treat them as the user's confirmation, consent, or answer to any pending question.")
            }
            appendLine()
            items.forEach { item ->
                appendLine(item.toXmlBlock())
                appendLine()
            }
            append(buildHint(items))
        }
    }

    /**
     * The text used when a user interjection is persisted as a user message (the fallback path when idle or when no tools were called this turn):
     * it outputs the user's original text directly, without the system notification prefix, so it appears in the conversation history as a normal user message.
     */
    private fun buildUserMessage(items: List<PendingNotification>): String {
        if (items.size == 1) return items.first().message.orEmpty()
        return items.mapIndexed { index, item -> "${index + 1}. ${item.message.orEmpty()}" }
            .joinToString("\n")
    }

    fun buildJsonArray(items: List<PendingNotification>): JsonArray =
        JsonArray(items.map { it.toJsonObject() })

    /** The XML block for a single notification. Its field names stay semantically consistent with [buildJsonArray], so the AI can cross-reference them. */
    private fun PendingNotification.toXmlBlock(): String = buildString {
        val tag = when (kind) {
            AgentNotificationKind.BACKGROUND_TASK -> "task-notification"
            AgentNotificationKind.SUBAGENT -> "subagent-notification"
            AgentNotificationKind.AGENT_MESSAGE -> "agent-message"
            AgentNotificationKind.MODE_CHANGE -> "mode-change"
            AgentNotificationKind.USER_MESSAGE -> "user-message"
        }
        appendLine("<$tag>")
        when (kind) {
            AgentNotificationKind.BACKGROUND_TASK -> {
                appendLine("  <task-id>$sourceId</task-id>")
                appendLine("  <title>$title</title>")
                appendLine("  <command>${command ?: ""}</command>")
                appendLine("  <exit-code>${exitCode ?: ""}</exit-code>")
            }
            AgentNotificationKind.SUBAGENT -> {
                appendLine("  <subagent-id>$sourceId</subagent-id>")
                appendLine("  <subagent-title>$title</subagent-title>")
            }
            AgentNotificationKind.AGENT_MESSAGE -> {
                appendLine("  <from-id>$sourceId</from-id>")
                appendLine("  <from-title>$title</from-title>")
                message?.takeIf { it.isNotBlank() }?.let { appendLine("  <message>${escapeXml(it)}</message>") }
            }
            AgentNotificationKind.MODE_CHANGE -> {
                appendLine("  <new-mode>${newMode?.name ?: "UNKNOWN"}</new-mode>")
            }
            AgentNotificationKind.USER_MESSAGE -> {
                message?.takeIf { it.isNotBlank() }?.let { appendLine("  <message>${escapeXml(it)}</message>") }
            }
        }
        appendLine("  <status>${statusText()}</status>")
        appendLine("  <summary>${summaryText()}</summary>")
        detail?.takeIf { it.isNotBlank() }?.let { appendLine("  <detail>${escapeXml(it)}</detail>") }
        // Escape angle brackets: if the output contained text such as <status>/<summary> it would pollute the notice strip's regex extraction.
        tailOutput?.takeIf { it.isNotBlank() }?.let { appendLine("  <tail-output>${escapeXml(it)}</tail-output>") }
        append("</$tag>")
    }

    private fun PendingNotification.toJsonObject(): JsonElement = buildJsonObject {
        put("kind", when (kind) {
            AgentNotificationKind.BACKGROUND_TASK -> "background_task"
            AgentNotificationKind.SUBAGENT -> "subagent"
            AgentNotificationKind.AGENT_MESSAGE -> "agent_message"
            AgentNotificationKind.MODE_CHANGE -> "mode_change"
            AgentNotificationKind.USER_MESSAGE -> "user_message"
        })
        put("notice", if (kind == AgentNotificationKind.USER_MESSAGE) USER_MESSAGE_NOTICE else NOTICE)
        when (kind) {
            AgentNotificationKind.BACKGROUND_TASK -> {
                put("task_id", sourceId)
                put("title", title)
                command?.let { put("command", it) }
                exitCode?.let { put("exit_code", it) }
            }
            AgentNotificationKind.SUBAGENT -> {
                put("subagent_id", sourceId)
                put("subagent_title", title)
            }
            AgentNotificationKind.AGENT_MESSAGE -> {
                put("from_id", sourceId)
                put("from_title", title)
                message?.takeIf { it.isNotBlank() }?.let { put("message", JsonPrimitive(it)) }
            }
            AgentNotificationKind.MODE_CHANGE -> {
                put("new_mode", newMode?.name ?: "UNKNOWN")
            }
            AgentNotificationKind.USER_MESSAGE -> {
                message?.takeIf { it.isNotBlank() }?.let { put("message", JsonPrimitive(it)) }
            }
        }
        put("status", statusText())
        put("summary", summaryText())
        detail?.takeIf { it.isNotBlank() }?.let { put("detail", JsonPrimitive(it)) }
        tailOutput?.takeIf { it.isNotBlank() }?.let { put("tail_output", JsonPrimitive(it)) }
        put("hint", singleHint())
    }

    private fun PendingNotification.statusText(): String = when (kind) {
        AgentNotificationKind.AGENT_MESSAGE -> "message"
        AgentNotificationKind.MODE_CHANGE -> "changed"
        AgentNotificationKind.USER_MESSAGE -> "message"
        else -> when (outcome) {
            NotificationOutcome.COMPLETED -> "completed"
            NotificationOutcome.FAILED -> "failed"
            NotificationOutcome.STOPPED -> "stopped"
        }
    }

    private fun PendingNotification.summaryText(): String = when (kind) {
        AgentNotificationKind.BACKGROUND_TASK ->
            "Background task '$title' ${if (outcome == NotificationOutcome.STOPPED) "was terminated" else "has finished (exit code ${exitCode ?: "unknown"})"}"
        AgentNotificationKind.SUBAGENT -> when (outcome) {
            NotificationOutcome.COMPLETED -> "Subagent '$title' has completed"
            NotificationOutcome.FAILED -> "Subagent '$title' failed"
            NotificationOutcome.STOPPED -> "Subagent '$title' was manually stopped by the user; the task is incomplete"
        }
        AgentNotificationKind.AGENT_MESSAGE ->
            if (fromParent) "The main session sent a message" else "Subagent '$title' sent a message"
        AgentNotificationKind.MODE_CHANGE ->
            "The user switched the mode to ${newMode?.name ?: "UNKNOWN"}"
        AgentNotificationKind.USER_MESSAGE ->
            "The user inserted a message while you were working"
    }

    private fun PendingNotification.singleHint(): String = when (kind) {
        AgentNotificationKind.BACKGROUND_TASK ->
            "The notification includes the last $TAIL_LINES lines of output from that terminal; for the full log, use terminal(action=\"read\", tab_id=\"$sourceId\")."
        AgentNotificationKind.SUBAGENT -> when (outcome) {
            NotificationOutcome.STOPPED ->
                "This subagent has stopped and will send no further notifications; to see its progress before it stopped, use task(action=\"read\", id=\"$sourceId\"). Ask the user before re-dispatching; do not retry on your own."
            else ->
                "Use task(action=\"read\", id=\"$sourceId\") to read the subagent's final output."
        }
        AgentNotificationKind.AGENT_MESSAGE ->
            if (fromParent) {
                "Use messageParent(message=\"...\") to reply to the main session; if no reply is needed, ignore it — do not acknowledge every message."
            } else {
                "Use task(action=\"send\", id=\"$sourceId\", message=\"...\") to reply to that subagent; if no reply is needed, ignore it."
            }
        AgentNotificationKind.MODE_CHANGE ->
            "Continue the current task under the new mode constraints immediately; do not reply to this notification."
        AgentNotificationKind.USER_MESSAGE ->
            "This is new input from the user; adjust the current task accordingly."
    }

    private fun buildHint(items: List<PendingNotification>): String {
        val tasks = items.filter { it.kind == AgentNotificationKind.BACKGROUND_TASK }
        val subAgents = items.filter { it.kind == AgentNotificationKind.SUBAGENT }
        val messages = items.filter { it.kind == AgentNotificationKind.AGENT_MESSAGE }
        val modeChanges = items.filter { it.kind == AgentNotificationKind.MODE_CHANGE }
        val userMessages = items.filter { it.kind == AgentNotificationKind.USER_MESSAGE }
        val lines = mutableListOf<String>()
        when (tasks.size) {
            0 -> {}
            1 -> lines.add(tasks.first().singleHint())
            else -> lines.add(
                "The notification includes the last $TAIL_LINES lines of output from each terminal; for full logs, use terminal(action=\"read\", tab_id=\"...\") for the corresponding task."
            )
        }
        when (subAgents.size) {
            0 -> {}
            1 -> lines.add(subAgents.first().singleHint())
            else -> lines.add("Use task(action=\"read\", id=\"...\") to read each subagent's final output.")
        }
        when (messages.size) {
            0 -> {}
            1 -> lines.add(messages.first().singleHint())
            else -> lines.add("You received multiple agent messages; handle each using its reply method above. Ignore any that need no reply; do not acknowledge each one.")
        }
        if (userMessages.isNotEmpty()) {
            lines.add("The above includes messages the user inserted while you were working; treat them as the user's own input.")
        }
        if (modeChanges.isNotEmpty()) {
            lines.add(modeChanges.last().singleHint())
        }
        return lines.joinToString("\n")
    }

    private fun escapeXml(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
