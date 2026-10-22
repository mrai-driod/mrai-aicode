package com.aicode.feature.agent.domain.subagent

import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.WorkspacePathMapper
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Project-level subagent definition source: `<projectRoot>/.aicode/agents/`; it travels with the workspace and can be tracked by git.
 * Accessed by container path via [FileAccessProvider]: locally it maps to the host workspace, remotely it lands in the remote workspace over SSH.
 */
@Singleton
class ProjectDirectoryAgentSource @Inject constructor(
    private val fileAccess: FileAccessProvider
) : AgentDefinitionSource {

    val agentsRoot: String = "${WorkspacePathMapper.CONTAINER_ROOT}/.aicode/agents"

    override fun listDefinitions(): List<AgentDefinition> =
        AgentDefinitionDirectoryScanner.scan(fileAccess, agentsRoot)
}
