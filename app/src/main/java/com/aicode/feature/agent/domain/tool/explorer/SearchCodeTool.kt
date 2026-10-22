package com.aicode.feature.agent.domain.tool.explorer

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.CommandEngine
import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.ParameterType
import com.aicode.feature.agent.domain.tool.ToolCapability
import com.aicode.feature.agent.domain.tool.ToolParameter
import com.aicode.feature.agent.domain.tool.ToolPermissionPolicy
import com.aicode.feature.agent.domain.tool.ToolResult
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.workspace.domain.PathHomeResolver
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

/**
 * ripgrep search tool: arguments are passed as-is to ripgrep inside the container; supports truncating output with `| head [-n N]`,
 * all other pipe commands are rejected (see [buildSearchCommand]); if the container is not ready, it reports an error.
 */
class SearchCodeTool @Inject constructor(
    private val commandEngine: CommandEngine,
    private val workspaceRepository: WorkspaceRepository,
    private val pathHomeResolver: PathHomeResolver
) : AgentTool() {

    private companion object {
        const val TAG = "SearchTool"
        const val SEARCH_TIMEOUT_MS = 30_000L
    }

    override val name = "search"
    override val description = "Search text content using ripgrep (rg). Example: args=\"-n \\\"fun main\\\" ~/workspace/app\". Supports appending `| head [-n N]` to truncate output."
    override val permissionPolicy = ToolPermissionPolicy.AUTO_APPROVE
    override val capabilities = setOf(ToolCapability.READ_WORKSPACE)

    override val parameters: Map<String, ToolParameter> = mapOf(
        "args" to ToolParameter(
            name = "args",
            type = ParameterType.STRING,
            description = "ripgrep arguments. Leaving it empty is invalid. Common: -i -F -e -g --hidden --. Supports appending `| head [-n N]` at the end to truncate output; other pipe commands (grep/sort/wc, etc.) are not supported.",
            required = true
        )
    )

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        return try {
            val rawArgs = args["args"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (rawArgs.isEmpty()) return ToolResult.Error("Missing required parameter: args", "MISSING_ARGS")

            val tokens = parseShellWords(rawArgs)
                ?: return ToolResult.Error("args contains an unclosed quote", "INVALID_ARGS")
            if (tokens.isEmpty()) return ToolResult.Error("Missing required parameter: args", "MISSING_ARGS")

            val command = buildSearchCommand(tokens, pathHomeResolver.home())
                ?: return ToolResult.Error("search only supports | head [-n N] to truncate output; other pipe commands are not supported", "INVALID_PIPE")

            val startedAt = System.currentTimeMillis()
            val result = commandEngine.runCommandSyncIfReady(
                command = command,
                projectPath = workspaceRepository.currentPath(),
                timeoutMs = SEARCH_TIMEOUT_MS
            ) ?: return ToolResult.Error("Container is not ready, cannot run rg", "CONTAINER_NOT_READY")

            // 127 = the shell cannot find the command; after merging stderr you can usually also see command not found -- a double safeguard
            if (isRgMissing(result.output) || result.exitCode == 127) {
                return ToolResult.Error("rg is not installed in the container", "RG_MISSING")
            }
            if (result.exitCode != null && result.exitCode > 1) {
                return ToolResult.Error(result.output.ifBlank { "rg execution failed" }, "RG_ERROR")
            }

            val lines = result.output.lineSequence().filter { it.isNotBlank() }.toList()
            ToolResult.Success(JsonObject(mapOf(
                "content" to JsonPrimitive(result.output),
                "matches" to JsonPrimitive(lines.size),
                "truncated" to JsonPrimitive(false),
                "elapsed_ms" to JsonPrimitive(System.currentTimeMillis() - startedAt),
                "backend" to JsonPrimitive("rg")
            )))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.e(TAG, "search error", e)
            ToolResult.Error(e.message ?: "Search failed", "SEARCH_ERROR")
        }
    }

    private fun isRgMissing(output: String): Boolean {
        return output.contains("command not found", ignoreCase = true) ||
            output.contains("rg: not found", ignoreCase = true)
    }
}
