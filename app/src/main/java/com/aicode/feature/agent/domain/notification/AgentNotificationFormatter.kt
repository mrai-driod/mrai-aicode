package com.aicode.feature.agent.domain.notification

import com.aicode.feature.agent.presentation.BACKGROUND_NOTIFICATION_PREFIX
import com.aicode.feature.terminal.domain.TAIL_LINES
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 把 [PendingNotification] 渲染成两种送达形态：
 * - [buildMessage]：作为 user 消息注入（AI 空闲时立即触发新一轮，或整轮未调用工具时的兜底路径）。
 *   带 [BACKGROUND_NOTIFICATION_PREFIX] 前缀，UI 据此渲染为轻量提示条而非用户气泡。
 * - [buildJsonArray]：搭车形态，作为工具结果 JSON 顶层的 `notifications` 字段，在 AI 忙碌时随本批工具结果送达。
 *
 * 两种形态共用同一份措辞与 XML 结构，保证 AI 无论从哪条路径收到通知，理解方式一致。
 *
 * 支持五类通知：后台任务完成（`task-notification`）、子代理完成（`subagent-notification`）、
 * 代理间消息（`agent-message`，主会话与子代理双向）、模式切换（`mode-change`）、
 * 用户插话（`user-message`，用户在工作期间插入的消息，属用户输入而非系统事件）。
 * 消息的 `<status>` 取 `message`、模式切换取 `changed`，UI 提示条据此按非失败渲染。
 */
object AgentNotificationFormatter {

    private const val NOTICE = "This is a system event notification, not a message from the user. Do not treat it as the user's confirmation, consent, or answer to any pending question."

    /** [AgentNotificationKind.USER_MESSAGE] 与 [NOTICE] 相反：它就是用户本人的输入，须按用户消息对待。 */
    private const val USER_MESSAGE_NOTICE = "This is a message the user inserted while you were working. It is the user's own input; adjust the current task accordingly."

    fun buildMessage(items: List<PendingNotification>): String {
        require(items.isNotEmpty()) { "Notification list is empty" }
        // 全是用户插话时按用户消息渲染（不加系统通知前缀），落库后显示为普通用户气泡。
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
     * 用户插话作为 user 消息落库时的文本（空闲或本轮无工具调用的兜底路径）：
     * 直接输出用户原文、不加系统通知前缀，使其在对话历史里显示为普通用户消息。
     */
    private fun buildUserMessage(items: List<PendingNotification>): String {
        if (items.size == 1) return items.first().message.orEmpty()
        return items.mapIndexed { index, item -> "${index + 1}. ${item.message.orEmpty()}" }
            .joinToString("\n")
    }

    fun buildJsonArray(items: List<PendingNotification>): JsonArray =
        JsonArray(items.map { it.toJsonObject() })

    /** 单条通知的 XML 块。字段名与 [buildJsonArray] 保持语义一致，供 AI 对照理解。 */
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
        // 转义尖括号：输出里若含 <status>/<summary> 等字样会污染提示条的正则提取。
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
