package com.aicode.feature.agent.domain.tool.container

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
import com.aicode.feature.terminal.domain.TerminalSessionProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

/**
 * Extracts the real content of a JSON string argument.
 *
 * Note: do not use `toString().removeSurrounding("\"")`: [toString] returns the JSON serialized form (with outer quotes,
 * and inner quotes escaped as `\"`); stripping the outer quotes leaves literal `\"` inside, which turns `echo "*.vue"` into
 * `echo \"*.vue\"`, making the shell output the quotes literally. You must use [jsonPrimitive]/[contentOrNull] to get the decoded content.
 */
private fun JsonElement.asPlainString(): String? =
    (this as? JsonPrimitive)?.contentOrNull

/**
 * The unified background terminal session tool: one `action` parameter distinguishes the operations, merging the former
 * `run_background_command` / `send_terminal_input` / `read_terminal_output` tools into one.
 *
 * - `start`: starts a long-running command (such as `npm run dev` or a service process) in a persistent background terminal tab and returns the tab_id immediately,
 *   without blocking or waiting. The command process keeps running, and the AI can later continue with send/read using the returned id.
 * - `send`: sends one line of command/input to a background or interactive terminal by tab_id (presses Enter to execute by default), then briefly captures output.
 * - `key`: sends a common shortcut/control character by tab_id (such as Ctrl-C, Ctrl-D, or the arrow keys).
 * - `read`: reads all current output of that terminal by tab_id (including live logs of background commands); omitting tab_id lists all tabs.
 * - `close`: proactively closes and destroys a terminal tab by tab_id.
 *
 * Authorization: the start action is matched by shell command prefix (see [com.aicode.feature.agent.domain.permission.ToolPermissionPolicyEngine]),
 * the send action is matched by input content, and key/read/close are matched by the whole tool.
 */
