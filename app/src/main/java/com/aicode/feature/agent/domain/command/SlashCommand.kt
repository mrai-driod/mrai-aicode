package com.aicode.feature.agent.domain.command

import androidx.annotation.StringRes
import com.aicode.feature.agent.domain.skill.Skill

/** The argument placeholder in the body of a prompt-style command. */
internal const val ARGUMENTS_PLACEHOLDER = "\$ARGUMENTS"

/**
 * Replaces `$ARGUMENTS` in the template with [args]; when the template has no placeholder and [args] is non-empty,
 * appends a line so the user input is not lost.
 */
internal fun substituteArguments(template: String, args: String): String = when {
    template.contains(ARGUMENTS_PLACEHOLDER) -> template.replace(ARGUMENTS_PLACEHOLDER, args)
    args.isBlank() -> template
    else -> "$template\n\nAdditional user input: $args"
}

/** Source of a slash command: a built-in command (local action) or a skill (injects the SKILL.md body into this turn). */
enum class SlashCommandKind { ACTION, SKILL }

/**
 * Display model for the `/` menu in the input box, representing built-in commands and skills uniformly.
 *
 * @param name The command name, without the leading `/` (e.g. `status`, `pdf-report`).
 * @param description The resolved menu description (for built-in commands, taken from the string resource; for skills, from their `description`).
 * @param kind The source type, so the menu can distinguish them when displaying.
 * @param acceptsArgs Whether arguments are accepted; when true, clicking in the menu appends a space so arguments can continue to be typed.
 */
data class SlashCommand(
    val name: String,
    val description: String,
    val kind: SlashCommandKind,
    val acceptsArgs: Boolean = false
)

/**
 * Handler for a built-in slash command (local-action type). Each command implements one of these,
 * collected into [SlashCommandRegistry] via Hilt `@Binds @IntoSet`.
 *
 * To add a command, just: create an implementation class + add the corresponding method to [SlashCommandContext] + implement it in the ViewModel +
 * add one binding line in [SlashCommandModule].
 */
interface ActionCommandHandler {

    /** The command name, without the leading `/`. */
    val name: String

    /** Menu description, from a bilingual string resource. */
    @get:StringRes
    val descriptionRes: Int

    /** Whether arguments are accepted. By default they are not: with extra arguments it does not match (falls back to a normal message, without swallowing the user input). */
    val acceptsArgs: Boolean get() = false

    /** Executed after a match. [args] is the raw text after the command name (already trimmed). */
    fun execute(context: SlashCommandContext, args: String)
}

/**
 * Command execution context: exposes only the minimum capabilities commands need, avoiding commands holding the ViewModel directly.
 * Implemented by AIAgentViewModel; when adding a command, add the method to this interface and implement it in the ViewModel.
 */
interface SlashCommandContext {
    fun compactCurrentSession()

    /** /usage - outputs today's and cumulative call counts, token usage and estimated cost as a Markdown table. */
    fun showUsage()

    /** /init - triggers one agent turn, analyzing the codebase and generating/improving the project rules file; [prompt] is the instruction body. */
    fun initProject(prompt: String)

    /** Skill trigger: executes the [skill] body as this turn's instruction, with [args] as additional arguments. */
    fun runSkill(skill: Skill, args: String)
}
