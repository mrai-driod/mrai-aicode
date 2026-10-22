package com.aicode.feature.agent.domain.skill

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
 * Persistence for skill enable/disable configuration, supporting two levels, global + project-level:
 * - Global: `~/.aicode/skills.json` in the current execution environment (kept across projects);
 * - Project-level: `workspacePath/.aicode/skills.json` (travels with the workspace, can be tracked by git).
 *
 * Format: `{"disabled": ["skill-a", "skill-b"]}`; it stores only the single fact of the "disabled list";
 * the effective disabled set = the union of global + project. Every read loads from disk, so external manual edits take effect immediately;
 * skill names in the list that no longer exist are naturally ignored when filtering, so no cleanup is needed.
 */
@Singleton
class SkillConfigRepository @Inject constructor(
    private val containerInstaller: ContainerInstaller,
    private val projectAicodeRoot: ProjectAicodeRoot,
    private val fileChangeHub: FileChangeHub,
    private val fileAccess: FileAccessProvider
) {
    /** The local global config file, used only for host file-change watching. */
    private fun globalFile(): File = File(containerInstaller.aicodeDir, CONFIG_FILE)

    /** The project-level config file of the current workspace: `workspacePath/.aicode/skills.json`. */
    private fun projectFile(): File = File(projectAicodeRoot.current(), CONFIG_FILE)

    /** The currently effective set of disabled skill names (union of global + project, normalized to lowercase). */
    fun disabledNames(): Set<String> {
        val global = readGlobalDisabled()
        val project = readDisabled(projectFile())
        return (global + project).map { it.lowercase() }.toSet()
    }

    /** Enables/disables a skill in the config of the given scope. */
    fun setDisabled(name: String, disabled: Boolean, scope: SkillScope) {
        val names = (if (scope == SkillScope.GLOBAL) readGlobalDisabled() else readDisabled(projectFile()))
            .toMutableSet()
        if (disabled) names.add(name) else names.remove(name)
        if (scope == SkillScope.GLOBAL) {
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

    // -- External change watching: after the skill directory or skills.json is added to, deleted from, or edited directly in the container / by hand, notify the UI to refresh within a few seconds --

    private val watchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Broadcasts once when the skill directory or a config file is modified externally. Subscription-driven: only while there are subscribers (the settings page) does
     * [FileChangeHub] watch the skill directory and the two skills.json files; with no subscribers there is zero overhead.
     */
    val changes: SharedFlow<Unit> = merge(
        fileChangeHub.watchAicode(SKILLS_DIR, recursive = true),
        fileChangeHub.watchAicode(),
        fileChangeHub.watchWorkspace("${FileChangeHub.CONTAINER_ROOT}/$AICODE_DIR/$SKILLS_DIR", recursive = true),
        fileChangeHub.watchWorkspace("${FileChangeHub.CONTAINER_ROOT}/$AICODE_DIR")
    ).mapNotNull { batch ->
        if (!batch.changes.any(::isSkillChange)) return@mapNotNull null
        FileLogger.i(TAG, "Detected a change to the skill directory or config; refresh notified")
        Unit
    }.shareIn(watchScope, SharingStarted.WhileSubscribed(), replay = 0)

    /** A skill-related change: either of the two skills.json files, or the global/project skill directory itself or any file under it. */
    private fun isSkillChange(change: FileChange): Boolean {
        val path = change.hostPath
        if (path == globalFile().absolutePath) return true
        if (path == projectFile().absolutePath) return true
        val globalSkills = File(containerInstaller.aicodeDir, SKILLS_DIR).absolutePath
        if (path == globalSkills || path.startsWith("$globalSkills/")) return true
        val projectSkills = File(projectAicodeRoot.current(), SKILLS_DIR).absolutePath
        return path == projectSkills || path.startsWith("$projectSkills/")
    }

    companion object {
        private const val TAG = "SkillConfigRepository"
        private const val CONFIG_FILE = "skills.json"
        private const val GLOBAL_CONFIG_PATH = "~/.aicode/$CONFIG_FILE"
        private const val AICODE_DIR = ".aicode"
        private const val SKILLS_DIR = "skills"
        private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }
        private val PRETTY_JSON = Json { prettyPrint = true }

        fun parseDisabled(raw: String): Set<String> {
            val root = runCatching { JSON.parseToJsonElement(raw).jsonObject }.getOrElse {
                FileLogger.w(TAG, "Failed to parse skill config JSON: ${it.message}")
                return emptySet()
            }
            return (root["disabled"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                ?.toSet()
                ?: emptySet()
        }

        fun serializeDisabled(names: Set<String>): String {
            val root = buildJsonObject {
                putJsonArray("disabled") { names.sorted().forEach { add(it) } }
            }
            return PRETTY_JSON.encodeToString(JsonObject.serializer(), root)
        }

        fun readDisabled(file: File): Set<String> {
            if (!file.isFile) return emptySet()
            return runCatching { parseDisabled(file.readText()) }.getOrElse {
                FileLogger.w(TAG, "Failed to read ${file.name}: ${it.message}")
                emptySet()
            }
        }

        fun writeDisabled(file: File, names: Set<String>) {
            file.parentFile?.mkdirs()
            val json = serializeDisabled(names)
            // Temp file + rename for an atomic write, so a crash mid-write does not corrupt the config
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(json)
            if (!tmp.renameTo(file)) {
                // If rename fails (rare), fall back to writing directly so the config is not lost
                file.writeText(json)
            }
        }
    }
}
