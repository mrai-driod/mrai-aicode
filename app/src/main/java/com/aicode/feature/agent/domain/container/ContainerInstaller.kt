package com.aicode.feature.agent.domain.container

import android.content.Context
import android.system.Os
import com.aicode.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import java.util.zip.GZIPInputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Responsible for installing the Alpine rootfs and PRoot binaries packed into assets into the App's private directory.
 *
 * Automatically loads the corresponding resources based on the device architecture (ARM / x86). targetSdk is locked to 28, so files in the data directory can be executed (see build.gradle.kts).
 */
@Singleton
class ContainerInstaller @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val containerOsDetector: ContainerOsDetector
) {
    companion object {
        private const val TAG = "ContainerInstaller"
        @Volatile private var docsExtractedSession = false

        /**
         * Extracts docs from assets to ~/.aicode/docs (built-in usage guidance), supporting category subdirectories such as guide/ advanced/.
         *
         * Deletes the whole directory before extraction: docs are categorized by feature, and category adjustments between versions change file paths; overwriting without cleaning would leave
         * same-named old files from the previous version at other paths, and the AI might read stale content.
         */
        fun extractDocs(context: Context) {
            if (docsExtractedSession) return
            val destDir = File(File(context.filesDir, "aicode"), "docs")
            runCatching {
                destDir.deleteRecursively()
                destDir.mkdirs()
                extractAssetsRecursive(context, "docs", destDir)
                docsExtractedSession = true
            }.onFailure {
                FileLogger.w(TAG, "Failed to extract built-in docs: ${it.message}", it)
            }
        }

        /**
         * Extracts built-in prompts from assets to ~/.aicode/prompts/, fully overwriting on every startup, so prompts update with App upgrades.
         *
         * User custom overrides are placed in ~/.aicode/prompts.custom/ (same name overrides), and this method does not touch that directory,
         * so fragments rewritten by the user are not overwritten by upgrades. See [com.aicode.feature.agent.domain.prompt.SystemPromptProvider].
         */
        fun extractPrompts(context: Context) {
            val destDir = File(File(context.filesDir, "aicode"), "prompts")
            destDir.mkdirs()
            runCatching {
                extractAssetsRecursive(context, "prompts", destDir)
            }.onFailure {
                FileLogger.w(TAG, "Failed to extract built-in prompts: ${it.message}", it)
            }
        }

        /** Recursively copies a directory under assets to the target directory, supporting subdirectories (e.g. prompts/agent/, docs/guide/). */
        private fun extractAssetsRecursive(context: Context, assetDir: String, destDir: File) {
            val entries = context.assets.list(assetDir) ?: return
            destDir.mkdirs()
            for (entry in entries) {
                val assetPath = "$assetDir/$entry"
                val destFile = File(destDir, entry)
                try {
                    context.assets.open(assetPath).use { input ->
                        destFile.outputStream().use { output -> input.copyTo(output) }
                    }
                } catch (e: IOException) {
                    // Directory entry: assets.open throws IOException for directories, so handle recursively
                    extractAssetsRecursive(context, assetPath, destFile)
                }
            }
        }

        /**
         * Extracts built-in sub-agent definitions (e.g. Explore) from assets to ~/.aicode/agents/.
         * If a file already exists it is not overwritten, preserving the user's modifications and their choice to rebuild after deletion.
         */
        fun extractAgents(context: Context) {
            val destDir = File(File(context.filesDir, "aicode"), "agents")
            destDir.mkdirs()
            runCatching {
                val entries = context.assets.list("agents") ?: return@runCatching
                for (entry in entries) {
                    val destFile = File(destDir, entry)
                    if (!destFile.exists()) {
                        context.assets.open("agents/$entry").use { input ->
                            destFile.outputStream().use { output -> input.copyTo(output) }
                        }
                    }
                }
            }.onFailure {
                FileLogger.w(TAG, "Failed to extract built-in sub-agent definitions: ${it.message}", it)
            }
        }

        /**
         * Extracts built-in scripts (e.g. the panel demo_balance.py) from assets to ~/.aicode/scripts/.
         * If a file already exists it is not overwritten, preserving the user's modifications.
         */
        fun extractScripts(context: Context) {
            val destDir = File(File(context.filesDir, "aicode"), "scripts")
            destDir.mkdirs()
            runCatching {
                val entries = context.assets.list("aicode/scripts") ?: return@runCatching
                for (entry in entries) {
                    val destFile = File(destDir, entry)
                    if (!destFile.exists()) {
                        context.assets.open("aicode/scripts/$entry").use { input ->
                            destFile.outputStream().use { output -> input.copyTo(output) }
                        }
                        destFile.setExecutable(true, false)
                    }
                }
            }.onFailure {
                FileLogger.w(TAG, "Failed to extract built-in scripts: ${it.message}", it)
            }
        }

        /**
         * Extracts the custom git credential helper from assets to ~/.aicode/git-credential-aicode and sets the executable bit.
         *
         * Via [LinuxContainerEngine]'s -b binding it is /root/.aicode/git-credential-aicode inside the container,
         * registered by the container initialization menu (provision.sh) in `.gitconfig` as the second credential.helper,
         * after `store` as a backstop for not-logged-in (double safety). For the helper's detailed behaviour see assets/aicode/git-credential-aicode.
         *
         * Extracted at startup, independent of provisioning success: if provisioning fails git is not installed, so the helper configuration not existing does not matter;
         * once git is installed and the configuration is registered, the helper is immediately usable. Extraction failure only warns and does not throw (a missing helper only means no dialog when not logged in;
         * git can still run bare and report an authentication failure, which is not fatal).
         */
        fun extractCredentialHelper(context: Context) {
            val dest = File(File(context.filesDir, "aicode"), "git-credential-aicode")
            runCatching {
                dest.parentFile?.mkdirs()
                context.assets.open("aicode/git-credential-aicode").use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                // Set the executable bit for all users (the proot process runs with the App uid; see the 0o111 pattern in [copyAsset]).
                if (!dest.setExecutable(true, false)) {
                    FileLogger.w(TAG, "setExecutable returned false: ${dest.absolutePath}")
                }
            }.onFailure {
                FileLogger.w(TAG, "Failed to extract git credential helper: ${it.message}", it)
            }
        }

        /**
         * Extracts the container initialization dependency installation script from assets to ~/.aicode/provision.sh and sets the executable bit.
         *
         * Via [LinuxContainerEngine]'s -b binding it is /root/.aicode/provision.sh inside the container, executed with `sh` by
         * the initialization menu on first entering the terminal - the script installs base packages per package manager
         * (apk/apt-get/dnf/yum/pacman); the package list is maintained inside the script rather than in code.
         * Extracted at startup and overwritten every time (the script updates with the App version). Extraction failure only warns and does not throw:
         * without the script the initialization menu will not appear, and the user can install base tools manually.
         */
        fun extractProvisionScript(context: Context) {
            val dest = File(File(context.filesDir, "aicode"), "provision.sh")
            runCatching {
                dest.parentFile?.mkdirs()
                context.assets.open("aicode/provision.sh").use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                if (!dest.setExecutable(true, false)) {
                    FileLogger.w(TAG, "setExecutable returned false: ${dest.absolutePath}")
                }
            }.onFailure {
                FileLogger.w(TAG, "Failed to extract provision script: ${it.message}", it)
            }
        }

        /**
         * Extracts the environment tool (`aicode` command + shared library lib/) from assets to ~/.aicode/bin and ~/.aicode/lib and sets executable bits.
         *
         * Via [LinuxContainerEngine]'s -b binding it is /root/.aicode/bin/aicode inside the container (on PATH, see
         * buildContainerEnv) and /root/.aicode/lib/ (env-common.sh, android-sdk.sh and the scenarios/ subdirectory),
         * for the user to run `aicode` at any time to install development environments (scenario-based). Overwritten every time (updates with the App version); extraction failure only warns
         * and does not throw: when the tool is missing, the container initialization menu (provision.sh) prompts that it is not ready, and the user can restart the App to re-extract.
         */
        fun extractEnvTool(context: Context) {
            runCatching {
                extractDirOverwrite(context, "aicode/bin", File(File(context.filesDir, "aicode"), "bin"), executable = true)
                extractDirOverwrite(context, "aicode/lib", File(File(context.filesDir, "aicode"), "lib"), executable = false)
            }.onFailure {
                FileLogger.w(TAG, "Failed to extract environment tool: ${it.message}", it)
            }
        }

        /** Recursively copies a directory under assets to [destDir] (overwrites every time, supports subdirectories such as lib/scenarios/); when [executable], sets the executable bit on each file. */
        private fun extractDirOverwrite(context: Context, assetDir: String, destDir: File, executable: Boolean) {
            val entries = context.assets.list(assetDir) ?: return
            destDir.mkdirs()
            for (entry in entries) {
                val assetPath = "$assetDir/$entry"
                val dest = File(destDir, entry)
                try {
                    context.assets.open(assetPath).use { input ->
                        dest.outputStream().use { output -> input.copyTo(output) }
                    }
                    if (executable && !dest.setExecutable(true, false)) {
                        FileLogger.w(TAG, "setExecutable returned false: ${dest.absolutePath}")
                    }
                } catch (e: IOException) {
                    // Directory entry: assets.open throws IOException for directories, so copy recursively (e.g. lib/scenarios/)
                    extractDirOverwrite(context, assetPath, dest, executable)
                }
            }
        }

        /**
         * The apk branch corresponding to the alpine-rootfs version in assets, used to build mirror source addresses.
         * Fixed at v3.21: consistent with [INSTALL_VERSION] (alpine-3.21.3); that version's apk-tools 2.14 is reliable under proot.
         */
        /**
         * Installation version. Increment by 1 when changing rootfs / proot or the installation logic, triggering re-extraction.
         * Keep it consistent with the Alpine version actually placed in assets for troubleshooting.
         */
        private const val INSTALL_VERSION = "alpine-3.21.3-v6"

        /** When deleting a rootfs, call back progress once per this many entries deleted (too frequent would flood the UI state flow). */
        private const val DELETE_PROGRESS_STEP = 2000
    }

    /** Architecture-specific directory within assets */
    val ASSET_DIR: String
        get() {
            // Prefer selecting the corresponding image directory by device ABI.
            // But after release packages are split by flavor, a single-architecture package contains only one image set (armsolo->arm, x86solo->x86):
            // if that set is not in assets (e.g. an armsolo package installed on a device reporting only x86), the code below falls back
            // to the set that actually exists, avoiding open() crashing directly - whether proot can actually run is decided by the device ABI,
            // but at least the asset lookup layer will not break.
            val preferX86 = android.os.Build.SUPPORTED_ABIS.any { it.contains("x86") }
            val first = if (preferX86) "container/x86" else "container/arm"
            val fallback = if (preferX86) "container/arm" else "container/x86"
            return if (assetExists(first)) first else fallback
        }

    /** Lightweight probe of whether an asset path is packed into the current APK (for [ASSET_DIR]'s fallback judgement) */
    private fun assetExists(path: String): Boolean =
        context.assets.list(path.substringBeforeLast('/'))?.any { it == path.substringAfterLast('/') } == true

    // Deliberately uses the neutral .bin suffix: AGP's asset merging treats .tar.gz/.tgz as archives, automatically extracting and renaming them,
    // causing runtime open("...tar.gz") to not find the file. .bin makes it be packed as-is as an ordinary binary.
    val ASSET_ROOTFS: String get() = "$ASSET_DIR/alpine-rootfs.bin"

    /** Root directory for rootfs extraction */
    val rootfsDir: File
        get() = File(context.filesDir, "rootfs")

    /**
     * AI configuration data root directory (skill instructions + MCP configuration), fixed in the app's private filesDir.
     *
     * Deliberately **independent of [rootfsDir]**: the rootfs is deleted as a whole and reinstalled on a container version upgrade (see [installRootfsIfNeed]),
     * while this directory carries user data and must be preserved across upgrades. It is bound by [com.aicode.feature.agent.domain.container.LinuxContainerEngine]
     * to `/root/.aicode` inside the container, so the `/root/.aicode` seen by the AI / terminal actually lands here.
     */
    val aicodeDir: File
        get() = File(context.filesDir, "aicode")

    /**
     * Directory holding the full proot set: the APK's `lib/<abi>/lib*.so` are extracted here by the installer (see build.gradle.kts's
     * jniLibs sourceSet and useLegacyPackaging). **Must not be changed back to filesDir** - it is the only location in the App directory
     * where execve is allowed; files under filesDir are refused execution by W^X on targetSdk 29+.
     */
    private val nativeLibDir: File
        get() = File(context.applicationInfo.nativeLibraryDir)

    /** PRoot executable (Termux build, includes statx, dynamically links libtalloc/libandroid-shmem) */
    val prootBin: File
        get() = File(nativeLibDir, "libproot.so")

    /** PRoot's 64/32-bit loaders (Termux proot has a separate loader, pointed to by PROOT_LOADER/_32). */
    val prootLoader: File
        get() = File(nativeLibDir, "libproot-loader.so")
    val prootLoader32: File
        get() = File(nativeLibDir, "libproot-loader32.so")

    /** proot's dynamic dependency library directory (libtalloc.so / libandroid-shmem.so), pointed to by LD_LIBRARY_PATH. */
    val prootLibDir: File
        get() = nativeLibDir

    /**
     * The built-in container's PRoot temp directory (Android has no /tmp). **Private**: container startup always goes through [prootTmpDirFor],
     * to avoid pointing other containers into the built-in rootfs again.
     * Placed at the rootfs's /tmp (host filesDir/rootfs/tmp): the cache directory is cleaned by the system (after cache clearing, proot
     * cannot find the temp directory and reports can't canonicalize), while the files directory is stable.
     */
    private val prootTmpDir: File
        get() = File(rootfsDir, "tmp")

    /**
     * The /tmp inside [profile]'s own rootfs, i.e. that container's `PROOT_TMP_DIR`.
     *
     * Must be obtained per profile: this is a host path and each container's rootfs directory is isolated; sharing the built-in container's tmp would, after the built-in rootfs
     * is reset and deleted, make the other containers all report can't canonicalize too (that it works while the built-in rootfs exists is just coincidence).
     */
    fun prootTmpDirFor(profile: ContainerProfile): File = File(rootfsDirFor(profile), "tmp")

    /** Marker file; its content is the installed version number */
    private val installedMarker: File
        get() = File(rootfsDir, ".installed")

    /** Checks whether the rootfs has been extracted and is ready at the current version (proot is installed with the APK and is not part of the installation state) */
    fun isInstalled(): Boolean {
        if (!rootfsDir.isDirectory) return false
        val marker = installedMarker
        return marker.exists() && marker.readText().trim() == INSTALL_VERSION
    }

    /**
     * If not installed (or the version does not match), extracts and installs from assets. Idempotent; can be called before each command execution.
     *
     * [onProgress] is called back at each stage of actual extraction to update [ContainerInitState]; the fast path when already installed does not call it.
     */
    suspend fun installRootfsIfNeed(
        onProgress: (ContainerInitState) -> Unit = {}
    ) = withContext(Dispatchers.IO) {
        if (isInstalled()) {
            FileLogger.d(TAG, "rootfs is already the current version ($INSTALL_VERSION), skipping extraction")
            return@withContext
        }

        val startedAt = System.currentTimeMillis()
        FileLogger.i(
            TAG,
            "Starting container rootfs installation (version $INSTALL_VERSION, asset=$ASSET_ROOTFS, target=${rootfsDir.absolutePath}, " +
                "parent directory available space=${usableSpaceMb(rootfsDir.parentFile)}MB)"
        )
        try {
            // On version mismatch, clear the old one to ensure a clean install (deleting a large rootfs is slow, so report progress to the UI)
            purgeRootfs(rootfsDir, installedMarker) { onProgress(ContainerInitState.CleaningOldRootfs(it)) }
            rootfsDir.mkdirs()
            if (!rootfsDir.isDirectory) FileLogger.e(TAG, "Failed to create rootfs directory: ${rootfsDir.absolutePath}")

            val entries = extractRootfs(onProgress)
            configureResolvConf()
            prootTmpDir.mkdirs()
            installedMarker.writeText(INSTALL_VERSION)
            FileLogger.i(
                TAG,
                "Container rootfs installation complete: $entries entries, took ${System.currentTimeMillis() - startedAt}ms, " +
                    "marker=${installedMarker.absolutePath}"
            )
        } catch (e: Exception) {
            FileLogger.e(
                TAG,
                "Container rootfs installation failed (took ${System.currentTimeMillis() - startedAt}ms, " +
                    "remaining space=${usableSpaceMb(rootfsDir.parentFile)}MB)",
                e
            )
            throw e
        }
    }

    /** A directory's usable space (MB), used to judge whether disk is insufficient when extraction fails; returns -1 when the path cannot be obtained. */
    private fun usableSpaceMb(dir: File?): Long =
        runCatching { (dir ?: return -1).usableSpace / (1024 * 1024) }.getOrDefault(-1)

    /**
     * Returns the rootfs directory for [profile]: built-in is still [rootfsDir] (unchanged), custom local images use filesDir/rootfs_<id>.
     * A remote SSH profile has no local rootfs, so a placeholder directory is returned (it will not be used/created).
     * Directory isolation - built-in and custom do not share with or delete each other, and switching back to built-in leaves its rootfs untouched.
     */
    fun rootfsDirFor(profile: ContainerProfile): File =
        if (profile.isBuiltin) rootfsDir
        else File(context.filesDir, "rootfs_${profile.id}")

    /** Installed marker for a custom image (independent of the built-in .installed, to avoid confusion). */
    private fun customInstalledMarker(profile: ContainerProfile): File =
        File(rootfsDirFor(profile), ".installed_custom")

    /** Judges for [profile] whether it is installed and ready: built-in uses the existing version check, custom local checks directory and marker, remote SSH is always ready. */
    fun isInstalledFor(profile: ContainerProfile): Boolean =
        when {
            profile.isBuiltin -> isInstalled()
            profile.rootfsSource is RootfsSource.RemoteSsh -> true
            else -> rootfsDirFor(profile).isDirectory && customInstalledMarker(profile).exists()
        }

    /**
     * Extracts and installs the rootfs for [profile]. Built-in uses the full assets flow (rootfs/resolv/apk sources); custom local images only extract
     * tar.gz + write DNS (do not write apk sources). Neither installs packages automatically - base tools are installed via the user's choice guided by the initialization menu
     * on entering the terminal (assets/aicode/provision.sh). A remote SSH profile has no local rootfs and returns directly
     * (command execution goes through [RemoteSshEngine] and needs no local rootfs).
     */
    suspend fun installRootfsIfNeed(
        profile: ContainerProfile,
        onProgress: (ContainerInitState) -> Unit = {}
    ) = withContext(Dispatchers.IO) {
        if (isInstalledFor(profile)) return@withContext

        // The rootfs will be re-extracted and the container OS may change, so clear the old identification cache to let the next run re-detect.
        containerOsDetector.clear(profile.id)

        if (profile.isBuiltin) {
            installRootfsIfNeed(onProgress)
            return@withContext
        }

        // Remote SSH profile: no local rootfs to extract, treated as ready.
        if (profile.rootfsSource is RootfsSource.RemoteSsh) return@withContext

        val dest = rootfsDirFor(profile)
        FileLogger.i(TAG, "Installing custom container rootfs: ${profile.id} -> ${dest.absolutePath}")
        purgeRootfs(dest, customInstalledMarker(profile)) { onProgress(ContainerInitState.CleaningOldRootfs(it)) }
        dest.mkdirs()

        when (val src = profile.rootfsSource) {
            is RootfsSource.Asset -> context.assets.open("${ASSET_DIR}/${src.path}").use {
                extractRootfsTo(dest, it, CompressedFormat.GZIP, onProgress)
            }
            is RootfsSource.LocalFile -> {
                val uri = android.net.Uri.parse(src.uri)
                val format = if (src.uri.endsWith(".xz") || src.uri.endsWith(".txz"))
                    CompressedFormat.XZ else CompressedFormat.GZIP
                context.contentResolver.openInputStream(uri)?.use {
                    extractRootfsTo(dest, it, format, onProgress)
                } ?: FileLogger.w(TAG, "Failed to open imported rootfs uri: ${src.uri}")
            }
            is RootfsSource.RemoteSsh -> { /* No local rootfs; already returned early above */ }
        }
        configureResolvConf(dest)
        prootTmpDirFor(profile).mkdirs()
        repairRootfsCompatibility(dest)
        customInstalledMarker(profile).writeText("custom")
        FileLogger.i(TAG, "Custom container rootfs installation complete: ${profile.id}")
    }

    /**
     * Rootfs compatibility inspection (idempotent, silent on failure): repairs known Ubuntu 25.10+ layout problems.
     * Called uniformly for all containers; its scope is converged by internal existence checks - non-matching containers get zero changes.
     */
    fun repairRootfsCompatibility(rootfs: File) {
        fixCoreutilsPermissions(rootfs)
        fixNodeGlobEntry(rootfs)
    }

    private fun fixCoreutilsPermissions(rootfs: File) {
        val coreutilsDir = File(rootfs, "usr/lib/cargo/bin/coreutils")
        if (!coreutilsDir.isDirectory) return
        val target = PosixFilePermissions.fromString("rwxr-xr-x")
        var fixed = 0
        fun fix(path: File) {
            runCatching {
                val p = path.toPath()
                if (Files.getPosixFilePermissions(p) != target) {
                    Files.setPosixFilePermissions(p, target)
                    fixed++
                }
            }.onFailure { FileLogger.w(TAG, "Failed to fix coreutils permissions: ${path.absolutePath}", it) }
        }
        fix(File(rootfs, "usr/lib/cargo"))
        fix(File(rootfs, "usr/lib/cargo/bin"))
        fix(coreutilsDir)
        coreutilsDir.listFiles()?.forEach { fix(it) }
        if (fixed > 0) {
            FileLogger.i(TAG, "Fixed container coreutils permissions: $fixed files (${coreutilsDir.absolutePath})")
        }
    }

    /**
     * Repairs an Ubuntu 26.04 repository packaging bug: node-glob 10.3.6 uses a `dist/cjs/src -> .` self-referencing symlink for packaging
     * flattening; node 22+'s realpath judges it ELOOP, and npm reports MODULE_NOT_FOUND on startup (unrelated to PRoot;
     * native Ubuntu 26.04 is also affected). The fix under the real-device layout: change the package.json entry from dist/cjs/src/
     * to point to dist/cjs/, where the actually flattened files are. Idempotent: if the entry is already normal, it is not touched.
     */
    private fun fixNodeGlobEntry(rootfs: File) {
        val candidates = listOf(
            File(rootfs, "usr/share/nodejs/glob/package.json"),
            File(rootfs, "usr/share/node_modules/glob/package.json")
        )
        for (f in candidates) {
            if (!f.isFile) continue
            runCatching {
                val text = f.readText()
                if (text.contains("dist/cjs/src/")) {
                    f.writeText(text.replace("dist/cjs/src/", "dist/cjs/"))
                    FileLogger.i(TAG, "Fixed node-glob entry: ${f.absolutePath}")
                }
            }.onFailure { FileLogger.w(TAG, "Failed to fix node-glob entry: ${f.absolutePath}", it) }
        }
    }

    /**
     * Deletes a custom profile's rootfs directory (called when deleting the profile). The built-in rootfs cannot be deleted; remote SSH has no rootfs to delete.
     * [onProgress] reports the number of entries deleted, for the UI to show progress.
     */
    suspend fun deleteCustomRootfs(
        profile: ContainerProfile,
        onProgress: (Int) -> Unit = {}
    ) = withContext(Dispatchers.IO) {
        // The profile was deleted or its image changed, so the cache is invalidated with it (re-detected on rerun).
        containerOsDetector.clear(profile.id)
        if (profile.isBuiltin) return@withContext
        if (profile.rootfsSource is RootfsSource.RemoteSsh) return@withContext
        purgeRootfs(rootfsDirFor(profile), customInstalledMarker(profile), onProgress)
    }

    /**
     * Resets the built-in Alpine container: deletes its rootfs directory (including the .installed / .provisioned markers);
     * the next [ensureInstalled] will re-extract it, and entering the terminal will re-guide installation via the initialization menu. Called by the built-in image's "Reset" button.
     */
    suspend fun resetBuiltinRootfs(onProgress: (Int) -> Unit = {}) = withContext(Dispatchers.IO) {
        purgeRootfs(rootfsDir, installedMarker, onProgress)
    }

    /** Uniformly resets the rootfs for [profile]: built-in goes through [resetBuiltinRootfs], custom local deletes its rootfs directory, remote SSH has no local data and does nothing. */
    suspend fun resetRootfs(profile: ContainerProfile, onProgress: (Int) -> Unit = {}) {
        if (profile.isBuiltin) {
            resetBuiltinRootfs(onProgress)
            return
        }
        deleteCustomRootfs(profile, onProgress)
    }

    /**
     * Empties a rootfs directory; [onProgress] reports the cumulative number of entries deleted.
     *
     * Deletes [marker] first: a rootfs full of tools has on the order of a hundred thousand inodes, and deleting it takes tens of seconds; if the process is killed midway, the marker
     * is already gone, so the incomplete directory will not be treated by [isInstalledFor] as an installed container, and the next container entry will delete it cleanly and re-extract.
     */
    private fun purgeRootfs(dir: File, marker: File, onProgress: (Int) -> Unit) {
        if (!dir.exists()) return
        marker.delete()
        val count = deleteTree(dir, 0, onProgress)
        FileLogger.i(TAG, "Deleted rootfs ${dir.name}, $count entries in total")
    }

    /**
     * Recursively deletes [root], returning the cumulative number of deleted entries counted from [startCount], calling back once per [DELETE_PROGRESS_STEP] entries.
     *
     * Does not use [File.deleteRecursively]: it judges by `File.isDirectory` and follows symlinks downwards - a rootfs's
     * /bin and /usr/lib are full of symlinks; following them not only wastes a pass over the link targets (several times slower on large containers), but when a link points to its own ancestor
     * it keeps descending until the path is too long, not to mention deleting data elsewhere when a link points outside the rootfs. Here
     * NOFOLLOW_LINKS is used to judge directories, and symlinks are always unlinked as ordinary entries.
     */
    private fun deleteTree(root: File, startCount: Int, onProgress: (Int) -> Unit): Int {
        var count = startCount
        val pending = ArrayDeque<File>()
        val dirs = ArrayDeque<File>()
        pending.addLast(root)
        while (pending.isNotEmpty()) {
            val entry = pending.removeLast()
            if (isRealDirectory(entry)) {
                dirs.addLast(entry)
                entry.listFiles()?.forEach { pending.addLast(it) }
            } else if (entry.delete()) {
                count++
                if (count % DELETE_PROGRESS_STEP == 0) onProgress(count)
            }
        }
        // A directory can only be deleted once it is empty itself: dirs are enqueued in discovery order from shallow to deep, and deleting from the tail backwards is deepest first.
        while (dirs.isNotEmpty()) {
            if (dirs.removeLast().delete()) count++
        }
        onProgress(count)
        return count
    }

    /** Whether it is a real directory: a symlink returns false even if it points to a directory, preventing deletion from following the link downwards. */
    private fun isRealDirectory(file: File): Boolean = runCatching {
        Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).isDirectory
    }.getOrDefault(false)

    init {
        CoroutineScope(Dispatchers.IO).launch {
            extractDocs(context)
            extractCredentialHelper(context)
            extractProvisionScript(context)
            extractEnvTool(context)
        }
    }

    /** Extracts docs from assets to ~/.aicode/docs (built-in usage guidance) */
    fun extractDocs() = extractDocs(context)

    /** Extracts the git credential helper from assets to ~/.aicode/git-credential-aicode and sets the executable bit. */
    fun extractCredentialHelper() = extractCredentialHelper(context)

    /** Extracts the container initialization dependency installation script from assets to ~/.aicode/provision.sh and sets the executable bit. */
    fun extractProvisionScript() = extractProvisionScript(context)

    /** Extracts the environment tool (aicode command + shared library) from assets to ~/.aicode/{bin,lib} and sets executable bits. */
    fun extractEnvTool() = extractEnvTool(context)

    /** Extracts alpine-minirootfs.tar.gz, correctly handling directories/files/symlinks/hard links and permission bits; returns the number of extracted entries. */
    private fun extractRootfs(onProgress: (ContainerInitState) -> Unit): Int {
        context.assets.open(ASSET_ROOTFS).use { rawIn ->
            return extractRootfsTo(rootfsDir, rawIn, CompressedFormat.GZIP, onProgress)
        }
    }

    /** Image compression format: the built-in Alpine uses gzip; user-imported ones may be gzip or xz. */
    enum class CompressedFormat { GZIP, XZ }

    /**
     * Extracts a tar.gz / tar.xz stream to [destDir], correctly handling directories/files/symlinks/hard links and permission bits.
     * Shared by the built-in Alpine ([extractRootfs] passes an assets stream) and user custom images ([installRootfsIfNeed] passes a content uri stream).
     */
    fun extractRootfsTo(
        destDir: File,
        input: java.io.InputStream,
        format: CompressedFormat = CompressedFormat.GZIP,
        onProgress: (ContainerInitState) -> Unit = {}
    ): Int {
        var processed = 0
        val startedAt = System.currentTimeMillis()
        val decompressed = when (format) {
            CompressedFormat.GZIP -> GZIPInputStream(input)
            CompressedFormat.XZ -> XZCompressorInputStream(input)
        }
        decompressed.use { decompIn ->
            TarArchiveInputStream(decompIn).use { tarIn ->
                var entry: TarArchiveEntry? = tarIn.nextEntry
                while (entry != null) {
                    extractEntry(destDir, tarIn, entry)
                    processed++
                    onProgress(ContainerInitState.ExtractingRootfs(processed))
                    entry = tarIn.nextEntry
                }
            }
        }
        FileLogger.i(
            TAG,
            "Extraction to ${destDir.absolutePath} complete: $processed entries, took ${System.currentTimeMillis() - startedAt}ms"
        )
        return processed
    }

    private fun extractEntry(
        destDir: File,
        tarIn: TarArchiveInputStream,
        entry: TarArchiveEntry
    ) {
        val outFile = File(destDir, entry.name)

        // Prevent zip-slip: ensure the extraction target lands inside destDir
        val canonicalRoot = destDir.canonicalPath
        if (!outFile.canonicalPath.startsWith(canonicalRoot + File.separator) &&
            outFile.canonicalPath != canonicalRoot
        ) {
            FileLogger.w(TAG, "Skipping out-of-bounds entry: ${entry.name}")
            return
        }

        when {
            entry.isDirectory -> outFile.mkdirs()

            entry.isSymbolicLink -> {
                outFile.parentFile?.mkdirs()
                // A symlink's target may be a relative/absolute path; create it as-is (resolved by proot inside the container)
                if (outFile.exists()) outFile.delete()
                runCatching { Os.symlink(entry.linkName, outFile.absolutePath) }
                    .onFailure { FileLogger.w(TAG, "symlink failed ${entry.name} -> ${entry.linkName}: ${it.message}") }
            }

            entry.isLink -> {
                // Hard link: linkName points to another file already extracted from the tar
                outFile.parentFile?.mkdirs()
                val target = File(destDir, entry.linkName)
                if (outFile.exists()) outFile.delete()
                runCatching { Os.link(target.absolutePath, outFile.absolutePath) }
                    .onFailure {
                        // Degrade to copying, ensuring the file exists
                        FileLogger.w(TAG, "hardlink failed ${entry.name} -> ${entry.linkName}, copying instead: ${it.message}")
                        runCatching { target.copyTo(outFile, overwrite = true) }
                    }
            }

            entry.isFile -> {
                outFile.parentFile?.mkdirs()
                outFile.outputStream().use { tarIn.copyTo(it) }
                applyMode(outFile, entry.mode)
            }

            else -> FileLogger.d(TAG, "Ignoring unsupported entry type: ${entry.name}")
        }
    }

    /** Sets executable bits per the tar entry's mode (if the owner has the x bit, execution is opened to everyone) */
    private fun applyMode(file: File, mode: Int) {
        val ownerExecutable = (mode and 0b001_000_000) != 0 // 0100
        if (ownerExecutable) {
            file.setExecutable(true, false)
        }
        file.setReadable(true, false)
        // owner write bit
        if ((mode and 0b010_000_000) != 0) file.setWritable(true, false)
    }

    /**
     * Writes DNS inside the container, otherwise network operations such as apk/npm will fail because domain names cannot be resolved.
     * Uses Alibaba Cloud public DNS: resolution is faster/more stable in China, and 8.8.8.8 is blocked in some network environments.
     * If it is already a valid configuration (a regular file containing nameserver) it is left alone, respecting the existing in-container setting;
     * an empty file or bad link (Ubuntu/Debian bases often link resolv.conf to systemd-resolved's stub, which is unusable under PRoot) is deleted first and then written.
     */
    private fun configureResolvConf(rootfs: File = rootfsDir) {
        val etc = File(rootfs, "etc").apply { mkdirs() }
        val conf = File(etc, "resolv.conf")
        if (conf.isFile) {
            runCatching {
                if (conf.readText().contains("nameserver")) {
                    FileLogger.d(TAG, "resolv.conf already has a valid configuration, leaving it unchanged: ${conf.absolutePath}")
                    return
                }
            }
        }
        conf.delete()
        conf.writeText("nameserver 223.5.5.5\nnameserver 223.6.6.6\n")
        FileLogger.i(TAG, "Wrote container DNS configuration: ${conf.absolutePath}")
    }
}
