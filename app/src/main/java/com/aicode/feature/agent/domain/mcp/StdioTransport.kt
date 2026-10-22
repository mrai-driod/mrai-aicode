package com.aicode.feature.agent.domain.mcp

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.ContainerProfile
import com.aicode.feature.agent.domain.container.LinuxContainerEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.BufferedWriter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * MCP "stdio" transport implementation: runs the MCP server as a long-lived subprocess inside the container,
 * writes one line of JSON-RPC to its **stdin** and reads one line of JSON-RPC (NDJSON) from its **stdout**;
 * **stderr** is consumed separately purely as server log and does not pollute the protocol stream.
 *
 * The process is launched by [LinuxContainerEngine.startStdioProcess] inside the proot container (e.g. `npx -y some-server`),
 * started lazily on the first [request]/[notify] (serialised by [startMutex]). The stdout read loop routes each response with an id to
 * the corresponding pending [CompletableDeferred]; requests/notifications initiated by the server (with a method) are not currently supported and are logged and ignored.
 *
 * Peer to [StreamableHttpTransport]: protocol errors are thrown as [McpException], and [McpClient]/[McpManager]
 * handle them per-server, so a single server failing does not affect other servers or the Agent as a whole.
 */
class StdioTransport(
    private val serverName: String,
    private val engine: LinuxContainerEngine,
    private val program: String,
    private val programArgs: List<String>,
    private val projectPath: String?,
    private val extraEnv: Map<String, String> = emptyMap(),
    private val runtimeProfile: ContainerProfile = ContainerProfile.BUILTIN_ALPINE,
    private val json: Json = DEFAULT_JSON
) : McpTransport {

    private companion object {
        const val TAG = "McpStdioTransport"

        /** Cap on waiting for a single request's response: a first `npx -y` run may need to download over the network, so allow ample time. */
        const val REQUEST_TIMEOUT_MS = 120_000L

        /** Cap on waiting for the process to exit after stdout closes: used to obtain the exit code. */
        const val EXIT_WAIT_MS = 2_000L

        @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
        val DEFAULT_JSON = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val startMutex = Mutex()
    private val writeMutex = Mutex()
    private val idCounter = AtomicLong(0)

    /** Requests already sent and awaiting a response: id -> the response to complete. */
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonRpcResponse>>()

    @Volatile private var process: Process? = null
    @Volatile private var writer: BufferedWriter? = null
    @Volatile private var closed = false

    override suspend fun request(method: String, params: JsonObject?): JsonRpcResponse {
        ensureStarted()
        val id = idCounter.incrementAndGet()
        val payload = JsonRpcRequest(id = id, method = method, params = params)
        val deferred = CompletableDeferred<JsonRpcResponse>()
        pending[id] = deferred
        FileLogger.d(TAG, "→ [$serverName] $method id=$id")

        try {
            writeLine(json.encodeToString(JsonRpcRequest.serializer(), payload))
        } catch (e: Exception) {
            pending.remove(id)
            throw McpException(message = "[$serverName] Failed to write to stdin: ${e.message}", cause = e)
        }

        val response = withTimeoutOrNull(REQUEST_TIMEOUT_MS) { deferred.await() }
        if (response == null) {
            pending.remove(id)
            throw McpException(message = "[$serverName] Request $method timed out (${REQUEST_TIMEOUT_MS}ms)")
        }
        response.error?.let {
            throw McpException(rpcCode = it.code, message = "$method returned error [${it.code}] ${it.message}")
        }
        return response
    }

    override suspend fun notify(method: String, params: JsonObject?) {
        ensureStarted()
        val payload = JsonRpcNotification(method = method, params = params)
        FileLogger.d(TAG, "→ notify [$serverName] $method")
        runCatching { writeLine(json.encodeToString(JsonRpcNotification.serializer(), payload)) }
            .onFailure { FileLogger.w(TAG, "[$serverName] Failed to send notification $method: ${it.message}") }
    }

    override fun close() {
        if (closed) return
        closed = true
        FileLogger.i(TAG, "[$serverName] Closing stdio transport")
        scope.cancel()
        runCatching { writer?.close() }
        runCatching { process?.destroy() }
        writer = null
        process = null
        failAllPending("transport closed")
    }

    /** Lazily starts the subprocess and attaches the stdout read loop / stderr drain. Serialised to avoid concurrent duplicate starts. */
    private suspend fun ensureStarted() = startMutex.withLock {
        if (process != null) return@withLock
        if (closed) throw McpException(message = "[$serverName] transport closed")

        FileLogger.i(TAG, "[$serverName] Starting stdio server: $program ${programArgs.joinToString(" ")}")
        val p = try {
            engine.startStdioProcess(program, programArgs, projectPath, extraEnv, runtimeProfile)
        } catch (e: Exception) {
            throw McpException(message = "[$serverName] Failed to start subprocess: ${e.message}", cause = e)
        }
        process = p
        writer = p.outputStream.bufferedWriter()
        scope.launch { readLoop(p) }
        scope.launch { drainStderr(p) }
    }

    /** Synchronised write of one line (multiple concurrent requests share one stdin). */
    private suspend fun writeLine(line: String) = writeMutex.withLock {
        val w = writer ?: throw McpException(message = "[$serverName] transport not started")
        withContext(Dispatchers.IO) {
            w.write(line)
            w.write("\n")
            w.flush()
        }
    }

    /** Reads stdout line by line, routing each response by id to the corresponding pending entry; non-responses (with a method, or unparseable) are ignored. */
    private fun readLoop(p: Process) {
        val reader = p.inputStream.bufferedReader()
        try {
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue

                val obj = runCatching { json.parseToJsonElement(line) as? JsonObject }.getOrNull()
                if (obj == null) {
                    // Raise to INFO and do not truncate: the spec requires stdout to carry only the protocol, so non-JSON output is mostly a server startup banner or an npx interactive prompt,
                    // which is a key clue for diagnosing startup failures; if it were logged only at DEBUG, release builds (minLevel=INFO) would not show it.
                    FileLogger.i(TAG, "[$serverName] Skipping non-JSON line: $line")
                    continue
                }
                // Entries with a method are server->client requests/notifications (such as sampling/roots); not currently supported, ignored.
                if (obj.containsKey("method")) {
                    FileLogger.d(TAG, "[$serverName] Ignoring server-initiated message: ${obj["method"]}")
                    continue
                }

                val resp = runCatching { json.decodeFromJsonElement(JsonRpcResponse.serializer(), obj) }.getOrNull()
                if (resp?.id == null) {
                    FileLogger.d(TAG, "[$serverName] Skipping response with no id")
                    continue
                }
                val deferred = pending.remove(resp.id)
                if (deferred == null) {
                    FileLogger.w(TAG, "[$serverName] Received response with no matching request id=${resp.id}")
                } else {
                    deferred.complete(resp)
                }
            }
        } catch (e: Exception) {
            FileLogger.w(TAG, "[$serverName] stdout read loop ended abnormally: ${e.message}")
        } finally {
            FileLogger.i(TAG, "[$serverName] stdout ended (process may have exited)")
            logExitCode(p)
            failAllPending("server stdout closed")
        }
    }

    /**
     * After stdout closes the process usually exits immediately, so record its exit code -- on install failure/abnormal termination the non-zero `exitCode` is directly visible,
     * without having to infer it from stderr content alone. If the process has not yet exited, wait up to [EXIT_WAIT_MS]; if it still has not, skip.
     */
    private fun logExitCode(p: Process) {
        val exited = runCatching { p.waitFor(EXIT_WAIT_MS, TimeUnit.MILLISECONDS) }.getOrDefault(false)
        val code = if (exited) runCatching { p.exitValue() }.getOrNull() else null
        if (code == null) return
        if (code == 0) {
            FileLogger.i(TAG, "[$serverName] Process exited exitCode=0")
        } else {
            FileLogger.w(TAG, "[$serverName] Process exited exitCode=$code")
        }
    }

    /** The server's diagnostic log is written to stderr, drained and recorded separately (npx package-fetch output is here too; raised to INFO to make install failures easier to locate). */
    private fun drainStderr(p: Process) {
        val reader = p.errorStream.bufferedReader()
        runCatching {
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isNotBlank()) FileLogger.i(TAG, "[$serverName] stderr: $line")
            }
        }
    }

    /** Makes all in-flight requests finish with an exception (called on process exit / close), avoiding callers hanging forever. */
    private fun failAllPending(reason: String) {
        pending.keys.toList().forEach { id ->
            pending.remove(id)?.completeExceptionally(McpException(message = "[$serverName] $reason"))
        }
    }
}
