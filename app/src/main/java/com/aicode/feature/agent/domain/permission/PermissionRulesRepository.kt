package com.aicode.feature.agent.domain.permission

import android.content.Context
import com.aicode.core.util.FileLogger
import com.aicode.core.watch.FileChangeBatch
import com.aicode.core.watch.FileChangeHub
import kotlinx.coroutines.ExperimentalCoroutinesApi
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.workspace.domain.ProjectAicodeRoot
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persistence for tool authorization rules. Global rules are stored in the app's private directory
 * (`filesDir/aicode/permissions.json`); project-level rules are stored in the workspace directory
 * (`.aicode/permissions.json`), so a team can share them via git.
 *
 * The file format is the compact `Tool(pattern)` style, for example:
 * ```json
 * {
 *   "permissions": {
 *     "allow": ["Bash(git pull)", "writeFile"],
 *     "deny": ["Bash(rm -rf /)"]
 *   }
 * }
 * ```
 *
 * Security points: global rules live in the app's private directory, where the AI cannot tamper with them;
 * project-level rules live inside the workspace and can be modified by the AI, but as declarative
 * project-level configuration that is intentional (they can be tracked/rolled back via git).
 *
 * The concurrency pattern follows [McpConfigRepository]: a Mutex protects file IO + a MutableStateFlow cache.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class PermissionRulesRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val workspaceRepository: WorkspaceRepository,
    private val projectAicodeRoot: ProjectAicodeRoot,
    private val fileChangeHub: FileChangeHub
) {
    private companion object {
        const val TAG = "PermissionRules"
        const val PERMISSIONS_FILE = "permissions.json"
        /** The project-level config directory name; inside the container this is `~/workspace/.aicode`. */
        const val AICODE_DIR_NAME = ".aicode"
        val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    }

    /** The global permissions file: `filesDir/aicode/permissions.json`, at the same level as mcp.json. */
    private val globalFile: File
        get() = File(File(context.filesDir, "aicode"), PERMISSIONS_FILE)

    /** The project-level permissions file for the current workspace: `workspacePath/.aicode/permissions.json`. */
    private fun projectFileForPath(workspacePath: String): File =
        File(projectAicodeRoot.forPath(workspacePath), PERMISSIONS_FILE)

    // ── In-memory cache and reactive flows ──────────────────────────────────────

    private val globalState = MutableStateFlow<List<PermissionRule>?>(null)
    private val projectStates = ConcurrentHashMap<String, MutableStateFlow<List<PermissionRule>?>>()
    private val mutex = Mutex()

    // ── External-change watching: after permissions.json is edited directly inside the container / by hand, refresh the cache so the UI and evaluation take effect immediately ──

    private val watchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Starts watching the config files: after an external change, refreshes the cache from the current on-disk
     * state. Called once at app startup (idempotent). Subscription-driven: [FileChangeHub] watches the
     * directories containing both permission files and reports merged batches; no self-built polling anymore.
     * The subscription stays resident because AI permission evaluation must read the latest rules at any time
     * (unlike a pure UI list, which can pause refreshing).
     */
    fun startWatching() {
        watchScope.launch {
            merge(
                fileChangeHub.watchAicode(),
                fileChangeHub.watchWorkspace("${FileChangeHub.CONTAINER_ROOT}/$AICODE_DIR_NAME")
            ).collect { batch -> refreshFromDisk(batch) }
        }
    }

    /** Only handles files that were hit; the cache is updated only when the content actually changed (a touch does not count). */
    private suspend fun refreshFromDisk(batch: FileChangeBatch) {
        val globalPath = globalFile.absolutePath
        if (batch.changes.any { it.hostPath == globalPath }) {
            val rules = loadFromFile(globalFile)
            if (rules != (globalState.value ?: emptyList<PermissionRule>())) {
                globalState.value = rules
                FileLogger.i(TAG, "Detected a change to the global permission config; refreshed")
            }
        }
        val path = workspaceRepository.currentPath()
        if (batch.changes.any { it.hostPath == projectFileForPath(path).absolutePath }) {
            val state = getProjectState(path)
            // Not handled while the cache has not loaded yet: the first load is done by [ensureProjectLoaded], and a workspace switch does not count as an external change.
            if (state.value != null) {
                val rules = loadFromFile(projectFileForPath(path))
                if (rules != state.value) {
                    state.value = rules
                    FileLogger.i(TAG, "Detected a change to the project permission config; refreshed")
                }
            }
        }
    }

    private fun getProjectState(workspacePath: String): MutableStateFlow<List<PermissionRule>?> =
        projectStates.getOrPut(workspacePath) { MutableStateFlow(null) }

    // ── Lazy loading ──────────────────────────────────────────────────

    private suspend fun ensureGlobalLoaded() {
        if (globalState.value != null) return
        mutex.withLock {
            if (globalState.value != null) return
            globalState.value = loadFromFile(globalFile)
        }
    }

    private suspend fun ensureProjectLoaded(workspacePath: String) {
        val state = getProjectState(workspacePath)
        if (state.value != null) return
        mutex.withLock {
            if (state.value != null) return
            state.value = loadFromFile(projectFileForPath(workspacePath))
        }
    }

    private suspend fun loadFromFile(file: File): List<PermissionRule> =
        withContext(Dispatchers.IO) {
            if (!file.isFile) return@withContext emptyList()
            runCatching {
                JSON.decodeFromString<PermissionFile>(file.readText()).toRuleList()
            }.getOrElse {
                FileLogger.w(TAG, "Failed to read ${file.path}: ${it.message}")
                emptyList()
            }
        }

    private fun writeToFile(file: File, rules: List<PermissionRule>) {
        file.parentFile?.mkdirs()
        file.writeText(JSON.encodeToString(PermissionFile.serializer(), rules.toPermissionFile()))
    }

    // ── Public API ────────────────────────────────────────────────

    /** The name of the currently selected project; null when none is selected (project-level rules are then unavailable and only the global ones apply). */
    fun currentProjectName(): String? = workspaceRepository.current.value?.name

    /** Flow of the current project name; updates automatically as the workspace switches, for the UI to subscribe to. */
    val currentProjectNameFlow: Flow<String?> = workspaceRepository.current.map { it?.name }

    /**
     * Flow of the current project's rules: automatically reloads the corresponding project rules as
     * [workspaceRepository.current] switches; emits an empty list when no workspace is selected.
     * For the UI to subscribe to, avoiding a one-shot snapshot reading null before initialization completes.
     */
    val currentProjectRulesFlow: Flow<List<PermissionRule>> =
        workspaceRepository.current.flatMapLatest { ws ->
            if (ws == null) flowOf(emptyList()) else projectRulesFlow(ws.name)
        }

    /** Flow of the global rules, for the management UI to observe. */
    val globalRulesFlow: Flow<List<PermissionRule>> = flow {
        ensureGlobalLoaded()
        emitAll(globalState.filterNotNull())
    }

    /** One-shot read of all global rules (for backup). */
    suspend fun getGlobalRulesOnce(): List<PermissionRule> {
        ensureGlobalLoaded()
        return globalState.value ?: emptyList()
    }

    /** Replaces all global rules (for backup import); writes the file atomically and updates the cache. */
    suspend fun setGlobalRules(rules: List<PermissionRule>) {
        mutex.withLock {
            withContext(Dispatchers.IO) { writeToFile(globalFile, rules) }
            globalState.value = rules
        }
    }

    /** Flow of the rules for the specified project, for the management UI to observe. */
    fun projectRulesFlow(projectName: String): Flow<List<PermissionRule>> {
        val workspacePath = workspaceRepository.currentPath()
        val state = getProjectState(workspacePath)
        return flow {
            ensureProjectLoaded(workspacePath)
            emitAll(state.filterNotNull())
        }
    }

    /**
     * For evaluation: the current project's rules + the global rules merged (project first). A one-shot snapshot read.
     */
    suspend fun loadEffectiveForCurrentProject(): List<PermissionRule> {
        ensureGlobalLoaded()
        val global = globalState.value ?: emptyList()
        val workspacePath = workspaceRepository.currentPath()
        ensureProjectLoaded(workspacePath)
        val project = getProjectState(workspacePath).value ?: emptyList()
        return project + global
    }

    /** Adds a rule by scope. PROJECT writes to the current project; with no current project it is ignored with a warning. */
    suspend fun add(scope: PermissionScope, rule: PermissionRule) {
        when (scope) {
            PermissionScope.GLOBAL -> editGlobal { if (rule !in it) it.add(rule) }
            PermissionScope.PROJECT -> {
                val workspacePath = workspaceRepository.currentPath()
                editProject(workspacePath) { if (rule !in it) it.add(rule) }
            }
        }
        FileLogger.i(TAG, "Remembered authorization rule [$scope]: ${rule.toolName} ${rule.pattern}")
    }

    suspend fun removeGlobalRule(rule: PermissionRule) = editGlobal { it.remove(rule) }

    suspend fun removeProjectRule(projectName: String, rule: PermissionRule) {
        val workspacePath = workspaceRepository.currentPath()
        editProject(workspacePath) { it.remove(rule) }
    }

    /** Promotes a project rule to global: deletes it from the project, adds it to global. */
    suspend fun promoteToGlobal(projectName: String, rule: PermissionRule) {
        val workspacePath = workspaceRepository.currentPath()
        editProject(workspacePath) { it.remove(rule) }
        editGlobal { if (rule !in it) it.add(rule) }
        FileLogger.i(TAG, "Promoted to global: ${rule.toolName} ${rule.pattern}")
    }

    // ── Internal writes ────────────────────────────────────────────────

    private suspend fun editGlobal(mutate: (MutableList<PermissionRule>) -> Unit) {
        ensureGlobalLoaded()
        mutex.withLock {
            val list = (globalState.value ?: emptyList()).toMutableList()
            mutate(list)
            withContext(Dispatchers.IO) { writeToFile(globalFile, list) }
            globalState.value = list
        }
    }

    private suspend fun editProject(workspacePath: String, mutate: (MutableList<PermissionRule>) -> Unit) {
        ensureProjectLoaded(workspacePath)
        mutex.withLock {
            val state = getProjectState(workspacePath)
            val list = (state.value ?: emptyList()).toMutableList()
            mutate(list)
            withContext(Dispatchers.IO) { writeToFile(projectFileForPath(workspacePath), list) }
            state.value = list
        }
    }
}
