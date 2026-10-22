package com.aicode.feature.agent.domain.tool.container

import com.aicode.feature.agent.domain.container.BoundedOutput
import com.aicode.feature.agent.domain.container.CommandEngine
import com.aicode.feature.agent.domain.container.CommandEvent
import com.aicode.feature.agent.domain.container.DelegatingCommandEngine
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.ParameterType
import com.aicode.feature.agent.domain.tool.PendingToolPermission
import com.aicode.feature.agent.domain.tool.StreamingAgentTool
import com.aicode.feature.agent.domain.tool.ToolParameter
import com.aicode.feature.agent.domain.tool.ToolCapability
import com.aicode.feature.agent.domain.tool.ToolPermissionPolicy
import com.aicode.feature.agent.domain.tool.ToolResult
import com.aicode.feature.agent.domain.tool.ToolStreamEvent
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import javax.inject.Inject

/**
 * Tool that allows the AI agent to execute commands inside the Linux container.
 *
 * Commands execute in the currently selected workspace directory, so the AI's shell operations (npm install, git, etc.)
 * act on the same directory as the file tools.
 *
 * Also implements [StreamingAgentTool]: it prefers line-by-line streaming output, so command execution can be watched live in the chat;
 * [execute] is kept as the non-streaming fallback, and the final aggregated result is the same in both (what is fed back to the model does not change).
 */
