package com.aicode.feature.agent.domain.mcp

import com.aicode.core.util.FileLogger
import com.aicode.core.watch.FileChangeHub
import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.workspace.domain.ProjectAicodeRoot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Configuration scope of an MCP server: global (shared across projects) or project-level (effective only in the current workspace). */
enum class McpScope { GLOBAL, PROJECT }

/** An effective MCP entry: config + its source scope, for the UI to label as "global/project". */
data class McpServerEntry(
    val server: McpServerConfig,
    val scope: McpScope
)

/**
 * MCP config persistence, supporting two levels, global + project:
 * - Global: `filesDir/aicode/mcp.json` (kept across projects and upgrades);
 * - Project-level: `workspacePath/.aicode/mcp.json` (travels with the workspace, can be tracked by git).
 *
 * Effective config = global + project merged, with **project level taking priority**: for the same name, the project entry overrides the global entry.
 * Concurrency model: a Mutex guards file IO + a MutableStateFlow cache; project level is cached per workspace path.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class McpConfigRepository @Inject constructor(
    private val containerInstaller: ContainerInstaller,
    private val workspaceRepository: WorkspaceRepository,
    private val projectAicodeRoot: ProjectAicodeRoot,
    private val fileChangeHub: FileChangeHub
) {
    private companion object {
        const val TAG = "McpConfigRepository"
        const val CONFIG_FILE = "mcp.json"
        const val DEFAULT_JSON = """{"mcpServers":{}}"""
        /** Project-level config directory name; inside the container this is `~/workspace/.aicode`. */
        const val AICODE_DIR_NAME = ".aicode"
        val JSON = Json { ignoreUnknownKeys = true; isLenient = true }
        val PRETTY_JSON = Json { prettyPrint = true }
    }

    /** Global config file: `filesDir/aicode/mcp.json`. */
    private val globalFile: File
        get() = File(containerInstaller.aicodeDir, CONFIG_FILE)

    /** Project-level config file for the current workspace: `workspacePath/.aicode/mcp.json`. */
    private fun projectFileForPath(workspacePath: String): File =
        File(projectAicodeRoot.forPath(workspacePath), CONFIG_FILE)

    private val globalState = MutableStateFlow<String?>(null)
    private val projectStates = ConcurrentHashMap<String, MutableStateFlow<String?>>()
    private val mutex = Mutex()

    // -- External change watching: after the config file is edited directly (inside the container / by hand), refresh the cache and broadcast to McpManager to reconnect --

    private val watchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Broadcasts once when the config is modified externally (content differs from the cache). Subscription-driven: only while there is a subscriber (McpManager)
     * does [FileChangeHub] watch the directories containing the two config files; with no subscriber there is zero overhead.
     */
    val externalChanges: SharedFlow<Unit> = merge(
        fileChangeHub.watchAicode(),
        fileChangeHub.watchWorkspace("${FileChangeHub.CONTAINER_ROOT}/$AICODE_DIR_NAME")
    ).mapNotNull { batch ->
        val globalPath = globalFile.absolutePath
        val projectPath = projectFileForPath(workspaceRepository.currentPath()).absolutePath
        val touched = batch.changes.any { it.hostPath == globalPath || it.hostPath == projectPath }
        // Changes to other files in the same directory (such as skills.json) do not trigger a reconnect; nor does content that did not really change (such as a touch).
        if (!touched) null else if (reloadFromDisk()) Unit else null
    }.shareIn(watchScope, SharingStarted.WhileSubscribed(), replay = 0)

    /**
     * After an external change, refreshes the cache from the on-disk state and returns true when the content really changed. Project level handles only the current workspace;
     * when its cache has not been loaded yet, no broadcast is made -- the first load is done by [ensureProjectLoaded], and a workspace switch does not count as an external change.
     */
    private suspend fun reloadFromDisk(): Boolean {
        var changed = false
        val globalContent = load(globalFile)
        if (globalContent != (globalState.value ?: DEFAULT_JSON)) {
            globalState.value = globalContent
            changed = true
            FileLogger.i(TAG, "Detected a global MCP config change, refreshed")
        }
        val path = workspaceRepository.currentPath()
        val state = getProjectState(path)
        if (state.value != null) {
            val content = load(projectFileForPath(path))
            if (content != state.value) {
                state.value = content
                changed = true
                FileLogger.i(TAG, "Detected a project MCP config change, refreshed")
            }
        }
        return changed
    }

    private fun getProjectState(workspacePath: String): MutableStateFlow<String?> =
        projectStates.getOrPut(workspacePath) { MutableStateFlow(null) }

    private suspend fun ensureGlobalLoaded() {
        if (globalState.value != null) return
        mutex.withLock {
            if (globalState.value != null) return
            globalState.value = load(globalFile)
        }
    }

    private suspend fun ensureProjectLoaded(workspacePath: String) {
        val state = getProjectState(workspacePath)
        if (state.value != null) return
        mutex.withLock {
            if (state.value != null) return
            state.value = load(projectFileForPath(workspacePath))
        }
    }

    private suspend fun load(file: File): String = withContext(Dispatchers.IO) {
        // Read-only loading: when the file does not exist, returns the default config and does not proactively create directories/files,
        // avoiding accidentally creating a .aicode directory under projectsRoot that the workspace scanner would mistake for a workspace.
        if (file.isFile) {
            return@withContext runCatching { file.readText() }.getOrElse {
                FileLogger.w(TAG, "Failed to read ${file.name}, falling back to the default config: ${it.message}")
                DEFAULT_JSON
            }
        }
        DEFAULT_JSON
    }

    private fun writeFile(file: File, json: String) {
        file.parentFile?.mkdirs()
        file.writeText(json)
    }

    /** Flow of the global MCP config. */
    val globalServersFlow: Flow<List<McpServerConfig>> = flow {
        ensureGlobalLoaded()
        emitAll(globalState.filterNotNull().map { parse(it) })
    }

    /**
     * Flow of the MCP entries effective for the current project (global + project merged, project overriding the same name),
     * automatically reloading the corresponding project config when the current workspace switches.
     */
    val effectiveEntriesFlow: Flow<List<McpServerEntry>> =
        workspaceRepository.current.flatMapLatest {
            val path = workspaceRepository.currentPath()
            ensureGlobalLoaded()
            ensureProjectLoaded(path)
            combine(globalState, getProjectState(path)) { g, p ->
                merge(parse(g ?: DEFAULT_JSON), parse(p ?: DEFAULT_JSON))
            }
        }

    suspend fun getGlobalServers(): List<McpServerConfig> {
        ensureGlobalLoaded()
        return parse(globalState.value ?: DEFAULT_JSON)
    }

    suspend fun getProjectServers(): List<McpServerConfig> {
        val path = workspaceRepository.currentPath()
        ensureProjectLoaded(path)
        return parse(getProjectState(path).value ?: DEFAULT_JSON)
    }

    suspend fun setGlobalServers(servers: List<McpServerConfig>) {
        val json = serialize(servers)
        mutex.withLock {
            withContext(Dispatchers.IO) { writeFile(globalFile, json) }
            globalState.value = json
        }
    }

    suspend fun setProjectServers(servers: List<McpServerConfig>) {
        val path = workspaceRepository.currentPath()
        val json = serialize(servers)
        mutex.withLock {
            withContext(Dispatchers.IO) { writeFile(projectFileForPath(path), json) }
            getProjectState(path).value = json
        }
    }

    /** The merged config effective for the current project (project overriding the same name), for [McpManager] to connect with. */
    suspend fun getEffectiveServers(): List<McpServerConfig> =
        getEffectiveEntries().map { it.server }

    /** The merged entries effective for the current project (including source scope), for the settings page list to label. */
    suspend fun getEffectiveEntries(): List<McpServerEntry> {
        val path = workspaceRepository.currentPath()
        ensureGlobalLoaded()
        ensureProjectLoaded(path)
        return merge(
            parse(globalState.value ?: DEFAULT_JSON),
            parse(getProjectState(path).value ?: DEFAULT_JSON)
        )
    }

    fun serialize(servers: List<McpServerConfig>): String {
        val serversObj = buildJsonObject {
            servers.forEach { server ->
                putJsonObject(server.name) {
                    if (server.isStdio) {
                        put("command", server.command)
                        if (server.args.isNotEmpty()) {
                            putJsonArray("args") { server.args.forEach { add(it) } }
                        }
                        if (server.env.isNotEmpty()) {
                            putJsonObject("env") {
                                server.env.forEach { (key, value) -> put(key, value) }
                            }
                        }
                    } else {
                        put("url", server.url ?: "")
                        if (server.headers.isNotEmpty()) {
                            putJsonObject("headers") {
                                server.headers.forEach { (key, value) -> put(key, value) }
                            }
                        }
                    }
                    put("enabled", server.enabled)
                    if (server.disabledTools.isNotEmpty()) {
                        putJsonArray("disabledTools") { server.disabledTools.forEach { add(it) } }
                    }
                }
            }
        }
        val root = buildJsonObject { put("mcpServers", serversObj) }
        return PRETTY_JSON.encodeToString(JsonObject.serializer(), root)
    }

    fun parse(raw: String): List<McpServerConfig> {
        val root = runCatching { JSON.parseToJsonElement(raw).jsonObject }.getOrElse {
            FileLogger.w(TAG, "Failed to parse MCP config JSON: ${it.message}")
            return emptyList()
        }
        val servers = (root["mcpServers"] as? JsonObject) ?: return emptyList()

        return servers.mapNotNull { (name, element) ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val enabled = (obj["enabled"] as? JsonPrimitive)?.booleanOrNull ?: true

            val command = (obj["command"] as? JsonPrimitive)?.contentOrNull
            val url = (obj["url"] as? JsonPrimitive)?.contentOrNull

            val disabledTools = (obj["disabledTools"] as? JsonArray)?.mapNotNull {
                (it as? JsonPrimitive)?.contentOrNull
            }?.toSet() ?: emptySet()

            when {
                !command.isNullOrBlank() -> {
                    val args = (obj["args"] as? JsonArray)?.mapNotNull {
                        (it as? JsonPrimitive)?.contentOrNull
                    } ?: emptyList()
                    val env = (obj["env"] as? JsonObject)?.mapNotNull { (k, v) ->
                        (v as? JsonPrimitive)?.contentOrNull?.let { k to it }
                    }?.toMap() ?: emptyMap()
                    McpServerConfig(
                        name = name,
                        command = command,
                        args = args,
                        env = env,
                        enabled = enabled,
                        disabledTools = disabledTools
                    )
                }
                !url.isNullOrBlank() -> {
                    val headers = (obj["headers"] as? JsonObject)?.mapNotNull { (k, v) ->
                        (v as? JsonPrimitive)?.contentOrNull?.let { k to it }
                    }?.toMap() ?: emptyMap()
                    McpServerConfig(
                        name = name,
                        url = url,
                        headers = headers,
                        enabled = enabled,
                        disabledTools = disabledTools
                    )
                }
                else -> {
                    // Has neither url nor command, cannot be recognised, skipping.
                    FileLogger.i(TAG, "Skipping unrecognisable MCP server (missing url/command): $name")
                    null
                }
            }
        }
    }

    /** Merges global and project config: global entries first in order, project entries override the same name, order = global order + new project entries. */
    private fun merge(
        global: List<McpServerConfig>,
        project: List<McpServerConfig>
    ): List<McpServerEntry> {
        val byName = LinkedHashMap<String, McpServerEntry>()
        global.forEach { byName[it.name] = McpServerEntry(it, McpScope.GLOBAL) }
        project.forEach { byName[it.name] = McpServerEntry(it, McpScope.PROJECT) }
        return byName.values.toList()
    }
}