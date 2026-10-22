package com.aicode.feature.agent.domain.skill

/**
 * A single parsed Skill model.
 *
 * @param name skill name (the unique identifier the model uses to invoke it)
 * @param description skill description (when this skill should be used)
 * @param requiredTools the dedicated tools this skill requires (optional)
 * @param dirPath container path of the skill's directory (a unified local/remote view, e.g. `~/workspace/.aicode/skills/foo`)
 * @param instructions the skill instruction body (the content after stripping the frontmatter)
 */
data class Skill(
    val name: String,
    val description: String,
    val requiredTools: List<String> = emptyList(),
    val dirPath: String? = null,
    val instructions: String
)

/** Snapshot of the form submitted when creating/editing a skill on the settings page. */
data class SkillForm(
    val name: String,
    val description: String,
    val instructions: String,
    /** The `required_tools` from a hand-written skill; the edit page does not expose it but writes it back verbatim so saving does not drop the field. */
    val requiredTools: List<String> = emptyList()
)

/** Reason a skill failed to save. */
enum class SkillSaveError {
    /** The name is empty, too long, or contains characters that cannot be used in a directory name. */
    INVALID_NAME,

    /** The instruction body is empty: a skill loaded like this gives the AI no guidance value. */
    EMPTY_INSTRUCTIONS,

    /** A skill with the same name already exists in the same scope. */
    NAME_CONFLICT,

    /** Writing to disk failed. */
    IO_FAILED
}