class ExecuteCommandTool @Inject constructor(
    private val delegatingEngine: DelegatingCommandEngine,
    private val workspaceRepository: WorkspaceRepository
) : AgentTool(), StreamingAgentTool {
    private companion object {
        const val TAG = "ExecuteCommandTool"

        /** Default timeout (seconds), aligned with [LinuxContainerEngine.DEFAULT_TIMEOUT_MS]. */
        const val DEFAULT_TIMEOUT_SECONDS = 120L

        /** Timeout upper limit (seconds), aligned with [LinuxContainerEngine.MAX_TIMEOUT_MS]. */
        const val MAX_TIMEOUT_SECONDS = 1_800L
    }

    override val name = "Bash"
    override val description = "Run a one-off shell command and return its output synchronously. By default it runs in the execution environment set by the user (currently the preferred environment); the env parameter can explicitly select the execution environment: use \"device\" to operate the real device system (/storage files, device commands such as dumpsys/pm/am), use \"container\" when the full Linux toolchain inside the container is needed (build environments such as git/npm/python/gcc/make), and the default \"auto\" follows the user's preference. For long-running or resident tasks (installing dependencies, starting services, etc.), use `terminal` instead; do not background with `&`."
    override val permissionPolicy = ToolPermissionPolicy.ASK
    override val capabilities = setOf(ToolCapability.EXECUTE_COMMANDS)

    override val parameters: Map<String, ToolParameter> = mapOf(
        "command" to ToolParameter(
            name = "command",
            type = ParameterType.STRING,
            description = "The shell command to execute",
            required = true
        ),
        "timeout" to ToolParameter(
            name = "timeout",
            type = ParameterType.INTEGER,
            description = "Maximum command execution time (seconds); the command is forcibly terminated on timeout. Default $DEFAULT_TIMEOUT_SECONDS seconds, maximum $MAX_TIMEOUT_SECONDS seconds. Increase it for long-running commands (e.g. installing dependencies).",
            required = false
        ),
        "elevate" to ToolParameter(
            name = "elevate",
            type = ParameterType.BOOLEAN,
            description = "Elevated retry: if the command was rejected by the built-in safety guard (e.g. catastrophic deletion) and is genuinely necessary, set to true to retry; a dialog will ask the user for one-time authorization (cannot be remembered). Ineffective in PLAN mode.",
            required = false
        ),
        "env" to ToolParameter(
            name = "env",
            type = ParameterType.STRING,
            description = "Execution environment selection. \"auto\" (default) follows the user's preferred execution environment; \"device\" runs directly on the real device (suitable for host operations such as /storage files, system commands dumpsys/pm/am, device status queries); \"container\" runs inside the local Linux container (suitable when the full toolchain installed in the container is needed: git/npm/python/gcc/make and other build and development environments). Choose appropriately: follow auto in day-to-day use, and specify explicitly only when another environment is clearly needed.",
            required = false
        )
    )

    /** Parses the env parameter into an execution environment; a missing or invalid value falls back to AUTO (following the user's preference). */
    private fun resolveEnv(args: Map<String, JsonElement>): DelegatingCommandEngine.TargetEnv {
        val raw = args["env"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase() ?: return DelegatingCommandEngine.TargetEnv.AUTO
        return when (raw) {
            "container", "linux", "proot" -> DelegatingCommandEngine.TargetEnv.CONTAINER
            "device", "host", "root" -> DelegatingCommandEngine.TargetEnv.DEVICE
            "remote", "ssh" -> DelegatingCommandEngine.TargetEnv.REMOTE
            else -> DelegatingCommandEngine.TargetEnv.AUTO
        }
    }

    /** Parses the timeout parameter (seconds) and clamps it to the valid range, returning milliseconds; uses the default when missing. */
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
            title = "Confirm executing command",
            summary = command,
            details = "Will run in the current execution environment.\nTimeout: ${timeoutSeconds} seconds",
            argsPreview = argsPreview
        )
    }

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        val command = args["command"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.Error("Missing required parameter: command")

        return try {
            val env = resolveEnv(args)
            // All environments pass the host absolute path as the workspace: the container engine internally uses -b to bind it as /root/workspace inside the container.
            // Do not call toContainerPath() here first -- it returns the literal ~/workspace, which proot cannot resolve on the host,
            // causing the bind to fail and /root/workspace inside the container to become an empty placeholder (the resident terminal uses the host absolute path, so it works normally).
            val workdir = workspaceRepository.currentPath()
            val engine = delegatingEngine.engineFor(env)
            // When an environment is explicitly selected, do a readiness check first: if the container is not installed/initialized, give the AI a clear reason instead of letting the command fail inexplicably.
            if (env != DelegatingCommandEngine.TargetEnv.AUTO) {
                engine.notReadyHint()?.let { hint ->
                    return ToolResult.Error("Execution environment not ready (${env.name}): $hint")
                }
            }
            val timeoutMs = resolveTimeoutMs(args)
            FileLogger.d(TAG, "execute_command (env=${env.name}, timeout=${timeoutMs}ms): $command")
            val output = engine.runCommandSync(command, workdir, timeoutMs)
            FileLogger.v(TAG, "execute_command done, output ${output.length} chars")
            ToolResult.Success(JsonPrimitive(output))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.e(TAG, "execute_command failed: $command", e)
            ToolResult.Error("Failed to execute command: ${e.message}")
        }
    }

    /**
     * Streaming execution: emits [ToolStreamEvent.Progress] line by line, and emits [ToolStreamEvent.Completed] when the command ends;
     * its final result is equivalent to [execute] (also capped by [BoundedOutput]: oversized output keeps only the beginning + end),
     * guaranteeing that what is fed back to the model is consistent and will not blow up the context.
     */
    override fun executeStream(
        args: Map<String, JsonElement>,
        context: com.aicode.feature.agent.domain.model.AgentContext
    ): Flow<ToolStreamEvent> = flow {
        val command = args["command"]?.jsonPrimitive?.contentOrNull
        if (command == null) {
            emit(ToolStreamEvent.Completed(ToolResult.Error("Missing required parameter: command")))
            return@flow
        }

        // Capped accumulation: the final result fed back to the model keeps only the beginning + end, avoiding oversized output blowing up the context.
        val accumulated = BoundedOutput()
        try {
            val env = resolveEnv(args)
            // Same as execute: all environments pass the host absolute path; the container engine internally maps it with -b to /root/workspace.
            val workdir = workspaceRepository.currentPath()
            val engine = delegatingEngine.engineFor(env)
            if (env != DelegatingCommandEngine.TargetEnv.AUTO) {
                engine.notReadyHint()?.let { hint ->
                    emit(ToolStreamEvent.Completed(ToolResult.Error("Execution environment not ready (${env.name}): $hint")))
                    return@flow
                }
            }
            val timeoutMs = resolveTimeoutMs(args)
            FileLogger.d(TAG, "execute_command (streaming, env=${env.name}, timeout=${timeoutMs}ms): $command")
            engine.runCommandStream(command, workdir, timeoutMs).collect { event ->
                when (event) {
                    is CommandEvent.Line -> {
                        accumulated.append(event.text)
                        accumulated.append("\n")
                        emit(ToolStreamEvent.Progress(event.text))
                    }
                    is CommandEvent.Exit -> { /* Aggregation happens centrally once the stream completes */ }
                }
            }
            FileLogger.v(TAG, "execute_command (streaming) done, output ${accumulated.totalChars} chars")
            emit(ToolStreamEvent.Completed(ToolResult.Success(JsonPrimitive(accumulated.build()))))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Fallback: when the underlying flow terminates with an error, the Progress already emitted line by line to the user should still be kept as the final result,
            // rather than being overwritten by the empty Error thrown here (otherwise the model only sees "execution failed" and all the output shown before is lost).
            FileLogger.e(TAG, "execute_command (streaming) error (keeping the ${accumulated.totalChars} chars of output so far): $command", e)
            val saved = accumulated.build()
            val result = if (saved.isNotEmpty()) {
                ToolResult.Success(JsonPrimitive(saved))
            } else {
                ToolResult.Error("Failed to execute command: ${e.message}")
            }
            emit(ToolStreamEvent.Completed(result))
        }
    }
}
