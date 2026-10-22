package com.aicode.feature.agent.presentation

import androidx.compose.runtime.Immutable
import com.aicode.feature.agent.domain.model.AgentImage
import com.aicode.feature.agent.domain.provider.RetryErrorInfo
import com.aicode.feature.workspace.domain.FileEntry
import kotlinx.serialization.Serializable

/**
 * A visible node of the drawer file tree (already flattened according to expansion state, carrying [depth] for UI indentation).
 * Expanded in place from the workspace root: only nodes appearing in [FileBrowseState.Success] are currently visible.
 */
@Immutable
data class FileTreeNode(
    val entry: FileEntry,
    /** Absolute container path (e.g. `~/workspace/app/src`); the workspace root when [isRoot]. */
    val path: String,
    /** Indentation level: the root is 0, its direct children are 1, and so on. */
    val depth: Int,
    /** Workspace root node: cannot be renamed/deleted; entries can only be created under it. */
    val isRoot: Boolean,
    /** Whether the directory is expanded; always false for non-directories. */
    val isExpanded: Boolean,
    /** Reading the children failed when this directory was expanded (e.g. insufficient permission); a root read failure goes through [FileBrowseState.Error]. */
    val hasError: Boolean = false,
    /** Matched by the workspace root .gitignore: the UI shows it dimmed in orange (VSCode-style). */
    val ignored: Boolean = false
)

/** File-browse clipboard: holds a single item at a time, awaiting paste after a copy or cut. */
@Immutable
data class BrowseClipboard(
    /** Path of the directory containing the source entry. */
    val sourcePath: String,
    /** Display name of the source entry (for UI hints). */
    val sourceName: String,
    /** Whether this is a cut (the source is deleted after a successful paste). */
    val isCut: Boolean
)

/** Final result state of a single workflow run (used by [AgentUIState.Result]). */
enum class WorkflowStatus {
    SUCCESS, PARTIAL_SUCCESS, FAILED, CANCELLED
}

sealed class AgentUIState {
    object Idle : AgentUIState()
    object Loading : AgentUIState()
    object Streaming : AgentUIState()
    data class Result(val status: WorkflowStatus) : AgentUIState()
    data class Error(val message: String) : AgentUIState()
}

/** File-tree load state for the drawer's "Files" tab. Remote mode goes through SFTP/exec, where reads can fail or be slow, hence the three states. */
sealed interface FileBrowseState {
    data object Loading : FileBrowseState
    data class Success(val nodes: List<FileTreeNode>) : FileBrowseState
    data class Error(val detail: String?) : FileBrowseState
}

/** A single chat-history search hit: session title + message snippet, for display in a drawer result row. */
@Immutable
data class ChatSearchHit(
    val sessionId: String,
    val sessionTitle: String,
    val messageId: String,
    /** Body snippet centred on the matched term (newlines/whitespace collapsed, ellipses added at either end as needed). */
    val snippet: String,
    val timestamp: Long
)

/** Drawer chat-history search state: the current query and the flat list of hits (newest first). */
@Immutable
data class ChatSearchState(
    val query: String = "",
    val loading: Boolean = false,
    val hits: List<ChatSearchHit> = emptyList()
)

/**
 * Event for a completed single LLM request (including the tokens that request consumed).
 */
