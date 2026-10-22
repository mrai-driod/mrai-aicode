package com.aicode.core.watch

import com.aicode.core.util.GitIgnoreMatcher
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** The watched root a change belongs to: the workspace, the AI config directory (`~/.aicode`), or another host directory specified by the subscriber. */
enum class ChangeRoot { WORKSPACE, AICODE, OTHER }

/** The type of a single change. Multiple changes to the same path within one batch window are merged into one. */
enum class ChangeKind { CREATED, MODIFIED, DELETED }

/**
 * A single file change. [containerPath] is the container-view path (as seen by the AI / terminal), for consumers to use directly;
 * consumers matching by host path (such as the sync engine) use [hostPath].
 */
data class FileChange(
    val root: ChangeRoot,
    val hostPath: String,
    val containerPath: String,
    val kind: ChangeKind
)

/**
 * Changes collected within one batch window. [changes] is deduplicated by path (each path appears once, kind already merged);
 * [truncated] being true means the details exceeded the window limit and were truncated, guaranteeing only the signal "there are changes in this range".
 */
data class FileChangeBatch(
    val changes: List<FileChange>,
    val truncated: Boolean = false
)

/**
 * Subscriber-side filter rules: only affects "whether to enter a directory when recursing down" and "whether events inside that directory are reported".
 * The check looks only at the parent path segments, excluding the last segment -- additions/removals of a pruned directory itself are still reported, as only the parent directory listing can see it.
 *
 * Deliberately contains no built-in ignore list: pruning is based entirely on the subscriber ([ignoredNames] / [extraPatterns])
 * and the subscription root's .gitignore ([followGitignore]).
 */
data class WatchFilter(
    val ignoredNames: Set<String> = emptySet(),
    val extraPatterns: List<String> = emptyList(),
    val followGitignore: Boolean = true
) {
    companion object {
        val DEFAULT = WatchFilter()
    }
}

/** Directory pruning decision (pure logic, easy to unit test). */
class IgnoreRules internal constructor(
    private val ignoredNames: Set<String>,
    private val patterns: List<String>
) {
    /** [relativeParts] are path segments relative to the subscription root (callers pass only the parent segments, excluding the last segment). */
    fun isIgnoredDir(relativeParts: List<String>): Boolean {
        if (relativeParts.isEmpty()) return false
        if (ignoredNames.isNotEmpty() && relativeParts.any { it in ignoredNames }) return true
        return patterns.isNotEmpty() && GitIgnoreMatcher.isIgnored(patterns, relativeParts)
    }

    companion object {
        fun of(filter: WatchFilter, gitignorePatterns: List<String>): IgnoreRules =
            IgnoreRules(filter.ignoredNames, gitignorePatterns + filter.extraPatterns)
    }
}

/** For UI subscriptions that only need the "has changes" signal; the batch window already merges dense changes in the same directory into one. */
fun Flow<FileChangeBatch>.asDirtySignal(): Flow<Unit> = map { }

/** Converts a host absolute path into path segments relative to [root]. */
internal fun relativePartsOf(root: File, path: File): List<String> {
    val rootPath = root.absolutePath.trimEnd(File.separatorChar)
    val abs = path.absolutePath
    // After the prefix there may be a `/` (Android/Linux) or `\` (Windows JVM, such as unit tests); both are stripped
    val rel = if (abs == rootPath) "" else abs.removePrefix(rootPath).trimStart('/', '\\')
    return rel.split(File.separatorChar, '/').filter { it.isNotEmpty() }
}

/** Reads the subscription root's .gitignore (removing blank lines and comments, stripping trailing slashes); returns empty if missing or unreadable. */
internal fun readGitignorePatterns(root: File): List<String> {
    val file = File(root, ".gitignore")
    if (!file.isFile) return emptyList()
    return runCatching {
        file.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { it.removeSuffix("/") }
    }.getOrDefault(emptyList())
}

/**
 * Single-level directory snapshot: child name -> marker. Files record mtime+size, directories record only the type (a directory's own mtime changes with additions/removals inside it,
 * so including it in the comparison would only duplicate the subdirectory's own events).
 */
internal fun snapshotDir(dir: File): Map<String, String> {
    val children = dir.listFiles() ?: return emptyMap()
    val map = HashMap<String, String>(children.size)
    for (child in children) {
        map[child.name] =
            if (child.isDirectory) "d" else "f:${child.lastModified()}:${child.length()}"
    }
    return map
}

/** Compares before/after snapshots and produces child name -> change type. */
internal fun diffSnapshot(
    prev: Map<String, String>,
    now: Map<String, String>
): List<Pair<String, ChangeKind>> {
    if (prev.isEmpty() && now.isEmpty()) return emptyList()
    val out = ArrayList<Pair<String, ChangeKind>>()
    for ((name, value) in now) {
        val old = prev[name]
        when {
            old == null -> out += name to ChangeKind.CREATED
            old != value -> out += name to ChangeKind.MODIFIED
        }
    }
    for (name in prev.keys) {
        if (name !in now) out += name to ChangeKind.DELETED
    }
    return out
}

/** Merge rule when the same path changes multiple times within one window. */
internal fun mergeKind(prev: ChangeKind, next: ChangeKind): ChangeKind = when {
    prev == next -> prev
    (prev == ChangeKind.CREATED && next == ChangeKind.DELETED) ||
        (prev == ChangeKind.DELETED && next == ChangeKind.CREATED) -> ChangeKind.MODIFIED
    else -> next
}