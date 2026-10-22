package com.aicode.feature.agent.domain.subagent

import com.aicode.feature.workspace.domain.FileAccessProvider
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Global subagent definition source: `~/.aicode/agents`, shared across projects. Accessed by container path via [FileAccessProvider],
 * following the current execution environment: in local mode it lands in the app-private `filesDir/aicode/agents` (in-container `/root/.aicode/agents`),
 * and in remote mode it lands over SSH in `.aicode/agents` under the server user's home. Remote is a separate execution environment, so reads and writes use the remote side as authoritative.
 */
@Singleton
class GlobalDirectoryAgentSource @Inject constructor(
    private val fileAccess: FileAccessProvider
) : AgentDefinitionSource {

    val agentsRoot: String = "~/.aicode/agents"

    override fun listDefinitions(): List<AgentDefinition> =
        AgentDefinitionDirectoryScanner.scan(fileAccess, agentsRoot)
}
