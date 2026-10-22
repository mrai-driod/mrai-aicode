package com.aicode.feature.agent.domain.mcp

import kotlinx.serialization.Serializable

// Two forms: remote HTTP (with url) or local stdio (with command), inferred by isStdio.
@Serializable
data class McpServerConfig(
    val name: String,
    val url: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val command: String? = null,
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val enabled: Boolean = true,
    val disabledTools: Set<String> = emptySet()
) {
    val isStdio: Boolean get() = !command.isNullOrBlank()

    companion object {
        /**
         * The name is embedded in function-calling tool names (`mcp__{name}__{toolName}`); only ASCII letters, digits, underscores and hyphens are allowed;
         * Chinese and other Unicode characters do not comply with providers' tool naming rules.
         */
        private val NAME_REGEX = Regex("[a-zA-Z0-9_-]+")

        fun isValidName(name: String): Boolean = NAME_REGEX.matches(name)
    }
}
