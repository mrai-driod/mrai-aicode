package com.aicode.feature.agent.domain.memory

import java.io.File

/**
 * A single parsed Memory model.
 *
 * @param name memory name (the unique identifier the model uses to invoke it, usually the file name without extension)
 * @param description memory description (a one-sentence summary, injected into the system prompt)
 * @param scope the memory's scope (GLOBAL or PROJECT)
 * @param file the local file corresponding to the memory
 * @param content the memory body (the detailed content after stripping the frontmatter)
 */
data class Memory(
    val name: String,
    val description: String,
    val scope: MemoryScope,
    val file: File? = null,
    val content: String
)

enum class MemoryScope {
    GLOBAL, PROJECT
}
