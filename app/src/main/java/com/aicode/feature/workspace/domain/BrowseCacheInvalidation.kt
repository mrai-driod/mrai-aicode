package com.aicode.feature.workspace.domain

import com.aicode.core.watch.ChangeKind
import com.aicode.core.watch.FileChangeBatch

/**
 * Plan for the impact of a batch of file changes on [DirectoryListingCache].
 *
 * @property relistDirs Directories that need to be re-read from disk (when the set is empty and [fullRefresh] is false, no tree rebuild is needed).
 * @property dropTrees Directories whose cache must be dropped together with their descendants (deletion -- a directory may be deleted and recreated, and stale child lists would be misleading).
 * @property fullRefresh When details are truncated / the batch cannot be parsed: only the "there was a change" signal is guaranteed, and the caller should clear the whole cache and rebuild.
 * @property gitignoreTouched The root `.gitignore` was modified: the caller must discard its cached parsed ignore rules.
 */
data class BrowseInvalidation(
    val relistDirs: Set<String> = emptySet(),
    val dropTrees: Set<String> = emptySet(),
    val fullRefresh: Boolean = false,
    val gitignoreTouched: Boolean = false
) {
    val isEmpty: Boolean
        get() = !fullRefresh && relistDirs.isEmpty() && dropTrees.isEmpty() && !gitignoreTouched
}

/**
 * Translate a [FileChangeBatch] into a [BrowseInvalidation] (pure function, easy to unit-test).
 *
 * Rules and rationale:
 * - The **parent directory of changed path P** must always be relisted -- file additions/deletions/renames change the parent directory's listing;
 * - **P itself** is also relisted: under single-level inotify semantics P is a direct child of a subscribed directory, and if P is a directory, changes inside it
 *   arrive as changes to P itself (MODIFIED); relisting P refreshes the expanded subtree; for file paths this is a cheap no-op;
 * - **A DELETED directory** drops its whole cached tree: after a path is deleted all cached descendants are invalid, and after a same-named directory is recreated
 *   its children may be completely different (paste-overwrite and checkouts are of this kind), so dropping only itself is not enough;
 * - **truncated batch** Details were truncated ([FileChangeBatch.truncated]), so there is no basis for per-item decisions and the only option is a full clear;
 * - When **`<root>/.gitignore`** is touched, remind the caller to re-read the ignore rules (the tree filters nodes by them).
 *
 * @param batch One batch window from the watch centre.
 * @param rootPath Workspace container root path (used to identify whether `.gitignore` refers to the root level).
 */
fun planBrowseInvalidation(batch: FileChangeBatch, rootPath: String): BrowseInvalidation {
    if (batch.truncated) return BrowseInvalidation(fullRefresh = true)
    val relist = LinkedHashSet<String>()
    val drop = LinkedHashSet<String>()
    var gitignoreTouched = false
    val gitignorePath = "$rootPath/.gitignore"
    for (change in batch.changes) {
        val p = change.containerPath
        val parent = p.substringBeforeLast('/', "")
        if (parent.isNotEmpty()) relist.add(parent)
        if (p.isNotEmpty()) relist.add(p)
        if (change.kind == ChangeKind.DELETED) drop.add(p)
        if (p == gitignorePath) gitignoreTouched = true
    }
    return BrowseInvalidation(
        relistDirs = relist,
        dropTrees = drop,
        fullRefresh = false,
        gitignoreTouched = gitignoreTouched
    )
}
