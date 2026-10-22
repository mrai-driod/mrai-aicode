package com.aicode.feature.agent.domain.skill

import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.WorkspacePathMapper
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Project-level skill source: `<projectRoot>/.aicode/skills/`; it travels with the workspace and can be tracked by git.
 * Accessed by container path via [FileAccessProvider]: locally it maps to the host workspace, remotely it lands in the remote workspace over SSH.
 */
@Singleton
class ProjectDirectorySkillSource @Inject constructor(
    private val fileAccess: FileAccessProvider
) : SkillSource {

    val skillsRoot: String = "${WorkspacePathMapper.CONTAINER_ROOT}/.aicode/skills"

    override fun listSkills(): List<Skill> = SkillDirectoryScanner.scan(fileAccess, skillsRoot)

    override fun loadInstructions(name: String): String? =
        SkillDirectoryScanner.locateAndRead(fileAccess, skillsRoot, name)
}
