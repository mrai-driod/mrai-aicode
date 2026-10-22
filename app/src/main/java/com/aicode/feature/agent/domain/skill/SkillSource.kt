package com.aicode.feature.agent.domain.skill

/**
 * Abstraction interface for skill data sources, supporting loading from multiple channels (local directories, remote URLs, etc.).
 */
interface SkillSource {
    /** Scans and returns all currently valid Skills from this data source. */
    fun listSkills(): List<Skill>

    /** Reads the full instruction body of the given skill; returns null if it does not exist or fails to parse. */
    fun loadInstructions(name: String): String?
}
