package com.aicode.feature.workspace.domain

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.agent.domain.container.ContainerProfile
import com.aicode.feature.settings.data.repository.ContainerSettingsRepository
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Converts between "container paths" and "host real paths", so the AI only sees / uses container paths.
 *
 * Background: the container runs under PRoot with the current profile's rootfs directory as its root (`-r rootfs`),
 * and the current workspace directory is bound as [CONTAINER_ROOT] inside the container (`~/workspace`, expanded to `$HOME/workspace`). So there are two deterministic mappings from "container path" to "host real file":
 * - `~/workspace[/…]` → host workspace directory (writing there writes to the host, and it is visible inside the container — bind mount);
 * - Other container absolute paths `/etc/…`, `/root/…` → the corresponding file under the current profile's rootfs directory (exactly the same files the terminal sees inside the container).
 *
 * This lets file tools (read/write/edit) read and write the whole container filesystem without entering PRoot, consistent with what `execute_command` sees.
 *
 * **Profile-aware**: the rootfs directory varies with the currently selected profile (built-in Alpine uses filesDir/rootfs, custom profiles use filesDir/rootfs_<id>).
 * [currentProfile] is cached from [ContainerSettingsRepository] to avoid synchronous DataStore reads; the first frame at startup is the built-in Alpine, same as before this change.
 *
 * This mapper unifies:
 * - Tool inputs (paths given by the AI) resolve via [toHostFile] to real host files;
 * - Paths echoed/returned by tools are converted back via [toContainerPath] to the container view (`~/workspace/…` or `/etc/…`), exposing only container paths to the AI.
 */
