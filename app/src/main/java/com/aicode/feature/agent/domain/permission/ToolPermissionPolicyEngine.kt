package com.aicode.feature.agent.domain.permission

import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.ToolCapability
import com.aicode.feature.settings.data.repository.CommandAuthLevel
import com.aicode.feature.settings.data.repository.ToolSafetySettingsRepository
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 工具授权策略引擎：在弹窗之前评估一次工具调用应「自动放行 / 自动拒绝 / 询问」，并在用户选择
 * 「始终允许」后把规则记忆下来。
 *
 * 会话模式（[com.aicode.feature.agent.domain.model.AgentMode]）与授权挡位
 * （[CommandAuthLevel]）解耦，各司其职（对齐 Claude Code 的 Build/Plan/Ask）：
 * - ASK（提问）：只读问答沙盒，一切写操作硬拒绝；AI 不得自行切换计划模式。
 * - PLAN（计划）：只读规划沙盒，一切写操作硬拒绝；退出时经计划审查面板批准才进入构建。
 * - BUILD（构建）：完整工具集；授权口径**完全由设置决定**——按「命令授权挡位」分流
 *   （规则拦截 / 自主执行 / 完全权限），不再有模式级「全放行」开关（原 AUTO 模式的职责
 *   由「完全权限」挡位承担）。
 *
 * 评估顺序（Bash，BUILD 模式）：
 *   0) 灾难性 rm 防护：删除根目录/系统关键目录/工作区根目录等 → 硬拒绝（可凭 elevate 参数降级为一次性授权）；
 *   1) Shizuku 高危工具：一律弹窗且不可记忆；
 *   2) 可静态判定且每段都命中内置只读白名单（ls/git status 等）→ 自动放行；
 *   3) 按「命令授权挡位」（[CommandAuthLevel]）分流：
 *      - 规则拦截（默认）：命中任一启用的高危拦截规则（见 [HighRiskRuleMatcher]）→ ASK 人工确认；
 *        命令不可静态判定 → 对原文做破坏特征扫描（宽松版 [looksDestructiveLoose]），
 *        无破坏特征 ALLOW，有破坏特征 ASK；其余 → ALLOW；
 *      - 自主执行：一律 ASK 并标记 [EvalResult.aiReviewable]，由工作流层先交「AI 命令审查器」评审，
 *        通过直接放行，拒绝/不确定/失败回退人工弹窗；
 *      - 完全权限：直接 ALLOW，仅保留步骤 0 的灾难防护（可提权；「禁用安全拦截」开启时也关闭）。
 *
 * 命令不再支持「始终允许」记忆：旧的项目/全局 ALLOW 规则会先于挡位生效，等于绕过高危拦截。
 * 非 shell 工具（文件读写/编辑等）按整工具（pattern=`*`）匹配，保留「始终允许此工具」记忆
 * （这类工具无高危规则可匹配，去掉记忆会导致每次写文件都弹窗）。
 *
 * [SHIZUKU_TOOL]（Shizuku）按高危处理：一律 ASK 且不可记忆；ASK/PLAN 模式下直接拒绝。
 * 「禁用安全拦截」开关对全部模式生效：开启后灾难性 rm 防护与 Shizuku 逐次确认一并关闭。
 */
