package com.aicode.feature.credentials.data

import android.content.Context
import com.aicode.core.util.FileLogger
import com.aicode.core.watch.FileChangeHub
import com.aicode.feature.agent.domain.container.LinuxContainerEngine
import com.aicode.feature.credentials.domain.model.GitCredential
import com.aicode.feature.credentials.domain.repository.CredentialRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * File IPC bridge between the custom git credential helper (inside the container at /root/.aicode/git-credential-aicode) and the app.
 *
 * When git inside the container lacks credentials, the helper writes `cred-req-<id>` to /root/.aicode (bound via PRoot -b, i.e. the same
 * inode as the host filesDir/aicode). This bridge subscribes to [FileChangeHub] aicode directory events to capture requests → exposes the [request] StateFlow for a global Compose dialog →
 * after the user fills it in, [respond] writes `cred-resp-<id>` as plain-text KV, the helper polls for it, picks it up and feeds it back to git, and git continues automatically. All three surfaces (UI / AI Bash /
 * interactive terminal) use this single unified path when git lacks credentials, with no per-command path adaptation needed.
 *
 * Temporary file cleanup: the request file is deleted by the helper itself after it receives the response (the helper knows when it ends), and the response file is also deleted by the helper after it reads it;
 * the app only writes the response and does not delete files it did not write, to avoid racing the helper on deletion. See the rm logic in the helper script for details.
 *
 * In-flight counting: on receiving a request [LinuxContainerEngine.incPromptInFlight], on writing back the response [decPromptInFlight], working with
 * [LinuxContainerEngine.launchKillWatchdog] to pause the timeout process kill while a helper is in flight——so if the user steps away for a few minutes to fill in credentials, the git command
 * will not be force-killed by the 120s watchdog.
 *
 * Directory watching is delegated to the central service [FileChangeHub] (inotify across PRoot -b is generally reliable for the app filesDir, but on some devices inotify on
 * bound directories may fail), so this bridge also starts a low-frequency fallback polling coroutine ([fallbackPollLoop]) to scan for unhandled `cred-req-*` items; the two are
 * deduplicated by requestId to prevent double handling.
 *
 * Lifecycle: @Singleton = app level, [start] is called via [com.aicode.AIEditorApp]. The dialog can appear on any page (terminal page / chat page / Git page),
 * not limited to the Git page.
 */
