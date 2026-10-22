package com.aicode.feature.agent.domain.tool.mode

import com.aicode.feature.agent.data.local.dao.ChatSessionDao
import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.model.AgentMode
import com.aicode.feature.agent.domain.tool.AbstractContextualTool
import com.aicode.feature.agent.domain.tool.ParameterType
import com.aicode.feature.agent.domain.tool.PendingToolPermission
import com.aicode.feature.agent.domain.tool.ToolParameter
import com.aicode.feature.agent.domain.tool.ToolCapability
import com.aicode.feature.agent.domain.tool.ToolPermissionPolicy
import com.aicode.feature.agent.domain.tool.ToolResult
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

/**
 * 让 AI 进入或退出 PLAN（计划）模式。退出时恢复到进入 PLAN 之前的模式（见 [AgentContext.modeBeforePlan]）。
 *
 * 退出（action="exit"）不是立即切换：workflow 会挂起并弹出「计划审查面板」，
 * 用户可选择：
 * - 批准并实施：按计划切回原模式执行；
 * - 继续反馈：回到 PLAN 模式，用户随后输入修改意见，AI 修订后再次 exit 重新提交，
 *   循环往复直到用户满意（对齐 Claude Code 的计划审批闭环）。
 */
class PlanModeTool @Inject constructor(
    private val chatSessionDao: ChatSessionDao
) : AbstractContextualTool() {

    override val name = "planMode"
    override val description = "Enter or exit PLAN (plan) mode. action=\"enter\": enter plan mode, switching to read-only exploration to design complex changes; do not write code directly in BUILD mode; action=\"exit\": exit plan mode when the plan is complete and ready to submit to the user for review. On exit, it automatically restores the mode in use before entering PLAN (BUILD if entered from BUILD, ASK if entered from ASK); you do not need to specify the target mode. Exiting opens a plan review panel where the user has two choices: 「Approve and implement」→ execute according to the plan; 「Continue feedback」→ stay in plan mode, the user will give revision comments, you revise and improve it, then call exit again to resubmit, repeating until the user is satisfied. Entering and exiting both require user authorization; mode switching can only be done manually by the user in the UI, and this tool cannot change the mode the user has currently selected."
    override val permissionPolicy = ToolPermissionPolicy.ASK
    override val capabilities = setOf(ToolCapability.MODIFY_SESSION_STATE)

    override val parameters: Map<String, ToolParameter> = mapOf(
        "action" to ToolParameter(
            name = "action",
            type = ParameterType.STRING,
            description = "Operation: 'enter' enters PLAN mode (read-only planning), 'exit' exits PLAN mode (restores the mode in use before entry)",
            required = true,
            enum = listOf("enter", "exit")
        ),
        "reason" to ToolParameter(
            name = "reason",
            type = ParameterType.STRING,
            description = "Reason for entering or exiting PLAN mode, shown to the user",
            required = true
        )
    )

    override suspend fun executeWithContext(
        args: Map<String, JsonElement>,
        context: AgentContext
    ): ToolResult {
        val action = args["action"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
            ?: return ToolResult.Error("Missing required parameter: action", "MISSING_ACTION")

        val reason = args["reason"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: return ToolResult.Error("Missing required parameter: reason", "MISSING_REASON")

        val entering = when (action) {
            "enter" -> true
            "exit" -> false
            else -> return ToolResult.Error("Invalid action: $action, must be 'enter' or 'exit'", "INVALID_ACTION")
        }

        if (entering && context.mode == AgentMode.PLAN) {
            return ToolResult.Success(JsonPrimitive("Already in PLAN mode; no need to enter."))
        }
        if (!entering && context.mode != AgentMode.PLAN) {
            return ToolResult.Error("Not currently in PLAN mode, cannot exit", "NOT_IN_PLAN")
        }

        val sessionId = context.sessionId
            ?: return ToolResult.Error("No associated session ID, cannot switch mode", "NO_SESSION")

        val sessionEntity = chatSessionDao.getById(sessionId)
            ?: return ToolResult.Error("Session record not found", "SESSION_NOT_FOUND")

        // 进入 PLAN 时记下当时的模式，退出时恢复到它：从 ASK/BUILD 进入 PLAN 的规划往返结束后
        // 应回到原模式，而不是一律落回 BUILD。该字段只在处于 PLAN 时被读取，每次进入 PLAN 都会覆写，因此无需在退出时清理。
        val effectiveMode: AgentMode
        val modeBeforePlan: String?
        if (entering) {
            effectiveMode = AgentMode.PLAN
            modeBeforePlan = context.mode.name
        } else {
            effectiveMode = sessionEntity.modeBeforePlan
                ?.let { runCatching { AgentMode.valueOf(it) }.getOrNull() }
                ?: AgentMode.BUILD
            modeBeforePlan = sessionEntity.modeBeforePlan
        }

        // 保存到数据库。UI 层通过 flow 监听，会自动更新外观与后续流程的上下文。
        chatSessionDao.upsert(sessionEntity.copy(mode = effectiveMode.name, modeBeforePlan = modeBeforePlan))

        return ToolResult.Success(
            JsonPrimitive(
                if (entering) "Entered PLAN mode." else "Exited PLAN mode, restored to ${effectiveMode.name} mode."
            )
        )
    }

    override fun buildPermissionRequest(
        callId: String,
        args: Map<String, JsonElement>,
        argsPreview: String
    ): PendingToolPermission {
        val entering = args["action"]?.jsonPrimitive?.contentOrNull?.trim()?.equals("enter", ignoreCase = true) ?: true
        val reason = args["reason"]?.jsonPrimitive?.contentOrNull ?: "No reason"
        val label = if (entering) "entering PLAN mode" else "exiting PLAN mode"

        return PendingToolPermission(
            id = callId,
            toolName = name,
            title = "Plan mode request",
            summary = "AI requests $label",
            details = "Operation: $label\n\nReason: $reason",
            argsPreview = argsPreview,
            rememberablePatterns = emptyList()
        )
    }
}