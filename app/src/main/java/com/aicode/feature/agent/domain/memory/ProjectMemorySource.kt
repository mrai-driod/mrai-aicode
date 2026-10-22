package com.aicode.feature.agent.domain.memory

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.workspace.domain.ProjectAicodeRoot
import java.io.File

/**
 * Project-level memory data source. Created dynamically by the Repository (depending on the current session's projectRoot).
 *
 * Local mode: stored in `<projectRoot>/.aicode/memory/` (following the workspace directory);
 * Remote mode: projectRoot is a path on the remote server and java.io.File cannot create that directory locally (saving would always fail),
 * so it is stored instead under `memory/projects/<project name>-<identity hash>/` in the global config directory (locally writable, stable across connections).
 * The identity hash is computed from "IP:port:path", so same-named paths on different servers (e.g. both called /home/u/workspace/default)
 * also land in different directories and are never confused.
 */
class ProjectMemorySource(
    private val projectRoot: String,
    private val executionModeHolder: ExecutionModeHolder,
    private val containerInstaller: ContainerInstaller,
    private val projectAicodeRoot: ProjectAicodeRoot
) : MemorySource {

    private val memoryRoot: File by lazy {
        if (executionModeHolder.currentMode() == ExecutionMode.REMOTE_SSH) {
            File(File(containerInstaller.aicodeDir, "memory/projects"), projectAicodeRoot.projectKey(projectRoot))
        } else {
            File(projectRoot, ".aicode/memory")
        }
    }

    override fun listMemories(): List<Memory> {
        if (projectRoot.isBlank() || !memoryRoot.exists()) return emptyList()
        val files = memoryRoot.listFiles { file -> file.isFile && file.extension == "md" } ?: return emptyList()
        
        return files.mapNotNull { file -> MemoryParser.parse(file, MemoryScope.PROJECT) }
            .sortedBy { it.name.lowercase() }
    }

    override fun loadContent(name: String): String? {
        if (projectRoot.isBlank()) return null
        return listMemories()
            .firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?.content
    }

    override fun saveMemory(name: String, description: String, content: String): Boolean {
        if (projectRoot.isBlank()) return false
        return try {
            if (!memoryRoot.exists()) memoryRoot.mkdirs()
            val file = MemorySource.resolveMemoryFile(memoryRoot, name)
            file.writeText(MemoryParser.format(MemorySource.sanitizeName(name), description, content))
            true
        } catch (e: Exception) {
            FileLogger.e("ProjectMemorySource", "Failed to save memory: $name", e)
            false
        }
    }

    override fun deleteMemory(name: String): Boolean {
        if (projectRoot.isBlank()) return false
        val file = MemorySource.resolveMemoryFile(memoryRoot, name)
        return if (file.exists()) file.delete() else false
    }
}
