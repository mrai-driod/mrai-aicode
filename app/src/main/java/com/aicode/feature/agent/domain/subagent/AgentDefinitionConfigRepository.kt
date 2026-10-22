package com.aicode.feature.agent.domain.subagent

import com.aicode.core.util.FileLogger
import com.aicode.core.watch.FileChange
import com.aicode.core.watch.FileChangeHub
import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.ProjectAicodeRoot
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.shareIn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.putJsonArray

/**
 * Persistence for subagent enable/disable configuration, following the same conventions as the skills' `skills.json`, supporting two levels, global + project-level:
 * - Global: `~/.aicode/agents.json` in the current execution environment (kept across projects);
 * - Project-level: `workspacePath/.aicode/agents.json` (travels with the workspace, can be tracked by git).
 *
 * Format `{"disabled": ["name-a"]}`; it stores only the disabled list; the effective disabled set = the union of global + project.
 * Every read loads from disk, so external manual edits take effect immediately; subagent names in the list that no longer exist are naturally ignored when filtering.
 */
@Singleton
class AgentDefinitionConfigRepository @Inject constructor(
    private val containerInstaller: ContainerInstaller,
    private val projectAicodeRoot: ProjectAicodeRoot,
    private val fileChangeHub: FileChangeHub,
    private val fileAccess: FileAccessProvider
) {
    /** The local global config file, used only for host file-change watching. */
    private fun globalFile(): File = File(containerInstaller.aicodeDir, CONFIG_FILE)

    private fun projectFile(): File = File(projectAicodeRoot.current(), CONFIG_FILE)

    // -- External change watching: after the subagent directory or agents.json is added to, deleted from, or edited directly in the container / by hand, notify the UI to refresh within a few seconds --

    private val watchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Broadcasts once when the subagent directory or a config file is modified externally. Subscription-driven: only while there are subscribers (the settings page) does
     * [FileChangeHub] watch the subagent directory and the two agents.json files; with no subscribers there is zero overhead.
     */
    val changes: SharedFlow<Unit> = merge(
        fileChangeHub.watchAicode(AGENTS_DIR, recursive = true),
        fileChangeHub.watchAicode(),
        fileChangeHub.watchWorkspace("${FileChangeHub.CONTAINER_ROOT}/$AICODE_DIR/$AGENTS_DIR", recursive = true),
        fileChangeHub.watchWorkspace("${FileChangeHub.CONTAINER_ROOT}/$AICODE_DIR")
    ).mapNotNull { batch ->
        if (!batch.changes.any(::isAgentChange)) return@mapNotNull null
        FileLogger.i(TAG, "Detected a change to the subagent directory or config; refresh notified")
        Unit
    }.shareIn(watchScope, SharingStarted.WhileSubscribed(), replay = 0)

    /** A subagent-related change: either of the two agents.json files, or the global/project subagent directory itself or any file under it. */
    private fun isAgentChange(change: FileChange): Boolean {
        val path = change.hostPath
        if (path == globalFile().absolutePath) return true
        if (path == projectFile().absolutePath) return true
        val globalAgents = File(containerInstaller.aicodeDir, AGENTS_DIR).absolutePath
        if (path == globalAgents || path.startsWith("$globalAgents/")) return true
        val projectAgents = File(projectAicodeRoot.current(), AGENTS_DIR).absolutePath
        return path == projectAgents || path.startsWith("$projectAgents/")
    }

    /** The currently effective set of disabled subagent names (union of global + project, normalized to lowercase). */
    fun disabledNames(): Set<String> =
        (readGlobalDisabled() + readDisabled(projectFile())).map { it.lowercase() }.toSet()

    /** Enables/disables a subagent in the config of the given scope. */
    fun setDisabled(name: String, disabled: Boolean, scope: AgentDefinitionScope) {
        val names = (if (scope == AgentDefinitionScope.GLOBAL) readGlobalDisabled() else readDisabled(projectFile()))
            .toMutableSet()
        if (disabled) names.add(name) else names.removeAll { it.equals(name, ignoreCase = true) }
        if (scope == AgentDefinitionScope.GLOBAL) {
            fileAccess.writeFile(GLOBAL_CONFIG_PATH, serializeDisabled(names))
        } else {
            writeDisabled(projectFile(), names)
        }
    }

    private fun readGlobalDisabled(): Set<String> = runCatching {
        if (fileAccess.isFile(GLOBAL_CONFIG_PATH)) parseDisabled(fileAccess.readFile(GLOBAL_CONFIG_PATH))
        else emptySet()
    }.getOrElse {
        FileLogger.w(TAG, "Failed to read $CONFIG_FILE: ${it.message}")
        emptySet()
    }

    companion object {
        private const val TAG = "AgentDefinitionConfigRepository"
        private const val CONFIG_FILE = "agents.json"
        private const val GLOBAL_CONFIG_PATH = "~/.aicode/$CONFIG_FILE"
        private const val AICODE_DIR = ".aicode"
        private const val AGENTS_DIR = "agents"
        private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }
        private val PRETTY_JSON = Json { prettyPrint = true }

        internal fun parseDisabled(raw: String): Set<String> {
            val root = runCatching { JSON.parseToJsonElement(raw).jsonObject }.getOrElse {
                FileLogger.w(TAG, "Failed to parse subagent config JSON: ${it.message}")
                return emptySet()
            }
            return (root["disabled"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                ?.toSet()
                ?: emptySet()
        }

        internal fun serializeDisabled(names: Set<String>): String {
            val root = buildJsonObject {
                putJsonArray("disabled") { names.sorted().forEach { add(it) } }
            }
            return PRETTY_JSON.encodeToString(JsonObject.serializer(), root)
        }

        private fun readDisabled(file: File): Set<String> {
            if (!file.isFile) return emptySet()
            return runCatching { parseDisabled(file.readText()) }.getOrElse {
                FileLogger.w(TAG, "Failed to read ${file.name}: ${it.message}")
                emptySet()
            }
        }

        private fun writeDisabled(file: File, names: Set<String>) {
            file.parentFile?.mkdirs()
            val json = serializeDisabled(names)
            // Temp file + rename for an atomic write, so a crash mid-write does not corrupt the config
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(json)
            if (!tmp.renameTo(file)) file.writeText(json)
        }
    }
}