@Immutable
data class LlmCallEvent(
    val sessionId: String,
    val inputTokens: Int,
    val outputTokens: Int,
    val cachedTokens: Int = 0,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Network request retry state (only for the UI's live "Retrying (N/M)..." hint).
 * [error] is a summary of the error that triggered the retry; the UI uses it to show the concrete cause (e.g. 429/500/network disconnect).
 * Decoupled from [AgentUIState]: retrying is a sub-state of Streaming and does not change the top-level agent state machine.
 */
@Immutable
data class RetryState(val attempt: Int, val maxRetries: Int, val error: RetryErrorInfo? = null)

/**
 * Multi-key automatic switch state (only for the UI's live "Switched to key N/M" hint).
 * Exists only briefly, between the switch and the resend; cleared as soon as content streams again or the turn ends.
 */
@Immutable
data class KeySwitchState(val newIndex: Int, val total: Int)

@Immutable
data class AgentUIMessage(
    val id: String,
    val role: MessageRole,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val attachments: List<AgentAttachment> = emptyList(),
    // TOOL messages only: for rendering; does not take part in context replay.
    val toolName: String? = null,
    // TOOL messages only: the arguments passed to this call (JSON text), used to render the executed command.
    val toolArgs: String? = null,
    val isError: Boolean = false,
    // ASSISTANT messages only: this turn's model reasoning, rendered as a collapsible "Thinking" bubble; null when there is none.
    val reasoning: String? = null,
    // Internal context-compaction anchor: no user bubble is shown; rendered as a compaction divider.
    val isCompactionMarker: Boolean = false,
    // Summary message produced by context compaction: rendered as an expandable compaction-summary card, coloured distinctly from tool calls.
    val isContextSummary: Boolean = false,
    // Context-compaction failure record (TOOL message): rendered as an expandable failure card showing the failure reason.
    val isCompactionFailure: Boolean = false,
    // Background-task completion notice: takes part in the model context but is not shown as a normal user bubble; rendered as a lightweight hint strip.
    val isBackgroundNotification: Boolean = false,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    // ASSISTANT messages only: the number of input tokens in this call that hit the server-side cache; the bubble footer computes the cache hit rate from it.
    val cachedInputTokens: Int = 0
)

@Immutable
@Serializable
data class AgentAttachment(
    val fileName: String,
    val containerPath: String,
    val localPath: String,
    val mimeType: String,
    val sizeBytes: Long,
    val isImage: Boolean
)

enum class MessageRole {
    USER, ASSISTANT, TOOL
}

/**
 * Fixed prefix of background-task completion notices. It is both
 * [com.aicode.feature.agent.domain.notification.AgentNotificationFormatter] the opening text used when generating a notification message, and
 * the UI layer's basis for recognising such messages (so they are not rendered as normal user bubbles). Changing it here requires syncing both sides.
 */
const val BACKGROUND_NOTIFICATION_PREFIX = "[System notification - not user input]"

/**
 * The toolName of a context-compaction failure record TOOL message. It is both the marker written to the database and the UI's basis for recognising the failure card;
 * this message has no paired toolCallId, so [MessagePersistenceUseCase.buildHistory] automatically drops it during replay and it never enters the model context.
 */
const val COMPACTION_FAILURE_TOOL_NAME = "Context compaction"

/**
 * Snapshot of the result of one session message query.
 * - [sessionId]: the session these messages belong to; null means no session has been resolved yet (cold start).
 * - [messages]: the filtered list of messages, ready to render directly.
 * - [loaded]: whether this session's data has been read from the database yet, distinguishing "loading" from "empty session".
 */
@Immutable
data class ChatMessagesState(
    val sessionId: String?,
    val messages: List<AgentUIMessage>,
    val loaded: Boolean,
    val hasMore: Boolean = false,
    val isLoadingMore: Boolean = false
)

/**
 * "No-ink" code points: neither whitespace nor in the format (Cf) / control (Cc) categories, yet they render as zero-width or pure blank.
 * The key case is the Hangul filler family - their Unicode category is Lo (other letters), so **no category-based check
 * can catch them**; they must be listed explicitly. This is the real reason the previous "filter only Cf/Cc" fix failed: some models,
 * in pure tool-call turns, emit filler characters such as U+3164, leaking one empty bubble per tool call.
 */
internal val BLANK_GLYPH_CODE_POINTS: Set<Char> = setOf(
    0x115F, 0x1160, 0x3164, 0xFFA0, // Hangul filler (Lo, zero-width, not catchable by category)
    0x2800,                         // Braille pattern blank (So, pure empty dots)
    0x034F,                         // Combining grapheme joiner CGJ
    0x17B4, 0x17B5,                 // Khmer inherent vowels (zero-width)
    0x2060, 0xFEFF,                 // Word joiner / BOM (Cf, redundant fallback)
    0x200B, 0x200C, 0x200D,         // Zero-width space / ZWNJ / ZWJ (Cf, redundant fallback)
).mapTo(HashSet()) { it.toChar() }

/**
 * Whether the text contains "visible (inked)" content. The test = at least one character that is neither whitespace nor in an invisible category (Cf/Cc/surrogate),
 * and is not in the [BLANK_GLYPH_CODE_POINTS] blocklist either. Much stricter than [CharSequence.isBlank]:
 * the latter treats only whitespace as empty, letting zero-width/filler characters leak out as blank bubbles.
 * This is the single gate for "whether to render an assistant bubble"; persistence normalisation and every rendering/filtering layer share it, so changing this one place takes effect across the whole chain.
 * Note: surrogate pairs (emoji, etc.) and ordinary visible characters are preserved; the zero-width joiner ZWJ counts as empty only for the "is the whole text empty" test,
 * and is never stripped from the displayed text, so emoji ligature sequences are unaffected.
 */
fun CharSequence.hasVisibleContent(): Boolean {
    var i = 0
    while (i < length) {
        val ch = get(i)
        if (!ch.isWhitespace() && ch.category != CharCategory.FORMAT &&
            ch.category != CharCategory.CONTROL && ch !in BLANK_GLYPH_CODE_POINTS
        ) {
            // The surrogate range (the supplementary planes where emoji live) counts as visible only as a valid high/low surrogate pair; lone or broken surrogates still count as empty
            if (ch.category != CharCategory.SURROGATE) return true
            if (Character.isHighSurrogate(ch) && i + 1 < length && Character.isLowSurrogate(get(i + 1))) return true
        }
        i++
    }
    return false
}

/**
 * Live accumulated output of a running tool. Held in memory only, never persisted: overlaid live in that tool message's bubble to show
 * the command's stdout line by line; cleared when the command ends, and the final full result is persisted through the normal path.
 */
@Immutable
data class RunningToolOutput(val messageId: String, val text: String, val toolName: String = "", val toolArgs: String = "")

data class QueuedRequest(
    val id: String,
    val request: String,
    val modelRequest: String = request,
    val currentFile: String?,
    val selectedCode: String?,
    val projectRoot: String,
    val inputImages: List<AgentImage> = emptyList(),
    val inputAttachments: List<AgentAttachment> = emptyList(),
    val isAutoTrigger: Boolean = false
)
