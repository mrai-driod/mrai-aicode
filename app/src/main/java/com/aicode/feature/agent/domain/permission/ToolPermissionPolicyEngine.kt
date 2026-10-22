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
 * Tool authorization policy engine: before any dialog, evaluates whether a tool call should be
 * "auto-allowed / auto-denied / asked about", and remembers the rule after the user chooses "Always allow".
 *
 * The session mode ([com.aicode.feature.agent.domain.model.AgentMode]) and the authorization level
 * ([CommandAuthLevel]) are decoupled, each with its own job (aligned with Claude Code's Build/Plan/Ask):
 * - ASK (questioning): a read-only Q&A sandbox; all write operations are hard-denied; the AI must not switch
 *   to plan mode by itself.
 * - PLAN (planning): a read-only planning sandbox; all write operations are hard-denied; on exit, entering
 *   build requires approval via the plan review panel.
 * - BUILD (building): the full toolset; the authorization policy is **decided entirely by the settings** —
 *   routed by the "command authorization level" (rule-based interception / autonomous execution / full access),
 *   with no mode-level "allow everything" switch anymore (the former AUTO mode's responsibility is taken over
 *   by the "Full access" level).
 *
 * Evaluation order (Bash, BUILD mode):
 *   0) Catastrophic rm guard: deleting the root directory / critical system directories / the workspace root,
 *      etc. -> hard deny (can be downgraded to a single-use authorization via the elevate parameter);
 *   1) Shizuku high-risk tool: always shows a dialog and cannot be remembered;
 *   2) Statically analyzable and every segment hits the built-in read-only allowlist (ls/git status, etc.)
 *      -> auto-allow;
 *   3) Routed by the "command authorization level" ([CommandAuthLevel]):
 *      - Rule-based interception (default): hitting any enabled high-risk interception rule (see
 *        [HighRiskRuleMatcher]) -> ASK for manual confirmation; if the command cannot be statically analyzed
 *        -> run a destructive-feature scan over the original text (the loose version, [looksDestructiveLoose]):
 *        no destructive features ALLOW, destructive features ASK; otherwise -> ALLOW;
 *      - Autonomous execution: always ASK, marked [EvalResult.aiReviewable]; the workflow layer first hands it
 *        to the "AI command reviewer" for review — a pass allows it directly, while reject/uncertain/failure
 *        falls back to a manual dialog;
 *      - Full access: ALLOW directly, keeping only step 0's catastrophe guard (elevatable; also disabled when
 *        "Disable safety interception" is on).
 *
 * Commands no longer support "Always allow" remembering: the old project/global ALLOW rules would take effect
 * before the level, which amounts to bypassing the high-risk interception.
 * Non-shell tools (file read/write/edit, etc.) are matched by whole tool (pattern=`*`) and keep the
 * "Always allow this tool" memory (these tools have no high-risk rules to match; removing the memory would
 * cause a dialog on every file write).
 *
 * [SHIZUKU_TOOL] (Shizuku) is treated as high risk: always ASK and cannot be remembered; denied outright in
 * ASK/PLAN modes. The "Disable safety interception" switch applies to all modes: when on, the catastrophic rm
 * guard and the per-call Shizuku confirmation are both disabled.
 */
