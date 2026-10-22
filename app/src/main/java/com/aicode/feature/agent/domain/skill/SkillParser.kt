package com.aicode.feature.agent.domain.skill

import com.aicode.core.util.FileLogger
import com.aicode.feature.workspace.domain.FileAccessProvider
import org.yaml.snakeyaml.Yaml

object SkillParser {
    private const val TAG = "SkillParser"

    /**
     * Per-description cap. Benchmarked against Claude Code's 250 characters: in the system prompt list a description only serves as a "when to use me"
     * index, and writing it too long only dilutes the list (with 118 skills, descriptions alone could take over ten thousand characters); the full execution steps belong
     * in the body that only appears after `loadSkill` loads it. When the user writes a longer description on the settings page there is no error; it is only truncated when read.
     */
    private const val MAX_DESC_CHARS = 250
    private const val SKILL_FILE = "SKILL.md"
    private const val CLAUDE_FILE = "CLAUDE.md"

    /**
     * Parses one skill directory; with no SKILL.md / CLAUDE.md or no name it is treated as invalid and returns null.
     * The directory is read via [provider] by container path, unified for local/remote.
     */
    fun parse(provider: FileAccessProvider, dirPath: String): Skill? {
        val dir = dirPath.trimEnd('/')
        // Prefer SKILL.md, then CLAUDE.md (for compatibility with skills that only use CLAUDE.md); name matching is case-insensitive
        val fileName = runCatching {
            provider.listFiles(dir).map { it.name }
        }.getOrElse {
            FileLogger.w(TAG, "Failed to list skill directory: $dir", it)
            return null
        }.let { names ->
            names.firstOrNull { it.equals(SKILL_FILE, ignoreCase = true) }
                ?: names.firstOrNull { it.equals(CLAUDE_FILE, ignoreCase = true) }
        } ?: return null

        val text = runCatching { provider.readFile("$dir/$fileName") }.getOrElse {
            FileLogger.w(TAG, "Failed to read skill file: $dir/$fileName", it)
            return null
        }

        return parseText(text, dir.substringAfterLast('/').ifBlank { dir }).copy(dirPath = dir)
    }

    /**
     * Parses a skill from raw Markdown text (not depending on disk), for use by file/archive import.
     * [fallbackName] is the fallback when the frontmatter has no name (usually the source file name or the containing directory name).
     */
    fun parseText(text: String, fallbackName: String): Skill {
        val (frontmatter, body) = splitAndParseFrontmatter(text)

        // The name is taken from the frontmatter first, falling back to the fallback name when absent
        val name = frontmatter["name"]?.toString()?.takeIf { it.isNotBlank() } ?: fallbackName
        val description = (frontmatter["description"]?.toString() ?: "").take(MAX_DESC_CHARS)

        val requiredTools = try {
            val toolsRaw = frontmatter["required_tools"]
            if (toolsRaw is List<*>) toolsRaw.filterIsInstance<String>() else emptyList()
        } catch (e: Exception) {
            FileLogger.w(TAG, "Failed to parse required_tools", e)
            emptyList()
        }

        return Skill(
            name = name,
            description = description,
            requiredTools = requiredTools,
            dirPath = null,
            instructions = body.trim()
        )
    }

    /**
     * Uses SnakeYAML to split and parse the YAML frontmatter.
     * @return (frontmatter key-value pairs, body)
     */
    private fun splitAndParseFrontmatter(text: String): Pair<Map<String, Any>, String> {
        val normalized = text.replace("\r\n", "\n")
        if (!normalized.startsWith("---\n")) return emptyMap<String, Any>() to normalized

        val end = normalized.indexOf("\n---", startIndex = 3)
        if (end < 0) return emptyMap<String, Any>() to normalized

        val block = normalized.substring(4, end)
        val rest = normalized.substring(end + 4).removePrefix("\n")
        
        val map = try {
            val yaml = Yaml()
            val loaded = yaml.load<Map<String, Any>>(block)
            loaded ?: emptyMap()
        } catch (e: Exception) {
            FileLogger.w(TAG, "Failed to parse YAML", e)
            emptyMap()
        }
        
        return map to rest
    }

    /**
     * Writes a settings-page form back to `SKILL.md` text (frontmatter + body), paired with [parse].
     *
     * `name` is always written out: the skill name is taken from the frontmatter first, so renaming only needs to change it here without touching the directory name (scripts in the skill directory
     * are often referenced by the body via their original paths, and renaming along with them would break those references).
     * Tool names are identifiers and go directly into a bracketed list; all other text fields are always quoted, so a colon or # in a description does not break the YAML.
     */
    fun serialize(
        name: String,
        description: String,
        requiredTools: List<String>,
        instructions: String
    ): String = buildString {
        appendLine("---")
        appendLine("name: ${quote(name)}")
        appendLine("description: ${quote(description)}")
        if (requiredTools.isNotEmpty()) appendLine("required_tools: [${requiredTools.joinToString(", ")}]")
        appendLine("---")
        appendLine(instructions.trim())
    }

    /** A frontmatter string value: wrapped in double quotes, backslashes and quotes escaped, newlines collapsed to spaces to guarantee a single line. */
    private fun quote(value: String): String {
        val escaped = value.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace(Regex("\\s*\\n\\s*"), " ")
            .trim()
        return "\"$escaped\""
    }
}