@Singleton
class WorkspacePathMapper @Inject constructor(
    private val workspaceRepository: WorkspaceRepository,
    private val containerInstaller: ContainerInstaller,
    private val containerSettingsRepository: ContainerSettingsRepository,
    private val pathHomeResolver: PathHomeResolver
) {
    companion object {
        /** Workspace root path as seen by the AI. The `~` form makes prompts/tool descriptions more natural; internally it is expanded via [resolvedContainerRoot] before matching. */
        const val CONTAINER_ROOT = "~/workspace"
        /** Path of the AI config directory inside the container, bound to the host [ContainerInstaller.aicodeDir] (independent of rootfs). */
        const val AICODE_ROOT = "/root/.aicode"
        private const val TAG = "WorkspacePathMapper"
    }

    /**
     * The currently selected profile (cached, to avoid synchronous DataStore reads). The first frame at startup is the built-in Alpine, same as before this change.
     * Updated by a flow collector after a profile switch; it may briefly differ from the engine cache at the moment of switching, but ensureInstalled and file-tool
     * calls have a natural ordering, so this has no practical effect.
     */
    @Volatile
    private var currentProfile: ContainerProfile = ContainerProfile.BUILTIN_ALPINE

    init {
        CoroutineScope(Dispatchers.IO).launch {
            containerSettingsRepository.activeProfileIdFlow.collect { id ->
                currentProfile = resolveProfile(id)
            }
        }
    }

    private suspend fun resolveProfile(id: String): ContainerProfile {
        val profiles = containerSettingsRepository.customProfilesFlow.first()
        return profiles.firstOrNull { it.id == id }
            ?: profiles.firstOrNull()
            ?: ContainerProfile.BUILTIN_ALPINE
    }

    /** Root directory of the current workspace on the host. */
    private fun hostRoot(): File = File(workspaceRepository.currentPath())

    /** Absolute path after expanding [CONTAINER_ROOT] (`$HOME/workspace`), used for path matching. Falls back to `/root` when home is not ready. */
    private fun resolvedContainerRoot(): String =
        pathHomeResolver.home().trimEnd('/') + "/workspace"

    /** Root directory of the container rootfs on the host (container `/` is this directory; varies with the current profile). */
    private fun rootfsRoot(): File = containerInstaller.rootfsDirFor(currentProfile)

    /** Root of the AI config directory on the host (container `/root/.aicode` is this directory, independent of rootfs). */
    private fun aicodeRoot(): File = containerInstaller.aicodeDir

    /**
     * Resolves a path provided by the AI to a real host file. Supports the following forms:
     * - Container absolute path `~/workspace[/…]` (or the expanded `$HOME/workspace[/…]`) → maps to the host workspace;
     * - Container absolute path `/root/.aicode[/…]` → maps to the host AI config directory (skill / mcp.json, independent of rootfs);
     * - Other container absolute paths `/etc/…`, `/root/…` → map to the corresponding file inside rootfs (container system files);
     * - Relative path `src/Main.kt` → placed under the host workspace root;
     *
     * `/root/.aicode` must be matched before the generic `/` → rootfs rule, otherwise it would land on a temporary copy inside rootfs (lost on upgrade).
     */
    fun toHostFile(path: String): File {
        val root = hostRoot()
        val wsRoot = resolvedContainerRoot()
        val p = pathHomeResolver.expandHome(path.trim())
        val file = when {
            p == wsRoot || p == "$wsRoot/" || p == CONTAINER_ROOT || p == "$CONTAINER_ROOT/" -> root
            p.startsWith("$wsRoot/") -> File(root, p.removePrefix("$wsRoot/"))
            p == AICODE_ROOT || p == "$AICODE_ROOT/" -> aicodeRoot()
            p.startsWith("$AICODE_ROOT/") -> File(aicodeRoot(), p.removePrefix("$AICODE_ROOT/"))
            else -> mountedHostFile(p)
                ?: if (p.startsWith("/")) File(rootfsRoot(), p.removePrefix("/")) else File(root, p)
        }
        FileLogger.v(TAG, "toHostFile '$path' -> ${file.absolutePath}")
        return file
    }

    /**
     * Converts a host path back to a container path:
     * - Inside the workspace → `~/workspace/…`;
     * - Inside the AI config directory → `/root/.aicode/…`;
     * - Inside rootfs → the container absolute path with the rootfs prefix removed (e.g. `/etc/apk/repositories`);
     * - Otherwise returned as-is (rare).
     *
     * The workspace is in `filesDir/projects`, the AI config in `filesDir/aicode`, and rootfs in `filesDir/rootfs`; the three do not overlap,
     * so the check order is unambiguous.
     */
    fun toContainerPath(hostPath: String): String {
        val rootPath = hostRoot().absolutePath.replace('\\', '/')
        val aicodePath = aicodeRoot().absolutePath.replace('\\', '/')
        val rootfsPath = rootfsRoot().absolutePath.replace('\\', '/')
        val abs = File(hostPath).absolutePath.replace('\\', '/')
        val raw = hostPath.trim().replace('\\', '/')
        val resolvedWs = resolvedContainerRoot().replace('\\', '/')
        return when {
            abs == rootPath -> CONTAINER_ROOT
            abs.startsWith("$rootPath/") -> CONTAINER_ROOT + "/" + abs.removePrefix("$rootPath/")
            // The expanded $HOME/workspace form is also converted back to ~/workspace (bind-mount paths may appear in absolute form)
            raw == resolvedWs || abs == resolvedWs -> CONTAINER_ROOT
            raw.startsWith("$resolvedWs/") -> CONTAINER_ROOT + "/" + raw.removePrefix("$resolvedWs/")
            abs.startsWith("$resolvedWs/") -> CONTAINER_ROOT + "/" + abs.removePrefix("$resolvedWs/")
            abs == aicodePath -> AICODE_ROOT
            abs.startsWith("$aicodePath/") -> AICODE_ROOT + "/" + abs.removePrefix("$aicodePath/")
            abs == rootfsPath -> "/"
            abs.startsWith("$rootfsPath/") -> "/" + abs.removePrefix("$rootfsPath/")
            else -> mountedContainerPath(abs) ?: hostPath
        }
    }

    /**
     * Parses the current profile's extra mounts ([ContainerProfile.extraBindings], format `local source:container target`) into
     * (host source, expanded container target with trailing slash removed), sorted by container-target length descending (longest prefix first, to avoid nested-mount ambiguity).
     * File tools do not enter PRoot; without this mapping, mount paths would fall back into rootfs,
     * which would differ from what the in-container shell (proot `-b` bind mount) sees.
     */
    private fun extraMounts(): List<Pair<String, String>> =
        currentProfile.extraBindings.mapNotNull { binding ->
            val idx = binding.indexOf(':')
            val src = (if (idx >= 0) binding.substring(0, idx) else binding).trim()
            val dstRaw = if (idx >= 0) binding.substring(idx + 1) else binding
            val dst = pathHomeResolver.expandHome(dstRaw.trim()).trimEnd('/')
            if (src.isEmpty() || dst.isEmpty()) null else src to dst
        }.sortedByDescending { it.second.length }

    /** If [p] (an already-expanded container path) falls under an extra mount target, maps it to the real file under the host source; otherwise null. */
    private fun mountedHostFile(p: String): File? {
        for ((src, dst) in extraMounts()) {
            when {
                p == dst || p == "$dst/" -> return File(src)
                p.startsWith("$dst/") -> return File(src, p.removePrefix("$dst/"))
            }
        }
        return null
    }

    /** If host [abs] falls under an extra mount source, converts it back to the container target path; otherwise null. */
    private fun mountedContainerPath(abs: String): String? {
        for ((src, dst) in extraMounts()) {
            val srcAbs = File(src).absolutePath
            when {
                abs == srcAbs -> return dst
                abs.startsWith("$srcAbs/") -> return "$dst/" + abs.removePrefix("$srcAbs/")
            }
        }
        return null
    }
}
