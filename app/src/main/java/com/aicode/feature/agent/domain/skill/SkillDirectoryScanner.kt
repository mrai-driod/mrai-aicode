package com.aicode.feature.agent.domain.skill

import com.aicode.core.util.FileLogger
import com.aicode.feature.workspace.domain.FileAccessProvider

/**
 * Shared scanning logic for directory-based skill sources: recursively finds SKILL.md / CLAUDE.md,
 * and parses each directory containing an instruction file into one Skill.
 *
 * Directories are accessed by container path via [FileAccessProvider], with the same logic for local and remote (SSH).
 */
object SkillDirectoryScanner {
    private const val TAG = "SkillDirectoryScanner"

    /** Allows some nesting depth (for example repo/skills/my-skill/SKILL.md). */
    private const val MAX_DEPTH = 4
    private const val SKILL_FILE = "SKILL.md"
    private const val CLAUDE_FILE = "CLAUDE.md"

    /**
     * Number of characters read for head matching: the frontmatter is only a few lines (name/description/required_tools),
     * and 8K is enough to cover it; an over-long frontmatter that is truncated and does not match goes through the full fallback, so there is no misjudgement.
     */
    private const val FRONTMATTER_HEAD_CHARS = 8 * 1024

    /** Whether this is an instruction file name (SKILL.md / CLAUDE.md, case-insensitive). */
    private fun isInstructionFile(relative: String): Boolean {
        val name = relative.substringAfterLast('/')
        return name.equals(SKILL_FILE, ignoreCase = true) || name.equals(CLAUDE_FILE, ignoreCase = true)
    }

    /**
     * Scans all valid skills under the [root] directory, sorted by name.
     * Returns an empty list when the directory does not exist; when scanning throws (IO failure, remote workspace not connected, etc.) it degrades to an empty list and logs,
     * without propagating: a skill scan failure should not crash the caller (workspace switching, AI requests).
     */
    fun scan(provider: FileAccessProvider, root: String): List<Skill> = runCatching {
        val picked = instructionFiles(provider, root)
        if (picked.isEmpty()) return@runCatching emptyList()

        // One batch read (one su round trip on the ROOT channel fetches the whole batch), instead of the hundreds of subprocesses of reading files one by one with readFile.
        val contents = provider.readFiles(picked.map { it.first })
        picked.mapNotNull { (filePath, dirPath) ->
            val text = contents[filePath] ?: return@mapNotNull null
            SkillParser.parseText(text, dirPath.substringAfterLast('/').ifBlank { dirPath })
                .copy(dirPath = dirPath)
        }.sortedBy { it.name.lowercase() }
    }.getOrElse { e ->
        FileLogger.w(TAG, "Failed to scan skill directory: $root", e)
        emptyList()
    }

    /**
     * Precisely locates and reads a single skill's body (dedicated to loadSkill): it reads only the **head** of each candidate file to match the frontmatter
     * name, and reads that file in full only after a hit. Compared with [scan] "reading every skill in full", the cost drops from
     * "recursive listing + batch-reading all bodies" to "recursive listing + one batch of head reads + one full read".
     *
     * The matching semantics are exactly the same as [scan]: when the frontmatter has no name it falls back to the directory name; in the same directory SKILL.md takes priority over CLAUDE.md.
     * When head matching misses entirely it falls back to a full [scan] match: when an over-long frontmatter was truncated, or the skill genuinely does not exist,
     * it still gives a result consistent with a full scan (when the skill does not exist, that cost is one accurate "not found" determination).
     */
    fun locateAndRead(provider: FileAccessProvider, root: String, name: String): String? = runCatching {
        val picked = instructionFiles(provider, root)
        if (picked.isEmpty()) return@runCatching null

        // Batch head read: the frontmatter is at the head of the file and 8K is enough; one su round trip on the ROOT channel fetches the whole batch.
        val heads = provider.readFileHeads(picked.map { it.first }, FRONTMATTER_HEAD_CHARS)
        for ((filePath, dirPath) in picked) {
            val head = heads[filePath] ?: continue
            val meta = SkillParser.parseText(head, dirPath.substringAfterLast('/').ifBlank { dirPath })
            if (!meta.name.equals(name, ignoreCase = true)) continue
            // Hit: read the full text and re-parse with the real frontmatter (the head may have truncated the body and the name field)
            val text = provider.readFiles(listOf(filePath))[filePath] ?: provider.readFile(filePath)
            return@runCatching SkillParser.parseText(text, meta.name).instructions.trim()
        }
        // No head match: fall back to a full scan (including the accurate determination for "skill does not exist")
        scan(provider, root).firstOrNull { it.name.equals(name, ignoreCase = true) }?.instructions
    }.getOrElse { e ->
        FileLogger.w(TAG, "Failed to locate skill: $root/$name", e)
        null
    }

    /**
     * Recursively lists the instruction files under [root], returning `(file container path, directory container path)`;
     * when SKILL.md / CLAUDE.md coexist in the same directory, like [scan] it takes only SKILL.md.
     */
    private fun instructionFiles(provider: FileAccessProvider, root: String): List<Pair<String, String>> {
        val base = root.trimEnd('/')
        // The recursive results already carry file names (in the form "repo/skills/my-skill/SKILL.md"), so pick the skill files directly from them,
        // without querying listFiles once per directory again: on a real device's ROOT channel that would fork one extra su process per skill.
        return provider.listFilesRecursive(root, MAX_DEPTH)
            .map { it.removePrefix("./") }
            .filter { isInstructionFile(it) }
            .groupBy { it.substringBeforeLast('/', "") }
            .values.mapNotNull { files ->
                val chosen = files.firstOrNull { it.substringAfterLast('/').equals(SKILL_FILE, ignoreCase = true) }
                    ?: files.firstOrNull { it.substringAfterLast('/').equals(CLAUDE_FILE, ignoreCase = true) }
                    ?: return@mapNotNull null
                val dirRel = chosen.substringBeforeLast('/', "")
                val dirPath = if (dirRel.isEmpty()) base else "$base/$dirRel"
                "$base/$chosen" to dirPath
            }
    }
}
