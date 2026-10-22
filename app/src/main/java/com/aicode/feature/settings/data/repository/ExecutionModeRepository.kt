package com.aicode.feature.settings.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aicode.core.datastore.preferencesCorruptionHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.executionModeDataStore by preferencesDataStore(
    name = "execution_mode_prefs",
    corruptionHandler = preferencesCorruptionHandler
)

/** Execution environment mode. */
enum class ExecutionMode {
    /** Local PRoot container (original behavior). */
    LOCAL_PROOT,
    /** Remote SSH server. */
    REMOTE_SSH,
    /** Real-device ROOT: commands are executed directly on this device as root via `su` (requires the device to be rooted). */
    DEVICE_ROOT
}

/**
 * Persists the current execution mode (local PRoot / remote SSH).
 *
 * Remote connection parameters are no longer persisted here; instead, they are
 * resolved in real time by [com.aicode.feature.agent.domain.container.ActiveRemoteConnectionResolver] based on the currently active container profile.
 * When the mode is switched, the DI layer uses this to inject the corresponding [com.aicode.feature.agent.domain.container.CommandEngine]
 * and [com.aicode.feature.workspace.domain.FileAccessProvider] implementations.
 */
@Singleton
class ExecutionModeRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private companion object {
        val MODE_KEY = stringPreferencesKey("execution_mode")
    }

    /** Current execution mode; defaults to local PRoot when unset. */
    val executionModeFlow: Flow<ExecutionMode> = context.executionModeDataStore.data.map { prefs ->
        prefs[MODE_KEY]?.let {
            runCatching { ExecutionMode.valueOf(it) }.getOrNull()
        } ?: ExecutionMode.LOCAL_PROOT
    }

    suspend fun setExecutionMode(mode: ExecutionMode) {
        context.executionModeDataStore.edit { it[MODE_KEY] = mode.name }
    }
}

/**
 * Normalizes the remote workspace root path. Older versions hard-coded the default as `/home/<username>/workspace` (which is wrong for users whose home is not
 * `/home/<username>`, such as root, whose home is `/root`); `~/workspace` is the path taken by a symbolic link,
 * so it cannot be used as the workspace root (it would collide with the link and create a self-reference). Both of these leftover values and empty values are always reset to the default.
 */
internal fun normalizeRemoteWorkspacePath(stored: String?, username: String): String {
    val v = stored?.trim().orEmpty()
    return if (v.isEmpty() || v == "/home/$username/workspace" || v == "~/workspace") {
        DEFAULT_REMOTE_WORKSPACE_ROOT
    } else {
        v
    }
}

/**
 * Default for the remote workspace root directory. **Cannot use `~/workspace`** -- that is the path taken by the "current workspace" symbolic link;
 * using the same name would make the root directory collide with the link.
 */
internal const val DEFAULT_REMOTE_WORKSPACE_ROOT = "~/.aicode/workspaces"
