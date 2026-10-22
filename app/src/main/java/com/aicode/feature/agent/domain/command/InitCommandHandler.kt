package com.aicode.feature.agent.domain.command

import com.aicode.R
import com.aicode.feature.agent.domain.prompt.SystemPromptProvider
import javax.inject.Inject

/**
 * /init - triggers one agent turn, analyzing the current workspace codebase and generating/improving the project rules file
 * (`AGENTS.md`; when only `CLAUDE.md` exists, improves that).
 *
 * The instruction body is stored in `prompts/agent/init.md`, and can be overridden by `~/.aicode/prompts.custom/agent/init.md`.
 */
class InitCommandHandler @Inject constructor(
    private val systemPromptProvider: SystemPromptProvider
) : ActionCommandHandler {
    override val name = "init"
    override val descriptionRes = R.string.slash_command_init_desc
    override val acceptsArgs = true

    override fun execute(context: SlashCommandContext, args: String) {
        context.initProject(substituteArguments(systemPromptProvider.resolvePrompt(PROMPT_NAME), args))
    }

    companion object {
        /** Name of the instruction-body fragment, matching the path under `prompts/`. */
        const val PROMPT_NAME = "agent/init.md"
    }
}
