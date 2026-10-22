package com.aicode.feature.agent.domain.skill

import com.aicode.core.util.FileLogger
import com.aicode.feature.workspace.domain.FileAccessProvider
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Skill repository, aggregating the skills provided by each [SkillSource] (global directory + project directory),
 * and filtering the injected list by [SkillConfigRepository]'s disabled list.
 *
 * Global skills `~/.aicode/skills` and project-level skills `<workspace>/.aicode/skills` are both read and written by container path via [FileAccessProvider],
 * following the current execution environment (local host directory or remote SSH workspace).
 */
@Singleton
class SkillRepository @Inject constructor(
    private val globalDirectorySkillSource: GlobalDirectorySkillSource,
    private val projectDirectorySkillSource: ProjectDirectorySkillSource,
    private val skillConfigRepository: SkillConfigRepository,
    private val fileAccess: FileAccessProvider
) {
    /** All skills (with their source scope), without disabled filtering; for same-named skills the project level wins (consistent with the two-level MCP config). */
    fun listAllSkills(): List<SkillEntry> =
        mergeAll(globalDirectorySkillSource.listSkills(), projectDirectorySkillSource.listSkills())

    /** The list of enabled skills (for injection into the system prompt); disabled skills are filtered out. */
    fun listSkills(): List<Skill> =
        filterDisabled(listAllSkills(), skillConfigRepository.disabledNames()).map { it.skill }

    /** Reads the full instruction body of the given skill; returns null when it does not exist / fails to parse / has been disabled. */
    fun loadInstructions(name: String): String? {
        if (name.lowercase() in skillConfigRepository.disabledNames()) return null
        return projectDirectorySkillSource.loadInstructions(name)
            ?: globalDirectorySkillSource.loadInstructions(name)
    }

    /** Whether the skill is disabled in any scope. */
    fun isSkillDisabled(name: String): Boolean =
        name.lowercase() in skillConfigRepository.disabledNames()

    /** Enables/disables a skill in the given scope. */
    fun setSkillDisabled(name: String, disabled: Boolean, scope: SkillScope) =
        skillConfigRepository.setDisabled(name, disabled, scope)

    /**
     * Writes a skill file (create or edit). [originalName] is the name before editing, or null when creating; returning null means success.
     *
     * When editing, it writes back to the original instruction file in the original directory (which may be named CLAUDE.md); renaming changes only the frontmatter name, not the directory name:
     * skill bodies often reference scripts in the same directory as `~/.aicode/skills/<directory>/run.py`, and renaming along with them would break those references.
     */
    fun save(form: SkillForm, scope: SkillScope, originalName: String? = null): SkillSaveError? {
        val name = form.name.trim()
        if (!isValidName(name)) return SkillSaveError.INVALID_NAME
        if (form.instructions.isBlank()) return SkillSaveError.EMPTY_INSTRUCTIONS

        val entries = listAllSkills()
        val keepingName = originalName != null && originalName.equals(name, ignoreCase = true)
        if (!keepingName) {
            val taken = entries.any { it.scope == scope && it.skill.name.equals(name, ignoreCase = true) }
            if (taken) return SkillSaveError.NAME_CONFLICT
        }

        val existingDir = originalName?.let { old ->
            entries.firstOrNull {
                it.scope == scope && it.skill.name.equals(old, ignoreCase = true)
            }?.skill?.dirPath
        }

        val text = SkillParser.serialize(
            name = name,
            description = form.description,
            requiredTools = form.requiredTools,
            instructions = form.instructions
        )

        val provider = fileAccess
        return try {
            val dir = existingDir?.takeIf { provider.isDirectory(it) } ?: "${skillsRoot(scope)}/$name"
            provider.mkdirs(dir)
            val target = instructionFile(provider, dir) ?: "${dir.trimEnd('/')}/$INSTRUCTION_FILE"
            provider.writeFile(target, text, overwrite = true)
            null
        } catch (e: Exception) {
            FileLogger.e(TAG, "Failed to save skill: $name", e)
            SkillSaveError.IO_FAILED
        }
    }

    /** The skill root directory for the given scope (container path). */
    fun skillsRoot(scope: SkillScope): String =
        if (scope == SkillScope.GLOBAL) {
            globalDirectorySkillSource.skillsRoot
        } else {
            projectDirectorySkillSource.skillsRoot
        }

    /**
     * Imports a skill from Markdown text into the given scope; [fallbackName] is the fallback name when the frontmatter has no name.
     * An invalid name / a same-name conflict / an empty body fails the import overall, with nothing written to disk.
     */
    fun importMarkdown(text: String, fallbackName: String, scope: SkillScope): SkillImportReport =
        SkillImporter.importMarkdown(fileAccess, skillsRoot(scope), existingNamesIn(scope), text, fallbackName)

    /** Imports skills from a zip input stream (which can contain multiple skill directories) into the given scope. */
    fun importZip(input: InputStream, fallbackName: String, scope: SkillScope): SkillImportReport =
        SkillImporter.importArchive(fileAccess, skillsRoot(scope), existingNamesIn(scope), input, fallbackName)

    /** Existing skill names in the given scope (lowercase), for import duplicate checking. */
    private fun existingNamesIn(scope: SkillScope): Set<String> =
        listAllSkills().filter { it.scope == scope }.map { it.skill.name.lowercase() }.toSet()

    /** Deletes the skill in the given scope (deleting its directory, unrecoverable). Returns whether it succeeded. */
    fun deleteSkill(name: String, scope: SkillScope): Boolean {
        val entry = listAllSkills().firstOrNull {
            it.skill.name.equals(name, ignoreCase = true) && it.scope == scope
        } ?: return false
        val dirPath = entry.skill.dirPath ?: return false
        return safeDeleteSkillDir(fileAccess, dirPath)
    }

    companion object {
        private const val TAG = "SkillRepository"

        /** The instruction file name written when creating a skill. */
        private const val INSTRUCTION_FILE = "SKILL.md"

        /** The name doubles as the directory name, so path separators and reserved characters are banned; the length cap prevents extreme directory names. */
        internal fun isValidName(name: String): Boolean {
            if (name.isBlank() || name.length > MAX_NAME_LENGTH) return false
            if (name == "." || name == "..") return false
            return name.none { it in ILLEGAL_NAME_CHARS || it.isISOControl() }
        }

        private const val MAX_NAME_LENGTH = 64
        private val ILLEGAL_NAME_CHARS = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')

        /** The full path of the instruction file already in the directory: SKILL.md first, then CLAUDE.md (the same fallback as [SkillParser.parse]). */
        internal fun instructionFile(provider: FileAccessProvider, dirPath: String): String? {
            val files = runCatching { provider.listFiles(dirPath) }.getOrNull() ?: return null
            val name = files.firstOrNull { !it.isDirectory && it.name.equals("SKILL.md", ignoreCase = true) }?.name
                ?: files.firstOrNull { !it.isDirectory && it.name.equals("CLAUDE.md", ignoreCase = true) }?.name
                ?: return null
            return "${dirPath.trimEnd('/')}/$name"
        }

        /** Merges the two source levels: for the same name the project level overrides the global one, sorted by name. */
        internal fun mergeAll(global: List<Skill>, project: List<Skill>): List<SkillEntry> {
            val byName = LinkedHashMap<String, SkillEntry>()
            global.forEach { byName[it.name.lowercase()] = SkillEntry(it, SkillScope.GLOBAL) }
            project.forEach { byName[it.name.lowercase()] = SkillEntry(it, SkillScope.PROJECT) }
            return byName.values.sortedBy { it.skill.name.lowercase() }
        }

        /** Filters out disabled skills (the disabled list is already normalized to lowercase). */
        internal fun filterDisabled(entries: List<SkillEntry>, disabled: Set<String>): List<SkillEntry> =
            entries.filterNot { it.skill.name.lowercase() in disabled }

        /** Deletes only when the directory exists and contains a SKILL.md/CLAUDE.md instruction file, to avoid deleting a non-skill directory by mistake. */
        internal fun safeDeleteSkillDir(provider: FileAccessProvider, dirPath: String): Boolean {
            if (!provider.isDirectory(dirPath)) return false
            val hasInstruction = runCatching { provider.listFiles(dirPath) }.getOrNull()?.any {
                !it.isDirectory && (it.name.equals("SKILL.md", ignoreCase = true) || it.name.equals("CLAUDE.md", ignoreCase = true))
            } ?: false
            if (!hasInstruction) return false
            return runCatching { provider.deleteRecursively(dirPath); true }.getOrDefault(false)
        }
    }
}
