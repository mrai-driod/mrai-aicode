package com.aicode.feature.agent.domain.mcp

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.ContainerProfile
import com.aicode.feature.agent.domain.container.LinuxContainerEngine
import com.aicode.feature.agent.domain.tool.ToolRegistry
import com.aicode.feature.settings.data.repository.ContainerSettingsRepository
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

data class McpServerStatus(
    val name: String,
    val state: State,
    val toolCount: Int = 0,
    val error: String? = null
) {
    enum class State { CONNECTING, CONNECTED, FAILED, DISABLED }
}

// reloadMutex serialises reconnects, avoiding concurrent register/unregister races from rapid taps on the settings page.
@Singleton
class McpManager @Inject constructor(
    private val configRepository: McpConfigRepository,
    private val toolRegistry: ToolRegistry,
    private val okHttpClient: OkHttpClient,
    private val containerEngine: LinuxContainerEngine,
    private val workspaceRepository: WorkspaceRepository,
    private val containerSettingsRepository: ContainerSettingsRepository
) {
    private companion object {
        const val TAG = "McpManager"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val reloadMutex = Mutex()

    private val activeClients = mutableMapOf<String, McpClient>()
    private val registeredToolNames = mutableSetOf<String>()

    private val _statuses = MutableStateFlow<List<McpServerStatus>>(emptyList())
    val statuses: StateFlow<List<McpServerStatus>> = _statuses.asStateFlow()

    fun start() {
        // Automatically reloads when the current workspace switches (the first frame immediately emits the current value, equivalent to reconnecting at startup;
        // when a project switches, both the merged config and the project path for stdio tools change, so connections must be rebuilt).
        scope.launch {
            workspaceRepository.current.collectLatest {
                reload()
            }
        }
        // The config file is edited externally (inside the container / by hand): reload automatically within a few seconds so additions/deletions/enable-disable take effect immediately.
        scope.launch {
            configRepository.externalChanges.collect {
                FileLogger.i(TAG, "Detected an external change to the MCP config file, reloading automatically")
                reload()
            }
        }
        // Container profile switch: the stdio server process runs on the old container's rootfs and must be rebuilt to use the new container;
        // HTTP servers do not depend on the container and are unaffected. drop(1) skips the first startup frame (reload already handled it).
        scope.launch {
            containerSettingsRepository.activeProfileIdFlow.drop(1).collect {
                reloadStdioServers()
            }
        }
        // Default container change: in remote mode the stdio server runs on the default container, so it likewise needs rebuilding.
        scope.launch {
            containerSettingsRepository.defaultContainerIdFlow.drop(1).collect {
                if (currentActiveProfile().mode == ExecutionMode.REMOTE_SSH) {
                    reloadStdioServers()
                }
            }
        }
    }

    suspend fun reload() = reloadMutex.withLock {
        val servers = configRepository.getEffectiveServers()
        FileLogger.i(TAG, "Reloading MCP config, ${servers.size} servers in total")

        teardown()

        if (servers.isEmpty()) {
            _statuses.value = emptyList()
            return@withLock
        }

        // First set every server to "connecting/disabled" so the UI gives immediate feedback.
        _statuses.value = servers.map { cfg ->
            McpServerStatus(
                name = cfg.name,
                state = if (cfg.enabled) McpServerStatus.State.CONNECTING else McpServerStatus.State.DISABLED
            )
        }

        // Connect all enabled servers in parallel; each fails independently.
        val results = withContext(Dispatchers.IO) {
            servers.filter { it.enabled }.map { cfg ->
                async { connectOne(cfg) }
            }.awaitAll()
        }

        // Merge the disabled entries with the connection results, preserving the original order.
        val byName = results.associateBy { it.name }
        _statuses.value = servers.map { cfg ->
            byName[cfg.name] ?: McpServerStatus(cfg.name, McpServerStatus.State.DISABLED)
        }
    }

    private suspend fun connectOne(cfg: McpServerConfig): McpServerStatus {
        val t0 = System.currentTimeMillis()
        return try {
            FileLogger.i(TAG, "[${cfg.name}] Starting connection (${if (cfg.isStdio) "stdio" else "HTTP"})")
            val transport = if (cfg.isStdio) {
                // The stdio server runs on the "runtime container": local mode uses the current container, remote SSH mode uses the default container.
                // If the container is not ready, do not initialise it automatically; fail directly and direct the user to the terminal page to finish initialisation.
                val runtimeProfile = resolveMcpRuntimeProfile()
                containerEngine.notReadyHintFor(runtimeProfile)?.let {
                    throw IllegalStateException(it)
                }
                StdioTransport(
                    serverName = cfg.name,
                    engine = containerEngine,
                    program = cfg.command!!,
                    programArgs = cfg.args,
                    projectPath = workspaceRepository.currentPath(),
                    extraEnv = cfg.env,
                    runtimeProfile = runtimeProfile
                )
            } else {
                StreamableHttpTransport(
                    endpoint = cfg.url.orEmpty(),
                    client = okHttpClient,
                    extraHeaders = cfg.headers
                )
            }
            val client = McpClient(serverName = cfg.name, transport = transport)
            client.connect()

            val tools = client.tools.map { McpTool(client, it) }
            val enabledTools = tools.filter { it.remoteName !in cfg.disabledTools }
            synchronized(activeClients) {
                activeClients[cfg.name] = client
                enabledTools.forEach { tool ->
                    toolRegistry.register(tool.name, tool)
                    registeredToolNames.add(tool.name)
                }
            }
            FileLogger.i(TAG, "[${cfg.name}] Connected successfully, registered ${enabledTools.size}/${tools.size} tools (${System.currentTimeMillis() - t0}ms)")
            McpServerStatus(cfg.name, McpServerStatus.State.CONNECTED, toolCount = enabledTools.size)
        } catch (e: Exception) {
            FileLogger.e(TAG, "[${cfg.name}] Connection failed (${System.currentTimeMillis() - t0}ms)", e)
            McpServerStatus(cfg.name, McpServerStatus.State.FAILED, error = e.message)
        }
    }

    fun getServerTools(serverName: String): List<McpToolDescriptor> {
        return synchronized(activeClients) {
            activeClients[serverName]?.tools ?: emptyList()
        }
    }

    /**
     * Reconnects a single server only (used for save / enable toggle / edit refresh); other servers' connections and registered tools are unaffected.
     * Silently returns when that name does not exist in the config.
     */
    suspend fun reloadServer(name: String) = reloadMutex.withLock {
        val cfg = configRepository.getEffectiveServers().firstOrNull { it.name == name } ?: return@withLock
        teardownServer(name)
        if (_statuses.value.none { it.name == name }) {
            _statuses.value = _statuses.value + McpServerStatus(name, McpServerStatus.State.CONNECTING)
        }
        if (!cfg.enabled) {
            _statuses.value = _statuses.value.map {
                if (it.name == name) McpServerStatus(name, McpServerStatus.State.DISABLED) else it
            }
            return@withLock
        }
        reconnectOne(cfg)
    }

    /**
     * Reconnects only currently unconnected servers (connected ones are skipped), as a fallback for new sessions: manageMcp additions/deletions only change the config without reconnecting,
     * prompting "takes effect next session"; this method makes a new session actually connect the unconnected servers without interrupting already-connected tools.
     */
    suspend fun reconnectUnconnected() = reloadMutex.withLock {
        val servers = configRepository.getEffectiveServers().filter { it.enabled }
        for (cfg in servers) {
            val connected = synchronized(activeClients) { activeClients.containsKey(cfg.name) }
            if (connected) continue
            reconnectOne(cfg)
        }
    }

    /**
     * Asynchronously reconnects unconnected servers as a fallback (does not block the caller). Creating a new session should not wait for MCP to be ready:
     * a stdio server's first run can stall for a long time downloading dependencies / timing out on the handshake, and waiting synchronously would make "new session" look frozen.
     */
    fun reconnectUnconnectedAsync() {
        scope.launch { reconnectUnconnected() }
    }

    private suspend fun reconnectOne(cfg: McpServerConfig) {
        if (_statuses.value.none { it.name == cfg.name }) {
            _statuses.value = _statuses.value + McpServerStatus(cfg.name, McpServerStatus.State.CONNECTING)
        }
        _statuses.value = _statuses.value.map {
            if (it.name == cfg.name) McpServerStatus(cfg.name, McpServerStatus.State.CONNECTING) else it
        }
        val result = withContext(Dispatchers.IO) { connectOne(cfg) }
        _statuses.value = _statuses.value.map { if (it.name == cfg.name) result else it }
    }

    /** Resolves the runtime container for a stdio server: local mode uses the current profile, remote SSH mode uses the default container. */
    private suspend fun resolveMcpRuntimeProfile(): ContainerProfile {
        val active = currentActiveProfile()
        return if (active.mode == ExecutionMode.REMOTE_SSH) resolveDefaultContainerProfile() else active
    }

    /** The currently active profile: resolved by id from the config list, falling back to the built-in Alpine when not found. */
    private suspend fun currentActiveProfile(): ContainerProfile {
        val id = containerSettingsRepository.activeProfileIdFlow.first()
        val profiles = containerSettingsRepository.customProfilesFlow.first()
        return profiles.firstOrNull { it.id == id } ?: ContainerProfile.BUILTIN_ALPINE
    }

    /** Default container: the configured id and in local PRoot mode; if not found/deleted/remote, falls back to the built-in Alpine. */
    private suspend fun resolveDefaultContainerProfile(): ContainerProfile {
        val id = containerSettingsRepository.defaultContainerIdFlow.first()
        val profiles = containerSettingsRepository.customProfilesFlow.first()
        return profiles.firstOrNull { it.id == id && it.mode == ExecutionMode.LOCAL_PROOT }
            ?: ContainerProfile.BUILTIN_ALPINE
    }

    /**
     * After container-related config changes, rebuild all stdio servers (HTTP does not depend on the container and is left alone).
     * Old processes are pinned to the old container's rootfs and must be torn down before they can be launched with the new container; a single server failing does not affect the others.
     */
    private suspend fun reloadStdioServers() = reloadMutex.withLock {
        val servers = configRepository.getEffectiveServers().filter { it.enabled && it.isStdio }
        if (servers.isEmpty()) return@withLock
        FileLogger.i(TAG, "Container config changed, rebuilding ${servers.size} stdio MCP servers")
        for (cfg in servers) {
            teardownServer(cfg.name)
            reconnectOne(cfg)
        }
    }

    /** When deleting a server, only disconnects it and unregisters its tools, without affecting other servers. */
    suspend fun removeServer(name: String) = reloadMutex.withLock {
        teardownServer(name)
        _statuses.value = _statuses.value.filterNot { it.name == name }
    }

    private fun teardownServer(name: String) {
        synchronized(activeClients) {
            val client = activeClients.remove(name) ?: return
            runCatching { client.close() }
            client.tools.forEach { tool ->
                toolRegistry.unregister(tool.name)
                registeredToolNames.remove(tool.name)
            }
        }
    }

    private fun teardown() {
        synchronized(activeClients) {
            registeredToolNames.forEach { toolRegistry.unregister(it) }
            registeredToolNames.clear()
            activeClients.values.forEach { runCatching { it.close() } }
            activeClients.clear()
        }
    }
}
