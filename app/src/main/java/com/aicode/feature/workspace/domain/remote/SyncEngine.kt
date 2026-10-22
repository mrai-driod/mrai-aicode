package com.aicode.feature.workspace.domain.remote

import com.aicode.core.util.FileLogger
import com.aicode.core.util.GitIgnoreMatcher
import com.aicode.feature.workspace.domain.model.SyncConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class SyncEngine(
    private val mount: com.aicode.feature.workspace.domain.model.RemoteMount,
    private val connection: com.aicode.feature.workspace.domain.model.RemoteConnection,
    private val syncClient: RemoteSyncClient,
    private val auth: RemoteAuth,
    private val ignoredPatternsStr: String,
    private val useGitIgnore: Boolean,
    private val maxSyncBatchSize: Int
) {
    companion object {
        private const val TAG = "SyncEngine"
        private const val PING_INTERVAL_MS = 15_000L
        private const val RECONNECT_BASE_MS = 5_000L
        private const val RECONNECT_MAX_MS = 60_000L
        private const val FILE_SYNC_DELAY_MS = 50L

        /** Upper limit of the pending-sync path queue; beyond it the oldest items are dropped, to prevent unbounded memory growth during bulk changes. */
        private const val MAX_PENDING_SYNC = 4096
    }

    private val customIgnores = ignoredPatternsStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    private var gitIgnorePatterns = emptyList<String>()

    private fun isIgnored(path: String): Boolean {
        // Convert uniformly to path segments relative to the mount root before matching: GitIgnoreMatcher's contract is relative path segments;
        // passing a host/remote absolute path directly would include prefix segments in matching and could cause false hits (e.g. gitignore contains `data` while
        // the host path happens to have a /data prefix). Local paths strip localMountPath, remote paths strip remotePath.
        val relative = path
            .removePrefix(mount.localMountPath)
            .removePrefix(mount.remotePath)
            .trimStart('/', File.separatorChar)
        val parts = relative.split(File.separatorChar, '/').filter { it.isNotEmpty() }

        // 1. Check custom ignore rules (mainly for directory and file names)
        if (parts.any { it in customIgnores }) return true

        // 2. Check .gitignore rules
        if (useGitIgnore && gitIgnorePatterns.isNotEmpty()) {
            if (GitIgnoreMatcher.isIgnored(gitIgnorePatterns, parts)) {
                return true
            }
        }
        return false
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val retryCounts = java.util.concurrent.ConcurrentHashMap<String, Int>()

    private val _connectionState = MutableStateFlow(SyncConnectionState.CONNECTED)
    /** Connection health: a failed probe moves to RECONNECTING, a successful reconnect returns to CONNECTED; shown in the UI. */
    val connectionState: StateFlow<SyncConnectionState> = _connectionState.asStateFlow()
    
    // Use a Channel for buffering and debouncing (accumulates up to the limit, then drops the oldest item)
    private val syncChannel = Channel<String>(
        capacity = MAX_PENDING_SYNC,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    init {
        if (useGitIgnore) {
            val gitignore = File(mount.localMountPath, ".gitignore")
            if (gitignore.exists()) {
                gitIgnorePatterns = gitignore.readLines()
                    .filter { it.isNotBlank() && !it.startsWith("#") }
                    .map { it.trim().removeSuffix("/") }
            }
        }

        scope.launch {
            while (isActive) {
                // Wait for the first event in the queue
                val firstItem = syncChannel.receive()
                val batch = mutableSetOf(firstItem) // Use a Set to deduplicate, so the same file does not trigger multiple uploads in a very short time

                // Try to collect more ready events, without exceeding the maximum batch size
                while (batch.size < maxSyncBatchSize) {
                    val next = syncChannel.tryReceive().getOrNull() ?: break
                    batch.add(next)
                }

                // Process the batch in order
                for (localPath in batch) {
                    handleLocalChange(localPath)
                }

                // If the batch limit is reached, bulk edits are probably in progress; delay briefly to let the server catch up
                if (batch.size >= maxSyncBatchSize) {
                    delay(300)
                }
            }
        }

        // Auto-reconnect supervisor for disconnects: probes periodically, and reconnects with backoff when disconnected.
        scope.launch {
            var backoffMs = RECONNECT_BASE_MS
            while (isActive) {
                delay(PING_INTERVAL_MS)
                if (!syncClient.ping()) {
                    FileLogger.w(TAG, "Sync: connection lost (${connection.name}), starting auto-reconnect")
                    _connectionState.value = SyncConnectionState.RECONNECTING
                    while (isActive && !syncClient.ping()) {
                        delay(backoffMs)
                        try {
                            syncClient.reconnect(connection.host, connection.port, connection.username, auth)
                            backoffMs = RECONNECT_BASE_MS
                            _connectionState.value = SyncConnectionState.CONNECTED
                            FileLogger.i(TAG, "Sync: auto-reconnect succeeded (${connection.name})")
                        } catch (e: Exception) {
                            FileLogger.e(TAG, "Sync: auto-reconnect failed (${connection.name}), retrying in ${backoffMs / 1000}s: ${e.message}")
                            backoffMs = (backoffMs * 2).coerceAtMost(RECONNECT_MAX_MS)
                        }
                    }
                }
            }
        }
    }

    /**
     * Full download of the remote workspace to local
     */
    suspend fun downloadWorkspace() = withContext(Dispatchers.IO) {
        if (!syncClient.isConnected()) {
            throw IllegalStateException("Client not connected")
        }
        val localRoot = File(mount.localMountPath)
        if (!localRoot.exists()) {
            localRoot.mkdirs()
        }
        
        // Simple recursive download implementation
        suspend fun pull(remoteDir: String, localDir: File) {
            val files = syncClient.listFiles(remoteDir)
            for (file in files) {
                if (isIgnored("$remoteDir/${file.name}")) continue
                val rPath = "$remoteDir/${file.name}"
                val lFile = File(localDir, file.name)
                if (file.isDirectory) {
                    lFile.mkdirs()
                    pull(rPath, lFile)
                } else {
                    try {
                        syncClient.downloadFile(rPath, lFile.absolutePath)
                        delay(FILE_SYNC_DELAY_MS)
                    } catch (e: Exception) {
                        FileLogger.e(TAG, "Download Error for $rPath: ${e.message}")
                        if (e !is RemoteFileRejectedException) forceReconnect()
                    }
                }
            }
        }
        pull(mount.remotePath, localRoot)
    }

    /**
     * Full upload of the local workspace to remote
     */
    suspend fun uploadWorkspace() = withContext(Dispatchers.IO) {
        if (!syncClient.isConnected()) {
            throw IllegalStateException("Client not connected")
        }
        val localRoot = File(mount.localMountPath)
        if (!localRoot.exists()) return@withContext

        suspend fun push(localDir: File, remoteDir: String) {
            val files = localDir.listFiles() ?: return
            for (file in files) {
                if (isIgnored(file.absolutePath)) continue
                val rPath = "$remoteDir/${file.name}"
                if (file.isDirectory) {
                    // The remote directory may already have been created by an earlier sync; an already-exists error can be ignored.
                    try { syncClient.createDirectory(rPath) } catch (e: Exception) {
                        FileLogger.w(TAG, "Sync: failed to create remote directory $rPath: ${e.message}")
                    }
                    push(file, rPath)
                } else {
                    try {
                        syncClient.uploadFile(file.absolutePath, rPath)
                        delay(FILE_SYNC_DELAY_MS)
                    } catch (e: Exception) {
                        FileLogger.e(TAG, "Upload Error for $rPath: ${e.message}")
                        if (e is RemoteFileRejectedException) continue
                        forceReconnect()
                        // Files that fail the full sync are also queued for incremental retry as a fallback
                        scope.launch {
                            delay(1000)
                            syncChannel.send(file.absolutePath)
                        }
                    }
                }
            }
        }
        push(localRoot, mount.remotePath)
    }

    /**
     * Delivered by an external subscriber (the central service subscription in [com.aicode.feature.workspace.domain.repository.RemoteRepository]) when it discovers
     * a local change; shares the same queue as failure retries, preserving the original batching and throttling semantics.
     */
    fun enqueueLocalChange(localPath: String) {
        FileLogger.d(TAG, "[diag] enqueueLocalChange localPath=$localPath ignored=${isIgnored(localPath)}")
        syncChannel.trySend(localPath)
    }

    private suspend fun handleLocalChange(localPath: String) {
        if (isIgnored(localPath)) return
        val file = File(localPath)
        // Compute the corresponding remote path
        val relativePath = localPath.removePrefix(mount.localMountPath).removePrefix("/")
        val remotePath = "${mount.remotePath}/$relativePath"

        try {
            if (file.exists()) {
                if (file.isDirectory) {
                    syncClient.createDirectory(remotePath)
                    FileLogger.i(TAG, "Sync: Created remote directory $remotePath")
                } else {
                    syncClient.uploadFile(localPath, remotePath)
                    FileLogger.i(TAG, "Sync: Uploaded to $remotePath")
                    delay(FILE_SYNC_DELAY_MS)
                }
            } else {
                syncClient.delete(remotePath)
                FileLogger.i(TAG, "Sync: Deleted $remotePath")
                delay(FILE_SYNC_DELAY_MS)
            }
            retryCounts.remove(localPath) // Clear the retry count after success
        } catch (e: Exception) {
            FileLogger.e(TAG, "Sync Error for $localPath: ${e.message}", e)
            if (e is RemoteFileRejectedException) {
                retryCounts.remove(localPath)
                FileLogger.w(TAG, "Sync: server rejected this path, skipping reconnect and retry: $remotePath")
                return
            }
            forceReconnect()
            val count = retryCounts.getOrDefault(localPath, 0)
            if (count < 3) {
                retryCounts[localPath] = count + 1
                FileLogger.i(TAG, "Sync: Retry ${count + 1}/3 for $localPath")
                // Re-queue the failed file
                scope.launch {
                    delay(1000)
                    syncChannel.send(localPath)
                }
            } else {
                FileLogger.e(TAG, "Sync: Max retries reached for $localPath. Giving up.")
                retryCounts.remove(localPath)
            }
        }
    }

    fun shutdown() {
        _connectionState.value = SyncConnectionState.DISCONNECTED
        scope.cancel()
    }

    private suspend fun forceReconnect() {
        try {
            // Disconnect and reconnect must complete atomically, otherwise other coroutines could operate on a half-connected state.
            syncClient.reconnect(connection.host, connection.port, connection.username, auth)
            _connectionState.value = SyncConnectionState.CONNECTED
            FileLogger.i(TAG, "Sync: Force reconnected to server successfully.")
        } catch (e: Exception) {
            FileLogger.e(TAG, "Sync: Failed to force reconnect: ${e.message}")
        }
    }
}
