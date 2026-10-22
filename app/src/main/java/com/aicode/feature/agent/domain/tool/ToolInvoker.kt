package com.aicode.feature.agent.domain.tool

import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.model.AgentImage
import com.aicode.feature.agent.presentation.AgentAttachment
import com.aicode.feature.agent.domain.workflow.AgentEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

/** The result of one tool execution: transport text (fed to the model) + an error flag + display attachments/images. */
data class ToolRunResult(
    val raw: String,
    val isError: Boolean,
    /** Only for display tools such as sendFile: the file-card metadata attached to the result, for UI rendering; it is not replayed into the model context. */
    val attachments: List<AgentAttachment> = emptyList(),
    val images: List<AgentImage> = emptyList()
)

/**
 * Tool execution component: the workflow's [com.aicode.feature.agent.domain.workflow.StatefulAgentWorkflow]
 * delegates execution of each tool in a batch to this component, which centrally handles thread scheduling and result post-processing.
 *
 * ## Why everything must switch to IO here (a hard-won fix)
 * The workflow's collector (AIAgentViewModel.executeAgentRequestStream) is `viewModelScope.launch`
 * with no dispatcher argument, i.e. `Dispatchers.Main.immediate`. The batch execution site `coroutineScope { async { ... } }`
 * inherits that single-threaded dispatcher: if IO is not switched centrally here,
 * 1. `async` is only a suspension point on a single thread, **so parallel batches actually run serially**;
 * 2. The tools' blocking IO directly occupies the main thread -- Bash's `runCommandSync` (under DEVICE_ROOT it is
 *    `su -c` fork + `Process.waitFor()`), list's per-item `fileAccess.exists/isDirectory`
 *    (in ROOT mode, one fork per item), search's `runCommandSyncIfReady`;
 * 3. The symptom is: as soon as the AI calls a few tools in parallel everything freezes, and a few more taps on the screen get the app ANR-killed (it crashes).
 *
 * After the central switch to IO: true parallelism on the IO thread pool (64 threads by default), with the main thread only dispatching events.
 * Tools that need main-thread cooperation (the browser WebView, the background terminal sessionManager) switch back themselves with
 * `withContext(Dispatchers.Main)`, unaffected by this component; for tools that already switch to IO internally
 * (readFile and the like) this is just a no-op switch.
 *
 * This component also centrally handles result post-processing (output capping/truncation, attachment extraction/stripping, exception wrapping),
 * so it can be unit-tested independently of the workflow.
 */
