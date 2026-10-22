package com.aicode.feature.agent.domain.container

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Events produced while streaming a command execution, for the terminal to render line by line in real time. */
sealed interface CommandEvent {
    /** One line of standard output/error (stderr has been merged into stdout). */
    data class Line(val text: String) : CommandEvent
    /** The command finished, with its exit code (null when the fallback shell cannot obtain it). */
    data class Exit(val code: Int?) : CommandEvent
}

/**
 * The result of one command execution: the full output after capping + the exit code (null on timeout/error).
 * [outputTruncated] being true means the output exceeded the engine's cap and was truncated (for the uncapped
 * channel see [MAX_UNBOUNDED_CHARS]); callers should not parse such output as a complete result.
 */
data class CommandResult(val output: String, val exitCode: Int?, val outputTruncated: Boolean = false)

/**
 * Command execution backend abstraction: decouples "where commands are executed" from the hardcoded local PRoot.
 *
 * Two implementations:
 * - [LinuxContainerEngine]: the local PRoot container, with the original logic unchanged;
 * - `RemoteSshEngine`: a remote SSH server, executing via an sshj exec channel.
 *
 * The tool layer ([com.aicode.feature.agent.domain.tool.container.ExecuteCommandTool],
 * [com.aicode.feature.agent.domain.tool.explorer.SearchCodeTool],
 * [com.aicode.feature.git.domain.GitRepository]) depends on this interface rather than a concrete
 * implementation; DI injects the corresponding instance according to the current execution mode.
 *
 * PRoot-specific methods ([LinuxContainerEngine.startStdioProcess],
 * [LinuxContainerEngine.buildProotInvocation], [LinuxContainerEngine.incPromptInFlight], etc.)
 * are not part of this interface — they are only for local MCP stdio / credential helpers / the terminal PTY,
 * and in remote mode they still run locally.
 */
interface CommandEngine {

    /** Execution backend initialization progress, for the UI to display in real time. In remote mode this is normally always [ContainerInitState.Ready]. */
    val initProgress: StateFlow<ContainerInitState>

    /**
     * Executes a command as a stream: emits a [CommandEvent.Line] for each line read, and emits
     * [CommandEvent.Exit] when the command ends. The first call may trigger backend initialization
     * (locally this extracts the rootfs).
     *
     * [timeoutMs] is the maximum execution time for the command (milliseconds); on timeout the command is
     * forcibly terminated and a timeout notice is appended.
     */
    fun runCommandStream(
        command: String,
        projectPath: String? = null,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): Flow<CommandEvent>

    /**
     * Executes a command synchronously and returns the output. The first call may trigger backend initialization.
     * [timeoutMs] is the maximum execution time for the command (milliseconds); on timeout the command is
     * forcibly terminated and the partial output collected so far is returned.
     */
    suspend fun runCommandSync(
        command: String,
        projectPath: String? = null,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): String

    /**
     * Same as [runCommandSync], but also returns the exit code (null on timeout/error).
     * For callers that need to judge success/failure by exit code (e.g. git write operations).
     */
    suspend fun runCommandSyncWithExit(
        command: String,
        projectPath: String? = null,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): CommandResult

    /**
     * Same as [runCommandSyncWithExit], but the output is **not capped or truncated** (the continuous beginning
     * is kept, with no omission notice inserted). For callers that need the complete text (e.g. the git diff
     * page reading a diff/file contents): the AI tool pipeline's default cap ([BoundedOutput] keeps 20k
     * characters each at the head and tail) would mix truncation placeholders into the diff data stream,
     * causing the UI to render fake diff lines.
     *
     * Once the output exceeds [MAX_UNBOUNDED_CHARS] characters, retention stops and
     * [CommandResult.outputTruncated] is set; the caller uses this to prompt "output too large" instead of
     * parsing half-truncated data.
     */
    suspend fun runCommandSyncUnbounded(
        command: String,
        projectPath: String? = null,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): CommandResult

    /**
     * Executes a command only when the backend is already ready; does not trigger initialization (does not
     * extract the rootfs / does not open an SSH connection). Returns null when not ready, letting the caller
     * fall back. Used by read-only tools as a performance enhancement (e.g. search preferring rg).
     */
    suspend fun runCommandSyncIfReady(
        command: String,
        projectPath: String? = null,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): CommandResult?

    /** Whether the backend is installed and ready. Locally judged by rootfs/proot; remotely by whether the SSH connection is alive. */
    fun isContainerInstalled(): Boolean

    /** Whether the base packages have finished being provisioned. Locally judged by the provision marker; remotely always true (the user guarantees the tools themselves). */
    fun isProvisioned(): Boolean

    /** The default command shell path. Locally bash/sh is chosen by provision state; remotely it is usually /bin/bash. */
    fun defaultShell(): String

    /**
     * Whether the backend is ready to execute commands. null means ready; non-null is a hint explaining why it
     * is not ready, which the caller shows to the user to guide them through initialization (locally,
     * "Please go to the terminal page to initialize first").
     *
     * The default implementation always returns null (remote SSH connection failures are handled by automatic
     * retry on the first command and are not in this category).
     * [LinuxContainerEngine] overrides this to return guidance text based on the rootfs/provision state.
     */
    fun notReadyHint(): String? = null

    /**
     * Idempotently ensures the backend is usable: locally this extracts the rootfs/proot and provisions the
     * base packages (slow on first run); remotely it establishes the SSH connection. Called only by the
     * terminal page (the sole initialization entry point); command execution entry points no longer trigger it automatically.
     */
    suspend fun ensureInstalled()

    companion object {
        /** Default command timeout (milliseconds). */
        const val DEFAULT_TIMEOUT_MS = 120_000L

        /** Maximum command timeout (milliseconds). */
        const val MAX_TIMEOUT_MS = 1_800_000L
    }
}

/**
 * Redacts a command string before logging: the command may carry an env prefix (e.g. a panel script's
 * `AICODE_PROVIDER_API_KEY=...`) or inline credentials (`--header "Authorization: Bearer ..."`).
 * Values are masked to `***` by sensitive key name.
 */
private val SENSITIVE_COMMAND_VALUE_REGEX = Regex(
    """(?i)(?<![A-Za-z0-9])(api[_-]?key|access[_-]?token|auth[_-]?token|token|password|passwd|secret|authorization|auth[_-]?cookie|cookie)\b\s*([=:])\s*(['"]?)(?:Bearer\s+|Basic\s+)?[^\s'"]+"""
)

fun sanitizeCommandForLog(command: String): String =
    SENSITIVE_COMMAND_VALUE_REGEX.replace(command) { m ->
        m.groupValues[1] + m.groupValues[2] + m.groupValues[3] + "***"
    }
