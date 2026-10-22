package com.aicode.feature.agent.domain.memory

import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.workspace.domain.ProjectAicodeRoot
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MemoryRepository @Inject constructor(
    private val globalMemorySource: GlobalMemorySource,
    private val executionModeHolder: ExecutionModeHolder,
    private val containerInstaller: ContainerInstaller,
    private val projectAicodeRoot: ProjectAicodeRoot
) {
    /** Creates the project-level data source for the current session's projectRoot (it decides the storage location internally by execution mode). */
    private fun projectSource(projectRoot: String) =
        ProjectMemorySource(projectRoot, executionModeHolder, containerInstaller, projectAicodeRoot)

    /** Scans and aggregates the global and project-level memories. For same-named memories the project level wins. */
    fun listMemories(projectRoot: String?): List<Memory> {
        val allMemories = mutableListOf<Memory>()
        
        // 1. Load the global memories
        allMemories.addAll(globalMemorySource.listMemories())
        
        // 2. Load the project memories (if any)
        if (!projectRoot.isNullOrBlank()) {
            allMemories.addAll(projectSource(projectRoot).listMemories())
        }
        
        // Deduplicate: group by lowercase name and keep the last one added (i.e. the project level overrides the global level)
        return allMemories
            .groupBy { it.name.lowercase() }
            .map { it.value.last() }
    }

    /** Reads the full instruction body of the given memory; returns null when it does not exist / fails to parse. */
    fun loadContent(name: String, projectRoot: String?): String? {
        // Read from the project level first
        if (!projectRoot.isNullOrBlank()) {
            val content = projectSource(projectRoot).loadContent(name)
            if (content != null) return content
        }
        // Fall back to the global level
        return globalMemorySource.loadContent(name)
    }

    fun saveMemory(name: String, description: String, content: String, scope: MemoryScope, projectRoot: String?): Boolean {
        return when (scope) {
            MemoryScope.GLOBAL -> globalMemorySource.saveMemory(name, description, content)
            MemoryScope.PROJECT -> {
                if (projectRoot.isNullOrBlank()) false
                else projectSource(projectRoot).saveMemory(name, description, content)
            }
        }
    }

    fun editMemory(name: String, edits: List<MemoryEdit>, scope: MemoryScope, projectRoot: String?): MemoryEditResult {
        return when (scope) {
            MemoryScope.GLOBAL -> globalMemorySource.editMemory(name, edits)
            MemoryScope.PROJECT -> {
                if (projectRoot.isNullOrBlank()) MemoryEditResult.Error("NO_WORKSPACE", "No workspace is currently selected; project-level memories cannot be edited")
                else projectSource(projectRoot).editMemory(name, edits)
            }
        }
    }

    fun deleteMemory(name: String, scope: MemoryScope, projectRoot: String?): Boolean {
        return when (scope) {
            MemoryScope.GLOBAL -> globalMemorySource.deleteMemory(name)
            MemoryScope.PROJECT -> {
                if (projectRoot.isNullOrBlank()) false
                else projectSource(projectRoot).deleteMemory(name)
            }
        }
    }
}