class ToolInvoker @Inject constructor(
    private val toolOutputStore: ToolOutputStore
) {
    /** Executes a non-streaming tool. Returns transport text that can go directly into the model context. */
    suspend fun runSync(
        tool: AgentTool?,
        toolCall: ToolCall,
        context: AgentContext
    ): ToolRunResult {
        val name = toolCall.name
        if (tool == null) {
            return ToolRunResult(ToolResult.Error("Tool $name does not exist", "TOOL_NOT_FOUND").toTransportString(), true)
        }
        return try {
            withContext(Dispatchers.IO) {
                val result = tool.executeWithContext(toolCall.arguments, context)
                val attachments = if (name == "sendFile" || name == "generateImage") extractAttachments(result) else emptyList()
                val images = if (result is ToolResult.Success) result.images else emptyList()
                val transportResult = if (attachments.isNotEmpty()) stripAttachments(result) else result
                val processed = toolOutputStore.process(name, toolCall.id, transportResult)
                ToolRunResult(processed.toTransportString(), processed is ToolResult.Error, attachments, images)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolRunResult(ToolResult.Error("Tool execution failed: ${e.message}", "TOOL_EXECUTION_FAILED").toTransportString(), true)
        }
    }

    /**
     * Executes a streaming tool: progress events are passed back via [onEvent] (accumulated tail capped at [LIVE_TAIL_CHARS] characters,
     * [PROGRESS_INTERVAL_MS] milliseconds of throttling), and the final result is returned at the end.
     * [onEvent] is the workflow's `send` -- a channelFlow's send can be called from any thread, so passing back from IO threads is safe.
     */
    suspend fun runStream(
        tool: StreamingAgentTool,
        toolCall: ToolCall,
        context: AgentContext,
        onEvent: suspend (AgentEvent) -> Unit
    ): ToolRunResult {
        val live = StringBuilder()
        var lastEmitMs = 0L
        var finalResult: ToolResult? = null
        return try {
            withContext(Dispatchers.IO) {
                tool.executeStream(toolCall.arguments, context).collect { ev ->
                    when (ev) {
                        is ToolStreamEvent.Progress -> {
                            live.append(ev.chunk).append('\n')
                            if (live.length > LIVE_TAIL_CHARS) {
                                live.delete(0, live.length - LIVE_TAIL_CHARS)
                            }
                            val now = System.currentTimeMillis()
                            if (now - lastEmitMs >= PROGRESS_INTERVAL_MS) {
                                lastEmitMs = now
                                onEvent(AgentEvent.ToolCallProgress(toolCall.id, toolCall.name, live.toString()))
                            }
                        }
                        is ToolStreamEvent.Completed -> finalResult = ev.result
                    }
                }
                val result = finalResult ?: ToolResult.Error("Streaming tool did not return a result", "MISSING_STREAM_RESULT")
                val processed = toolOutputStore.process(toolCall.name, toolCall.id, result)
                val images = if (result is ToolResult.Success) result.images else emptyList()
                ToolRunResult(processed.toTransportString(), processed is ToolResult.Error, images = images)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolRunResult(ToolResult.Error("Tool execution failed: ${e.message}", "TOOL_EXECUTION_FAILED").toTransportString(), true)
        }
    }

    /**
     * Extracts file-card metadata from the `files` array of a sendFile tool result (including the host local path, for the UI to open the file).
     * If any file is missing a key field, returns empty overall (consistent with sendFile's atomic semantics).
     */
    private fun extractAttachments(result: ToolResult): List<AgentAttachment> {
        val data = (result as? ToolResult.Success)?.data as? JsonObject ?: return emptyList()
        val files = data["files"] as? JsonArray ?: return emptyList()
        val attachments = files.mapNotNull { elem ->
            val obj = elem as? JsonObject ?: return@mapNotNull null
            val path = obj["path"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val localPath = obj["local_path"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: path.substringAfterLast('/')
            val mimeType = obj["mime_type"]?.jsonPrimitive?.contentOrNull ?: "application/octet-stream"
            AgentAttachment(
                fileName = name,
                containerPath = path,
                localPath = localPath,
                mimeType = mimeType,
                sizeBytes = obj["size_bytes"]?.jsonPrimitive?.longOrNull ?: 0L,
                isImage = obj["is_image"]?.jsonPrimitive?.booleanOrNull ?: mimeType.startsWith("image/")
            )
        }
        return if (attachments.size == files.size) attachments else emptyList()
    }

    /** Strips host local paths from the sendFile result sent back to the model (the model should only see container paths). */
    private fun stripAttachments(result: ToolResult): ToolResult {
        val success = result as? ToolResult.Success ?: return result
        val data = success.data as? JsonObject ?: return result
        val strippedFiles = (data["files"] as? JsonArray)?.map { elem ->
            val obj = elem as? JsonObject ?: return@map elem
            JsonObject(obj.toMutableMap().apply { remove("local_path") })
        } ?: return result
        val strippedData = data.toMutableMap().apply {
            this["files"] = JsonArray(strippedFiles)
            this["files_attached"] = JsonPrimitive(true)
        }
        return ToolResult.Success(JsonObject(strippedData))
    }

    private companion object {
        /** How many characters of accumulated output a streaming tool keeps when passing back to the UI (to stop very long output slowing recomposition). */
        const val LIVE_TAIL_CHARS = 4_000
        /** Minimum interval between streaming tool progress events (milliseconds). */
        const val PROGRESS_INTERVAL_MS = 250L
    }
}
