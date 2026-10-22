package com.aicode.feature.agent.domain.workflow

import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.model.AgentMode
import com.aicode.feature.agent.domain.provider.RetryErrorInfo
import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.ToolCall
import kotlinx.coroutines.flow.Flow

/**
 * Step-by-step events pushed to the UI during Agent execution.
 * Each event corresponds to one message in the chat (or an update to one), persisted and rendered by the ViewModel.
 */
sealed class AgentEvent {
    /** A block of text reply produced by the model (possibly accompanied by tool calls). [reasoning] is this round's thought process, persisted for history display; an empty string means none. */
    data class AssistantText(
        val content: String,
        val toolCalls: List<ToolCall> = emptyList(),
        val reasoning: String = "",
        /** The encrypted signature of Anthropic extended thinking, persisted with reasoning for playback in the tool loop. Empty string for other providers. */
        val signature: String = "",
        val inputTokens: Int = 0,
        val outputTokens: Int = 0,
        val cachedInputTokens: Int = 0,
        /** A verbatim snapshot (JSON array text) of Anthropic thinking / redacted_thinking content blocks, persisted with reasoning for verbatim playback in later rounds. */
        val thinkingBlocksJson: String = "",
        /** File cards built after images output directly by the model (Gemini image models) are saved to disk, persisted with the message for UI rendering. */
        val attachments: List<com.aicode.feature.agent.presentation.AgentAttachment> = emptyList(),
        val messageId: String = java.util.UUID.randomUUID().toString(),
        val persisted: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    ) : AgentEvent()

    /** Text streamed out word by word by the model during streaming ([accumulated] is the full text accumulated so far this round, for live UI rendering; not persisted). */
    data class AssistantDelta(val accumulated: String) : AgentEvent()

    /** The thought process streamed out word by word by the model during streaming ([accumulated] is the full thought process accumulated so far this round, for live UI rendering; not persisted). */
    data class ReasoningDelta(val accumulated: String) : AgentEvent()

    /** The model decides to call a tool (before execution). */
    data class ToolCallStarted(val id: String, val toolName: String, val argsPreview: String) : AgentEvent()

    /**
     * The model has just started producing a tool call and the tool name is known (arguments are still streaming in).
     *
     * For tools with long arguments (writing a whole file, long commands), argument streaming can last several seconds, during which there is neither body text nor reasoning increments,
     * and the UI can only show a generic "thinking" state. This event lets the UI state the status more specifically in advance; it is not persisted and does not participate in execution decisions.
     */
    data class ToolCallPreparing(val toolName: String) : AgentEvent()

    /** Live accumulated output during tool execution (produced only by streaming tools, for live UI rendering; not persisted). */
    data class ToolCallProgress(val id: String, val toolName: String, val accumulated: String) : AgentEvent()

    /** The result of a completed tool execution (success, failure, or rejected by the user). */
    data class ToolCallFinished(
        val id: String,
        val toolName: String,
        val result: String,
        val isError: Boolean,
        val argsPreview: String? = null,
        /** Display-only tools such as sendFile only: file card metadata attached with the result, persisted for UI rendering and not played back into the model context. */
        val attachments: List<com.aicode.feature.agent.presentation.AgentAttachment> = emptyList(),
        val persisted: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    ) : AgentEvent()

    /** The network request is being retried (an automatic retry triggered by a failure before the first byte). For live UI display only; not persisted. [error] is a summary of the error that triggered the retry. */
    data class Retrying(val attempt: Int, val maxRetries: Int, val error: RetryErrorInfo) : AgentEvent()

    /** Multi-Key automatic switching: the current Key is unavailable, so this request was resent using Key [newIndex]/[total]. For live UI display only; not persisted. */
    data class KeySwitched(val newIndex: Int, val total: Int) : AgentEvent()

    /** Context compaction is in progress. For live UI display only; not persisted. */
    data class CompactionStarted(val estimatedTokens: Int) : AgentEvent()

    /** The context compaction flow has ended (success or failure). For live UI display only; not persisted. */
    object CompactionFinished : AgentEvent()

    data class ContextUsage(val inputTokens: Int, val inputBudget: Int, val estimated: Boolean) : AgentEvent()

    /** Context compaction failed (e.g. the compaction model is unavailable). Carries the failure reason; the UI shows it as an expandable failure card; not persisted. */
    data class CompactionFailed(val reason: String) : AgentEvent()

    /**
     * The whole flow terminated due to an error (e.g. the streaming request was interrupted by the network, or the iteration limit was reached). Unlike [Completed]: it carries an error, and the UI should show the error rather than success.
     * [reasonCode] is the type code given by the server (e.g. Anthropic's refusal / model_context_window_exceeded),
     * for the UI to replace with a localized explanation; when null, [error] is shown directly.
     */
    data class Failed(val error: String, val reasonCode: String? = null) : AgentEvent()

    /** The whole flow has ended. */
    object Completed : AgentEvent()

    /** The mode has switched (triggered by the AI calling planMode); the UI uses this to show the plan review panel and similar. */
    data class ModeChanged(val newMode: AgentMode, val reason: String) : AgentEvent()
}

interface AgentWorkflow {
    /**
     * Runs the Agent loop and pushes progress step by step as an event flow:
     * model reply -> (possible) tool calls -> tool results -> another reply ... until the model no longer calls tools.
     */
    fun executeEvents(
        userRequest: String,
        context: AgentContext,
        tools: List<AgentTool>
    ): Flow<AgentEvent>

    /**
     * Manually triggers context compaction for the specified session (called by the /compress command).
     * Reuses the automatic compaction logic: resolves the currently effective provider and calls ContextCompactor on the history messages.
     * The compaction result is persisted inside ContextCompactor, and compaction progress events are pushed via the [onEvent] callback.
     * @return whether compaction was actually triggered (returns false when the history is not over the threshold, with no changes).
     */
    suspend fun compactSession(sessionId: String, onEvent: suspend (AgentEvent) -> Unit = {}): Boolean

    /**
     * Generates a title for a newly created session (called asynchronously after the first user message).
     * Defaults to following the current chat model; if a dedicated title summarization model is configured and available, it is used instead.
     * Returns null when generation fails or no title can be obtained (the caller keeps the temporary title).
     */
    suspend fun generateTitle(sessionId: String, request: String): String?

    /**
     * Generates a commit message conforming to the Conventional Commits specification from Git diff text.
     * Defaults to using the globally effective AI provider and default model.
     * @param diff the diff text of the changes (`git diff --cached` or the working-tree diff)
     * @return the suggested commit message, or null on failure.
     */
    suspend fun generateCommitMessage(diff: String): String?
}
