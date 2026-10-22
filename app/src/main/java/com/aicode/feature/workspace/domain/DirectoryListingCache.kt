package com.aicode.feature.workspace.domain

import java.util.LinkedHashMap

/**
 * Directory listing cache: container path -> snapshot of the entries from that directory's most recent **successful listing**.
 *
 * Why it exists: every rebuild of the file tree (expand/collapse/file change/manual refresh) does not need to re-listFiles every expanded
 * directory -- in local mode one list is dozens to hundreds of stats, in ROOT mode it is one `su` fork + a stat fork per item,
 * and in remote mode it is one network round trip. After expanding a directory, collapsing it and expanding it again, there is no reason to read the disk again.
 *
 * Design conventions (read this section before long-term maintenance):
 * - **Only successful results are cached**: directories whose listing failed are not cached, so the next rebuild retries and the error state does not stick;
 * - **Invalidation is entirely event-driven**: this class provides no TTL; correctness depends on the caller calling
 *   [invalidate]/[invalidateTree] on file changes (see [planBrowseInvalidation]). The cost of a missed invalidation is a brief inconsistency
 *   between the tree and the disk, so callers must keep a manual full-refresh entry point as a fallback;
 * - **Capacity is bounded**: beyond [maxEntries], the oldest entries are evicted in insertion order, preventing unbounded memory growth after the user expands a whole repository;
 * - **Thread-safe**: internal `synchronized`; reads and writes are fast (nanosecond level) and do not contend on a lock with listFiles IO.
 *
 * @param maxEntries Maximum number of directories to cache; eviction discards whole directories, with no child-cascade logic.
 */
class DirectoryListingCache(private val maxEntries: Int = DEFAULT_MAX_ENTRIES) {

    private data class Snapshot(val entries: List<FileEntry>)

    private val lock = Any()

    /** Insertion-ordered LinkedHashMap: eviction deletes the oldest from the head. */
    private val map = LinkedHashMap<String, Snapshot>(16, 0.75f, false)

    /** On a hit, returns the entry list; on a miss, returns null (the caller is responsible for filling in via listFiles). */
    fun get(path: String): List<FileEntry>? = synchronized(lock) {
        map[path]?.entries
    }

    /** Write a successful listing result; evicts the oldest entries when over capacity. */
    fun put(path: String, entries: List<FileEntry>) = synchronized(lock) {
        map.remove(path)
        map[path] = Snapshot(entries)
        while (map.size > maxEntries) {
            val eldest = map.keys.iterator().next()
            map.remove(eldest)
        }
    }

    /** Invalidate a single directory (its listing is re-read from disk). A no-op when the path does not exist. */
    fun invalidate(path: String) = synchronized(lock) {
        if (path.isNotEmpty()) map.remove(path)
    }

    /**
     * Invalidate a directory and all its descendants (used when a directory is deleted/moved): cached `path/...` children are discarded as well,
     * to avoid showing stale child lists after a directory is deleted and recreated.
     */
    fun invalidateTree(path: String) = synchronized(lock) {
        if (path.isEmpty()) return@synchronized
        map.remove(path)
        val prefix = "$path/"
        map.keys.iterator().let { iter ->
            while (iter.hasNext()) if (iter.next().startsWith(prefix)) iter.remove()
        }
    }

    /** Clear everything (used on workspace switch and manual user refresh). */
    fun clear() = synchronized(lock) { map.clear() }

    /** Current number of cached directories (for diagnostics/tests). */
    fun size(): Int = synchronized(lock) { map.size }

    companion object {
        /** Default capacity: sized to the order of "the number of directories the user actually expands"; 256 directories x a few hundred entries each is not a memory burden. */
        const val DEFAULT_MAX_ENTRIES = 256
    }
}
