package com.aicode.feature.agent.domain.tool.shizuku

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.BoundedOutput
import com.aicode.feature.agent.domain.shizuku.ShizukuManager
import com.aicode.feature.agent.domain.shizuku.ShizukuState
import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.ParameterType
import com.aicode.feature.agent.domain.tool.PendingToolPermission
import com.aicode.feature.agent.domain.tool.ToolCapability
import com.aicode.feature.agent.domain.tool.ToolParameter
import com.aicode.feature.agent.domain.tool.ToolPermissionPolicy
import com.aicode.feature.agent.domain.tool.ToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import javax.inject.Inject

/**
 * A tool that executes commands through Shizuku with the adb shell (uid 2000) identity.
 *
 * Unlike [com.aicode.feature.agent.domain.tool.container.ExecuteCommandTool] (local container / remote SSH),
 * this tool acts directly on the Android system itself: it can run system commands such as `pm` / `am` / `cmd`, read and write `/sdcard`, etc.
 * The user must have Shizuku installed and have granted this app permission, otherwise it returns an error hint.
 *
 * The UserService AIDL call is synchronous and one-shot (no line-by-line streaming back), so streaming output is not implemented.
 */
class ShizukuTool @Inject constructor(
    private val shizukuManager: ShizukuManager
) : AgentTool() {
    private companion object {
        const val TAG = "ShizukuTool"

        const val DEFAULT_TIMEOUT_SECONDS = 120L
        const val MAX_TIMEOUT_SECONDS = 1_800L
    }

    override val name = "Shizuku"

    override val description =
        "Execute commands on the host Android system via Shizuku as adb shell (uid 2000) (equivalent to `adb shell`), for system operations such as `pm`/`am`/`cmd` and reading/writing /sdcard." +
            "The user must have Shizuku installed and authorized, and every call shows a confirmation dialog."

    override val permissionPolicy = ToolPermissionPolicy.ASK
    override val capabilities = setOf(ToolCapability.EXECUTE_COMMANDS)

    override val parameters: Map<String, ToolParameter> = mapOf(
        "command" to ToolParameter(
            name = "command",
            type = ParameterType.STRING,
            description = "Shell command to execute via adb shell",
            required = true
        ),
        "timeout" to ToolParameter(
            name = "timeout",
            type = ParameterType.INTEGER,
            description = "Maximum command execution time (seconds); on timeout it will be forcibly terminated. Default $DEFAULT_TIMEOUT_SECONDS seconds, maximum $MAX_TIMEOUT_SECONDS seconds.",
            required = false
        )
    )

    private fun resolveTimeoutMs(args: Map<String, JsonElement>): Long {
        val seconds = args["timeout"]?.jsonPrimitive?.longOrNull ?: DEFAULT_TIMEOUT_SECONDS
        return seconds.coerceIn(1L, MAX_TIMEOUT_SECONDS) * 1000L
    }

    override fun buildPermissionRequest(
        callId: String,
        args: Map<String, JsonElement>,
        argsPreview: String
    ): PendingToolPermission {
        val command = args["command"]?.jsonPrimitive?.contentOrNull ?: "Unknown command"
        val timeoutSeconds = resolveTimeoutMs(args) / 1000L
        return PendingToolPermission(
            id = callId,
            toolName = name,
            title = "Confirm Shizuku command execution",
            summary = command,
            details = "Will be executed on the Android system as adb shell.\nTimeout: ${timeoutSeconds} seconds",
            argsPreview = argsPreview
        )
    }

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        val command = args["command"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.Error("Missing required parameter: command")

        val state = shizukuManager.state.value
        if (state != ShizukuState.READY) {
            return ToolResult.Error("Shizuku is not ready: ${stateHint(state)}", code = "SHIZUKU_NOT_READY")
        }

        return try {
            val timeoutMs = resolveTimeoutMs(args)
            FileLogger.d(TAG, "Shizuku exec (timeout=${timeoutMs}ms): $command")
            val result = shizukuManager.runCommand(command, timeoutMs)
            val output = BoundedOutput().apply { append(result.output) }.build()
            FileLogger.v(TAG, "Shizuku exec done, output ${result.output.length} chars, exit code ${result.exitCode}")
            ToolResult.Success(JsonPrimitive(output))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.e(TAG, "Shizuku exec failed: $command", e)
            ToolResult.Error("Failed to execute Shizuku command: ${e.message}")
        }
    }

    private fun stateHint(state: ShizukuState): String = when (state) {
        ShizukuState.NOT_INSTALLED -> "Shizuku is not installed; please install and start the Shizuku service first"
        ShizukuState.NOT_RUNNING -> "The Shizuku service is not running; please start the service in the Shizuku app"
        ShizukuState.PERMISSION_DENIED -> "This app has not been granted Shizuku authorization yet; please grant it in settings"
        ShizukuState.READY -> ""
    }
}
