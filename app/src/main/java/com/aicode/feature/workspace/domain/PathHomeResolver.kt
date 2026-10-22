package com.aicode.feature.workspace.domain

import android.content.Context
import com.aicode.feature.agent.domain.container.RemoteSshConnection
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Unified source for the home directory of the current execution environment (local PRoot container / remote SSH server / device ROOT), used by tools to expand `~`.
 *
 * Each environment obtains its real home differently: after the local container is ready, look up the cached `$HOME` ([containerHome], written by
 * [com.aicode.feature.agent.domain.container.LinuxContainerEngine]); after a remote connection succeeds, look up
 * the cached remote `$HOME` ([RemoteSshConnection.remoteHome]); device ROOT always uses the app-private filesDir
 * (both the app and root shell channels can read and write it, and `workspace` / `.aicode` symlinks can be created in it,
 * so that `~/workspace` and `~/.aicode` in the shell agree with the file tools' path aliases). This class hides those differences,
 * uniformly providing [home] and [expandHome], so tools do not each hardcode (e.g. `/root`) or repeatedly check the execution mode.
 */
@Singleton
class PathHomeResolver @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val executionModeHolder: ExecutionModeHolder,
    private val remoteSshConnection: RemoteSshConnection
) {
    /** Real home inside the local container (cached $HOME lookup after the container is ready); null when not ready. */
    @Volatile
    var containerHome: String? = null

    /**
     * Home of the current environment: remote takes the remote user's home (cached after connection), device ROOT takes the app-private filesDir,
     * local container takes the container home; falls back to /root when unknown.
     */
    fun home(): String = when (executionModeHolder.currentMode()) {
        ExecutionMode.REMOTE_SSH -> remoteSshConnection.remoteHome ?: "/root"
        ExecutionMode.DEVICE_ROOT -> context.filesDir.absolutePath
        ExecutionMode.LOCAL_PROOT -> containerHome ?: "/root"
    }

    /**
     * AI config root directory for the current execution environment (`<home>/.aicode`): for the local container this is `/root/.aicode`,
     * and for remote it is `.aicode` under the server user's home. For code that needs to execute/display files in that directory to obtain it uniformly,
     * avoiding hardcoding `/root` (which would point wrong for non-root remote users).
     */
    fun aicodeRoot(): String = home().trimEnd('/') + "/.aicode"

    /** Expand a `~` or `~/` prefix to the current environment's home path; other paths are returned unchanged. */
    fun expandHome(path: String): String {
        val h = home()
        return when {
            path == "~" -> h
            path.startsWith("~/") -> h.trimEnd('/') + path.removePrefix("~")
            else -> path
        }
    }
}
