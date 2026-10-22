package com.aicode.feature.agent.domain.tool.skill

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.skill.SkillRepository
import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.ParameterType
import com.aicode.feature.agent.domain.tool.ToolParameter
import com.aicode.feature.agent.domain.tool.ToolCapability
import com.aicode.feature.agent.domain.tool.ToolResult
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

/**
 * Lets the AI load a skill's full instruction body on demand.
 *
 * The system prompt only injects each skill's name+description list (the budget may omit some); when the AI judges a skill applicable, it calls this tool to get
 * the SKILL.md body (which may contain execution steps such as "first apk add python3, then python ~/.aicode/skills/<name>/x.py"),
 * and then acts on the body with tools such as read_file/execute_command. The skill directory in the current execution environment is `~/.aicode/skills/<name>/`.
 *
 * The description wording follows the hard requirements of Codex/Claude Code (measured locally: 118 matching skills in the list, and in 239 tool calls
 * loadSkill was 0 -- soft wording such as "use when applicable" is equivalent to no constraint at all):
 * it must load before acting, announce which skill it used and why, and give a reason when skipping an obviously applicable one.
 */
class LoadSkillTool @Inject constructor(
    private val skillRepository: SkillRepository
) : AgentTool() {
    private companion object {
        const val TAG = "LoadSkillTool"
    }

    override val name = "loadSkill"
    override val capabilities = setOf(ToolCapability.READ_AGENT_CONFIG)
    override val description =
        "Load the full instruction body (SKILL.md) of the specified skill. When a task relates to any skill in the \"Available skills\" list, " +
            "you must first call this tool to load that skill and strictly follow its body in this turn before choosing other tools to act; " +
            "before acting, state which skill you used and why; if you skip an obviously applicable skill, explain why first. " +
            "When the user triggers with `/skill-name`, the body is already injected into this turn, so there is no need to load it again."

    override val parameters: Map<String, ToolParameter> = mapOf(
        "skill_name" to ToolParameter(
            name = "skill_name",
            type = ParameterType.STRING,
            description = "Name of the skill to load (must match a name in the \"Available skills\" list in the system prompt).",
            required = true
        )
    )

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        val skillName = args["skill_name"]?.jsonPrimitive?.contentOrNull?.trim()
        if (skillName.isNullOrEmpty()) {
            return ToolResult.Error("Missing required parameter: skill_name", "MISSING_SKILL_NAME")
        }

        val instructions = skillRepository.loadInstructions(skillName)
        if (instructions == null) {
            val available = skillRepository.listSkills().joinToString(", ") { it.name }
            FileLogger.w(TAG, "load_skill not found: $skillName, available: $available")
            return ToolResult.Error(
                "Skill \"$skillName\" not found. Available skills: ${available.ifEmpty { "(none)" }}",
                "SKILL_NOT_FOUND"
            )
        }

        FileLogger.d(TAG, "load_skill loaded successfully: $skillName (${instructions.length} chars)")
        return ToolResult.Success(JsonPrimitive(instructions))
    }
}
