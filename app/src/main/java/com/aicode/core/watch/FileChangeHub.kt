package com.aicode.core.watch

import android.os.FileObserver
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.workspace.domain.WorkspacePathMapper
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * App-level file change hub: watches the two roots, the current active workspace and `~/.aicode` (any host directory can also be subscribed),
 * merges changes into batches and broadcasts them; subscribers declare scope and filter rules as needed, and directories nobody subscribes to hold no inotify handle and are not polled.
 *
 * Two layers of event sources:
 * - single-level inotify per directory (automatically added for new directories when a subscription declares recursion);
 * - subscription-level snapshot polling fallback (recursively comparing mtime/size over the subscription scope), covering cases on PRoot bind directories and external FUSE directories
 *   where inotify fails on some devices, and also detecting changes with incomplete inotify events such as "a whole directory being moved/replaced".
 *
 * Filtering applies per subscriber: both recursive pruning and event delivery use the subscription's own [WatchFilter], so the same directory can be visible to one subscription
 * and ignored by another. Pruning only looks at the parent path segments, so additions/removals of a pruned directory itself are still reported (only the parent directory listing can see it).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class FileChangeHub @Inject constructor(
    private val workspaceRepository: WorkspaceRepository,
    private val containerInstaller: ContainerInstaller,
    private val pathMapper: WorkspacePathMapper,
    private val executionModeHolder: ExecutionModeHolder
) {
    companion object {
        private const val TAG = "FileChangeHub"

        /** Default batch window length: changes to the same path within the window are merged into one, and the batch is always sent when the window expires. */
        const val DEFAULT_BATCH_WINDOW_MS = 300

        /** Per-batch detail limit; exceeding it truncates (keeping only the "has changes" signal). */
        private const val MAX_BATCH_SIZE = 512

        /** Snapshot polling fallback interval. */
        private const val POLL_INTERVAL_MS = 2000L

        /** Probe interval while the subscription root does not yet exist. */
        private const val ROOT_WAIT_INTERVAL_MS = 2000L

        /** Maximum entries collected in a single snapshot, preventing per-round traversal cost from spiralling for very large subscriptions. */
        private const val MAX_SNAPSHOT_ENTRIES = 4096

        /** Maximum number of directories a single subscription can hold inotify handles for, preventing large repositories from exhausting inotify watches. */
        private const val MAX_WATCHED_DIRS = 512

        /** Pending raw event queue limit; when exceeded the oldest events are dropped (with snapshot polling as fallback), preventing unbounded memory growth during a burst of files. */
        private const val MAX_PENDING_EVENTS = 4096

        /** Pending broadcast batch queue limit; when exceeded the oldest batches are dropped. */
        private const val MAX_PENDING_BATCHES = 256

        /** Workspace root path as seen by the AI. */
        const val CONTAINER_ROOT = WorkspacePathMapper.CONTAINER_ROOT

        /** Root path of the AI config directory inside the container. */
        const val AICODE_ROOT = WorkspacePathMapper.AICODE_ROOT

        private const val IN_IGNORED = 0x00008000

        private val MASK = FileObserver.CREATE or FileObserver.DELETE or FileObserver.MOVED_TO or
            FileObserver.MOVED_FROM or FileObserver.CLOSE_WRITE
    }

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** FileObserver must be created and startWatching called on a thread with a Looper, so observer start/stop uniformly goes through the main thread. */
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Host directory path -> that directory's observer (shared by multiple subscriptions). */
    private val dirWatches = ConcurrentHashMap<String, DirWatch>()

    /** Subscribes to both roots, recursively. */
    fun watchAll(
        filter: WatchFilter = WatchFilter.DEFAULT,
        fallbackPoll: Boolean = true,
        batchWindowMs: Int = DEFAULT_BATCH_WINDOW_MS
    ): Flow<FileChangeBatch> = merge(
        watchWorkspace(
            recursive = true,
            filter = filter,
            fallbackPoll = fallbackPoll,
            batchWindowMs = batchWindowMs
        ),
        watchAicode(
            recursive = true,
            filter = filter,
            fallbackPoll = fallbackPoll,
            batchWindowMs = batchWindowMs
        )
    )

    /**
     * Subscribes to the current workspace (automatically rebuilt when the workspace switches). [containerSubPath] is a container path, defaulting to the whole workspace root.
     * In remote mode the workspace is on the server and the host has no corresponding directory, so the subscription degrades to an empty flow.
     */
    fun watchWorkspace(
        containerSubPath: String = CONTAINER_ROOT,
        recursive: Boolean = false,
        filter: WatchFilter = WatchFilter.DEFAULT,
        fallbackPoll: Boolean = true,
        batchWindowMs: Int = DEFAULT_BATCH_WINDOW_MS
    ): Flow<FileChangeBatch> = workspaceRepository.current
        .map { workspaceRepository.currentPath() }
        .distinctUntilChanged()
        .flatMapLatest {
            if (executionModeHolder.currentMode() == ExecutionMode.REMOTE_SSH) {
                emptyFlow()
            } else {
                watchHostDir(
                    hostDir = pathMapper.toHostFile(containerSubPath),
                    containerRootPath = containerSubPath.trimEnd('/'),
                    root = ChangeRoot.WORKSPACE,
                    recursive = recursive,
                    filter = filter,
                    fallbackPoll = fallbackPoll,
                    batchWindowMs = batchWindowMs
                )
            }
        }

    /** Subscribes to a subpath under the AI config directory (`~/.aicode`); an empty [containerSubPath] means that directory itself. */
    fun watchAicode(
        containerSubPath: String = "",
        recursive: Boolean = false,
        filter: WatchFilter = WatchFilter.DEFAULT,
        fallbackPoll: Boolean = true,
        batchWindowMs: Int = DEFAULT_BATCH_WINDOW_MS
    ): Flow<FileChangeBatch> {
        val sub = containerSubPath.trim('/')
        return watchHostDir(
            hostDir = if (sub.isEmpty()) containerInstaller.aicodeDir else File(containerInstaller.aicodeDir, sub),
            containerRootPath = if (sub.isEmpty()) AICODE_ROOT else "$AICODE_ROOT/$sub",
            root = ChangeRoot.AICODE,
            recursive = recursive,
            filter = filter,
            fallbackPoll = fallbackPoll,
            batchWindowMs = batchWindowMs
        )
    }

    /**
     * Subscribes to any host directory (such as the sync engine's local mirror directory). When [containerRootPath] is null, the container path is
     * derived via [WorkspacePathMapper.toContainerPath]. If the directory does not yet exist the subscription is not abandoned -- it waits for it to appear, then attaches the watch,
     * and reports "the directory appeared" as a change (project-level directories such as `.aicode/skills` are often created later).
     */
    fun watchHostDir(
        hostDir: File,
        containerRootPath: String? = null,
        root: ChangeRoot = ChangeRoot.OTHER,
        recursive: Boolean = false,
        filter: WatchFilter = WatchFilter.DEFAULT,
        fallbackPoll: Boolean = true,
        batchWindowMs: Int = DEFAULT_BATCH_WINDOW_MS
    ): Flow<FileChangeBatch> = flow {
        val subscription = Subscription(
            rootDir = hostDir,
            containerRootPath = containerRootPath,
            rootKind = root,
            recursive = recursive,
            filter = filter,
            fallbackPoll = fallbackPoll,
            windowMs = batchWindowMs
        )
        try {
            subscription.start()
            emitAll(subscription.batches)
        } finally {
            subscription.stop()
        }
    }

    private data class RawEvent(val hostPath: String, val kind: ChangeKind)

    /** One subscription: holds its own registered directory set, filter rules, batch window and snapshot polling. */
    private inner class Subscription(
        private val rootDir: File,
        private val containerRootPath: String?,
        private val rootKind: ChangeRoot,
        private val recursive: Boolean,
        private val filter: WatchFilter,
        private val fallbackPoll: Boolean,
        private val windowMs: Int
    ) {
        private val events = Channel<RawEvent>(
            capacity = MAX_PENDING_EVENTS,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
        private val batchesChannel = Channel<FileChangeBatch>(
            capacity = MAX_PENDING_BATCHES,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
        val batches: Flow<FileChangeBatch> = batchesChannel.receiveAsFlow()

        private val myDirs = ConcurrentHashMap.newKeySet<String>()

        @Volatile
        private var rules: IgnoreRules = IgnoreRules.of(filter, emptyList())
        private var mergeJob: Job? = null
        private var pollJob: Job? = null
        private var attachJob: Job? = null

        fun start() {
            rules = IgnoreRules.of(
                filter,
                if (filter.followGitignore) readGitignorePatterns(rootDir) else emptyList()
            )
            mergeJob = ioScope.launch { mergeLoop() }
            if (rootDir.isDirectory) {
                attachTree()
            } else {
                awaitRoot()
            }
        }

        fun stop() {
            mergeJob?.cancel()
            mergeJob = null
            pollJob?.cancel()
            pollJob = null
            attachJob?.cancel()
            attachJob = null
            for (path in myDirs) dirWatches[path]?.unsubscribe(this)
            myDirs.clear()
            batchesChannel.close()
        }

        private fun attachTree() {
            registerTree(rootDir, reportNew = false)
            startPolling()
        }

        /** Subscription root does not exist yet: wait for it to appear, then attach the watch; its appearance itself counts as a change. */
        private fun awaitRoot() {
            attachJob = ioScope.launch {
                while (isActive && !rootDir.isDirectory) delay(ROOT_WAIT_INTERVAL_MS)
                if (!isActive) return@launch
                events.trySend(RawEvent(rootDir.absolutePath, ChangeKind.CREATED))
                attachTree()
            }
        }

        /** Recursively registers directories; pruning only targets the parent path segments, so it checks before registering. When [reportNew] is true, existing direct children of a newly attached directory are additionally reported. */
        private fun registerTree(dir: File, reportNew: Boolean) {
            val parts = relativePartsOf(rootDir, dir)
            if (parts.isNotEmpty() && rules.isIgnoredDir(parts)) return
            register(dir, reportNew)
            if (!recursive) return
            dir.listFiles()?.forEach { child -> if (child.isDirectory) registerTree(child, reportNew) }
        }

        private fun register(dir: File, reportNew: Boolean) {
            val path = dir.absolutePath
            if (!myDirs.add(path)) {
                FileLogger.d(TAG, "[diag] Directory watch already registered, skipping duplicate registration: $path")
                return
            }
            if (dirWatches.size >= MAX_WATCHED_DIRS && !dirWatches.containsKey(path)) {
                myDirs.remove(path)
                FileLogger.w(TAG, "Watched directory count reached limit $MAX_WATCHED_DIRS, stopping expansion: $path")
                return
            }
            // Before the watch takes effect for a newly appeared directory, no watcher is present for its entries and inotify will not report them.
            // Additionally reporting that directory's existing direct children covers this gap; later additions are still reported by inotify. The initial registration does not additionally report,
            // otherwise establishing a subscription would report the whole workspace as newly added.
            val onReady: (() -> Unit)? = if (reportNew) {
                { ioScope.launch { reportExistingChildren(dir) } }
            } else null
            FileLogger.d(TAG, "[diag] Registering directory watch: $path reportNew=$reportNew")
            dirWatches.computeIfAbsent(path) { DirWatch(dir) }.subscribe(this, onReady)
        }

        /** After attaching the watch, additionally scans children, covering directories created between the first scan and startWatching. */
        private fun reportExistingChildren(dir: File) {
            val children = dir.listFiles() ?: return
            FileLogger.d(TAG, "[diag] Additionally reporting new directory children: ${dir.absolutePath} count=${children.size}")
            for (child in children) {
                events.trySend(RawEvent(child.absolutePath, ChangeKind.CREATED))
                if (recursive && child.isDirectory) registerTree(child, reportNew = true)
            }
        }

        fun onWatchInvalidated(path: String) {
            if (!myDirs.remove(path)) return
            FileLogger.w(TAG, "Directory watch invalidated, preparing to re-attach: $path")
            val dir = File(path)
            if (dir.isDirectory) {
                registerTree(dir, reportNew = true)
            } else if (path == rootDir.absolutePath) {
                awaitRoot()
            }
        }

        fun onEvent(watch: DirWatch, hostPath: String, kind: ChangeKind) {
            events.trySend(RawEvent(hostPath, kind))
            // Recursive subscription: newly appeared directories need a watch attached (the callback is on the main thread, the filesystem check switches to IO).
            if (recursive && kind == ChangeKind.CREATED) {
                ioScope.launch {
                    val child = File(hostPath)
                    if (child.isDirectory) registerTree(child, reportNew = true)
                }
            }
        }

        /**
         * Snapshot polling fallback: recursively compares over the subscription scope (non-recursive subscriptions only compare the root directory's single level).
         * A recursive snapshot can discover deep changes missed by inotify and "a whole subtree being moved/replaced".
         */
        private fun startPolling() {
            if (!fallbackPoll || pollJob != null) return
            pollJob = ioScope.launch {
                var last = snapshotOfScope()
                while (isActive) {
                    delay(POLL_INTERVAL_MS)
                    val now = snapshotOfScope()
                    val diff = diffSnapshot(last, now)
                    last = now
                    for ((rel, kind) in diff) {
                        val hostPath =
                            if (rel.isEmpty()) rootDir.absolutePath else File(rootDir, rel).absolutePath
                        events.trySend(RawEvent(hostPath, kind))
                        if (recursive && kind == ChangeKind.CREATED) {
                            val child = File(hostPath)
                            if (child.isDirectory) registerTree(child, reportNew = true)
                        }
                    }
                }
            }
        }

        /** Subscription scope snapshot: keys are paths relative to the subscription root (the root itself is the empty string); pruned directories are not entered. */
        private fun snapshotOfScope(): Map<String, String> {
            val out = HashMap<String, String>()
            if (!rootDir.isDirectory) return out
            var count = 0

            fun walk(dir: File, rel: String) {
                val children = dir.listFiles() ?: return
                for (child in children) {
                    if (count >= MAX_SNAPSHOT_ENTRIES) return
                    val childRel = if (rel.isEmpty()) child.name else "$rel/${child.name}"
                    count++
                    if (child.isDirectory) {
                        out[childRel] = "d"
                        if (recursive && !rules.isIgnoredDir(relativePartsOf(rootDir, child))) {
                            walk(child, childRel)
                        }
                    } else {
                        out[childRel] = "f:${child.lastModified()}:${child.length()}"
                    }
                }
            }

            walk(rootDir, "")
            return out
        }

        private fun containerPathOf(hostPath: String): String {
            val base = containerRootPath ?: return pathMapper.toContainerPath(hostPath)
            val rel = relativePartsOf(rootDir, File(hostPath)).joinToString("/")
            return if (rel.isEmpty()) base else "$base/$rel"
        }

        private suspend fun mergeLoop() {
            while (true) {
                val first = events.receive()
                val merged = LinkedHashMap<String, ChangeKind>()
                var truncated = false

                fun accept(event: RawEvent) {
                    val existing = merged[event.hostPath]
                    if (existing == null) {
                        if (merged.size >= MAX_BATCH_SIZE) {
                            truncated = true
                            return
                        }
                        merged[event.hostPath] = event.kind
                    } else {
                        merged[event.hostPath] = mergeKind(existing, event.kind)
                    }
                }

                accept(first)
                // Fixed window: always sends on expiry. A quiet debounce would never emit during continuous changes such as a compilation, swallowing the refresh.
                val deadline = System.currentTimeMillis() + windowMs
                while (true) {
                    val remain = deadline - System.currentTimeMillis()
                    if (remain <= 0) break
                    val next = withTimeoutOrNull(remain) { events.receive() } ?: break
                    accept(next)
                }

                val changes = merged.map { (path, kind) ->
                    FileChange(rootKind, path, containerPathOf(path), kind)
                }
                batchesChannel.send(FileChangeBatch(changes, truncated))
            }
        }
    }

    /** Observer for one host directory, started/stopped by subscription reference counting (shared by multiple subscriptions). */
    private inner class DirWatch(private val dir: File) {
        private val path = dir.absolutePath
        private val subscribers: MutableSet<Subscription> =
            Collections.newSetFromMap(ConcurrentHashMap<Subscription, Boolean>())
        private val lock = Any()

        private var observer: FileObserver? = null

        fun subscribe(sub: Subscription, onReady: (() -> Unit)? = null) {
            val firstSubscriber = synchronized(lock) {
                val first = subscribers.isEmpty()
                subscribers.add(sub)
                first
            }
            // If the watch is already active, call back immediately; otherwise queue after startObserver on the main thread, guaranteeing onReady is after startWatching.
            if (firstSubscriber) startObserver(onReady) else mainScope.launch { onReady?.invoke() }
        }

        fun unsubscribe(sub: Subscription) {
            val empty = synchronized(lock) {
                subscribers.remove(sub)
                subscribers.isEmpty()
            }
            if (empty) {
                stopObserver()
                dirWatches.remove(path, this)
            }
        }

        private fun startObserver(onReady: (() -> Unit)? = null) {
            mainScope.launch {
                if (observer != null) {
                    onReady?.invoke()
                    return@launch
                }
                @Suppress("DEPRECATION")
                val created = object : FileObserver(path, MASK) {
                    override fun onEvent(event: Int, child: String?) {
                        FileLogger.d(TAG, "[diag] FileObserver.onEvent path=$path event=0x${Integer.toHexString(event)} child=$child")
                        if (event and IN_IGNORED != 0) {
                            invalidate(this)
                            return
                        }
                        val name = child ?: return
                        val kind = kindOf(event) ?: return
                        dispatch(name, kind)
                    }
                }
                created.startWatching()
                observer = created
                FileLogger.d(TAG, "[diag] startWatching path=$path")
                // Must be after startWatching: from this point inotify can capture additions, and the additional report only covers earlier existing entries, so the two join seamlessly.
                onReady?.invoke()
            }
        }

        private fun invalidate(current: FileObserver) {
            if (observer !== current) return
            observer = null
            dirWatches.remove(path, this)
            val affected = synchronized(lock) {
                subscribers.toList().also { subscribers.clear() }
            }
            FileLogger.w(TAG, "Directory observer removed by the system: $path, subscriptions=${affected.size}")
            for (sub in affected) sub.onWatchInvalidated(path)
        }

        private fun stopObserver() {
            val current = observer ?: return
            observer = null
            mainScope.launch { runCatching { current.stopWatching() } }
        }

        private fun dispatch(name: String, kind: ChangeKind) {
            val hostPath = File(dir, name).absolutePath
            FileLogger.d(TAG, "[diag] dispatch hostPath=$hostPath kind=$kind subscribers=${subscribers.size}")
            for (sub in subscribers) sub.onEvent(this, hostPath, kind)
        }

        private fun kindOf(event: Int): ChangeKind? = when {
            event and (FileObserver.CREATE or FileObserver.MOVED_TO) != 0 -> ChangeKind.CREATED
            event and (FileObserver.DELETE or FileObserver.MOVED_FROM) != 0 -> ChangeKind.DELETED
            event and FileObserver.CLOSE_WRITE != 0 -> ChangeKind.MODIFIED
            else -> null
        }
    }
}