@Singleton
class ToolPermissionPolicyEngine @Inject constructor(
    private val rulesRepo: PermissionRulesRepository,
    private val toolSafetySettings: ToolSafetySettingsRepository,
    private val highRiskRulesRepo: HighRiskRulesRepository
) {
    private companion object {
        /** Tools that carry a shell command in the `command` parameter and are matched at instruction level by command prefix. */
        val SHELL_TOOLS = setOf("Bash", SHIZUKU_TOOL)

        /** The Shizuku tool: operates directly on the host Android system as an adb shell; treated as high risk (always shows a dialog, cannot be remembered). */
        const val SHIZUKU_TOOL = "Shizuku"

        const val REASON_SHIZUKU =
            "Shizuku operates on the host Android system directly as an adb shell; high risk. Single-use approval only; cannot be remembered."

        /**
         * The merged terminal session tool: its `start` action carries a shell command and needs instruction-level
         * prefix matching; the `send`/`read` actions carry no command and are matched by whole tool.
         * Routing is therefore by action in [evaluate].
         */
        const val TERMINAL_TOOL = "terminal"
        const val TERMINAL_SHELL_ACTION = "start"
        val NON_REMEMBERABLE_CAPABILITIES = setOf(
            ToolCapability.MODIFY_AGENT_CONFIG,
            ToolCapability.MODIFY_CONTAINER_ENV
        )

        /**
         * The merged sub-agent tool: read-only and message-sending operations (read/list/send) are auto-allowed
         * with no dialog; write operations (create/stop) go through normal rule evaluation.
         */
        const val TASK_TOOL = "task"
        private val TASK_AUTO_ACTIONS = setOf("read", "list", "send")

        /**
         * The browser tool: navigate/click/fill/select/evaluate/dialog/back/forward/reload/newTab/closeTab/selectTab are write operations (blocked in PLAN),
         * getText/getHtml/getBackbone/screenshot/console/wait/listTabs are read-only (allowed in PLAN).
         */
        const val BROWSER_TOOL = "browser"
        private val BROWSER_READ_ONLY_ACTIONS = setOf("getText", "getHtml", "getBackbone", "screenshot", "console", "wait", "listTabs")

        /**
         * The elevation parameter: when a command is rejected by a built-in safety guard (catastrophic rm),
         * it can be retried with this set to true at call time, downgrading the hard deny to a single-use user authorization.
         */
        const val ELEVATE_ARG = "elevate"

        /** The expansion target of `~` inside the container (PRoot runs as root). */
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

        /** Relative/wildcard deletion targets (judged after normalization): deleting the current directory as a whole, or arbitrary contents. */
        val RELATIVE_WILDCARD_TARGETS = setOf("*", ".*", ".", "..", "../*", "../.*")

        /** Root-level globs (`/et*`, `/usr*`, etc.) may expand to protected system directories; intercepted directly. */
        val ROOT_LEVEL_GLOB = Regex("^/[^/*?]*[*?]")

        /** Deletion targets for the workspace root directory (judged after normalization); subdirectories of the workspace are not included. */
        val WORKSPACE_ROOT_TARGETS = setOf("workspace", "workspace/*", "$HOME_DIR/workspace", "$HOME_DIR/workspace/*")

        /** Protected critical system directories: hitting the directory itself or any of its subpaths is intercepted (`/tmp` and the workspace subtree are handled separately). */
        val PROTECTED_SYSTEM_DIRS = setOf(
            "/bin", "/boot", "/dev", "/etc", "/home", "/lib", "/lib64", "/lost+found",
            "/media", "/mnt", "/opt", "/proc", "/root", "/run", "/sbin", "/srv", "/sys", "/usr", "/var"
        )

        /** Suspected destructive programs (used for conservative interception only when a command cannot be statically analyzed). */
        val DESTRUCTIVE_PROGRAM = Regex(
            "(^|[\\s;&|()`<>])(rm|dd|shred|truncate|mkfs(?:\\.[a-z0-9]+)?|wipefs|fdisk|sfdisk|parted|mkswap|blkdiscard)([\\s;&|()`<>]|$)"
        )
    }

    enum class Verdict { ALLOW, DENY, ASK }

    /**
     * @param verdict The evaluation conclusion.
     * @param rememberablePatterns When [verdict] is ASK, the patterns that "Always allow" would remember;
     *   empty means it cannot be remembered (the command cannot be statically analyzed and can only be allowed
     *   for a single use).
     * @param aiReviewable true means this ASK comes from the "Autonomous execution" level; the workflow layer
     *   should first hand it to the AI command reviewer, and fall back to a manual dialog only when the review
     *   rejects, is uncertain, or fails.
     */
    data class EvalResult(
        val verdict: Verdict,
        val rememberablePatterns: List<String>,
        val denyReason: String? = null,
        val rememberDisabledReason: String? = null,
        /** Dialog title override for ASK; null means use the tool's default title. */
        val askTitle: String? = null,
        val aiReviewable: Boolean = false
    )

    suspend fun evaluate(tool: AgentTool?, toolName: String, args: Map<String, JsonElement>, mode: com.aicode.feature.agent.domain.model.AgentMode): EvalResult {
        val capabilities = tool?.effectiveCapabilities(args).orEmpty()
        if (mode == com.aicode.feature.agent.domain.model.AgentMode.PLAN && isDangerousTool(toolName, args, capabilities)) {
            return EvalResult(Verdict.DENY, emptyList(), denyReason = REASON_PLAN_DENY)
        }

        if (mode == com.aicode.feature.agent.domain.model.AgentMode.ASK) {
            // ASK is a purely read-only Q&A mode: write operations are hard-denied just as in PLAN; the AI is
            // also forbidden from switching to plan mode by itself — mode switching can only be done by the user
            // in the UI (planMode's MODIFY_SESSION_STATE capability is not in isDangerousTool's dangerous
            // capability set, so it needs a separate interception, otherwise it would execute after a dialog).
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

        // task read-only actions (read/list): auto-allowed directly (no dialog).
        if (toolName == TASK_TOOL) {
            val action = (args["action"] as? JsonPrimitive)?.content?.trim()?.lowercase() ?: "create"
            if (action in TASK_AUTO_ACTIONS) return EvalResult(Verdict.ALLOW, emptyList())
        }

        val level = toolSafetySettings.getCommandAuthLevel()
        // "Disable safety interception" applies to all modes (the former AUTO-only switch retired along with AUTO):
        // when on, both the catastrophic rm guard and the per-call Shizuku confirmation are disabled, and commands are all allowed.
        val safetyDisabled = toolSafetySettings.isSafetyInterceptionDisabled()
        return if (isShellTool(toolName, args)) {
            // Only the "Rule-based interception" level needs the high-risk rule set; "Full access" no longer reads the safety switch (see evaluateShell).
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

        // Read-only exploration tools are always safe in PLAN mode
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

    /** Whether to match by shell command prefix: tools in [SHELL_TOOLS], or the terminal tool's start/send actions. */
    private fun isShellTool(toolName: String, args: Map<String, JsonElement>): Boolean {
        if (toolName in SHELL_TOOLS) return true
        if (toolName == TERMINAL_TOOL) {
            val action = (args["action"] as? JsonPrimitive)?.content?.trim()?.lowercase()
            return action == TERMINAL_SHELL_ACTION || action == "send"
        }
        return false
    }

    /** Whether the call carries the elevation parameter (`elevate: true`). */
    private fun isElevationRequested(args: Map<String, JsonElement>): Boolean =
        (args[ELEVATE_ARG] as? JsonPrimitive)?.content?.trim()?.lowercase() == "true"

    /**
     * Destructiveness judgment (used as the fallback routing for commands that "cannot be statically
     * analyzed"). Rather ask too often than allow by mistake — but read-only troubleshooting commands must not
     * all be judged destructive either. Only these count here:
     * 1) A [DESTRUCTIVE_PROGRAM] hit (rm/dd/mkfs..., whose regex boundaries include `()` and backticks,
     *    covering destruction hidden inside command substitution such as `$(rm -rf x)`);
     * 2) `find … -delete`;
     * 3) An overwrite redirect `> target` — where the target is not /dev/null, not an fd copy (2>&1),
     *    and not a `>>` append.
     *
     * History: a strict version that treated "contains `>`" as destructive was once used; AI troubleshooting
     * commands almost all carry `2>/dev/null`, so many commands unrelated to deletion were misjudged as
     * destructive (both the RULE_BASED and AUTO levels have since been fixed to the loose version); the strict
     * implementation has been deleted and only this function remains.
     */
    private fun looksDestructiveLoose(command: String): Boolean =
        command.contains("-delete") ||
            DESTRUCTIVE_PROGRAM.containsMatchIn(command) ||
            hasOverwriteRedirect(command)

    /** Whether an overwrite redirect (`> file`) exists. `>>` appends, `2>/dev/null`, `2>&1` and `<<<` do not count. */
    private fun hasOverwriteRedirect(command: String): Boolean {
        var i = 0
        val n = command.length
        while (i < n) {
            if (command[i] == '>') {
                val double = i + 1 < n && command[i + 1] == '>'
                // A > inside a herestring (<<<) does not count as a redirect
                if (i > 0 && command[i - 1] == '<') { i++; continue }
                if (!double) {
                    val rest = command.substring(i + 1).trimStart()
                    // An empty target / fd copy (&1) / discarded output (/dev/null) are all harmless
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
     * The verdict for a catastrophic deletion: when the elevation parameter is carried, it is downgraded to a
     * single-use user authorization (ASK); otherwise it is a hard deny (DENY), with the reason hinting that it
     * can be retried with elevation. Shared by AUTO and non-AUTO modes. Elevation applies to this call only
     * and cannot be remembered.
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

    /** Persists an "Always allow" choice as an ALLOW rule (deduplication is left to the repository). */
    suspend fun remember(toolName: String, patterns: List<String>, scope: PermissionScope) {
        patterns.distinct().forEach { pattern ->
            rulesRepo.add(scope, PermissionRule(toolName, pattern, PermissionDecision.ALLOW))
        }
    }

    /**
     * The verdict for non-shell tools (file read/write/edit, etc.).
     *
     * The authorization policy for command tools has been uniformly converged to the three levels + high-risk
     * rules, and the old "project/global" remembered rules no longer participate; but for non-command tools the
     * "Always allow" memory is still kept — these tools have no high-risk rules to match, and removing the
     * memory would cause a dialog on every file write, making normal use impossible.
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
        // Full access: command and non-command tools are all allowed.
        if (level == CommandAuthLevel.FULL_ACCESS) {
            return EvalResult(Verdict.ALLOW, emptyList())
        }
        // A remembered whole-tool ALLOW ("Always allow this tool") -> allowed directly.
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

    /** The verdict for a single deletion target: returns the corresponding reason when it hits a protected scope, otherwise null. */
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
            // Workspace subdirectories (build artifacts, etc.) are normal operations; allowed before the system-directory judgment
            path.startsWith("$HOME_DIR/workspace/") || path.startsWith("workspace/") -> null
            path == "/tmp" || path == "/tmp/*" -> REASON_RM_TMP
            PROTECTED_SYSTEM_DIRS.any { path == it || path.startsWith("$it/") } -> REASON_RM_SYSTEM_DIR
            else -> null
        }
    }

    /**
     * Lexical path normalization: expands a leading `~` / `$HOME` / `${HOME}` to the container home directory,
     * collapses repeated `/`, and resolves `.` and `..`. Pure string processing with no filesystem access, so
     * it cannot cover paths only known at runtime such as `$(...)`.
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

        // 0) rm high-risk operation guard: forbids directly deleting the system root directory, the workspace
        //    root directory, or critical system directories. When the elevation parameter is carried, it is
        //    downgraded to a single-use user authorization. Shared by all levels;
        //    skipped when "Disable safety interception" is on (formerly an AUTO-only switch, now applying to all modes).
        if (!safetyDisabled) {
            val catastrophicReason = checkCatastrophicRm(analysis.segments)
            if (catastrophicReason != null) {
                return elevationOrDeny(catastrophicReason, args)
            }
        }

        // 1) Shizuku high-risk tool: regardless of the built-in allowlist, always shows a dialog and cannot be remembered (except when safety interception is off).
        if (forceAsk && !safetyDisabled) {
            return EvalResult(Verdict.ASK, emptyList(), rememberDisabledReason = REASON_SHIZUKU)
        }

        // 2) Statically analyzable and every segment hits the built-in read-only allowlist (ls/git status, etc.) -> auto-allow.
        //    Analyzability is mandatory: for a command-substitution attack like `cat $(rm -rf /)` the segment still starts with cat, so it must not be allowed on the allowlist alone.
        if (analysis.analyzable && analysis.segments.isNotEmpty() &&
            analysis.segments.all { BuiltInSafeCommands.isSafe(it) }
        ) {
            return EvalResult(Verdict.ALLOW, emptyList())
        }

        // 3) Level routing.
        //    Note: the old "project/global" remembered rules (ALLOW/DENY) have been retired as a whole — they
        //    would take effect before the level, which amounts to bypassing the high-risk interception rules;
        //    the authorization policy is now decided uniformly by the three levels + high-risk rules.
        return when (level) {
            // Full access: on top of the catastrophe guard (step 0's checkCatastrophicRm), **everything is
            // allowed directly**. There used to be an additional fallback here — "cannot be statically analyzed
            // and suspected destructive -> intercept once" — but it relied on the strict judgment that "contains
            // `>` means destructive": any single `>` in the command hit it, even the output-discarding form
            // `2>/dev/null`. AI troubleshooting commands almost all carry `$()`/`2>/dev/null`, so many commands
            // unrelated to deletion ended up hard-denied (elevationOrDeny has no dialog and fails directly),
            // contradicting the semantics of "Full access". Since the user chose this level, execute it literally.
            CommandAuthLevel.FULL_ACCESS -> EvalResult(Verdict.ALLOW, emptyList())

            // Rule-based interception: hitting a high-risk rule -> manual confirmation; cannot be statically analyzed -> destructive-feature scan; everything else is auto-allowed.
            CommandAuthLevel.RULE_BASED -> {
                HighRiskRuleMatcher.matchAny(highRiskRules, command)?.let { hit ->
                    return askWithRememberable(
                        analysis,
                        askTitle = "High-risk rule hit: ${hit.description.ifBlank { hit.pattern }}"
                    )
                }
                if (!analysis.analyzable) {
                    // Cannot be statically analyzed (command substitution/sub-shells/loops, etc.). Previously
                    // these always showed a dialog — AI troubleshooting commands are full of $() and backticks,
                    // and in practice every one hit the fallback branch while the high-risk rules never fired
                    // once, making the authorization dialog mere harassment. Changed to run a destructive-feature
                    // scan over the **original text**: the DESTRUCTIVE_PROGRAM regex's boundary characters include
                    // `()` and backticks, covering the inside of $(); overwrite redirects (> file) are judged
                    // separately (2>/dev/null, >> and 2>&1 are harmless).
                    // No destructive features -> allow; destructive features -> manual confirmation.
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

            // Autonomous execution: uniformly handed to the AI command reviewer (handled by the workflow layer); if the review does not pass, fall back to a human.
            CommandAuthLevel.AUTONOMOUS -> askWithRememberable(analysis, aiReviewable = true)
        }
    }

    /**
     * Builds an ASK verdict for command tools. The "Always allow" memory for commands has been retired — it
     * would land in the project/global rules and take effect before the level and the high-risk interception
     * rules, which amounts to bypassing the authorization level; commands only support single-use approval.
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
