package com.aicode.feature.agent.domain.subagent

import com.aicode.core.util.FileLogger
import com.aicode.feature.workspace.domain.FileAccessProvider
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Subagent definition repository, aggregating the global and project-level sources; for same-named definitions the project level wins (consistent with the skills and MCP two-level config).
 * The enable/disable state is held by [AgentDefinitionConfigRepository]: a disabled definition still appears in the settings-page list,
 * but it does not enter the main agent's dispatchable list and cannot be dispatched via [find].
 *
 * Global definitions `~/.aicode/agents` and project-level definitions `<workspace>/.aicode/agents` are both read and written by container path via [FileAccessProvider],
 * following the current execution environment (local host directory or remote SSH workspace).
 */
@Singleton
class AgentDefinitionRepository @Inject constructor(
    private val globalSource: GlobalDirectoryAgentSource,
    private val projectSource: ProjectDirectoryAgentSource,
    private val configRepository: AgentDefinitionConfigRepository,
    private val fileAccess: FileAccessProvider
) {
    /** All definitions (with their source scope), without disabled filtering, sorted by name. */
    fun listAll(): List<AgentDefinitionEntry> =
        mergeAll(globalSource.listDefinitions(), projectSource.listDefinitions())

    /** The enabled definitions (for the dispatchable list injected into the main agent). */
    fun listEnabled(): List<AgentDefinitionEntry> {
        val disabled = configRepository.disabledNames()
        return listAll().filterNot { it.definition.name.lowercase() in disabled }
    }

    /** Finds a dispatchable definition by name (case-insensitive); returns null when it does not exist or has been disabled. */
    fun find(name: String): AgentDefinition? =
        listEnabled().firstOrNull { it.definition.name.equals(name, ignoreCase = true) }?.definition

    /**
     * Finds a definition by name, including disabled ones. A sub session that has already been dispatched uses it to restore its own prompt and tool set:
     * disabling should only block new dispatches, not swap the configuration of a running subagent mid-flight.
     */
    fun findIncludingDisabled(name: String): AgentDefinition? =
        listAll().firstOrNull { it.definition.name.equals(name, ignoreCase = true) }?.definition

    /** Whether this subagent is disabled in any scope. */
    fun isDisabled(name: String): Boolean = name.lowercase() in configRepository.disabledNames()

    /** Enables/disables a subagent in the given scope. */
    fun setDisabled(name: String, disabled: Boolean, scope: AgentDefinitionScope) =
        configRepository.setDisabled(name, disabled, scope)

    /**
     * Writes a definition file (create or edit). [originalName] is the name before editing, or null when creating;
     * when renamed, it writes the new file and then deletes the old one, which is equivalent to a rename. Returning null means success.
     */
    fun save(
        form: AgentDefinitionForm,
        scope: AgentDefinitionScope,
        originalName: String? = null
    ): AgentSaveError? {
        val name = form.name.trim()
        if (!isValidName(name)) return AgentSaveError.INVALID_NAME
        if (form.prompt.isBlank()) return AgentSaveError.EMPTY_PROMPT

        val overwritingSelf = originalName != null && originalName.equals(name, ignoreCase = true)
        if (!overwritingSelf) {
            val taken = listAll().any {
                it.scope == scope && it.definition.name.equals(name, ignoreCase = true)
            }
            if (taken) return AgentSaveError.NAME_CONFLICT
        }

        val existingFile = originalName?.let { old ->
            listAll().firstOrNull {
                it.scope == scope && it.definition.name.equals(old, ignoreCase = true)
            }?.definition?.filePath
        }

        val provider = fileAccess
        val root = agentsRoot(scope)
        val text = AgentDefinitionParser.serialize(
            name = name,
            description = form.description,
            providerId = form.providerId,
            model = form.model,
            reasoningEffort = form.reasoningEffort,
            mode = form.mode,
            allowedTools = form.allowedTools,
            disallowedTools = form.disallowedTools,
            inject = form.inject,
            prompt = form.prompt
        )

        return try {
            // When the name is unchanged, write back to the original file instead of rebuilding the file name from name: the built-in Explore's file is called explore.md
            // while its frontmatter says Explore, so rebuilding it would create an extra Explore.md on a case-sensitive file system.
            val target = existingFile?.takeIf { overwritingSelf && provider.isFile(it) }
                ?: "${root.trimEnd('/')}/$name.md"
            provider.writeFile(target, text, overwrite = true)
            // After a rename, remove the old file, otherwise an extra old-named subagent with the same content would remain
            if (!overwritingSelf && existingFile != null && existingFile != target && provider.isFile(existingFile)) {
                runCatching { provider.delete(existingFile) }.onFailure {
                    FileLogger.w(TAG, "Failed to delete old definition after rename: $existingFile")
                }
            }
            null
        } catch (e: Exception) {
            FileLogger.e(TAG, "Failed to save subagent definition: $name", e)
            AgentSaveError.IO_FAILED
        }
    }

    /** Deletes the definition file in the given scope, unrecoverable. Returns whether it succeeded. */
    fun delete(name: String, scope: AgentDefinitionScope): Boolean {
        val entry = listAll().firstOrNull {
            it.definition.name.equals(name, ignoreCase = true) && it.scope == scope
        } ?: return false
        val filePath = entry.definition.filePath ?: return false
        val provider = fileAccess
        if (!provider.isFile(filePath)) return false
        return runCatching { provider.delete(filePath); true }.getOrDefault(false)
    }

    /** The definition directory for the given scope (container path). */
    fun agentsRoot(scope: AgentDefinitionScope): String =
        if (scope == AgentDefinitionScope.GLOBAL) globalSource.agentsRoot else projectSource.agentsRoot

    companion object {
        private const val TAG = "AgentDefinitionRepository"

        /** The name doubles as the file name, so path separators and reserved characters are banned; the length cap prevents extreme file names. */
        internal fun isValidName(name: String): Boolean {
            if (name.isBlank() || name.length > MAX_NAME_LENGTH) return false
            if (name == "." || name == "..") return false
            return name.none { it in ILLEGAL_NAME_CHARS || it.isISOControl() }
        }

        private const val MAX_NAME_LENGTH = 40
        private val ILLEGAL_NAME_CHARS = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')

        /** Merges the two source levels: for the same name the project level overrides the global one, sorted by name. */
        internal fun mergeAll(
            global: List<AgentDefinition>,
            project: List<AgentDefinition>
        ): List<AgentDefinitionEntry> {
            val byName = LinkedHashMap<String, AgentDefinitionEntry>()
            global.forEach { byName[it.name.lowercase()] = AgentDefinitionEntry(it, AgentDefinitionScope.GLOBAL) }
            project.forEach { byName[it.name.lowercase()] = AgentDefinitionEntry(it, AgentDefinitionScope.PROJECT) }
            return byName.values.sortedBy { it.definition.name.lowercase() }
        }
    }
}
