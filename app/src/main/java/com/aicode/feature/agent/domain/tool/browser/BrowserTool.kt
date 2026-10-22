package com.aicode.feature.agent.domain.tool.browser

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.ParameterType
import com.aicode.feature.agent.domain.tool.ToolCapability
import com.aicode.feature.agent.domain.tool.ToolParameter
import com.aicode.feature.agent.domain.tool.ToolResult
import com.aicode.feature.workspace.domain.FileAccessProvider
import android.util.Base64
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

class BrowserTool @Inject constructor(
    private val browserManager: BrowserManager,
    private val fileAccess: FileAccessProvider
) : AgentTool() {

    private companion object {
        const val TAG = "BrowserTool"
        const val DEFAULT_WAIT_TIMEOUT_MS = 10_000L
        const val DEFAULT_BACKBONE_DEPTH = 15
        const val DEFAULT_SCREENSHOT_DIR = "~/workspace/.aicode/browser-screenshots"
        val READ_ONLY_ACTIONS = setOf("getText", "getHtml", "getBackbone", "screenshot", "console", "wait", "listTabs")
    }

    override val name = "browser"
    override val description = "Control the built-in browser to perform automated operations, with multi-tab support and background running. All operations accept an optional tabId (defaults to the currently active tab)." +
        "selector supports ref= / text= / text*= / role=button[name=xxx] / xpath= / CSS; getBackbone returns the accessibility tree (role/name/ref), and a ref can be used directly as a selector." +
        "To view the page's visual content, explicitly call action=\"screenshot\"."
    override val capabilities = setOf(ToolCapability.NETWORK_READ, ToolCapability.NETWORK_WRITE)

    private val actionEnum = listOf(
        "navigate", "evaluate", "click", "fill", "select", "hover", "press",
        "getText", "getHtml", "getBackbone", "screenshot", "console",
        "wait", "scroll", "dialog", "back", "forward", "reload",
        "newTab", "closeTab", "selectTab", "listTabs"
    )

    private val actionSchema: Map<String, Any> = mapOf(
        "type" to "string",
        "enum" to actionEnum,
        "description" to "Operation type. navigate=navigate to a URL (supports http(s)/file:// and container paths; non-URL input is treated as search keywords); evaluate=execute JS (supports Promise); click/fill/hover/press=interaction (React-compatible); select=dropdown selection; getText/getHtml=extract content; getBackbone=accessibility tree (role/name/ref); screenshot=take a screenshot; console=console logs; wait=wait for a condition; scroll=scroll; dialog=handle confirm/prompt; back/forward/reload=navigation control; newTab/closeTab/selectTab/listTabs=tab management"
    )

    override val parameters = mapOf(
        "action" to ToolParameter("action", ParameterType.STRING, "Operation type, see the enum list", true),
        "tabId" to ToolParameter("tabId", ParameterType.STRING, "Target tab ID (e.g. tab-1); defaults to the currently active tab", false),
        "path" to ToolParameter("path", ParameterType.STRING, "screenshot: path to save the screenshot (optional; defaults to ~/workspace/.aicode/browser-screenshots/)", false),
        "url" to ToolParameter("url", ParameterType.STRING, "navigate/newTab: URL. Supports http(s); local files support file:// or container paths (~/workspace/…, /etc/…); without a scheme, https is tried first with fallback to http on failure; input that does not look like a URL is searched with the built-in search engine (Bing)", false),
        "script" to ToolParameter("script", ParameterType.STRING, "evaluate: JS code (supports Promise/async)", false),
        "selector" to ToolParameter("selector", ParameterType.STRING,
            "click/fill/hover/getText/getHtml/scroll/wait: selector. Supports ref=e22 (a reference returned by getBackbone) / text=Login / text*=Login / role=button[name=\"Login\"] / xpath=//a / CSS", false),
        "value" to ToolParameter("value", ParameterType.STRING, "fill/select: the value to fill in or the option to select (value or visible text)", false),
        "accept" to ToolParameter("accept", ParameterType.BOOLEAN, "dialog: whether to accept the dialog (true=OK, false=Cancel), default true", false),
        "text" to ToolParameter("text", ParameterType.STRING, "dialog: prompt input text (applies when accept=true)", false),
        "level" to ToolParameter("level", ParameterType.STRING, "console: log level filter (log/warning/error/debug)", false),
        "clear" to ToolParameter("clear", ParameterType.BOOLEAN, "console: whether to clear the logs after fetching, default false", false),
        "key" to ToolParameter("key", ParameterType.STRING,
            "press: key name (Enter/Escape/Tab/ArrowUp/ArrowDown/ArrowLeft/ArrowRight/Backspace/Delete/space/ordinary characters)", false),
        "condition" to ToolParameter("condition", ParameterType.STRING,
            "wait: wait condition. text=Login (exact match) / text*=Login (contains match) / selector=#result (CSS exists) / domStable (DOM stable)", false),
        "timeout" to ToolParameter("timeout", ParameterType.INTEGER, "wait: timeout in milliseconds, default 10000", false),
        "maxDepth" to ToolParameter("maxDepth", ParameterType.INTEGER, "getBackbone: maximum depth of the accessibility tree, default 15", false)
    )

    override fun toJsonSchema(): Map<String, Any> {
        val intKeys = setOf("timeout", "maxDepth")
        val boolKeys = setOf("accept", "clear")
        val properties = LinkedHashMap<String, Any>()
        properties["action"] = actionSchema
        for ((key, param) in parameters) {
            if (key == "action") continue
            properties[key] = mapOf(
                "type" to when {
                    key in intKeys -> "integer"
                    key in boolKeys -> "boolean"
                    else -> "string"
                },
                "description" to param.description
            )
        }
        return mapOf("type" to "object", "properties" to properties, "required" to listOf("action"))
    }

    override fun effectiveCapabilities(args: Map<String, JsonElement>): Set<ToolCapability> {
        val action = args["action"]?.jsonPrimitive?.contentOrNull?.trim()
        return if (action in READ_ONLY_ACTIONS) setOf(ToolCapability.NETWORK_READ)
        else setOf(ToolCapability.NETWORK_READ, ToolCapability.NETWORK_WRITE)
    }

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        val action = args["action"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: return ToolResult.Error("Missing required parameter: action", "MISSING_ACTION")
        val tabId = args["tabId"]?.jsonPrimitive?.contentOrNull?.trim()

        return try {
            executeAction(action, tabId, args)
        } catch (e: IllegalStateException) {
            FileLogger.e(TAG, "Browser operation failed: $action", e)
            ToolResult.Error(e.message ?: "Browser not initialized", "BROWSER_NOT_READY")
        } catch (e: IllegalArgumentException) {
            ToolResult.Error(e.message ?: "Invalid parameter", "INVALID_ARGUMENT")
        } catch (e: TimeoutCancellationException) {
            ToolResult.Error("Operation timed out: $action", "TIMEOUT")
        } catch (e: Exception) {
            FileLogger.e(TAG, "Browser operation error: $action", e)
            ToolResult.Error("Operation failed: ${e.message}", "BROWSER_ERROR")
        }
    }

    private suspend fun executeAction(action: String, tabId: String?, args: Map<String, JsonElement>): ToolResult {
        return when (action) {
            "newTab" -> {
                val url = args["url"]?.jsonPrimitive?.contentOrNull
                FileLogger.i(TAG, "newTab: url=$url")
                val newId = browserManager.newTab(url)
                buildOk(action, mapOf(
                    "tabId" to JsonPrimitive(newId),
                    "url" to JsonPrimitive(browserManager.getUrl(newId)),
                    "title" to JsonPrimitive(browserManager.getTitle(newId))
                ), tabId = newId)
            }

            "closeTab" -> {
                val targetTabId = tabId ?: browserManager.getActiveTabId()
                FileLogger.i(TAG, "closeTab: targetTabId=$targetTabId")
                val closed = browserManager.closeTab(targetTabId)
                val remainingTabs = browserManager.listTabs().map { JsonPrimitive(it.id) }
                buildOk(action, mapOf(
                    "closedTabId" to JsonPrimitive(targetTabId),
                    "success" to JsonPrimitive(closed),
                    "activeTabId" to JsonPrimitive(browserManager.getActiveTabId()),
                    "tabs" to JsonArray(remainingTabs)
                ))
            }

            "selectTab" -> {
                val targetTabId = tabId
                    ?: return ToolResult.Error("selectTab missing required parameter: tabId", "MISSING_TAB_ID")
                FileLogger.i(TAG, "selectTab: targetTabId=$targetTabId")
                val selected = browserManager.selectTab(targetTabId)
                buildOk(action, mapOf(
                    "activeTabId" to JsonPrimitive(targetTabId),
                    "success" to JsonPrimitive(selected),
                    "url" to JsonPrimitive(browserManager.getUrl(targetTabId)),
                    "title" to JsonPrimitive(browserManager.getTitle(targetTabId))
                ), tabId = targetTabId)
            }

            "listTabs" -> {
                FileLogger.i(TAG, "listTabs")
                val tabs = browserManager.listTabs().map { tab ->
                    JsonObject(mapOf(
                        "id" to JsonPrimitive(tab.id),
                        "url" to JsonPrimitive(tab.url),
                        "title" to JsonPrimitive(tab.title),
                        "loading" to JsonPrimitive(tab.loading),
                        "active" to JsonPrimitive(tab.id == browserManager.getActiveTabId())
                    ))
                }
                buildOk(action, mapOf(
                    "tabs" to JsonArray(tabs),
                    "activeTabId" to JsonPrimitive(browserManager.getActiveTabId())
                ))
            }

            "navigate" -> {
                val url = args["url"]?.jsonPrimitive?.contentOrNull
                    ?: return ToolResult.Error("navigate missing required parameter: url", "MISSING_URL")
                FileLogger.i(TAG, "navigate: $url (tabId=$tabId)")
                val finalUrl = browserManager.navigate(url, tabId)
                val targetTabId = tabId ?: browserManager.getActiveTabId()
                buildOk(action, mapOf(
                    "requestedUrl" to JsonPrimitive(url),
                    "finalUrl" to JsonPrimitive(finalUrl),
                    "title" to JsonPrimitive(browserManager.getTitle(targetTabId))
                ), tabId = targetTabId)
            }

            "evaluate" -> {
                val script = args["script"]?.jsonPrimitive?.contentOrNull
                    ?: return ToolResult.Error("evaluate missing required parameter: script", "MISSING_SCRIPT")
                FileLogger.i(TAG, "evaluate: ${script.take(100)} (tabId=$tabId)")
                val result = browserManager.evaluateJavaScript(script, tabId)
                val value = browserManager.parseEvalResult(result)
                buildOk(action, mapOf("value" to value), tabId = tabId)
            }

            "click" -> {
                val selector = args["selector"]?.jsonPrimitive?.contentOrNull
                    ?: return ToolResult.Error("click missing required parameter: selector", "MISSING_SELECTOR")
                FileLogger.i(TAG, "click: $selector (tabId=$tabId)")
                val json = browserManager.clickElement(selector, tabId)
                buildFromJson(action, json, tabId)
            }

            "fill" -> {
                val selector = args["selector"]?.jsonPrimitive?.contentOrNull
                    ?: return ToolResult.Error("fill missing required parameter: selector", "MISSING_SELECTOR")
                val value = args["value"]?.jsonPrimitive?.contentOrNull
                    ?: return ToolResult.Error("fill missing required parameter: value", "MISSING_VALUE")
                FileLogger.i(TAG, "fill: $selector (tabId=$tabId)")
                val json = browserManager.fillElement(selector, value, tabId)
                buildFromJson(action, json, tabId)
            }

            "hover" -> {
                val selector = args["selector"]?.jsonPrimitive?.contentOrNull
                    ?: return ToolResult.Error("hover missing required parameter: selector", "MISSING_SELECTOR")
                FileLogger.i(TAG, "hover: $selector (tabId=$tabId)")
                val json = browserManager.hoverElement(selector, tabId)
                buildFromJson(action, json, tabId)
            }

            "press" -> {
                val key = args["key"]?.jsonPrimitive?.contentOrNull
                    ?: return ToolResult.Error("press missing required parameter: key", "MISSING_KEY")
                FileLogger.i(TAG, "press: $key (tabId=$tabId)")
                browserManager.pressKey(key, tabId)
                buildOk(action, mapOf("key" to JsonPrimitive(key)), tabId = tabId)
            }

            "getText" -> {
                val selector = args["selector"]?.jsonPrimitive?.contentOrNull
                val text = browserManager.getText(selector, tabId)
                val truncated = text.take(50_000)
                val resultText = if (text.length > 50_000) "$truncated\n\n[Content too long, truncated]" else truncated
                buildOk(action, mapOf(
                    "text" to JsonPrimitive(resultText),
                    "selector" to JsonPrimitive(selector ?: "body")
                ), tabId = tabId)
            }

            "getHtml" -> {
                val selector = args["selector"]?.jsonPrimitive?.contentOrNull
                val html = browserManager.getHtml(selector, tabId)
                buildOk(action, mapOf(
                    "html" to JsonPrimitive(html),
                    "selector" to JsonPrimitive(selector ?: "document")
                ), tabId = tabId)
            }

            "getBackbone" -> {
                val maxDepth = args["maxDepth"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: DEFAULT_BACKBONE_DEPTH
                FileLogger.i(TAG, "getBackbone: depth=$maxDepth (tabId=$tabId)")
                val tree = browserManager.getBackbone(maxDepth, tabId)
                buildOk(action, mapOf(
                    "tree" to browserManager.parseEvalResult(tree),
                    "maxDepth" to JsonPrimitive(maxDepth)
                ), tabId = tabId)
            }

            "screenshot" -> {
                FileLogger.i(TAG, "screenshot (tabId=$tabId)")
                val image = browserManager.screenshot(tabId)
                if (image != null) {
                    val resolvedTabId = tabId ?: browserManager.getActiveTabId()
                    val timestamp = System.currentTimeMillis()
                    val customPath = args["path"]?.jsonPrimitive?.contentOrNull?.trim()
                    val savePath = if (!customPath.isNullOrEmpty()) customPath else "$DEFAULT_SCREENSHOT_DIR/screenshot_${timestamp}_$resolvedTabId.jpg"

                    try {
                        val bytes = Base64.decode(image.base64Data, Base64.DEFAULT)
                        fileAccess.writeBytes(savePath, bytes, overwrite = true)
                    } catch (e: Exception) {
                        FileLogger.e(TAG, "Failed to save screenshot to file: $savePath", e)
                    }

                    val viewport = browserManager.getViewportInfo(tabId)
                    val data = buildData(action, mapOf(
                        "status" to JsonPrimitive("captured"),
                        "filePath" to JsonPrimitive(savePath),
                        "viewport" to (browserManager.parseEvalResult(viewport))
                    ), tabId = tabId)
                    ToolResult.Success(data, images = listOf(image.copy(path = savePath)))
                } else {
                    buildError(action, "Screenshot failed: browser size is 0 or rendering error.", "SCREENSHOT_FAILED", tabId = tabId)
                }
            }

            "wait" -> {
                val condition = args["condition"]?.jsonPrimitive?.contentOrNull
                    ?: return ToolResult.Error("wait missing required parameter: condition", "MISSING_CONDITION")
                val timeout = args["timeout"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: DEFAULT_WAIT_TIMEOUT_MS
                FileLogger.i(TAG, "wait: $condition, timeout=$timeout (tabId=$tabId)")
                val met = try {
                    browserManager.wait(condition, timeout, tabId)
                } catch (e: TimeoutCancellationException) { false }
                buildOk(action, mapOf(
                    "condition" to JsonPrimitive(condition),
                    "met" to JsonPrimitive(met),
                    "message" to JsonPrimitive(if (met) "Condition met" else "Timed out, condition not met")
                ), tabId = tabId)
            }

            "select" -> {
                val selector = args["selector"]?.jsonPrimitive?.contentOrNull
                    ?: return ToolResult.Error("select missing required parameter: selector", "MISSING_SELECTOR")
                val value = args["value"]?.jsonPrimitive?.contentOrNull
                    ?: return ToolResult.Error("select missing required parameter: value", "MISSING_VALUE")
                FileLogger.i(TAG, "select: $selector -> $value (tabId=$tabId)")
                val json = browserManager.selectOption(selector, value, tabId)
                buildFromJson(action, json, tabId)
            }

            "dialog" -> {
                val accept = args["accept"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true
                val text = args["text"]?.jsonPrimitive?.contentOrNull
                FileLogger.i(TAG, "dialog: accept=$accept (tabId=$tabId)")
                val detail = browserManager.parseEvalResult(browserManager.resolveDialog(accept, text, tabId))
                buildOk(action, mapOf("result" to detail), tabId = tabId)
            }

            "console" -> {
                val level = args["level"]?.jsonPrimitive?.contentOrNull
                val clear = args["clear"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
                FileLogger.i(TAG, "console: level=$level, clear=$clear (tabId=$tabId)")
                val json = browserManager.getConsoleLogs(level, clear, tabId)
                buildOk(action, mapOf("logs" to browserManager.parseEvalResult(json)), tabId = tabId)
            }

            "scroll" -> {
                val selector = args["selector"]?.jsonPrimitive?.contentOrNull
                val x = args["x"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                val y = args["y"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                FileLogger.i(TAG, "scroll: selector=$selector, x=$x, y=$y (tabId=$tabId)")
                val json = browserManager.scroll(selector, x, y, tabId)
                buildFromJson(action, json, tabId)
            }

            "back" -> {
                val success = browserManager.goBack(tabId)
                buildResult(success, action, mapOf(
                    "success" to JsonPrimitive(success),
                    "message" to JsonPrimitive(if (success) "Went back" else "Cannot go back: no history or navigation did not take effect")
                ), tabId = tabId)
            }

            "forward" -> {
                val success = browserManager.goForward(tabId)
                buildResult(success, action, mapOf(
                    "success" to JsonPrimitive(success),
                    "message" to JsonPrimitive(if (success) "Went forward" else "Cannot go forward: no history or navigation did not take effect")
                ), tabId = tabId)
            }

            "reload" -> {
                val url = browserManager.reload(tabId)
                val targetTabId = tabId ?: browserManager.getActiveTabId()
                buildOk(action, mapOf(
                    "url" to JsonPrimitive(url),
                    "title" to JsonPrimitive(browserManager.getTitle(targetTabId))
                ), tabId = targetTabId)
            }

            else -> ToolResult.Error("Unknown action: $action", "UNKNOWN_ACTION")
        }
    }

    /** Unified response wrapper; when a confirm/prompt is pending, attaches a pendingDialog hint telling the AI to handle it. */
    private fun envelope(ok: Boolean, action: String, detail: JsonElement, tabId: String? = null): JsonObject {
        val resolvedTabId = tabId ?: browserManager.getActiveTabId()
        val fields = LinkedHashMap<String, JsonElement>()
        fields["ok"] = JsonPrimitive(ok)
        fields["action"] = JsonPrimitive(action)
        fields["tabId"] = JsonPrimitive(resolvedTabId)
        fields["url"] = JsonPrimitive(browserManager.getUrl(resolvedTabId))
        fields["title"] = JsonPrimitive(browserManager.getTitle(resolvedTabId))
        fields["detail"] = detail
        fields["error"] = JsonNull
        browserManager.pendingDialogInfo(resolvedTabId)?.let { d ->
            fields["pendingDialog"] = JsonObject(mapOf(
                "type" to JsonPrimitive(d.type),
                "message" to JsonPrimitive(d.message)
            ))
        }
        return JsonObject(fields)
    }

    private fun buildResult(ok: Boolean, action: String, detail: Map<String, JsonElement>, tabId: String? = null): ToolResult {
        return ToolResult.Success(envelope(ok, action, JsonObject(detail), tabId))
    }

    private fun buildData(action: String, detail: Map<String, JsonElement>, tabId: String? = null): JsonObject {
        return envelope(true, action, JsonObject(detail), tabId)
    }

    private fun buildOk(action: String, detail: Map<String, JsonElement>, tabId: String? = null): ToolResult {
        return ToolResult.Success(buildData(action, detail, tabId))
    }

    private fun buildFromJson(action: String, jsonStr: String, tabId: String? = null): ToolResult {
        val detail = browserManager.parseEvalResult(jsonStr)
        val ok = (detail as? JsonObject)?.get("matched")?.jsonPrimitive?.contentOrNull != "false"
        return ToolResult.Success(envelope(ok, action, detail, tabId))
    }

    private fun buildError(action: String, message: String, code: String, tabId: String? = null): ToolResult {
        val resolvedTabId = tabId ?: browserManager.getActiveTabId()
        return ToolResult.Success(JsonObject(mapOf(
            "ok" to JsonPrimitive(false),
            "action" to JsonPrimitive(action),
            "tabId" to JsonPrimitive(resolvedTabId),
            "url" to JsonPrimitive(browserManager.getUrl(resolvedTabId)),
            "title" to JsonPrimitive(browserManager.getTitle(resolvedTabId)),
            "detail" to JsonObject(emptyMap()),
            "error" to JsonObject(mapOf(
                "code" to JsonPrimitive(code),
                "message" to JsonPrimitive(message)
            ))
        )))
    }
}