@Singleton
class CredentialRequestBridge @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val credentialRepository: CredentialRepository,
    private val containerEngine: LinuxContainerEngine,
    private val fileChangeHub: FileChangeHub
) {
    private companion object {
        const val TAG = "CredentialRequestBridge"
        const val REQ_PREFIX = "cred-req-"
        const val RESP_PREFIX = "cred-resp-"
        /** Fallback polling interval: when the central service's inotify does not work, requests can still be captured within ~1s. */
        const val FALLBACK_POLL_MS = 1000L
        /** Capacity cap for the seen deduplication set; the oldest entries are evicted beyond it. */
        const val SEEN_CAP = 64
    }

    /** The credential request currently pending display (host comes from the host field of the git credential protocol). null means nothing is pending. */
    data class CredentialRequest(
        val requestId: String,
        val host: String
    )

    private val _request = MutableStateFlow<CredentialRequest?>(null)
    val request: StateFlow<CredentialRequest?> = _request.asStateFlow()

    /** Deduplication set of handled / in-flight requestIds, preventing the event subscription and fallback polling from both triggering on the same one. */
    private val seen = LinkedHashSetWithCap(SEEN_CAP)

    private val seenMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var started = false
    private var aicodeDir: File? = null

    /**
     * Start listening: subscribe to the central service's aicode directory events to capture requests, and start the fallback polling coroutine. Idempotent and safe to call repeatedly.
     */
    fun start() {
        val dir = File(context.filesDir, "aicode").apply { mkdirs() }
        aicodeDir = dir
        if (!started) {
            started = true
            // Request files land in the root of ~/.aicode and are watched centrally by the central service; this bridge only does business-side filtering, deduplication and dialog display.
            scope.launch {
                fileChangeHub.watchAicode().collect { batch ->
                    for (change in batch.changes) {
                        val name = File(change.hostPath).name
                        if (!name.startsWith(REQ_PREFIX) || name.endsWith(".tmp")) continue
                        handleRequest(File(dir, name), name)
                    }
                }
            }
            FileLogger.i(TAG, "Subscribed to aicode directory events, watching ${dir.absolutePath}")
        }
        // Fallback polling: on some devices where inotify on -b directories fails, requests can still be picked up; also cleans up leftovers from the previous session.
        scope.launch { cleanupStale(dir); fallbackPollLoop(dir) }
    }

    /** Fallback polling: scan cred-req-* files not in seen, to make up for events the central service missed. The app is resident and never exits. */
    private suspend fun fallbackPollLoop(dir: File) {
        while (true) {
            delay(FALLBACK_POLL_MS)
            runCatching {
                dir.listFiles { f -> f.name.startsWith(REQ_PREFIX) && !f.name.endsWith(".tmp") }
                    ?.forEach { f -> handleRequest(f, f.name) }
            }
        }
    }

    /** On startup, clean up cred-req/cred-resp leftovers from the previous session to avoid triggering dialogs by mistake. */
    private fun cleanupStale(dir: File) {
        runCatching {
            dir.listFiles { f -> f.name.startsWith(REQ_PREFIX) || f.name.startsWith(RESP_PREFIX) }
                ?.forEach { it.delete() }
        }
    }

    /** Parse a request file → set [request], and inc in-flight. Both the event subscription and polling go through here, deduplicated via seen. */
    private suspend fun handleRequest(file: File, name: String) {
        val requestId = name.removePrefix(REQ_PREFIX)
        val fresh = seenMutex.withLock { seen.addCapped(requestId) }
        if (!fresh) return   // Already handled, skip
        // The file was already deleted by the helper or is empty (startup leftovers were already cleaned up, so this normally should not happen here), skip.
        if (!file.exists() || file.length() == 0L) return
        val text = runCatching { file.readText() }.getOrNull() ?: return
        val host = text.lineSequence()
            .firstOrNull { it.startsWith("host=") }
            ?.removePrefix("host=")
            ?.trim()
            ?.lowercase()
            ?: ""
        if (host.isBlank()) return
        containerEngine.incPromptInFlight()
        FileLogger.i(TAG, "Received credential request host=$host id=$requestId")
        _request.value = CredentialRequest(requestId, host)
    }

    /**
     * User confirms after filling in credentials: write the response plain-text KV + save the credential file (one per host, so no refilling later) + dec in-flight + clear [request].
     * [host] comes from [CredentialRequest] (the Git process does not delete req before receiving resp, but host is already in the request, so passing it directly is more reliable).
     */
    fun respond(requestId: String, host: String, username: String, token: String) {
        val dir = aicodeDir ?: File(context.filesDir, "aicode")
        scope.launch {
            writeRespAtomically(dir, requestId, "username=$username\npassword=$token\n")
            runCatching {
                val h = host.trim().lowercase()
                credentialRepository.save(
                    GitCredential(id = h, host = h, username = username.trim(), token = token)
                )
            }.onFailure { FileLogger.e(TAG, "Failed to save filled-in credential", it) }
            containerEngine.decPromptInFlight()
            clearIfCurrent(requestId)
            FileLogger.i(TAG, "Filled in credential id=$requestId host=$host")
        }
    }

    /** User cancels the dialog: write a cancel response (the helper exits non-zero so git reports an authentication failure) + dec in-flight + clear state. */
    fun cancel(requestId: String) {
        val dir = aicodeDir ?: File(context.filesDir, "aicode")
        scope.launch {
            writeRespAtomically(dir, requestId, "cancel=1\n")
            containerEngine.decPromptInFlight()
            clearIfCurrent(requestId)
            FileLogger.i(TAG, "User cancelled credential dialog id=$requestId")
        }
    }

    private fun clearIfCurrent(requestId: String) {
        if (_request.value?.requestId == requestId) _request.value = null
    }

    /** Write to .tmp first, then rename for an atomic write, so the helper's polling does not read a half-written file. */
    private fun writeRespAtomically(dir: File, requestId: String, content: String) {
        val target = File(dir, RESP_PREFIX + requestId)
        val tmp = File(dir, "$RESP_PREFIX${requestId}.tmp")
        runCatching {
            tmp.writeText(content)
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                target.writeText(content)
                tmp.delete()
            }
        }.onFailure { FileLogger.e(TAG, "Failed to write credential response file", it) }
    }
}

/** A [LinkedHashSet] with a capacity cap; entries beyond [cap] automatically evict the oldest. Used for deduplication by [CredentialRequestBridge.seen]. */
private class LinkedHashSetWithCap(private val cap: Int) : LinkedHashSet<String>() {
    fun addCapped(id: String): Boolean {
        if (contains(id)) return false
        add(id)
        if (size > cap) {
            iterator().next().let { remove(it) }
        }
        return true
    }
}
