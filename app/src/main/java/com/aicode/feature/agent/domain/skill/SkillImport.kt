package com.aicode.feature.agent.domain.skill

import com.aicode.core.util.FileLogger
import com.aicode.feature.workspace.domain.FileAccessProvider
import java.io.InputStream
import java.util.zip.ZipInputStream

/** Reason importing a single skill failed. */
enum class SkillImportError {
    /** The name is empty, too long, or contains characters that cannot be used in a directory name. */
    INVALID_NAME,

    /** A skill with the same name already exists in the same scope. */
    NAME_CONFLICT,

    /** The instruction body is empty. */
    EMPTY_CONTENT,

    /** No skill was found in the archive (missing SKILL.md / CLAUDE.md). */
    NO_SKILL_FOUND,

    /** The archive cannot be read (not a valid zip, an entry out of bounds, or over the size limit). */
    INVALID_ARCHIVE,

    /** The selected file type is not supported (e.g. importing a non-zip file as an archive). */
    UNSUPPORTED_FILE,

    /** Writing to disk failed. */
    IO_FAILED
}

/** Record of one skill's failure during a batch import. */
data class SkillImportFailure(val name: String, val error: SkillImportError)

/**
 * The result of one import: [imported] holds the names of skills written successfully; [failures] holds the skipped skills and their reasons;
 * a non-null [fatal] means the import failed overall (not a single skill was imported, e.g. the archive is invalid or contains no skill).
 */
data class SkillImportReport(
    val imported: List<String>,
    val failures: List<SkillImportFailure> = emptyList(),
    val fatal: SkillImportError? = null
)

/** A single skill parsed from an archive: name + the files under that skill's directory (relative to the skill root, `/`-separated). */
internal data class ArchivedSkill(val name: String, val files: Map<String, ByteArray>)

/**
 * Core skill import logic: writes a Markdown text or a zip archive into the skill root directory of the given scope.
 *
 * Depends only on [FileAccessProvider] + the skill root path + the set of existing names, with the same logic for local and remote (SSH), making it easy to unit-test.
 * Name validation and same-name conflict determination match creation inside the app; conflicts are never overwritten, they are skipped and recorded in the failure list.
 */
internal object SkillImporter {
    private const val TAG = "SkillImporter"
    private const val SKILL_FILE = "SKILL.md"
    private const val CLAUDE_FILE = "CLAUDE.md"

    /** Caps on total extracted size and entry count, to stop a malformed archive from exhausting memory. */
    private const val MAX_TOTAL_BYTES = 64L * 1024 * 1024
    private const val MAX_ENTRIES = 2000

    /**
     * Imports a single skill from Markdown text: parses the frontmatter for the name/description/tools, then normalizes it to SKILL.md on disk.
     * [fallbackName] is the fallback when the body has no name (e.g. the source file name).
     */
    fun importMarkdown(
        provider: FileAccessProvider,
        skillsRoot: String,
        existingNames: Set<String>,
        text: String,
        fallbackName: String
    ): SkillImportReport {
        val parsed = SkillParser.parseText(text, fallbackName)
        val name = parsed.name.trim()
        validate(name, existingNames)?.let { return SkillImportReport(emptyList(), fatal = it) }
        if (parsed.instructions.isBlank()) {
            return SkillImportReport(emptyList(), fatal = SkillImportError.EMPTY_CONTENT)
        }
        val content = SkillParser.serialize(name, parsed.description, parsed.requiredTools, parsed.instructions)
        return try {
            provider.writeFile("${rootFor(skillsRoot, name)}/$SKILL_FILE", content, overwrite = true)
            SkillImportReport(imported = listOf(name))
        } catch (e: Exception) {
            FileLogger.e(TAG, "Failed to import skill: $name", e)
            SkillImportReport(emptyList(), fatal = SkillImportError.IO_FAILED)
        }
    }

    /** Imports skills from a zip input stream (one archive can contain multiple skill directories). */
    fun importArchive(
        provider: FileAccessProvider,
        skillsRoot: String,
        existingNames: Set<String>,
        input: InputStream,
        fallbackName: String
    ): SkillImportReport {
        val skills = when (val read = readArchive(input, fallbackName)) {
            is ArchiveRead.Failed -> return SkillImportReport(emptyList(), fatal = read.error)
            is ArchiveRead.Ok -> read.skills
        }

        val imported = mutableListOf<String>()
        val failures = mutableListOf<SkillImportFailure>()
        // Names already taken within this same import batch, so two same-named skills in one archive do not overwrite each other.
        val used = existingNames.toMutableSet()

        for (skill in skills) {
            val name = skill.name.trim()
            val error = validate(name, used) ?: if (skill.files.isEmpty()) SkillImportError.EMPTY_CONTENT else null
            if (error != null) {
                failures += SkillImportFailure(name, error)
                continue
            }
            try {
                skill.files.forEach { (relative, bytes) ->
                    provider.writeBytes("${rootFor(skillsRoot, name)}/$relative", bytes, overwrite = true)
                }
                used += name.lowercase()
                imported += name
            } catch (e: Exception) {
                FileLogger.e(TAG, "Failed to import skill: $name", e)
                failures += SkillImportFailure(name, SkillImportError.IO_FAILED)
            }
        }
        return SkillImportReport(imported = imported, failures = failures)
    }

