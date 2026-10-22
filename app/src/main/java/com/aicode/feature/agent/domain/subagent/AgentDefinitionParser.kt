package com.aicode.feature.agent.domain.subagent

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.model.AgentMode
import com.aicode.feature.agent.domain.model.ReasoningEffort
import com.aicode.feature.workspace.domain.FileAccessProvider
import org.yaml.snakeyaml.Yaml

/**
 * Parses a single subagent definition file (`agents/<name>.md`): the YAML frontmatter is the configuration and the body is the agent prompt.
 * Uses the same frontmatter conventions as [com.aicode.feature.agent.domain.skill.SkillParser].
 */
object AgentDefinitionParser {
    private const val TAG = "AgentDefinitionParser"
    private const val MAX_DESC_CHARS = 500

    /** An empty body is treated as an invalid definition (an agent must have a prompt) and returns null. The definition file is read via [provider] by container path. */
    fun parse(provider: FileAccessProvider, filePath: String): AgentDefinition? {
        val text = runCatching { provider.readFile(filePath) }.getOrElse {
            FileLogger.w(TAG, "Failed to read subagent definition: $filePath", it)
            return null
        }
        return parseText(text, filePath)
    }

    /**
     * Parses a definition from raw Markdown text (not depending on disk), for unified parsing after batch reading via [com.aicode.feature.workspace.domain.FileAccessProvider.readFiles]
     * -- on a real device's ROOT channel, calling [parse] one by one would mean one `su` subprocess per definition file.
     */
    fun parseText(text: String, filePath: String): AgentDefinition? {
        val (frontmatter, body) = splitAndParseFrontmatter(text)
        val prompt = body.trim()
        if (prompt.isEmpty()) return null

        val fileName = filePath.substringAfterLast('/')
        val name = frontmatter["name"]?.toString()?.trim()?.takeIf { it.isNotBlank() }
            ?: fileName.substringBeforeLast('.')
        val description = (frontmatter["description"]?.toString() ?: "").take(MAX_DESC_CHARS)

        return AgentDefinition(
            name = name,
            description = description,
            providerId = frontmatter["provider"]?.toString()?.trim()?.takeIf { it.isNotBlank() },
            model = frontmatter["model"]?.toString()?.trim()?.takeIf { it.isNotBlank() },
            reasoningEffort = frontmatter["reasoningEffort"]?.toString()?.trim()?.lowercase()
                ?.takeIf { it in VALID_EFFORTS },
            mode = parseMode(frontmatter["mode"]),
            allowedTools = stringList(frontmatter["tools"]),
            disallowedTools = stringList(frontmatter["disallowedTools"]),
            inject = parseInject(frontmatter["inject"]),
            prompt = prompt,
            filePath = filePath
        )
    }

    /** Accepts both a YAML list and a comma-separated string. */
    internal fun stringList(raw: Any?): List<String> = when (raw) {
        null -> emptyList()
        is List<*> -> raw.mapNotNull { it?.toString()?.trim() }.filter { it.isNotEmpty() }
        is String -> raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        else -> emptyList()
    }

    /** Mode value: ignores case and hyphen/underscore differences; an invalid value falls back to null (inherit from the parent session). */
    internal fun parseMode(raw: Any?): AgentMode? {
        val token = raw?.toString()?.trim()?.uppercase()
            ?.replace("-", "")?.replace("_", "")
            ?.takeIf { it.isNotEmpty() } ?: return null
        return AgentMode.entries.firstOrNull { it.name == token }
    }

    /** When absent or when every value is invalid, falls back to the default injections, so a typo in a definition does not lose all context. */
    internal fun parseInject(raw: Any?): Set<InjectPart> {
        val tokens = stringList(raw)
        if (tokens.isEmpty()) return AgentDefinition.DEFAULT_INJECT
        if (tokens.size == 1 && tokens.first().equals("none", ignoreCase = true)) return emptySet()
        val parsed = tokens.mapNotNull { InjectPart.fromToken(it) }.toSet()
        return parsed.ifEmpty { AgentDefinition.DEFAULT_INJECT }
    }

    private fun splitAndParseFrontmatter(text: String): Pair<Map<String, Any>, String> {
        val normalized = text.replace("\r\n", "\n")
        if (!normalized.startsWith("---\n")) return emptyMap<String, Any>() to normalized

        val end = normalized.indexOf("\n---", startIndex = 3)
        if (end < 0) return emptyMap<String, Any>() to normalized

        val block = normalized.substring(4, end)
        val rest = normalized.substring(end + 4).removePrefix("\n")

        val map = try {
            Yaml().load<Map<String, Any>>(block) ?: emptyMap()
        } catch (e: Exception) {
            FileLogger.w(TAG, "Failed to parse subagent definition YAML", e)
            emptyMap()
        }
        return map to rest
    }

    private val VALID_EFFORTS = ReasoningEffort.entries.map { it.apiValue }.toSet()

    /**
     * Writes a settings-page form back to definition file text (frontmatter + body), paired with [parse].
     *
     * `name` is always written out and kept consistent with the file name, avoiding "one name in the file name, another in the frontmatter".
     * Tool names are identifiers and go directly into a bracketed list; all other text fields are always quoted, so a colon or # in a description does not break the YAML.
     */
    fun serialize(
        name: String,
        description: String,
        providerId: String?,
        model: String?,
        reasoningEffort: String?,
        mode: AgentMode? = null,
        allowedTools: List<String>,
        disallowedTools: List<String>,
        inject: Set<InjectPart>,
        prompt: String
    ): String = buildString {
        appendLine("---")
        appendLine("name: ${quote(name)}")
        appendLine("description: ${quote(description)}")
        providerId?.takeIf { it.isNotBlank() }?.let { appendLine("provider: ${quote(it)}") }
        model?.takeIf { it.isNotBlank() }?.let { appendLine("model: ${quote(it)}") }
        reasoningEffort?.takeIf { it in VALID_EFFORTS }?.let { appendLine("reasoningEffort: $it") }
        mode?.let { appendLine("mode: ${it.name.lowercase()}") }
        if (allowedTools.isNotEmpty()) appendLine("tools: [${allowedTools.joinToString(", ")}]")
        if (disallowedTools.isNotEmpty()) appendLine("disallowedTools: [${disallowedTools.joinToString(", ")}]")
        // An empty set must be written explicitly as [none]: omitting it would be parsed as the default injections, exactly the opposite of the user's "inject nothing" choice.
        val injectTokens = if (inject.isEmpty()) {
            "none"
        } else {
            InjectPart.entries.filter { it in inject }.joinToString(", ") { it.token }
        }
        appendLine("inject: [$injectTokens]")
        appendLine("---")
        appendLine(prompt.trim())
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
