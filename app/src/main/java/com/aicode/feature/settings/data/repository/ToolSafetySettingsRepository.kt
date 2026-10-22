package com.aicode.feature.settings.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.toolSafetyDataStore by preferencesDataStore(name = "tool_safety_prefs")

/**
 * Command-execution authorisation level (applies to the evaluation of shell-type tools in BUILD mode; the PLAN/ASK read-only sandbox is a physical block and is not affected by the level).
 * - [AUTONOMOUS]: Autonomous execution — an "AI command reviewer" independently reviews each command, and commands that pass review are allowed directly;
 *   a review rejection/uncertainty/failure always falls through to human approval (on rejection, the danger explanation given by the reviewer is attached).
 * - [RULE_BASED]: Rule-based blocking — commands matching a high-risk blocking rule prompt for human confirmation, all other commands execute automatically (default).
 * - [FULL_ACCESS]: Full access — all commands execute directly, keeping only catastrophic-deletion protection.
 */
enum class CommandAuthLevel { AUTONOMOUS, RULE_BASED, FULL_ACCESS }

/**
 * Persists the "disable safety interception" switch, off by default.
 *
 * When on, even catastrophic `rm` commands (deleting the root directory, critical system directories, the workspace root, etc.) are no longer
 * blocked in any mode, all commands are allowed, and the `Shizuku` tool no longer prompts each time. PLAN/ASK write operations'
 * physical sandbox is unaffected (that is a mode-level hard rejection, not an option controlled by this switch).
 */
@Singleton
class ToolSafetySettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private companion object {
        val DISABLED_KEY = booleanPreferencesKey("disable_safety_interception")
        val AUTH_LEVEL_KEY = stringPreferencesKey("command_auth_level")
    }

    /** Flow of the current switch value; falls back to false when unset (safety interception is kept by default). */
    val disableSafetyInterceptionFlow: Flow<Boolean> =
        context.toolSafetyDataStore.data.map { it[DISABLED_KEY] ?: false }

    suspend fun setDisableSafetyInterception(disabled: Boolean) {
        context.toolSafetyDataStore.edit { it[DISABLED_KEY] = disabled }
    }

    /** Reads the current value once (used during permission evaluation). */
    suspend fun isSafetyInterceptionDisabled(): Boolean = disableSafetyInterceptionFlow.first()

    /** Flow of the current command authorisation level; falls back to [CommandAuthLevel.RULE_BASED] (rule-based blocking) when unset. */
    val commandAuthLevelFlow: Flow<CommandAuthLevel> =
        context.toolSafetyDataStore.data.map { prefs ->
            prefs[AUTH_LEVEL_KEY]?.let { runCatching { CommandAuthLevel.valueOf(it) }.getOrNull() }
                ?: CommandAuthLevel.RULE_BASED
        }

    suspend fun setCommandAuthLevel(level: CommandAuthLevel) {
        context.toolSafetyDataStore.edit { it[AUTH_LEVEL_KEY] = level.name }
    }

    /** Reads the current level once (used during permission evaluation). */
    suspend fun getCommandAuthLevel(): CommandAuthLevel = commandAuthLevelFlow.first()
}