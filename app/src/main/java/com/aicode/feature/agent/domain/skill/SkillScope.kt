package com.aicode.feature.agent.domain.skill

/** The source scope of a skill: global (shared across projects) or project-level (only takes effect in the current workspace). */
enum class SkillScope { GLOBAL, PROJECT }

/** An active skill entry: the skill itself + its source scope, so the UI can label it "Global/Project". */
data class SkillEntry(
    val skill: Skill,
    val scope: SkillScope
)
