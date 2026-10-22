package com.aicode.feature.workspace.domain.model

/** Workspace type: internal (under the app-private projects root) / external local directory (a device directory chosen by the user) / remote. */
enum class WorkspaceType {
    INTERNAL,
    EXTERNAL_LOCAL,
    REMOTE
}

/**
 * A workspace = the AI's root operating directory.
 *
 * - [INTERNAL]: a subfolder under the app-private project root (filesDir/projects), physically on private ext4.
 * - [EXTERNAL_LOCAL]: a local device directory chosen by the user via the system directory picker (e.g. /storage/emulated/0/xxx),
 *   read and written directly in both directions, with no copying. Deleting the workspace only removes the association, not the physical files.
 * - [REMOTE]: a subfolder under remoteWorkspacePath on a remote SSH server.
 *
 * The AI's file tools and command execution are all rooted at [path]; switching workspace switches the AI's operating scope.
 */
data class Workspace(
    /** Folder name, used as the unique identifier. */
    val name: String,
    /** Absolute path, usable directly with java.io.File and container mounts. */
    val path: String,
    /** Workspace type; defaults to an internal workspace for compatibility with older constructors. */
    val type: WorkspaceType = WorkspaceType.INTERNAL,
    /** Whether the workspace is currently available (false when an external local directory has been moved/deleted); available by default. */
    val available: Boolean = true
)
