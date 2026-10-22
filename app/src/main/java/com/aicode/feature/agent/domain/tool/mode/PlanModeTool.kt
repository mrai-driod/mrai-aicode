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
 * Lets the AI enter or exit PLAN (plan) mode. On exit it restores the mode in use before entering PLAN (see [AgentContext.modeBeforePlan]).
 *
 * Exiting (action="exit") does not switch immediately: the workflow suspends and pops up a "plan review panel",
 * where the user can choose:
 * - Approve and implement: switch back to the original mode according to the plan and execute;
 * - Continue feedback: return to PLAN mode; the user then enters revision comments, the AI revises and calls exit again to resubmit,
 *   repeating until the user is satisfied (aligned with Claude Code's plan approval loop).
 */
class PlanModeTool @Inject constructor(
    private val chatSessionDao: ChatSessionDao
) : AbstractContextualTool() {

    override val name = "planMode"
    override val description = "Enter or exit PLAN (plan) mode. action=\"enter\": enter plan mode, switching to read-only exploration to design complex changes; do not write code directly in BUILD mode; action=\"exit\": exit plan mode when the plan is complete and ready to submit to the user for review. On exit, it automatically restores the mode in use before entering PLAN (BUILD if entered from BUILD, ASK if entered from ASK); you do not need to specify the target mode. Exiting opens a plan review panel where the user has two choices: 'Approve and implement': execute according to the plan; 'Continue feedback': stay in plan mode, the user will give revision comments, you revise and improve it, then call exit again to resubmit, repeating until the user is satisfied. Entering and exiting both require user authorization; mode switching can only be done manually by the user in the UI, and this tool cannot change the mode the user has currently selected."
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

        // When entering PLAN, record the mode at that time; on exit, restore to it: after a planning round trip entered from ASK/BUILD,
        // it should return to the original mode rather than always falling back to BUILD. This field is only read while in PLAN, and every entry into PLAN overwrites it, so there is no need to clear it on exit.
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

        // Save to the database. The UI layer listens via a flow and automatically updates the appearance and the context of subsequent flows.
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