    private fun validate(name: String, existingNames: Set<String>): SkillImportError? = when {
        !SkillRepository.isValidName(name) -> SkillImportError.INVALID_NAME
        name.lowercase() in existingNames -> SkillImportError.NAME_CONFLICT
        else -> null
    }

    private fun rootFor(skillsRoot: String, name: String) = "${skillsRoot.trimEnd('/')}/$name"

    private sealed interface ArchiveRead {
        data class Ok(val skills: List<ArchivedSkill>) : ArchiveRead
        data class Failed(val error: SkillImportError) : ArchiveRead
    }

    /**
     * Reads the zip into memory and assigns each file to a skill by "the deepest skill directory that is its ancestor", avoiding parent/child duplication for nested skills.
     * Guards against zip-slip and limits the entry count and total size; an invalid archive or one with no skill returns [ArchiveRead.Failed].
     */
    private fun readArchive(input: InputStream, fallbackName: String): ArchiveRead {
        val entries = LinkedHashMap<String, ByteArray>()
        var total = 0L
        try {
            ZipInputStream(input).use { zip ->
                var count = 0
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) {
                        zip.closeEntry()
                        continue
                    }
                    if (++count > MAX_ENTRIES) return ArchiveRead.Failed(SkillImportError.INVALID_ARCHIVE)
                    val path = normalize(entry.name)
                    if (path != null) {
                        val bytes = zip.readBytes()
                        total += bytes.size
                        if (total > MAX_TOTAL_BYTES) return ArchiveRead.Failed(SkillImportError.INVALID_ARCHIVE)
                        entries[path] = bytes
                    }
                    zip.closeEntry()
                }
            }
        } catch (e: Exception) {
            FileLogger.w(TAG, "Failed to read archive", e)
            return ArchiveRead.Failed(SkillImportError.INVALID_ARCHIVE)
        }
        if (entries.isEmpty()) return ArchiveRead.Failed(SkillImportError.INVALID_ARCHIVE)

        // Skill directories: the directory holding an entry with an instruction file (relative path; the root directory is "").
        val skillDirs = entries.keys
            .filter { isInstructionFile(it.substringAfterLast('/')) }
            .map { it.substringBeforeLast('/', "") }
            .distinct()
        if (skillDirs.isEmpty()) return ArchiveRead.Failed(SkillImportError.NO_SKILL_FOUND)

        // Ownership: each file goes to the deepest ancestor skill directory (a root-directory skill matches everything as a fallback).
        val grouped = skillDirs.associateWith { mutableMapOf<String, ByteArray>() }
        entries.forEach { (key, bytes) ->
            val owner = skillDirs
                .filter { it.isEmpty() || key.startsWith("$it/") }
                .maxByOrNull { it.length } ?: return@forEach
            val relative = if (owner.isEmpty()) key else key.substring(owner.length + 1)
            grouped.getValue(owner)[relative] = bytes
        }

        val skills = skillDirs.map { dir ->
            val files = grouped.getValue(dir)
            val instructionText = files.entries
                .firstOrNull { isInstructionFile(it.key.substringAfterLast('/')) }
                ?.value?.toString(Charsets.UTF_8).orEmpty()
            val dirFallback = dir.substringAfterLast('/').ifBlank { fallbackName }
            ArchivedSkill(name = SkillParser.parseText(instructionText, dirFallback).name, files = files)
        }
        return ArchiveRead.Ok(skills)
    }

    /** Normalizes a zip entry path: backslashes become forward slashes and a leading `./` is stripped; absolute paths and out-of-bounds entries containing `..` are rejected. */
    private fun normalize(raw: String): String? {
        val path = raw.replace('\\', '/').trimStart('/').removePrefix("./")
        val segments = path.split('/')
        if (segments.any { it == ".." }) return null
        val cleaned = segments.filter { it.isNotEmpty() && it != "." }
        return if (cleaned.isEmpty()) null else cleaned.joinToString("/")
    }

    private fun isInstructionFile(name: String): Boolean =
        name.equals(SKILL_FILE, ignoreCase = true) || name.equals(CLAUDE_FILE, ignoreCase = true)
}
