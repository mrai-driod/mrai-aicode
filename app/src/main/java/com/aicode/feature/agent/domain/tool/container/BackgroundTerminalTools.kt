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
 * 取出 JSON 字符串参数的「真实内容」。
 *
 * 注意：不能用 `toString().removeSurrounding("\"")`：[toString] 返回 JSON 序列化形式（外层带引号、
 * 内部引号被转义成 `\"`），剥掉外层引号后内部仍是字面 `\"`，会把 `echo "*.vue"` 变成
 * `echo \"*.vue\"` 导致 shell 把引号当字面输出。必须用 [jsonPrimitive]/[contentOrNull] 取解码后内容。
 */
private fun JsonElement.asPlainString(): String? =
    (this as? JsonPrimitive)?.contentOrNull

/**
 * 统一的后台终端会话工具：用一个 `action` 参数区分三种操作，把原先的
 * `run_background_command` / `send_terminal_input` / `read_terminal_output` 三个工具合一。
 *
 * - `start`：把长驻命令（如 `npm run dev`、服务进程）开在一个常驻后台终端标签里，立即返回 tab_id，
 *   不阻塞等待。命令进程持续运行，AI 之后凭返回的 id 用 send/read 继续。
 * - `send`：按 tab_id 向某个后台或交互终端发送一行命令/输入（默认自动回车执行），随后短暂捕获输出。
 * - `key`：按 tab_id 发送常见快捷键/控制字符（如 Ctrl-C、Ctrl-D、方向键）。
 * - `read`：按 tab_id 读取该终端当前的全部输出（含后台命令实时日志）；省略 tab_id 则列出所有标签。
 * - `close`：按 tab_id 主动关闭并销毁某个终端标签。
 *
 * 授权：start 动作按 shell 命令前缀匹配（见 [com.aicode.feature.agent.domain.permission.ToolPermissionPolicyEngine]），
 * send 动作按输入内容匹配，key/read/close 按整工具匹配。
 */
class TerminalSessionTool @Inject constructor(
    private val sessionManager: TerminalSessionProvider
) : AgentTool(), StreamingAgentTool {
    private companion object {
        const val TAG = "TerminalSessionTool"

        /** start 启动后捕获初始输出的最长时间（毫秒）：命令若提前退出则立即返回，否则最多等满。 */
        const val START_CAPTURE_MS = 5_000L

        /** 轮询命令是否退出的间隔（毫秒）。 */
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

    /** 非流式兜底路径也带上发起会话 id，与 [executeStream] 保持一致（后台命令通知能回投到源会话）。 */
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
     * 事件流路径下，让 start/send 像 Bash 一样把等待窗口内的新输出实时推给 UI。
     * 其它动作保持非流式语义，直接返回最终结果。
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
            FileLogger.e(TAG, "启动后台命令失败: $command", e)
            emit(ToolStreamEvent.Completed(ToolResult.Error("Failed to start background command: ${e.message}")))
            return
        }

        FileLogger.i(TAG, "后台命令已启动 tab=$tabId(流式捕获): $command")
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
        FileLogger.i(TAG, "向 $tabId 发送输入(流式捕获): $input")

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
     * 启动后台命令标签，并捕获最多 [START_CAPTURE_MS] 毫秒内的初始输出后返回。
     *
     * 不像普通 start 那样只返回 tab_id 就结束——那样 AI 还得再调一次 read 才知道命令有没有立刻报错
     * （如命令不存在、端口被占）。这里启动后轮询：命令提前退出则立即返回（早失败早看见），否则等满
     * [START_CAPTURE_MS] 抓一段初始输出（dev server 的启动横幅、首次报错等通常都在这几秒内）。
     * 返回对象含 tab_id / 是否仍在运行 / 捕获到的输出，AI 据此判断要不要继续 send/read。
     *
     * TerminalSessionManager 内部需主线程，故整段切到 Main。
     */
    private suspend fun start(args: Map<String, JsonElement>, sourceSessionId: String?): ToolResult = withContext(Dispatchers.Main) {
        val command = args["command"]?.asPlainString()
            ?: return@withContext ToolResult.Error("Missing required parameter for start: command")
        val title = args["title"]?.asPlainString()
        val notify = args["notify"]?.asPlainString()?.toBooleanStrictOrNull() ?: false
        try {
            val tabId = sessionManager.startBackgroundCommand(command, title, notify, sourceSessionId)
            FileLogger.i(TAG, "后台命令已启动 tab=$tabId: $command")

            // 轮询捕获初始输出：命令退出则提前结束，否则最多等满 START_CAPTURE_MS。
            var elapsed = 0L
            while (elapsed < START_CAPTURE_MS) {
                delay(START_POLL_INTERVAL_MS)
                elapsed += START_POLL_INTERVAL_MS
                val running = sessionManager.listTabs().firstOrNull { it.id == tabId }?.running ?: false
                if (!running) break
            }

            val running = sessionManager.listTabs().firstOrNull { it.id == tabId }?.running ?: false
            val output = sessionManager.getTabOutput(tabId) ?: ""
            FileLogger.v(TAG, "start 捕获 tab=$tabId running=$running 输出 ${output.length} 字符")
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
            FileLogger.e(TAG, "启动后台命令失败: $command", e)
            ToolResult.Error("Failed to start background command: ${e.message}")
        }
    }

    /**
     * 向指定终端发送一行输入，默认回车执行，并等待一小段时间捕获输出。
     *
     * 与 start 保持一致：返回 tab_id / running / output，避免 AI 发送命令后必须再手动 read 一次
     * 才能看到即时结果。交互 shell 没有可靠的“命令结束”信号，因此这里固定等待 [START_CAPTURE_MS]。
     */
    private suspend fun send(args: Map<String, JsonElement>): ToolResult = withContext(Dispatchers.Main) {
        val tabId = args["tab_id"]?.asPlainString()
            ?: return@withContext ToolResult.Error("Missing required parameter for send: tab_id")
        val input = args["input"]?.asPlainString()
            ?: return@withContext ToolResult.Error("Missing required parameter for send: input")
        val submit = args["submit"]?.asPlainString()?.toBooleanStrictOrNull() ?: true
        val ok = sessionManager.sendInput(tabId, input, appendNewline = submit)
        if (ok) {
            FileLogger.i(TAG, "向 $tabId 发送输入: $input")
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

    /** 向指定终端发送预定义快捷键/控制字符。TerminalSessionManager 需主线程。 */
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
            FileLogger.i(TAG, "向 $tabId 发送快捷键: $normalized")
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

    /** 读取终端输出；省略 tab_id 则列出所有标签。TerminalSessionManager 需主线程。 */
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
        FileLogger.v(TAG, "读取 $tabId 输出 ${output.length} 字符")
        ToolResult.Success(JsonPrimitive(output))
    }

    /** 关闭指定终端标签。TerminalSessionManager 需主线程。 */
    private suspend fun close(args: Map<String, JsonElement>): ToolResult = withContext(Dispatchers.Main) {
        val tabId = args["tab_id"]?.asPlainString()
            ?: return@withContext ToolResult.Error("Missing required parameter for close: tab_id")
        val ok = sessionManager.closeTab(tabId)
        if (ok) {
            FileLogger.i(TAG, "关闭终端标签: $tabId")
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
            "$actionLabel 捕获 tab=$tabId running=${tab.running} 输出 ${rawOutput.length} 字符"
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
