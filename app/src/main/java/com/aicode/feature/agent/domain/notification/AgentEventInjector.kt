package com.aicode.feature.agent.domain.notification

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Piggyback-injects pending system events into a tool result. Extracted as an interface so each feature can reuse the same injection point and semantics,
 * without each implementing the details of "stuffing events into the raw result" itself.
 */
interface AgentEventInjector {
    /**
     * @param rawToolResult the raw tool result (usually transport JSON text)
     * @param events the pending system events
     * @return the result text after injection, still parseable downstream
     */
    fun inject(rawToolResult: String, events: List<PendingNotification>): String
}

/**
 * Default implementation: it preferentially injects the events as the top-level `notifications` field of the transport JSON, so the result is still valid JSON,
 * and the UI's formatToolResult (which only reads data/message) and the various structured parsers are unaffected.
 * When raw is no longer valid JSON (e.g. plain text such as a mode-change notice has already been appended to it), it degrades to a text append.
 */
class DefaultAgentEventInjector : AgentEventInjector {

    override fun inject(rawToolResult: String, events: List<PendingNotification>): String {
        if (events.isEmpty()) return rawToolResult
        val obj = runCatching { Json.parseToJsonElement(rawToolResult).jsonObject }.getOrNull()
            ?: return rawToolResult + "\n\n" + AgentNotificationFormatter.buildMessage(events)
        return JsonObject(obj + ("notifications" to AgentNotificationFormatter.buildJsonArray(events))).toString()
    }
}