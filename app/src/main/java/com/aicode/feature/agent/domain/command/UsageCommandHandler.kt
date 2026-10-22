package com.aicode.feature.agent.domain.command

import com.aicode.R
import javax.inject.Inject

/**
 * /usage - views today's and cumulative call counts, token usage and estimated cost (global statistics, across sessions).
 * The result is output as an AI bubble in the form of a Markdown table.
 */
class UsageCommandHandler @Inject constructor() : ActionCommandHandler {
    override val name = "usage"
    override val descriptionRes = R.string.slash_command_usage_desc

    override fun execute(context: SlashCommandContext, args: String) {
        context.showUsage()
    }
}
