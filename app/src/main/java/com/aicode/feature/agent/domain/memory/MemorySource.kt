package com.aicode.feature.agent.domain.memory

import com.aicode.core.util.FileLogger
import java.io.File

/** A single edit item, with the same semantics as editFile's edits. */
data class MemoryEdit(
    val oldString: String,
    val newString: String,
    val replaceAll: Boolean
)

/** The result of a memory edit operation. */
sealed interface MemoryEditResult {
    /** The edit succeeded and was written to disk. */
    data object Success : MemoryEditResult

    /** The memory with the given name does not exist. */
    data class NotFound(val name: String) : MemoryEditResult

    /** Edit validation or writing to disk failed. */
    data class Error(val code: String, val message: String) : MemoryEditResult
}

/**
 * Abstraction interface for memory data sources, supporting the global and project levels.
 */
interface MemorySource {
    /** Scans and returns all currently valid Memories from this data source. */
    fun listMemories(): List<Memory>

    /** Reads the full instruction body of the given memory; returns null when it does not exist or fails to parse. */
    fun loadContent(name: String): String?

    /** Saves one memory (create or overwrite). */
    fun saveMemory(name: String, description: String, content: String): Boolean

    /**
     * Applies local edits to an existing memory's body (exact old_string/new_string matching), with the same semantics as editFile.
     * The default implementation locates the memory file via [listMemories] and applies all edits in order in memory;
     * if any edit fails validation, the whole batch is not written to disk (atomic); on success it is reformatted with the original name/description and written back.
     */
    fun editMemory(name: String, edits: List<MemoryEdit>): MemoryEditResult {
        val memory = listMemories().firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?: return MemoryEditResult.NotFound(name)
        val file = memory.file ?: return MemoryEditResult.NotFound(name)
        if (edits.isEmpty()) {
            return MemoryEditResult.Error("EMPTY_EDITS", "edits must not be empty; provide at least one {old_string,new_string} edit")
        }

        var content = memory.content
        edits.forEachIndexed { i, e ->
            if (e.oldString.isEmpty()) {
                return MemoryEditResult.Error("EMPTY_OLD_STRING", "The old_string of edit ${i + 1} must not be empty")
            }
            if (e.oldString == e.newString) {
                return MemoryEditResult.Error("NO_OP", "The old_string and new_string of edit ${i + 1} are identical; no change is needed")
            }
            val occurrences = content.split(e.oldString).size - 1
            if (occurrences == 0) {
                return MemoryEditResult.Error(
                    "NO_MATCH",
                    "Edit ${i + 1} did not find old_string in the body of memory '${memory.name}'; make sure the content matches the body returned by read exactly (including indentation/newlines)"
                )
            }
            if (occurrences > 1 && !e.replaceAll) {
                return MemoryEditResult.Error(
                    "MULTIPLE_MATCHES",
                    "The old_string of edit ${i + 1} matched $occurrences places in the body; provide a longer unique context, or set replace_all=true for this edit"
                )
            }
            content = if (e.replaceAll) content.replace(e.oldString, e.newString)
            else content.replaceFirst(e.oldString, e.newString)
        }

        return try {
            file.writeText(MemoryParser.format(memory.name, memory.description, content))
            MemoryEditResult.Success
        } catch (e: Exception) {
            FileLogger.e("MemorySource", "Failed to edit memory: $name", e)
            MemoryEditResult.Error("EDIT_WRITE_FAILED", "Failed to write memory file: ${e.message}")
        }
    }

    fun deleteMemory(name: String): Boolean

    companion object {
        /**
         * Normalizes a memory name passed in by the model into a safe file-name fragment.
         *
         * Memory names come from model tool arguments (free text); appending one directly to "$name.md" without processing would introduce path traversal
         * (e.g. "../x" would write outside memoryRoot), illegal file names containing separators, and similar problems.
         * This keeps only Unicode "letters/digits/hyphens/underscores" characters, replaces the rest with '-', and trims leading/trailing hyphens;
         * the result contains only legal file-name characters and no path separators, and is deterministic for the same input, guaranteeing
         * that "the file name written out <-> the name read back by MemoryParser.parse" stays consistent.
         */
        fun sanitizeName(raw: String): String {
            val cleaned = raw.map { ch ->
                if (ch.isLetterOrDigit() || ch == '-' || ch == '_') ch else '-'
            }.joinToString("").trim('-')
            // When every character is illegal, fall back to a placeholder name to avoid an empty file name
            return if (cleaned.isBlank()) "memory" else cleaned
        }

        /**
         * Resolves the disk file for a memory name, and verifies that the resolved canonical path still lies within [root],
         * preventing out-of-bounds writes from anything sanitize missed.
         */
        fun resolveMemoryFile(root: File, name: String): File {
            val safe = sanitizeName(name)
            return File(root, "$safe.md")
        }
    }
}
