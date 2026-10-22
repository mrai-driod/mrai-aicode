package com.aicode.feature.agent.domain.container

import com.aicode.feature.settings.data.repository.ExecutionMode
import kotlinx.serialization.Serializable

/**
 * A switchable container configuration: image source + shell path + extra proot bindings/arguments.
 *
 * The built-in [BUILTIN_ALPINE] describes the existing Alpine rootfs (from assets). User-defined profiles provide
 * a rootfs by importing a tar.gz. Every container shows the same initialization menu the first time its terminal
 * is entered (see assets/aicode/provision.sh), where the user chooses automatic installation of the base tools or
 * manual installation; a package installation failure does not block entering the shell, and handling is consistent.
 *
 * A remote SSH profile ([mode] == [ExecutionMode.REMOTE_SSH]) does not import a local rootfs; instead it binds an
 * SSH channel already configured for the workspace ([RootfsSource.RemoteSsh]), and command execution goes through [RemoteSshEngine].
 */
@Serializable
data class ContainerProfile(
    val id: String,
    val name: String,
    val rootfsSource: RootfsSource,
    /** Shell for custom images (such as /bin/sh or /bin/bash); when unspecified, chosen automatically from the provision state. */
    val shellPath: String?,
    /** Extra -b bindings, such as ["/sdcard:/mnt/sdcard"], each appended to the proot argv as `-b <binding>`. */
    val extraBindings: List<String> = emptyList(),
    /** Extra proot arguments, appended verbatim to the base argv (such as ["-k","..."]). */
    val extraArgs: List<String> = emptyList(),
    /** Custom environment variables, injected into processes inside the container (overriding defaults of the same name). */
    val env: Map<String, String> = emptyMap(),
    val isBuiltin: Boolean,
    /** This profile's execution mode: local PRoot container or remote SSH. Selecting it switches the global [ExecutionMode] accordingly. */
    val mode: ExecutionMode = ExecutionMode.LOCAL_PROOT,
    /** Time added (millisecond timestamp); the container list is sorted descending by this, so legacy data / built-ins with the default 0 come last. */
    val createdAt: Long = 0
) {
    companion object {
        const val BUILTIN_ID = "builtin-alpine"

        /**
         * Proot arguments injected by default for new containers. They deliberately go through the same path as
         * user-added arguments ([extraArgs], appended at the end of the proot argv) rather than being hardcoded
         * in the engine: a hardcoded position would differ from the manually added one, causing abnormal
         * --link2symlink behaviour, and could not be undone on the edit page.
         *  - --link2symlink: Android SELinux forbids the app domain from creating hard links, and dpkg backing up
         *    files with link() gets Permission denied, so proot emulates hard links with symlinks to get around it;
         *  - --kill-on-exit: when proot exits it cascades and kills the child processes inside the container, so
         *    resetting the container while a terminal is still running leaves no orphan processes behind to freeze it.
         */
        val DEFAULT_PROOT_ARGS = listOf("--link2symlink", "--kill-on-exit")

        /** Built-in Alpine profile: the image comes from assets and reuses the existing install/provision flow. */
        val BUILTIN_ALPINE = ContainerProfile(
            id = BUILTIN_ID,
            name = "Built-in Alpine",
            rootfsSource = RootfsSource.Asset("alpine-rootfs.bin"),
            shellPath = null,
            extraArgs = DEFAULT_PROOT_ARGS,
            isBuiltin = true
        )
    }
}

@Serializable
sealed interface RootfsSource {
    /** Built-in: a rootfs file in assets (used by [ContainerProfile.BUILTIN_ALPINE]). */
    @Serializable
    data class Asset(val path: String) : RootfsSource

    /** A user-imported tar.gz, referenced by content uri and extracted to filesDir/rootfs_<id>. */
    @Serializable
    data class LocalFile(val uri: String) : RootfsSource

    /** Remote SSH: binds an SSH channel already configured for the workspace (connectionId) + a remote workspace path; no local rootfs is imported. */
    @Serializable
    data class RemoteSsh(
        val connectionId: String,
        val remoteWorkspacePath: String
    ) : RootfsSource
}
