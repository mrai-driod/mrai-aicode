package com.aicode.feature.agent.domain.skill

import com.aicode.feature.workspace.domain.FileAccessProvider
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Global skill source: `~/.aicode/skills`, shared across projects. Accessed by container path via [FileAccessProvider],
 * following the current execution environment: in local mode it lands in the app-private `filesDir/aicode/skills` (in-container `/root/.aicode/skills`),
 * and in remote mode it lands over SSH in `.aicode/skills` under the server user's home. Remote is a separate execution environment, so reads and writes use the remote side as authoritative.
 */
@Singleton
class GlobalDirectorySkillSource @Inject constructor(
    private val fileAccess: FileAccessProvider
) : SkillSource {

    val skillsRoot: String = "~/.aicode/skills"

    override fun listSkills(): List<Skill> = SkillDirectoryScanner.scan(fileAccess, skillsRoot)

    override fun loadInstructions(name: String): String? =
        SkillDirectoryScanner.locateAndRead(fileAccess, skillsRoot, name)
}
