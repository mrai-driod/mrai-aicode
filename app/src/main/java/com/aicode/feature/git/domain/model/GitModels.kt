package com.aicode.feature.git.domain.model

/**
 * Git domain models. All fields are parsed by [com.aicode.feature.git.domain.GitRepository]
 * from the output of `git status --porcelain` / `git log` / `git for-each-ref`. Pure data, no Android dependencies.
 */

/** A single file's change: path + the two-character XY status code from git status + whether it is staged (decided by the X column). */
data class GitFileChange(
    val path: String,
    /** Of the form "M"/"A"/"D"/"R"/"??", from porcelain output. */
    val statusCode: String,
    val staged: Boolean
)

data class GitCommit(
    val hash: String,
    val shortHash: String,
    val author: String,
    val date: String,
    val message: String
)

data class GitBranch(
    val name: String,
    val current: Boolean,
    val remote: Boolean,
    val upstream: String? = null,
    val ahead: Int = 0,
    val behind: Int = 0
)

data class GitTag(
    val name: String,
    val shortHash: String
)

data class GitStash(
    val index: String,
    val message: String,
    val date: String
)

/** Aggregated view of `git status`: branch tracking info + file changes grouped by category. */
data class GitStatus(
    val branch: String,
    val ahead: Int,
    val behind: Int,
    val staged: List<GitFileChange>,
    val unstaged: List<GitFileChange>,
    val untracked: List<String>,
    val conflicted: List<GitFileChange> = emptyList(),
    val isMerging: Boolean = false,
    val upstream: String? = null,
    val isDetached: Boolean = false
) {
    val hasChanges: Boolean
        get() = staged.isNotEmpty() || unstaged.isNotEmpty() || untracked.isNotEmpty() || conflicted.isNotEmpty()
}

enum class GitTab { STATUS, BRANCHES, LOG }
