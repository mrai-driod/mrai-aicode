package com.aicode.feature.agent.domain.command

import android.content.Context
import com.aicode.feature.agent.domain.skill.Skill
import com.aicode.feature.agent.domain.skill.SkillRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Slash command registry: aggregates built-in commands ([ActionCommandHandler]) and enabled skills,
 * providing them uniformly for `/` menu display and for parsing at send time.
 *
 * - Menu: [commands] is the merged [SlashCommand] list - built-in commands take priority, skills with the same name are skipped, sorted by name.
 * - Parsing: [resolve] splits `/name args` into a command name and arguments, matching a built-in command or a skill; purely in-memory, no IO.
 * - Refresh: [refresh] rescans skills (file IO); the caller is responsible for when to trigger it.
 */
@Singleton
class SlashCommandRegistry @Inject constructor(
    actionHandlers: Set<@JvmSuppressWildcards ActionCommandHandler>,
    private val skillRepository: SkillRepository,
    @param:ApplicationContext private val context: Context
) {
    private val actions: List<ActionCommandHandler> = actionHandlers.sortedBy { it.name.lowercase() }
    private val actionsByName: Map<String, ActionCommandHandler> =
        actions.associateBy { it.name.lowercase() }

    /** Skill snapshot: updated by [refresh] on an IO thread, read-only for [resolve]. */
    @Volatile
    private var skills: List<Skill> = emptyList()

    private val _commands = MutableStateFlow<List<SlashCommand>>(emptyList())

    /** The command list shown in the `/` menu. */
    val commands: StateFlow<List<SlashCommand>> = _commands.asStateFlow()

    /** Rescans skills and rebuilds the menu. Involves file IO, so callers should call it in an IO context. */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        skills = skillRepository.listSkills()
        _commands.value = buildCommands()
    }

    private fun buildCommands(): List<SlashCommand> = buildList {
        actions.forEach {
            add(
                SlashCommand(
                    name = it.name,
                    description = context.getString(it.descriptionRes),
                    kind = SlashCommandKind.ACTION,
                    acceptsArgs = it.acceptsArgs
                )
            )
        }
        skills.filterNot { it.name.lowercase() in actionsByName }
            .sortedBy { it.name.lowercase() }
            .forEach {
            add(
                SlashCommand(
                    name = it.name,
                    description = it.description,
                    kind = SlashCommandKind.SKILL,
                    acceptsArgs = true
                )
            )
        }
    }

    /**
     * Parses the text being sent: returns non-null only when the input starts with `/` and matches a built-in command or a skill.
     * When a built-in command does not accept arguments, having arguments is treated as no match (falls back to a normal message).
     */
    fun resolve(input: String): ResolvedCommand? {
        val trimmed = input.trim()
        if (!trimmed.startsWith('/')) return null
        val body = trimmed.substring(1)
        val name = body.substringBefore(' ').trim()
        if (name.isEmpty()) return null
        val args = body.substringAfter(' ', "").trim()

        actionsByName[name.lowercase()]?.let { handler ->
            if (!handler.acceptsArgs && args.isNotEmpty()) return null
            return ResolvedCommand.Action(handler, args)
        }
        val skill = skills.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: return null
        return ResolvedCommand.SkillCommand(skill, args)
    }

    /** Parse result: a built-in action or a skill. */
    sealed interface ResolvedCommand {
        val name: String
        val args: String

        data class Action(val handler: ActionCommandHandler, override val args: String) : ResolvedCommand {
            override val name: String get() = handler.name
        }

        data class SkillCommand(val skill: Skill, override val args: String) : ResolvedCommand {
            override val name: String get() = skill.name
        }
    }

    companion object {
        /**
         * Builds this turn's instruction for a skill: replaces `$ARGUMENTS` in the skill body with [args];
         * when there is no placeholder and [args] is non-empty, appends a line to ensure the user input is not lost.
         */
        fun buildSkillPrompt(skill: Skill, args: String): String =
            substituteArguments(skill.instructions, args)
    }
}
