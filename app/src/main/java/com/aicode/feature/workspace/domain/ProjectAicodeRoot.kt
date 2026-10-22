package com.aicode.feature.workspace.domain

import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.agent.domain.container.RemoteSshConnection
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import java.io.File
import java.math.BigInteger
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ownership resolution for the project-level `.aicode` config directory.
 *
 * - Local mode: config travels with the workspace (`<workspacePath>/.aicode`), can be edited directly by AI and tracked by git;
 * - Remote mode: the workspace is on an SSH server and [java.io.File] cannot create that directory locally (writes would always fail),
 *   so it is stored instead in the app-private directory `filesDir/aicode/projects/<project name-identifier hash>/`. The identifier is based on
 *   "IP:port:remote path", so same-named paths on different servers do not overwrite each other.
 *
 * Uses the same identifier algorithm as project-level memory [com.aicode.feature.agent.domain.memory.ProjectMemorySource].
 */
@Singleton
class ProjectAicodeRoot @Inject constructor(
    private val workspaceRepository: WorkspaceRepository,
    private val executionModeHolder: ExecutionModeHolder,
    private val containerInstaller: ContainerInstaller,
    private val remoteSshConnection: RemoteSshConnection
) {
    /** Project-level config directory for the current workspace. */
    fun current(): File = forPath(workspaceRepository.currentPath())

    /** Project-level config directory for the given workspace path. */
    fun forPath(workspacePath: String): File =
        if (isRemote()) {
            File(File(containerInstaller.aicodeDir, PROJECTS_DIR), projectKey(workspacePath))
        } else {
            File(workspacePath, AICODE_DIR)
        }

    /** Identifier for remote project-level resources in the private directory: project name + first 8 characters of the "IP:port:path" hash. */
    fun projectKey(workspacePath: String): String {
        val name = workspacePath.trimEnd('/').substringAfterLast('/').ifBlank { "default" }
        val cfg = remoteSshConnection.config
        val identity = if (cfg != null) "${cfg.host}:${cfg.port}:$workspacePath" else workspacePath
        val digest = MessageDigest.getInstance("MD5").digest(identity.toByteArray(Charsets.UTF_8))
        val hash = BigInteger(1, digest).toString(16).padStart(32, '0').take(8)
        return "$name-$hash"
    }

    private fun isRemote(): Boolean =
        executionModeHolder.currentMode() == ExecutionMode.REMOTE_SSH

    private companion object {
        const val AICODE_DIR = ".aicode"
        const val PROJECTS_DIR = "projects"
    }
}
