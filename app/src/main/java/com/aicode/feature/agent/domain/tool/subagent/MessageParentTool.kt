package com.aicode.feature.agent.domain.tool.subagent

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.session.SessionUseCase
import com.aicode.feature.agent.domain.subagent.SubAgentEvent
import com.aicode.feature.agent.domain.subagent.SubAgentEventBus
import com.aicode.feature.agent.domain.subagent.SubAgentEventType
import com.aicode.feature.agent.domain.tool.AbstractContextualTool
import com.aicode.feature.agent.domain.tool.ParameterType
import com.aicode.feature.agent.domain.tool.ToolParameter
import com.aicode.feature.agent.domain.tool.ToolResult
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import javax.inject.Inject

/**
 * 子代理 → 主会话的单向消息工具 `messageParent`。
 *
 * 异步不阻塞：调用即返回，消息由 ViewModel 按主会话状态分发（忙碌时搭车送达，空闲时触发新一轮）。
 * 只在子代理会话中可用——主会话的工具集剔除了它（见 [com.aicode.feature.agent.presentation.AIAgentViewModel]）。
 */
class MessageParentTool @Inject constructor(
    private val sessionUseCase: SessionUseCase,
    private val eventBus: SubAgentEventBus
) : AbstractContextualTool() {

    private companion object {
        const val TAG = "MessageParentTool"
        const val MESSAGE_MAX = 4000
    }

    override val name = "messageParent"

    override val description = "Send a message to the parent session (report progress, ask for help, or raise items needing coordination or a decision); asynchronous and non-blocking, replies are delivered as follow-up messages."

    override val parameters: Map<String, ToolParameter> = mapOf(
        "message" to ToolParameter(
            name = "message",
            type = ParameterType.STRING,
            description = "Body of the message to the parent session (progress, questions, items needing coordination or a decision)",
            required = true
        )
    )

    override suspend fun executeWithContext(args: Map<String, JsonElement>, context: AgentContext): ToolResult {
        val sessionId = context.sessionId ?: return ToolResult.Error("Missing session context", "NO_SESSION")
        val parentSessionId = sessionUseCase.getSessionById(sessionId)?.parentId
            ?: return ToolResult.Error("Current session is not a subagent; cannot send a message to the parent session", "NO_PARENT")
        val message = (args["message"] as? JsonPrimitive)?.contentOrNull?.trim()
        if (message.isNullOrBlank()) {
            return ToolResult.Error("Invalid parameter: message must not be empty", "INVALID_ARGS")
        }
        if (message.length > MESSAGE_MAX) {
            return ToolResult.Error("Message too long (limit: $MESSAGE_MAX characters)", "INVALID_ARGS")
        }

        eventBus.emit(
            SubAgentEvent(
                subSessionId = sessionId,
                parentSessionId = parentSessionId,
                type = SubAgentEventType.MESSAGE_FROM_SUB,
                detail = message
            )
        )
        FileLogger.i(TAG, "子代理向主会话发消息: session=$sessionId parent=$parentSessionId")
        return ToolResult.Success(
            buildJsonObject {
                put("state", "sent")
                put("message", "Message sent to the parent session. You can continue your current work; the parent session's reply will arrive later.")
            }
        )
    }
}