@Singleton
class ToolPermissionPolicyEngine @Inject constructor(
    private val rulesRepo: PermissionRulesRepository,
    private val toolSafetySettings: ToolSafetySettingsRepository,
    private val highRiskRulesRepo: HighRiskRulesRepository
) {
    private companion object {
        /** 以 `command` 参数承载 shell 命令、按命令前缀做指令级匹配的工具。 */
        val SHELL_TOOLS = setOf("Bash", SHIZUKU_TOOL)

        /** Shizuku 工具：以 adb shell 身份直接操作宿主 Android 系统，按高危处理（一律弹窗、不可记忆）。 */
        const val SHIZUKU_TOOL = "Shizuku"

        const val REASON_SHIZUKU =
            "Shizuku operates on the host Android system directly as an adb shell; high risk. Single-use approval only; cannot be remembered."

        /**
         * 合并后的终端会话工具：其 `start` 动作承载 shell 命令，需走指令级前缀匹配；
         * `send`/`read` 动作不承载命令，按整工具匹配。故在 [evaluate] 中按 action 路由。
         */
        const val TERMINAL_TOOL = "terminal"
        const val TERMINAL_SHELL_ACTION = "start"
        val NON_REMEMBERABLE_CAPABILITIES = setOf(
            ToolCapability.MODIFY_AGENT_CONFIG,
            ToolCapability.MODIFY_CONTAINER_ENV
        )

        /**
         * 合并后的子代理工具：只读与发消息操作（read/list/send）自动放行，无需弹窗；
         * 写操作（create/stop）走正常规则评估。
         */
        const val TASK_TOOL = "task"
        private val TASK_AUTO_ACTIONS = setOf("read", "list", "send")

        /**
         * 浏览器工具：navigate/click/fill/select/evaluate/dialog/back/forward/reload/newTab/closeTab/selectTab 为写操作（PLAN 拦截），
         * getText/getHtml/getBackbone/screenshot/console/wait/listTabs 为只读（PLAN 放行）。
         */
        const val BROWSER_TOOL = "browser"
        private val BROWSER_READ_ONLY_ACTIONS = setOf("getText", "getHtml", "getBackbone", "screenshot", "console", "wait", "listTabs")

        /**
         * 提权参数：命令因内置安全防护（灾难性 rm）被拒时，
         * 可在调用时置为 true 重试，把硬拒绝降级为一次性用户授权。
         */
        const val ELEVATE_ARG = "elevate"

        /** 容器内 `~` 展开目标（PRoot 以 root 运行）。 */
        const val HOME_DIR = "/root"
        const val HOME_TOKEN = "\$HOME"
        const val HOME_BRACED = "\${HOME}"

        const val REASON_RM_ROOT = "Safety guard: high-risk deletion is forbidden (root directory deletion)"
        const val REASON_RM_RELATIVE = "Safety guard: high-risk deletion is forbidden (global or relative-path wildcard deletion)"
        const val REASON_RM_HOME = "Safety guard: high-risk deletion is forbidden (user directory deletion)"
        const val REASON_RM_WORKSPACE = "Safety guard: high-risk deletion is forbidden (workspace root deletion)"
        const val REASON_RM_TMP = "Safety guard: high-risk deletion is forbidden (whole system temp directory deletion)"
        const val REASON_RM_SYSTEM_DIR = "Safety guard: high-risk deletion is forbidden (critical system directory deletion)"

        const val REASON_PLAN_DENY =
            "You are currently in PLAN mode; the system sandbox forbids modifying system state or performing writes. In plan mode, use read-only tools only to explore the code; do not modify files or run commands."

        const val REASON_ASK_DENY =
            "You are currently in ASK mode; the system sandbox forbids modifying system state or performing writes. In ask mode, only do read-only Q&A and exploration; if changes are needed, ask the user to switch to 'Build' or 'Plan' mode."

        const val REASON_ASK_NO_PLAN_SWITCH =
            "You are currently in ASK mode; the AI must not switch to plan mode itself. If planning is needed, the user must switch to 'Plan' mode manually in the UI."

        /** 相对/通配类删除目标（归一化后判定）：整体删除当前目录或任意内容。 */
        val RELATIVE_WILDCARD_TARGETS = setOf("*", ".*", ".", "..", "../*", "../.*")

        /** 根级通配（`/et*`、`/usr*` 等）可能展开为受保护的系统目录，直接拦截。 */
        val ROOT_LEVEL_GLOB = Regex("^/[^/*?]*[*?]")

        /** 工作区根目录的删除目标（归一化后判定）；工作区的子目录不在此列。 */
        val WORKSPACE_ROOT_TARGETS = setOf("workspace", "workspace/*", "$HOME_DIR/workspace", "$HOME_DIR/workspace/*")

        /** 受保护的系统关键目录：命中其本身或其任意子路径即拦截（`/tmp`、工作区子树单独处理）。 */
        val PROTECTED_SYSTEM_DIRS = setOf(
            "/bin", "/boot", "/dev", "/etc", "/home", "/lib", "/lib64", "/lost+found",
            "/media", "/mnt", "/opt", "/proc", "/root", "/run", "/sbin", "/srv", "/sys", "/usr", "/var"
        )

        /** 疑似破坏性程序（仅在命令不可静态判定时用于保守拦截）。 */
        val DESTRUCTIVE_PROGRAM = Regex(
            "(^|[\\s;&|()`<>])(rm|dd|shred|truncate|mkfs(?:\\.[a-z0-9]+)?|wipefs|fdisk|sfdisk|parted|mkswap|blkdiscard)([\\s;&|()`<>]|$)"
        )
    }

    enum class Verdict { ALLOW, DENY, ASK }

    /**
     * @param verdict 评估结论。
     * @param rememberablePatterns 当 [verdict] 为 ASK 时，「始终允许」会记忆的模式；为空表示不可记忆
     *   （命令不可静态判定，只能单次放行）。
     * @param aiReviewable true 表示该 ASK 来自「自主执行」挡位，工作流层应先交 AI 命令审查器评审，
     *   仅在审查拒绝/不确定/失败时才回退人工弹窗。
     */
    data class EvalResult(
        val verdict: Verdict,
        val rememberablePatterns: List<String>,
        val denyReason: String? = null,
        val rememberDisabledReason: String? = null,
        /** ASK 时的弹窗标题覆盖；null 表示用工具默认标题。 */
        val askTitle: String? = null,
        val aiReviewable: Boolean = false
    )

    suspend fun evaluate(tool: AgentTool?, toolName: String, args: Map<String, JsonElement>, mode: com.aicode.feature.agent.domain.model.AgentMode): EvalResult {
        val capabilities = tool?.effectiveCapabilities(args).orEmpty()
        if (mode == com.aicode.feature.agent.domain.model.AgentMode.PLAN && isDangerousTool(toolName, args, capabilities)) {
            return EvalResult(Verdict.DENY, emptyList(), denyReason = REASON_PLAN_DENY)
        }

        if (mode == com.aicode.feature.agent.domain.model.AgentMode.ASK) {
            // ASK 是纯只读问答模式：写操作与 PLAN 一样硬拒绝；同时禁止 AI 自行切换计划模式，
            // 模式切换只能由用户在界面完成（planMode 的 MODIFY_SESSION_STATE 能力不在
            // isDangerousTool 的危险能力集里，需单独拦截，否则会弹窗后被执行）。
            if (toolName == "planMode") {
                return EvalResult(Verdict.DENY, emptyList(), denyReason = REASON_ASK_NO_PLAN_SWITCH)
            }
            if (isDangerousTool(toolName, args, capabilities)) {
                return EvalResult(Verdict.DENY, emptyList(), denyReason = REASON_ASK_DENY)
            }
        }

        if (capabilities == setOf(ToolCapability.READ_AGENT_CONFIG)) {
            return EvalResult(Verdict.ALLOW, emptyList())
        }

        // task 只读动作（read/list）：直接自动放行（不弹窗）。
        if (toolName == TASK_TOOL) {
            val action = (args["action"] as? JsonPrimitive)?.content?.trim()?.lowercase() ?: "create"
            if (action in TASK_AUTO_ACTIONS) return EvalResult(Verdict.ALLOW, emptyList())
        }

        val level = toolSafetySettings.getCommandAuthLevel()
        // 「禁用安全拦截」对全部模式生效（原 AUTO 专属开关随 AUTO 一并退场）：
        // 开启后灾难性 rm 防护与 Shizuku 逐次确认都关闭，命令一律放行。
        val safetyDisabled = toolSafetySettings.isSafetyInterceptionDisabled()
        return if (isShellTool(toolName, args)) {
            // 仅「规则拦截」挡位需要高危规则集；「完全权限」不再读安全开关（见 evaluateShell）。
            val highRiskRules = if (level == CommandAuthLevel.RULE_BASED) highRiskRulesRepo.getRulesOnce() else emptyList()
            evaluateShell(
                args,
                forceAsk = toolName == SHIZUKU_TOOL,
                level = level,
                highRiskRules = highRiskRules,
                safetyDisabled = safetyDisabled
            )
        } else {
            evaluateGeneric(toolName, capabilities, level)
        }
    }

    private fun isDangerousTool(toolName: String, args: Map<String, JsonElement>, capabilities: Set<ToolCapability>): Boolean {
        val dangerousCapabilities = setOf(
            ToolCapability.WRITE_WORKSPACE,
            ToolCapability.EXECUTE_COMMANDS,
            ToolCapability.NETWORK_WRITE,
            ToolCapability.MODIFY_AGENT_CONFIG,
            ToolCapability.MODIFY_CONTAINER_ENV,
            ToolCapability.EXTERNAL_TOOL
        )
        if (capabilities.any { it in dangerousCapabilities }) return true

        // 只读探索工具在 PLAN 模式下永远安全
        val safePlanModeTools = setOf("list", "search")
        if (toolName in safePlanModeTools) return false

        val dangerousTools = setOf(
            "writeFile",
            "editFile",
            "Bash"
        )
        if (toolName in dangerousTools) return true
        if (toolName == TERMINAL_TOOL) {
            val action = (args["action"] as? JsonPrimitive)?.content?.trim()?.lowercase()
            return action != "read"
        }
        if (toolName == BROWSER_TOOL) {
            val action = (args["action"] as? JsonPrimitive)?.content?.trim()
            return action !in BROWSER_READ_ONLY_ACTIONS
        }
        return false
    }

    /** 是否按 shell 命令前缀匹配：[SHELL_TOOLS] 中的工具，或终端工具的 start/send 动作。 */
    private fun isShellTool(toolName: String, args: Map<String, JsonElement>): Boolean {
        if (toolName in SHELL_TOOLS) return true
        if (toolName == TERMINAL_TOOL) {
            val action = (args["action"] as? JsonPrimitive)?.content?.trim()?.lowercase()
            return action == TERMINAL_SHELL_ACTION || action == "send"
        }
        return false
    }

    /** 调用是否携带提权参数（`elevate: true`）。 */
    private fun isElevationRequested(args: Map<String, JsonElement>): Boolean =
        (args[ELEVATE_ARG] as? JsonPrimitive)?.content?.trim()?.lowercase() == "true"

    /**
     * 破坏性判定（用于「不可静态分析」命令的兜底分流）。宁可多问，不误放——但也不能
     * 把只读排查命令全部判成破坏。这里只认：
     * 1) [DESTRUCTIVE_PROGRAM] 命中（rm/dd/mkfs…，正则边界含 `()` 与反引号，
     *    覆盖 `$(rm -rf x)` 这类藏在命令替换内部的破坏）；
     * 2) `find … -delete`；
     * 3) 覆盖写重定向 `> 目标`——目标不是 /dev/null、不是 fd 复制（2>&1）、
     *    且不是 `>>` 追加。
     *
     * 历史：曾用「含 `>` 即破坏」的严格版，AI 排查类命令几乎条条带 `2>/dev/null`，
     * 大量与删除无关的命令被误判为破坏（RULE_BASED 与 AUTO 两个挡位均已修为宽松版），
     * 严格版实现已删除，只保留本函数。
     */
    private fun looksDestructiveLoose(command: String): Boolean =
        command.contains("-delete") ||
            DESTRUCTIVE_PROGRAM.containsMatchIn(command) ||
            hasOverwriteRedirect(command)

    /** 是否存在覆盖写重定向（`> file`）。`>>` 追加、`2>/dev/null`、`2>&1`、`<<<` 不算。 */
    private fun hasOverwriteRedirect(command: String): Boolean {
        var i = 0
        val n = command.length
        while (i < n) {
            if (command[i] == '>') {
                val double = i + 1 < n && command[i + 1] == '>'
                // herestring（<<<）里的 > 不算重定向
                if (i > 0 && command[i - 1] == '<') { i++; continue }
                if (!double) {
                    val rest = command.substring(i + 1).trimStart()
                    // 空 target / fd 复制（&1）/ 丢弃输出（/dev/null）都无害
                    if (rest.isNotEmpty() && !rest.startsWith("&") && !rest.startsWith("/dev/null")) {
                        return true
                    }
                }
                i += if (double) 2 else 1
            } else {
                i++
            }
        }
        return false
    }

    /**
     * 灾难性删除的裁决：携带提权参数时降级为一次性用户授权（ASK），否则硬拒绝（DENY）
     * 并在原因里提示可提权重试。AUTO 与非 AUTO 模式共用。提权仅对本次调用生效，不可记忆。
     */
    private fun elevationOrDeny(catastrophicReason: String, args: Map<String, JsonElement>): EvalResult =
        if (isElevationRequested(args)) {
            EvalResult(
                verdict = Verdict.ASK,
                rememberablePatterns = emptyList(),
                askTitle = "High-risk operation elevation confirmation",
                rememberDisabledReason = "The command hit a built-in safety guard; elevation supports single-use approval only and cannot be remembered"
            )
        } else {
            EvalResult(
                Verdict.DENY,
                emptyList(),
                denyReason = "$catastrophicReason. If this must be run, retry with `$ELEVATE_ARG: true` in the call and the system will ask the user for authorization."
            )
        }

    /** 把「始终允许」的选择落库为 ALLOW 规则（去重交给仓库）。 */
    suspend fun remember(toolName: String, patterns: List<String>, scope: PermissionScope) {
        patterns.distinct().forEach { pattern ->
            rulesRepo.add(scope, PermissionRule(toolName, pattern, PermissionDecision.ALLOW))
        }
    }

    /**
     * 非 shell 工具（文件读写/编辑等）的裁决。
     *
     * 命令类工具的授权口径已统一收敛到三挡位 + 高危规则，旧的「项目/全局」记忆规则不再参与；
     * 但对非命令类工具仍然保留「始终允许」记忆——这类工具没有高危规则可匹配，
     * 若去掉记忆，每次写文件都要弹窗，无法正常使用。
     */
    private suspend fun evaluateGeneric(
        toolName: String,
        capabilities: Set<ToolCapability>,
        level: CommandAuthLevel
    ): EvalResult {
        if (capabilities.any { it in NON_REMEMBERABLE_CAPABILITIES }) {
            return EvalResult(
                Verdict.ASK,
                rememberablePatterns = emptyList(),
                rememberDisabledReason = "This tool modifies Agent configuration, the container environment, or invokes external dynamic tools; to reduce the risk of mistaken authorization, single-use approval only"
            )
        }
        // 完全权限：命令与非命令工具一律放行。
        if (level == CommandAuthLevel.FULL_ACCESS) {
            return EvalResult(Verdict.ALLOW, emptyList())
        }
        // 已记忆的整工具 ALLOW（「始终允许此工具」）→ 直接放行。
        val rules = rulesRepo.loadEffectiveForCurrentProject()
        if (rules.any { it.toolName == toolName && it.decision == PermissionDecision.ALLOW && it.pattern == PermissionRule.WHOLE_TOOL }) {
            return EvalResult(Verdict.ALLOW, emptyList())
        }
        return EvalResult(Verdict.ASK, listOf(PermissionRule.WHOLE_TOOL))
    }

    private fun checkCatastrophicRm(segments: List<List<String>>): String? {
        for (seg in segments) {
            val rmInfo = ShellCommandParser.parseRmInfo(seg)
            if (!rmInfo.isRm) continue
            for (rawPath in rmInfo.targetPaths) {
                catastrophicReasonFor(rawPath)?.let { return it }
            }
        }
        return null
    }

    /** 单个删除目标的裁决：命中受保护范围返回对应原因，否则 null。 */
    private fun catastrophicReasonFor(rawPath: String): String? {
        val raw = rawPath.trim()
        if (raw.isEmpty()) return null
        val path = normalizePath(raw)
        return when {
            path == "/" || path == "/*" -> REASON_RM_ROOT
            path in RELATIVE_WILDCARD_TARGETS -> REASON_RM_RELATIVE
            ROOT_LEVEL_GLOB.matches(path) -> REASON_RM_SYSTEM_DIR
            path == HOME_DIR || path == "$HOME_DIR/*" -> REASON_RM_HOME
            path in WORKSPACE_ROOT_TARGETS -> REASON_RM_WORKSPACE
            // 工作区子目录（构建产物等）属正常操作，置于系统目录判定之前放行
            path.startsWith("$HOME_DIR/workspace/") || path.startsWith("workspace/") -> null
            path == "/tmp" || path == "/tmp/*" -> REASON_RM_TMP
            PROTECTED_SYSTEM_DIRS.any { path == it || path.startsWith("$it/") } -> REASON_RM_SYSTEM_DIR
            else -> null
        }
    }

    /**
     * 词法路径归一化：把开头的 `~` / `$HOME` / `${HOME}` 展开为容器家目录，折叠重复 `/`，
     * 解析 `.` 与 `..`。纯字符串处理、不访问文件系统，故无法覆盖 `$(...)` 等运行时才可知的路径。
     */
    private fun normalizePath(raw: String): String {
        val expanded = when {
            raw == "~" -> HOME_DIR
            raw.startsWith("~/") -> HOME_DIR + raw.drop(1)
            raw == HOME_TOKEN || raw == HOME_BRACED -> HOME_DIR
            raw.startsWith("$HOME_TOKEN/") -> HOME_DIR + raw.drop(HOME_TOKEN.length)
            raw.startsWith("$HOME_BRACED/") -> HOME_DIR + raw.drop(HOME_BRACED.length)
            else -> raw
        }
        val absolute = expanded.startsWith("/")
        val parts = ArrayDeque<String>()
        for (part in expanded.split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty() && parts.last() != "..") parts.removeLast() else if (!absolute) parts.addLast("..")
                else -> parts.addLast(part)
            }
        }
        val joined = parts.joinToString("/")
        return when {
            absolute -> "/$joined"
            joined.isEmpty() -> "."
            else -> joined
        }
    }

    private fun evaluateShell(
        args: Map<String, JsonElement>,
        forceAsk: Boolean = false,
        level: CommandAuthLevel,
        highRiskRules: List<HighRiskRule>,
        safetyDisabled: Boolean = false
    ): EvalResult {
        val command = ((args["command"] ?: args["input"]) as? JsonPrimitive)?.content
            ?: return EvalResult(Verdict.ASK, emptyList())

        val analysis = ShellCommandParser.analyze(command)

        // 0) rm 高危操作防护：禁止直接删除系统根目录、工作区根目录或系统关键目录。
        //    携带提权参数时降级为一次性用户授权。所有挡位共用；
        //    「禁用安全拦截」开启时跳过（原为 AUTO 专属开关，现对全模式生效）。
        if (!safetyDisabled) {
            val catastrophicReason = checkCatastrophicRm(analysis.segments)
            if (catastrophicReason != null) {
                return elevationOrDeny(catastrophicReason, args)
            }
        }

        // 1) Shizuku 高危工具：无论内置白名单，一律弹窗且不可记忆（关闭安全拦截时除外）。
        if (forceAsk && !safetyDisabled) {
            return EvalResult(Verdict.ASK, emptyList(), rememberDisabledReason = REASON_SHIZUKU)
        }

        // 2) 可静态判定且每段都命中内置只读白名单（ls/git status 等）→ 自动放行。
        //    必须要求可判定：`cat $(rm -rf /)` 之类命令替换攻击的段首仍是 cat，不可凭白名单放行。
        if (analysis.analyzable && analysis.segments.isNotEmpty() &&
            analysis.segments.all { BuiltInSafeCommands.isSafe(it) }
        ) {
            return EvalResult(Verdict.ALLOW, emptyList())
        }

        // 3) 挡位分流。
        //    注：旧的「项目/全局」记忆规则（ALLOW/DENY）已整体下线——它会先于挡位生效，
        //    等于绕过高危拦截规则；现在授权口径统一由三挡位 + 高危规则决定。
        return when (level) {
            // 完全权限：灾难防护（步骤 0 的 checkCatastrophicRm）之上**一律直接放行**。
            // 此前这里还有一道「不可静态判定且疑似破坏性 → 拦一次」的兜底，但它依据
            // 「含 `>` 即破坏」的严格判定：只要命令里出现任何一个 `>` 就命中，连
            // `2>/dev/null` 这种丢弃输出的写法也算。AI 排查类命令几乎条条带
            // `$()`/`2>/dev/null`，结果大量与删除无关的命令被硬拒绝
            // （elevationOrDeny 无弹窗、直接失败），与「完全权限」的语义矛盾。
            // 用户既然选了这一挡，就按其字面执行。
            CommandAuthLevel.FULL_ACCESS -> EvalResult(Verdict.ALLOW, emptyList())

            // 规则拦截：命中高危规则 → 人工确认；不可静态判定 → 破坏特征扫描；其余自动放行。
            CommandAuthLevel.RULE_BASED -> {
                HighRiskRuleMatcher.matchAny(highRiskRules, command)?.let { hit ->
                    return askWithRememberable(
                        analysis,
                        askTitle = "High-risk rule hit: ${hit.description.ifBlank { hit.pattern }}"
                    )
                }
                if (!analysis.analyzable) {
                    // 不可静态判定（命令替换/子shell/循环等）。此前一律弹窗——AI 排查类
                    // 命令充满 $() 与反引号，实测条条命中兜底分支，高危规则反而一次没
                    // 触发过，授权弹窗形同骚扰。改为对**原文**做破坏特征扫描：
                    // DESTRUCTIVE_PROGRAM 正则的边界符含 `()` 与反引号，能覆盖 $()
                    // 内部；覆盖写重定向（> file）单独判定（2>/dev/null、>>、2>&1 无害）。
                    // 无破坏特征 → 放行；有 → 人工确认。
                    if (looksDestructiveLoose(command)) {
                        return askWithRememberable(
                            analysis,
                            askTitle = "Command contains complex constructs (command substitution/sub-shell) with destructive features detected; manual confirmation required"
                        )
                    }
                    return EvalResult(Verdict.ALLOW, emptyList())
                }
                EvalResult(Verdict.ALLOW, emptyList())
            }

            // 自主执行：统一转交 AI 命令审查器（工作流层处理），审查不过再回退人工。
            CommandAuthLevel.AUTONOMOUS -> askWithRememberable(analysis, aiReviewable = true)
        }
    }

    /**
     * 构造命令类 ASK 裁决。命令的「始终允许」记忆已下线——它会落到项目/全局规则里，
     * 先于挡位与高危拦截规则生效，等于绕过授权挡位；命令一律只支持单次放行。
     */
    private fun askWithRememberable(
        analysis: ShellCommandParser.Analysis,
        askTitle: String? = null,
        aiReviewable: Boolean = false
    ): EvalResult {
        val hasRmUnrememberable = analysis.segments.any { seg ->
            val rmInfo = ShellCommandParser.parseRmInfo(seg)
            rmInfo.isRm && (rmInfo.targetPaths.isEmpty() || rmInfo.isRecursive || rmInfo.isWildcard)
        }
        val reason = if (hasRmUnrememberable) {
            "High-risk deletion (recursive/wildcard/forced bulk delete); for data safety it cannot be remembered — single-use approval only"
        } else {
            "Command authorization is managed centrally by 'auth level + high-risk rules'; single-use approval only"
        }
        return EvalResult(
            verdict = Verdict.ASK,
            rememberablePatterns = emptyList(),
            rememberDisabledReason = reason,
            askTitle = askTitle,
            aiReviewable = aiReviewable
        )
    }
}