class TerminalSessionTool @Inject constructor(
    private val sessionManager: TerminalSessionProvider
) : AgentTool(), StreamingAgentTool {
    private companion object {
        const val TAG = "TerminalSessionTool"

        /** The longest time to capture initial output after start launches (milliseconds): returns immediately if the command exits early, otherwise waits the full time. */
        const val START_CAPTURE_MS = 5_000L

        /** The interval for polling whether the command has exited (milliseconds). */
        const val START_POLL_INTERVAL_MS = 200L

        val SUPPORTED_KEYS = listOf(
            "ctrl+c",
            "ctrl+d",
            "ctrl+z",
            "ctrl+l",
            "ctrl+u",
            "ctrl+w",
            "esc",
            "tab",
            "enter",
            "up",
            "down",
            "left",
            "right"
        )
    }

    override val name = "terminal"
    override val description =
        "Manage persistent background terminal sessions (start commands, send input/keys, read output, close tabs). For tasks that finish on their own and whose result you need to wait for, use start + notify=true (the system notifies you when it ends; do not poll); for resident services use notify=false and read when needed. To interrupt a foreground process, use key=ctrl+c."
    override val permissionPolicy = ToolPermissionPolicy.ASK
    override val capabilities = setOf(ToolCapability.EXECUTE_COMMANDS)

    override fun effectiveCapabilities(args: Map<String, JsonElement>): Set<ToolCapability> {
        return when (actionOf(args)) {
            "read" -> setOf(ToolCapability.READ_WORKSPACE)
            else -> capabilities
        }
    }

    override val parameters: Map<String, ToolParameter> = mapOf(
        "action" to ToolParameter(
            name = "action",
            type = ParameterType.STRING,
            description = "Action type: start=start a background command; send=send input to a terminal; key=send a shortcut key; read=read terminal output; close=close a terminal tab",
            required = true,
            enum = listOf("start", "send", "key", "read", "close")
        ),
        "command" to ToolParameter(
            name = "command",
            type = ParameterType.STRING,
            description = "Required for start: the shell command to run in the background, e.g. 'npm run dev'",
            required = false
        ),
        "title" to ToolParameter(
            name = "title",
            type = ParameterType.STRING,
            description = "Optional for start: the tab display name; defaults to the tab id if omitted",
            required = false
        ),
        "notify" to ToolParameter(
            name = "notify",
            type = ParameterType.BOOLEAN,
            description = "Optional for start: whether the system notifies the AI when the command ends. true is for tasks that finish on their own, such as builds/tests (start only captures about 5 seconds of initial output; you are notified when it ends — do not poll); false (default) is for resident services such as dev servers, where the tab stays alive for reuse.",
            required = false
        ),
        "tab_id" to ToolParameter(
            name = "tab_id",
            type = ParameterType.STRING,
            description = "Used by send/key/read/close: the target terminal tab id, e.g. 'term-2'; omit for read to list all tabs",
            required = false
        ),
        "input" to ToolParameter(
            name = "input",
            type = ParameterType.STRING,
            description = "Required for send: the command or text to send",
            required = false
        ),
        "key" to ToolParameter(
            name = "key",
            type = ParameterType.STRING,
            description = "Required for key: the shortcut key/control character to send. Common: ctrl+c interrupts the foreground process, ctrl+d sends EOF, ctrl+z suspends, esc/tab/enter/arrow keys are for interactive programs",
            required = false,
            enum = SUPPORTED_KEYS
        ),
        "submit" to ToolParameter(
            name = "submit",
            type = ParameterType.BOOLEAN,
            description = "Optional for send: whether to append Enter at the end to execute the command; default true",
            required = false
        ),
        "elevate" to ToolParameter(
            name = "elevate",
            type = ParameterType.BOOLEAN,
            description = "Elevated retry: if a start/send command was rejected by the built-in safety guard (e.g. catastrophic deletion) and is genuinely necessary, set to true to retry; a dialog will ask the user for one-time authorization (cannot be remembered). Ineffective in PLAN mode.",
            required = false
        )
    )

    private fun actionOf(args: Map<String, JsonElement>): String =
        args["action"]?.asPlainString()?.trim()?.lowercase() ?: ""

    override fun buildPermissionRequest(
        callId: String,
        args: Map<String, JsonElement>,
        argsPreview: String
    ): PendingToolPermission = when (actionOf(args)) {
        "start" -> {
            val command = args["command"]?.asPlainString() ?: "Unknown command"
            val title = args["title"]?.asPlainString()
            PendingToolPermission(
                id = callId,
                toolName = name,
                title = "Confirm starting background command",
                summary = command,
                details = "Background tab: ${title?.takeIf { it.isNotBlank() } ?: "Auto-named"}\nThe command will keep running until the process exits or is stopped manually.",
                argsPreview = argsPreview
            )
        }
        "send" -> {
            val tabId = args["tab_id"]?.asPlainString() ?: "Unknown tab"
            val input = args["input"]?.asPlainString() ?: "Unknown input"
            val submit = args["submit"]?.asPlainString()?.toBooleanStrictOrNull() ?: true
            PendingToolPermission(
                id = callId,
                toolName = name,
                title = "Confirm sending terminal input",
                summary = input,
                details = "Target terminal: $tabId\nAuto-submit with Enter: ${if (submit) "Yes" else "No"}",
                argsPreview = argsPreview
            )
        }
        "key" -> {
            val tabId = args["tab_id"]?.asPlainString() ?: "Unknown tab"
            val key = args["key"]?.asPlainString() ?: "Unknown key"
            PendingToolPermission(
                id = callId,
                toolName = name,
                title = "Confirm sending terminal key",
                summary = key,
                details = "Target terminal: $tabId",
                argsPreview = argsPreview
            )
        }
        "close" -> {
            val tabId = args["tab_id"]?.asPlainString() ?: "Unknown tab"
            PendingToolPermission(
                id = callId,
                toolName = name,
                title = "Confirm closing terminal tab",
                summary = tabId,
                details = "This will close terminal $tabId and terminate any processes still running in it.",
                argsPreview = argsPreview
            )
        }
        else -> {
            val tabId = args["tab_id"]?.asPlainString()
            PendingToolPermission(
                id = callId,
                toolName = name,
                title = "Confirm reading terminal output",
                summary = tabId?.takeIf { it.isNotBlank() } ?: "List all tabs",
                details = if (tabId.isNullOrBlank()) "List all current terminal tabs and their states" else "Read the current output of terminal $tabId",
                argsPreview = argsPreview
            )
        }
    }

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult = execute(args, null)

    /** The non-streaming fallback path also carries the initiating session id, consistent with [executeStream] (so background command notifications can be delivered back to the source session). */
    override suspend fun executeWithContext(
        args: Map<String, JsonElement>,
        context: com.aicode.feature.agent.domain.model.AgentContext
    ): ToolResult = execute(args, context.sessionId)

    private suspend fun execute(args: Map<String, JsonElement>, sourceSessionId: String?): ToolResult =
        when (actionOf(args)) {
            "start" -> start(args, sourceSessionId)
            "send" -> send(args)
            "key" -> sendKey(args)
            "read" -> read(args)
            "close" -> close(args)
            else -> ToolResult.Error("Missing or invalid action parameter (must be start/send/key/read/close)")
        }

    /**
     * On the event-stream path, makes start/send push new output within the wait window to the UI in real time, like Bash.
     * Other actions keep non-streaming semantics and return the final result directly.
     */
    override fun executeStream(
        args: Map<String, JsonElement>,
        context: com.aicode.feature.agent.domain.model.AgentContext
    ): Flow<ToolStreamEvent> = flow {
        when (actionOf(args)) {
            "start" -> streamStart(args, context)
            "send" -> streamSend(args)
            else -> emit(ToolStreamEvent.Completed(execute(args)))
        }
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<ToolStreamEvent>.streamStart(
        args: Map<String, JsonElement>,
        context: com.aicode.feature.agent.domain.model.AgentContext
    ) {
        val command = args["command"]?.asPlainString()
        if (command == null) {
            emit(ToolStreamEvent.Completed(ToolResult.Error("Missing required parameter for start: command")))
            return
        }
        val title = args["title"]?.asPlainString()
        val notify = args["notify"]?.asPlainString()?.toBooleanStrictOrNull() ?: false
        val tabId = try {
            withContext(Dispatchers.Main) { sessionManager.startBackgroundCommand(command, title, notify, context.sessionId) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.e(TAG, "Failed to start background command: $command", e)
            emit(ToolStreamEvent.Completed(ToolResult.Error("Failed to start background command: ${e.message}")))
            return
        }

        FileLogger.i(TAG, "Background command started tab=$tabId (streaming capture): $command")
        captureOutputFor(tabId, initialOutput = "")
        val result = withContext(Dispatchers.Main) { capturedResult(tabId, actionLabel = "start") }
        emit(ToolStreamEvent.Completed(result))
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<ToolStreamEvent>.streamSend(args: Map<String, JsonElement>) {
        val tabId = args["tab_id"]?.asPlainString()
        if (tabId.isNullOrBlank()) {
            emit(ToolStreamEvent.Completed(ToolResult.Error("Missing required parameter for send: tab_id")))
            return
        }
        val input = args["input"]?.asPlainString()
        if (input == null) {
            emit(ToolStreamEvent.Completed(ToolResult.Error("Missing required parameter for send: input")))
            return
        }
        val submit = args["submit"]?.asPlainString()?.toBooleanStrictOrNull() ?: true

        val before = withContext(Dispatchers.Main) { sessionManager.getTabOutput(tabId) }
        if (before == null) {
            emit(ToolStreamEvent.Completed(ToolResult.Error("Terminal tab not found: $tabId")))
            return
        }

        val ok = withContext(Dispatchers.Main) { sessionManager.sendInput(tabId, input, appendNewline = submit) }
        if (!ok) {
            emit(ToolStreamEvent.Completed(ToolResult.Error("Terminal tab not found: $tabId")))
            return
        }
        FileLogger.i(TAG, "Sending input to $tabId (streaming capture): $input")

        captureOutputFor(tabId, initialOutput = before)
        val result = withContext(Dispatchers.Main) { capturedResult(tabId, actionLabel = "send") }
        emit(ToolStreamEvent.Completed(result))
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<ToolStreamEvent>.captureOutputFor(
        tabId: String,
        initialOutput: String
    ) {
        var elapsed = 0L
        var lastOutput = initialOutput
        while (elapsed < START_CAPTURE_MS) {
            delay(START_POLL_INTERVAL_MS)
            elapsed += START_POLL_INTERVAL_MS

            val output = withContext(Dispatchers.Main) { sessionManager.getTabOutput(tabId) }
                ?: break
            val delta = outputDelta(lastOutput, output)
            if (delta.isNotEmpty()) {
                emit(ToolStreamEvent.Progress(delta))
                lastOutput = output
            }
        }
    }

    /**
     * Starts a background command tab and captures the initial output for up to [START_CAPTURE_MS] milliseconds before returning.
     *
     * Unlike a plain start, this does not end by just returning the tab_id -- otherwise the AI would have to call read again to know whether the command failed immediately
     * (such as the command not existing or the port being in use). Here it polls after starting: it returns immediately if the command exits early (fail early, see it early); otherwise it waits the full
     * [START_CAPTURE_MS] to capture a stretch of initial output (a dev server's startup banner, the first error, and so on usually appear within these few seconds).
     * The returned object contains tab_id / whether it is still running / the captured output, and the AI judges from that whether to continue with send/read.
     *
     * TerminalSessionManager needs the main thread internally, so the whole block switches to Main.
     */
    private suspend fun start(args: Map<String, JsonElement>, sourceSessionId: String?): ToolResult = withContext(Dispatchers.Main) {
        val command = args["command"]?.asPlainString()
            ?: return@withContext ToolResult.Error("Missing required parameter for start: command")
        val title = args["title"]?.asPlainString()
        val notify = args["notify"]?.asPlainString()?.toBooleanStrictOrNull() ?: false
        try {
            val tabId = sessionManager.startBackgroundCommand(command, title, notify, sourceSessionId)
            FileLogger.i(TAG, "Background command started tab=$tabId: $command")

            // Poll to capture the initial output: end early if the command exits, otherwise wait at most START_CAPTURE_MS.
            var elapsed = 0L
            while (elapsed < START_CAPTURE_MS) {
                delay(START_POLL_INTERVAL_MS)
                elapsed += START_POLL_INTERVAL_MS
                val running = sessionManager.listTabs().firstOrNull { it.id == tabId }?.running ?: false
                if (!running) break
            }

            val running = sessionManager.listTabs().firstOrNull { it.id == tabId }?.running ?: false
            val output = sessionManager.getTabOutput(tabId) ?: ""
            FileLogger.v(TAG, "start capture tab=$tabId running=$running output ${output.length} chars")
            ToolResult.Success(
                JsonObject(
                    mapOf(
                        "tab_id" to JsonPrimitive(tabId),
                        "running" to JsonPrimitive(running),
                        "output" to JsonPrimitive(output)
                    )
                )
            )
        } catch (e: Exception) {
            FileLogger.e(TAG, "Failed to start background command: $command", e)
            ToolResult.Error("Failed to start background command: ${e.message}")
        }
    }

    /**
     * Sends one line of input to the specified terminal, pressing Enter to execute by default, and waits a short time to capture output.
     *
     * Consistent with start: returns tab_id / running / output, avoiding the AI having to manually read again after sending a command
     * to see the immediate result. An interactive shell has no reliable "command finished" signal, so this waits a fixed [START_CAPTURE_MS].
     */
    private suspend fun send(args: Map<String, JsonElement>): ToolResult = withContext(Dispatchers.Main) {
        val tabId = args["tab_id"]?.asPlainString()
            ?: return@withContext ToolResult.Error("Missing required parameter for send: tab_id")
        val input = args["input"]?.asPlainString()
            ?: return@withContext ToolResult.Error("Missing required parameter for send: input")
        val submit = args["submit"]?.asPlainString()?.toBooleanStrictOrNull() ?: true
        val ok = sessionManager.sendInput(tabId, input, appendNewline = submit)
        if (ok) {
            FileLogger.i(TAG, "Sending input to $tabId: $input")
            var elapsed = 0L
            while (elapsed < START_CAPTURE_MS) {
                delay(START_POLL_INTERVAL_MS)
                elapsed += START_POLL_INTERVAL_MS
            }
            capturedResult(tabId, actionLabel = "send")
        } else {
            val exists = sessionManager.listTabs().any { it.id == tabId }
            if (exists) {
                ToolResult.Error("Terminal $tabId has ended and cannot accept commands; use start to create a new terminal to run a new command", "TERMINAL_NOT_ACTIVE")
            } else {
                ToolResult.Error("Terminal tab not found: $tabId", "TERMINAL_NOT_FOUND")
            }
        }
    }

    /** Sends a predefined shortcut/control character to the specified terminal. TerminalSessionManager needs the main thread. */
    private suspend fun sendKey(args: Map<String, JsonElement>): ToolResult = withContext(Dispatchers.Main) {
        val tabId = args["tab_id"]?.asPlainString()
            ?: return@withContext ToolResult.Error("Missing required parameter for key: tab_id")
        val key = args["key"]?.asPlainString()
            ?: return@withContext ToolResult.Error("Missing required parameter for key: key")
        val normalized = normalizeKey(key)
        val ok = when (normalized) {
            "ctrl+c" -> sessionManager.writeBytesToTab(tabId, 0x03)
            "ctrl+d" -> sessionManager.writeBytesToTab(tabId, 0x04)
            "ctrl+z" -> sessionManager.writeBytesToTab(tabId, 0x1A)
            "ctrl+l" -> sessionManager.writeBytesToTab(tabId, 0x0C)
            "ctrl+u" -> sessionManager.writeBytesToTab(tabId, 0x15)
            "ctrl+w" -> sessionManager.writeBytesToTab(tabId, 0x17)
            "esc", "escape" -> sessionManager.writeBytesToTab(tabId, 0x1B)
            "tab" -> sessionManager.writeBytesToTab(tabId, 0x09)
            "enter", "return" -> sessionManager.writeBytesToTab(tabId, 0x0D)
            "up" -> sessionManager.writeToTab(tabId, "\u001B[A")
            "down" -> sessionManager.writeToTab(tabId, "\u001B[B")
            "right" -> sessionManager.writeToTab(tabId, "\u001B[C")
            "left" -> sessionManager.writeToTab(tabId, "\u001B[D")
            else -> return@withContext ToolResult.Error("Unsupported key: $key. Supported keys: ${SUPPORTED_KEYS.joinToString(", ")}")
        }
        if (ok) {
            FileLogger.i(TAG, "Sending shortcut key to $tabId: $normalized")
            ToolResult.Success(JsonPrimitive("Sent key $normalized to terminal tab $tabId. Use terminal(action=\"read\", tab_id=\"$tabId\") to view the result."))
        } else {
            val exists = sessionManager.listTabs().any { it.id == tabId }
            if (exists) {
                ToolResult.Error("Terminal $tabId has ended and cannot accept keys", "TERMINAL_NOT_ACTIVE")
            } else {
                ToolResult.Error("Terminal tab not found: $tabId", "TERMINAL_NOT_FOUND")
            }
        }
    }

    /** Reads terminal output; omitting tab_id lists all tabs. TerminalSessionManager needs the main thread. */
    private suspend fun read(args: Map<String, JsonElement>): ToolResult = withContext(Dispatchers.Main) {
        val tabId = args["tab_id"]?.asPlainString()
        if (tabId.isNullOrBlank()) {
            val list = sessionManager.listTabs()
            if (list.isEmpty()) return@withContext ToolResult.Success(JsonPrimitive("There are currently no terminal tabs."))
            val text = list.joinToString("\n") {
                val state = if (it.running) "running" else "ended"
                val kind = if (it.isBackground) "background" else "interactive"
                "${it.id}  [$kind/$state]  ${it.title}" + (it.command?.let { c -> "  ($c)" } ?: "")
            }
            return@withContext ToolResult.Success(JsonPrimitive(text))
        }
        val output = sessionManager.getTabOutput(tabId)
            ?: return@withContext ToolResult.Error("Terminal tab not found: $tabId")
        FileLogger.v(TAG, "Read $tabId output, ${output.length} chars")
        ToolResult.Success(JsonPrimitive(output))
    }

    /** Closes the specified terminal tab. TerminalSessionManager needs the main thread. */
    private suspend fun close(args: Map<String, JsonElement>): ToolResult = withContext(Dispatchers.Main) {
        val tabId = args["tab_id"]?.asPlainString()
            ?: return@withContext ToolResult.Error("Missing required parameter for close: tab_id")
        val ok = sessionManager.closeTab(tabId)
        if (ok) {
            FileLogger.i(TAG, "Closing terminal tab: $tabId")
            ToolResult.Success(JsonPrimitive("Closed terminal tab $tabId."))
        } else {
            ToolResult.Error("Terminal tab not found: $tabId")
        }
    }

    private fun normalizeKey(key: String): String =
        key.trim().lowercase().replace(" ", "").replace("_", "+").replace("-", "+")

    private fun capturedResult(tabId: String, actionLabel: String): ToolResult {
        val tab = sessionManager.listTabs().firstOrNull { it.id == tabId }
            ?: return ToolResult.Error("Terminal tab not found: $tabId")
        val rawOutput = sessionManager.getTabOutput(tabId) ?: ""
        FileLogger.v(
            TAG,
            "$actionLabel capture tab=$tabId running=${tab.running} output ${rawOutput.length} chars"
        )
        return ToolResult.Success(
            JsonObject(
                mapOf(
                    "tab_id" to JsonPrimitive(tabId),
                    "running" to JsonPrimitive(tab.running),
                    "output" to JsonPrimitive(rawOutput)
                )
            )
        )
    }

    private fun outputDelta(previous: String, current: String): String {
        if (current == previous) return ""
        if (current.startsWith(previous)) return current.substring(previous.length)

        val maxOverlap = minOf(previous.length, current.length)
        for (length in maxOverlap downTo 1) {
            if (previous.endsWith(current.take(length))) {
                return current.substring(length)
            }
        }
        return current
    }
}
