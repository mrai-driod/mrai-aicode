package com.aicode.feature.agent.presentation.component

import android.content.ClipData
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.core.ui.ImageViewerHost
import com.aicode.core.ui.LocalImageViewer
import com.aicode.core.ui.readableContentMaxWidth
import com.aicode.core.ui.rememberImageViewerState
import com.aicode.core.ui.rememberViewerDecodeSpec
import com.aicode.feature.agent.domain.tool.question.UserQuestionAnswer
import com.aicode.feature.agent.presentation.AgentUIMessage
import com.aicode.feature.agent.presentation.AgentUIState
import com.aicode.feature.agent.presentation.AIAgentViewModel
import com.aicode.feature.agent.presentation.MessageRole
import com.aicode.feature.agent.presentation.QueuedRequest
import com.aicode.feature.agent.presentation.hasVisibleContent
import com.aicode.feature.onboarding.domain.OnboardingStep
import com.aicode.feature.onboarding.presentation.onboardingTarget
import com.aicode.feature.settings.data.repository.TodoDisplayPosition
import com.aicode.core.ui.glassBackdrop
import com.aicode.core.ui.rememberChatGlassLayer
import com.aicode.core.ui.rememberGlassLuminance
import com.aicode.feature.settings.presentation.SettingsViewModel
import com.aicode.feature.settings.domain.model.DashboardContext
import com.aicode.feature.settings.domain.model.ProviderDashboardState
import com.aicode.feature.settings.domain.model.modelMetadataKey
import com.aicode.feature.workspace.domain.WorkspacePathMapper
import com.aicode.feature.workspace.presentation.WorkspaceViewModel
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowDown
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch


/**
 * The three states of the streaming tail, used for dispatching in [when] branches.
 *
 * Earlier, [androidx.compose.animation.Crossfade] was used for the fade-in, but Crossfade caches the content sub-composition by targetState
 * — during streaming targetState never changes, so as the text grows content is not called again,
 * causing [StreamingBubble] to receive no later text and stay stuck on the first sentence. So switch to an enum + direct [when] dispatch.
 */
private enum class TailKind { THINKING, STREAMING, COMPACTING, RETRYING, KEY_SWITCHED, NONE }

/** Gap between the floating layer (banner/panel/input box) and the last message. */
private val FLOATING_LAYER_GAP_DP = 8.dp

/** Tolerance (px) for calibration driven by the content bottom: only calibrate downward when the last content's bottom exceeds the safe area by more than this value, avoiding sub-pixel jitter. */
private const val AUTO_SCROLL_TOLERANCE_PX = 2

/** How long (ms) to keep calibrating frame by frame after the AI finishes: async md parsing may still change the height, so do not stop the moment it finishes. */
private const val CALIBRATE_TAIL_MS = 1_200L

/** Diameter of the scroll-to-bottom button (dp). */
private const val SCROLL_TO_BOTTOM_BTN_SIZE = 34

/** Stagger interval (ms) for consecutive new messages entering, and its overall cap: when many cards are inserted at once, the last one must not be made to wait several seconds. */
private const val MESSAGE_ENTRY_STAGGER_MS = 90L
private const val MESSAGE_ENTRY_MAX_STAGGER_MS = 360L

/** How long (ms) to delay before showing the loading hint when messages are not ready: reading the local database is fast, so showing it immediately would just flicker. */
private const val MESSAGES_LOADING_HINT_DELAY_MS = 220L

/**
 * Rendering long messages by splitting them into chunks:
 *
 * If an over-long assistant body (e.g. a restatement of a long document) is stuffed into a single LazyColumn item, once it stretches several screens deep the gesture hit area
 * falls outside the list's visible area, and horizontal scrolling / long-press copying / clicking on tables deep inside all stop working (the root cause of the broken deep interactions in the original version).
 * A height-limited + internally scrolling single-window approach would instead create a disconnected, separate-block break between the window and the list world.
 *
 * The chunking idea is: split into multiple list items of bounded height, each a normal bubble (reasoning only in the first chunk,
 * the action row only in the last chunk, adjacent chunks joined seamlessly with zero spacing), with a single list scroll axis — the world is fully connected top to bottom, and deep
 * interactions (horizontal scrolling / copying / clicking on tables, etc.) all work again because each item's height is bounded.
 *
 * If the body length does not exceed the threshold, it is not chunked and behaves exactly like a normal message.
 *
 * internal (rather than private) so that use cases of [buildChatItems] can assert the render items; see ToolGroupExpansionTest.
 */
internal data class ChatRenderItem(
    val message: AgentUIMessage,
    val key: String,
    val contentType: String,
    val slice: String? = null,
    val isChunkHeader: Boolean = true,
    val isChunkFooter: Boolean = true,
    /**
     * Non-null means this is a "consecutive tool calls" group header item. Members are each still independent items (only generated when that group is expanded),
     * so whether expanded or not, every item's height is bounded — this is the precondition for deep interactions (table horizontal scrolling, long-press copying) not breaking.
     */
    val toolGroup: List<AgentUIMessage>? = null,
    /** Whether the group is currently expanded (already includes the result of any user manual override). */
    val groupExpanded: Boolean = true,
    /** Non-null = a whole-turn collapse header item ("Running / Completed in Xs"). */
    val turnHeader: TurnHeaderState? = null,
    /** The process items carried by the turn header (reasoning / tool groups / intermediate body text): when the whole turn is expanded, they expand smoothly together with the collapse header. */
    val turnProcess: List<ChatRenderItem> = emptyList(),
    /** Whether to render the message's own reasoning block; result blocks pass false after extracting the reasoning as a process item. */
    val reasoningVisible: Boolean = true,
)

/** contentType of a tool-call group header item. */
private const val TOOL_GROUP_CONTENT_TYPE = "tool-group"

/** contentType of a whole-turn collapse header item. */
private const val TURN_HEADER_CONTENT_TYPE = "turn-header"

/** contentType of the last assistant reasoning of the turn, extracted as a standalone process item when the whole turn is expanded. */
private const val REASONING_CONTENT_TYPE = "reasoning"

/** First user message id of a turn -> the persistence key for the whole-turn collapse (same convention as ViewModel.turnExpansionOverrides). */
internal fun turnKeyOf(firstMessageId: String): String = "turn:$firstMessageId"

/**
 * Render state of a whole-turn task collapse header item. Non-null means this is a "Running / Completed" header row.
 *
 * [endMessageId] is the id of the last assistant message of the turn, used to look up this turn's duration (see [computeTaskDurations], same convention).
 */
internal data class TurnHeaderState(
    val key: String,
    val endMessageId: String?,
    /** Whether this turn is still running (the last turn and the agent is busy). */
    val running: Boolean,
    /** Expanded state (already includes the result of any user manual override). */
    val expanded: Boolean,
)

/**
 * Left indent of member rows when a group is expanded: the leading elements of the group header and the member rows were originally aligned exactly the same on the left (the same 16dp icon cell + the same spacing),
 * making the hierarchy invisible; indent each member row by one level, so the group header is the parent and the member rows are the children, and the hierarchy is clear at a glance.
 * Fixed at 16dp (not [Spacing.lg]: under compact density it is only 14dp, so the indent would vary with the screen form).
 */
private val ToolGroupMemberPadding = PaddingValues(start = 16.dp)

/**
 * Whether this message takes part in "consecutive tool call" grouping.
 *
 * Context compaction failures/summaries and background notifications — these TOOL messages each have dedicated rendering branches (see the early returns in [AgentMessageItem]),
 * so if mixed into a group they would be treated as ordinary tool rows, hence they are excluded as well; ordinary messages (user/assistant) naturally break a group.
 *
 * **Tools with attachments (`sendFile` / `generateImage`) are not grouped either**: the file row they produce is the result itself,
 * and folding it into "N tool calls" and collapsing the group by default would be equivalent to hiding the sent file; only as a top-level item does it stay always visible.
 */
private fun AgentUIMessage.isGroupableTool(): Boolean =
    role == MessageRole.TOOL && !isCompactionFailure && !isContextSummary &&
        !isCompactionMarker && !isBackgroundNotification && attachments.isEmpty()

/** Group identifier: takes the id of the first message in the group, ensuring the item key of a batch of tool calls stays stable as more are appended (no rebuild causing the viewport to jump). */
private fun toolGroupKey(first: AgentUIMessage): String = "toolgroup:${first.id}"

/**
 * Whether this assistant message renders the metadata row below the bubble — i.e. whether it can host that row of "Copy / More" buttons.
 *
 * Compaction markers, context summaries, compaction failures and background notification bars each have a dedicated rendering branch in [AgentMessageItem] and return
 * early, without producing this row; the same applies to assistant messages with reasoning only and no body (there is no body to copy). Attaching the "latest one" buttons
 * to them would leave the whole conversation with not a single button.
 */
private fun AgentUIMessage.rendersActionRow(): Boolean =
    role == MessageRole.ASSISTANT &&
        !isCompactionMarker && !isContextSummary && !isCompactionFailure && !isBackgroundNotification &&
        (content.hasVisibleContent() || attachments.isNotEmpty())

/**
 * The items a single message (not a tool group) occupies in one render:
 * an over-long assistant body is split into multiple bounded chunks; all other messages are 1:1.
 */
private fun messageRenderItems(message: AgentUIMessage): List<ChatRenderItem> {
    val canSplit = message.role == MessageRole.ASSISTANT &&
        !message.isCompactionMarker &&
        !message.isContextSummary &&
        !message.isCompactionFailure &&
        !message.isBackgroundNotification &&
        message.content.length > CHUNK_SPLIT_THRESHOLD_CHARS
    if (!canSplit) {
        return listOf(
            ChatRenderItem(message = message, key = message.id, contentType = message.role.name)
        )
    }
    val slices = splitLongContent(message.content)
    if (slices.size <= 1) {
        // Only one chunk was split out (e.g. an over-long single paragraph with no blank lines): treat it as a normal message.
        return listOf(
            ChatRenderItem(message = message, key = message.id, contentType = message.role.name)
        )
    }
    return slices.mapIndexed { idx, slice ->
        ChatRenderItem(
            message = message,
            key = "${message.id}#chunk$idx",
            contentType = "assistant-chunk",
            slice = slice,
            isChunkHeader = idx == 0,
            isChunkFooter = idx == slices.lastIndex,
        )
    }
}

/**
 * Message list -> LazyColumn item list.
 *
 * Three things: **whole-turn task collapsing**, long-text chunking and **consecutive tool call grouping**.
 *
 * First split into turns by "user message -> before the next user message" (see [splitChatTurns]): each turn produces in order "user message ->
 * turn header (carrying the process items, only when the turn has process items or is running) -> always-visible items and the result". The process items hang inside the turn header item, and when the whole turn is expanded they
 * expand smoothly together with the collapse header; always-visible items such as compaction summaries/background notifications/tools with attachments do not take part in the collapsing.
 * Result = the last ordinary assistant message in the turn with visible body text (its reasoning is extracted as a process item).
 *
 * Turn header expanded state: when there is no manual record, "the last turn and the agent is busy" is expanded by default and everything else is collapsed by default ([activeTurnKey]);
 * once the user has tapped in [turnOverrides], their choice wins, and automatically collapsing when the task completes will not override it.
 *
 * Tool call groups are still **collapsed by default**, honouring only the manual choice in [groupOverrides].
 */
internal fun buildChatItems(
    messages: List<AgentUIMessage>,
    groupOverrides: Map<String, Boolean>,
    turnOverrides: Map<String, Boolean> = emptyMap(),
    activeTurnKey: String? = null,
): List<ChatRenderItem> {
    val items = ArrayList<ChatRenderItem>(messages.size)
    val (leading, turns) = splitChatTurns(messages)
    // Loose messages before the first turn (assistant/tool output appearing at the very start of a conversation, rare) are not collapsed and stay always visible as-is.
    leading.forEach { items += messageRenderItems(it) }
    for (turn in turns) {
        items += messageRenderItems(turn.userMessage)
        val endAssistant = turn.messages.lastOrNull {
            it.role == MessageRole.ASSISTANT && !it.isPersistentInTurn()
        }
        val running = turn.key == activeTurnKey
        val resultId = if (running) null else turn.messages.lastOrNull { it.isResultCandidate() }?.id
        // The process items expand together inside the turn header item (ensuring the expansion animation is an overall height change); always-visible items and the result are separate items.
        val process = buildTurnProcessItems(turn.messages, resultId, groupOverrides)
        val expanded = turnOverrides[turn.key] ?: running
        if (process.isNotEmpty() || running) {
            items += ChatRenderItem(
                message = turn.userMessage,
                key = "${turn.key}#header",
                contentType = TURN_HEADER_CONTENT_TYPE,
                turnHeader = TurnHeaderState(
                    key = turn.key,
                    endMessageId = endAssistant?.id,
                    running = running,
                    expanded = expanded,
                ),
                turnProcess = process,
            )
        }
        items += buildTurnPersistentItems(turn.messages, resultId)
    }
    return items
}

/** One turn of a task: the first user message + all the following messages (assistant / tool / compaction cards, etc.). */
private class ChatTurn(
    val key: String,
    val userMessage: AgentUIMessage,
    val messages: List<AgentUIMessage>,
)

/**
 * Split into turns by "user message". Returns (loose messages before the first turn, list of turns).
 *
 * A background notification may have the USER role, but it is a system-injected hint bar rather than user input, so it is not a turn start (it belongs to the current turn's content).
 */
private fun splitChatTurns(messages: List<AgentUIMessage>): Pair<List<AgentUIMessage>, List<ChatTurn>> {
    val leading = ArrayList<AgentUIMessage>()
    val turns = ArrayList<ChatTurn>()
    var user: AgentUIMessage? = null
    var body: MutableList<AgentUIMessage>? = null
    for (message in messages) {
        if (message.role == MessageRole.USER && !message.isBackgroundNotification) {
            user?.let { turns += ChatTurn(turnKeyOf(it.id), it, body?.toList().orEmpty()) }
            user = message
            body = ArrayList()
        } else {
            if (body == null) leading += message else body.add(message)
        }
    }
    user?.let { turns += ChatTurn(turnKeyOf(it.id), it, body?.toList().orEmpty()) }
    return leading to turns
}

/** Whether this message is "always visible" in the whole-turn collapse — it does not take part in process collapsing and is shown whether the turn is expanded or not. */
private fun AgentUIMessage.isPersistentInTurn(): Boolean =
    isCompactionMarker || isContextSummary || isCompactionFailure || isBackgroundNotification ||
        (role == MessageRole.TOOL && attachments.isNotEmpty())

/** Whether it can be a turn's "result": the last ordinary assistant message with visible body text. */
private fun AgentUIMessage.isResultCandidate(): Boolean =
    role == MessageRole.ASSISTANT && !isPersistentInTurn() && content.hasVisibleContent()

/**
 * The "process" items within a turn (keeping the original order): reasoning, consecutive tool call groups and intermediate assistant body text. They hang inside the turn header item and expand together.
 *
 * The result message's reasoning belongs to the process (extracted as one [REASONING_CONTENT_TYPE] item); compaction summaries/background notifications/tools with attachments and other
 * always-visible items do not enter the process and are produced separately by [buildTurnPersistentItems].
 */
private fun buildTurnProcessItems(
    messages: List<AgentUIMessage>,
    resultId: String?,
    groupOverrides: Map<String, Boolean>,
): List<ChatRenderItem> {
    val out = ArrayList<ChatRenderItem>(messages.size)
    var i = 0
    while (i < messages.size) {
        val message = messages[i]
        if (message.isGroupableTool()) {
            var j = i
            while (j < messages.size && messages[j].isGroupableTool()) j++
            val members = messages.subList(i, j).toList()
            // With only one tool, do not fold it into "1 tool call"; display it directly as an ordinary tool row.
            if (members.size == 1) {
                out += messageRenderItems(members.first())
            } else {
                val key = toolGroupKey(members.first())
                out += ChatRenderItem(
                    message = members.first(),
                    key = key,
                    contentType = TOOL_GROUP_CONTENT_TYPE,
                    toolGroup = members,
                    groupExpanded = groupOverrides[key] == true,
                )
            }
            i = j
            continue
        }
        when {
            message.isPersistentInTurn() -> Unit
            message.id == resultId -> {
                if (!message.reasoning.isNullOrEmpty()) {
                    out += ChatRenderItem(
                        message = message,
                        key = "${message.id}#reasoning",
                        contentType = REASONING_CONTENT_TYPE,
                    )
                }
            }
            else -> out += messageRenderItems(message)
        }
        i++
    }
    return out
}

/** The "always visible" items within a turn (keeping the original order): the result body (chunked, without re-rendering the reasoning) and compaction cards/notifications/tools with attachments. */
private fun buildTurnPersistentItems(
    messages: List<AgentUIMessage>,
    resultId: String?,
): List<ChatRenderItem> {
    val out = ArrayList<ChatRenderItem>()
    for (message in messages) {
        if (message.id == resultId) {
            messageRenderItems(message).forEach { out += it.copy(reasoningVisible = false) }
        } else if (message.isPersistentInTurn()) {
            out += messageRenderItems(message)
        }
    }
    return out
}

/** Assistant body text longer than this length (characters) is split into multiple bounded chunks. */
private const val CHUNK_SPLIT_THRESHOLD_CHARS = 2_000

/** Target character budget per chunk: the body is packed by markdown blocks; when a single block exceeds the budget (e.g. a huge table),
 *  it is hard-cut by line as a fallback, ensuring any chunk's height is bounded (approx. 0.5-0.7 of a screen). */
private const val CHUNK_BUDGET_CHARS = 1_200

/**
 * Splitting long body text into chunks: identify three kinds of markdown blocks line by line — code fences (whole block), tables (consecutive | lines,
 * the whole table kept intact) and ordinary paragraphs (separated by blank lines) — then greedily pack them into chunks by character budget.
 * A single block over budget is split into multiple blocks by line; better to break a table than let an item grow unbounded in height.
 */
internal fun splitLongContent(text: String): List<String> {
    val lines = text.lines()
    val rawBlocks = ArrayList<String>()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trimStart()
        when {
            trimmed.startsWith("```") || trimmed.startsWith("~~~") -> {
                val fence = if (trimmed.startsWith("```")) "```" else "~~~"
                val sb = StringBuilder(line)
                var j = i + 1
                while (j < lines.size && !lines[j].trimStart().startsWith(fence)) {
                    sb.append('\n').append(lines[j]); j++
                }
                if (j < lines.size) {
                    sb.append('\n').append(lines[j]); j++
                }
                rawBlocks.add(sb.toString()); i = j
            }
            line.isBlank() -> i++
            trimmed.startsWith("|") -> {
                val sb = StringBuilder(line)
                var j = i + 1
                while (j < lines.size && lines[j].isNotBlank() && lines[j].trimStart().startsWith("|")) {
                    sb.append('\n').append(lines[j]); j++
                }
                rawBlocks.add(sb.toString()); i = j
            }
            else -> {
                val sb = StringBuilder(line)
                var j = i + 1
                while (j < lines.size && lines[j].isNotBlank() &&
                    !lines[j].trimStart().startsWith("```") &&
                    !lines[j].trimStart().startsWith("~~~") &&
                    !lines[j].trimStart().startsWith("|")
                ) {
                    sb.append('\n').append(lines[j]); j++
                }
                rawBlocks.add(sb.toString()); i = j
            }
        }
    }
    if (rawBlocks.isEmpty()) return listOf(text)

    // A single block over budget (e.g. a giant table/giant paragraph) is cut by line into small blocks within budget, as a fallback to guarantee boundedness.
    // Code blocks (``` / ~~~) are indivisible syntactic units and must stay intact; they must never be broken up by line,
    // otherwise the fence closing is broken, later fragments degrade into ordinary body text and lose highlighting and copying, and HTML would even be wrongly cleaned by the preprocessor.
    val blocks = ArrayList<String>()
    for (block in rawBlocks) {
        val isCodeBlock = block.trimStart().let { it.startsWith("```") || it.startsWith("~~~") }
        if (block.length <= CHUNK_BUDGET_CHARS || isCodeBlock) {
            blocks.add(block)
        } else {
            val piece = StringBuilder()
            var weight = 0
            for (ln in block.lines()) {
                if (weight > 0 && weight + 1 + ln.length > CHUNK_BUDGET_CHARS) {
                    blocks.add(piece.toString()); piece.setLength(0); weight = 0
                }
                piece.append(ln).append('\n'); weight += ln.length + 1
            }
            if (piece.isNotBlank()) blocks.add(piece.toString())
        }
    }

    val chunks = ArrayList<String>()
    val cur = StringBuilder()
    var weight = 0
    for (block in blocks) {
        val w = block.length + 2
        if (weight > 0 && weight + w > CHUNK_BUDGET_CHARS) {
            chunks.add(cur.toString()); cur.setLength(0); weight = 0
        }
        // A blank line must be left between blocks: dropping it would make markdown semantics stick together (a paragraph + an immediately following --- would be parsed as a setext heading, inflating the paragraph to heading font size).
        if (weight > 0) cur.append('\n')
        cur.append(block).append('\n'); weight += w
    }
    if (cur.isNotBlank()) chunks.add(cur.toString())
    return chunks
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AIChatPanel(
    viewModel: AIAgentViewModel,
    onNavigateToTerminal: () -> Unit = {},
    onNavigateToGit: () -> Unit = {},
    onNavigateToBrowser: () -> Unit = {},
    settingsViewModel: SettingsViewModel? = null,
    workspaceViewModel: WorkspaceViewModel? = null,
    onOpenDrawer: () -> Unit,
    showMenuButton: Boolean = true,
    /** When the large-screen right pane currently has the terminal / Git open, the corresponding top bar icon is highlighted. */
    terminalActive: Boolean = false,
    gitActive: Boolean = false,
    browserActive: Boolean = false,
    currentFile: String? = null,
    selectedCode: String? = null,
    onboardingStep: OnboardingStep? = null,
    onSelectModelInOnboarding: (() -> Unit)? = null,
    onDismissModelSheetInOnboarding: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val agentState by viewModel.agentState.collectAsStateWithLifecycle()
    val messagesState by viewModel.messagesState.collectAsStateWithLifecycle()
    val messages = messagesState.messages
    val pendingScroll by viewModel.pendingScrollMessage.collectAsStateWithLifecycle()

    val currentSessionId by viewModel.currentSessionId.collectAsStateWithLifecycle()
    // Tool card entry scheduling: only TOOL messages newly appended to the tail during this browsing session are scheduled, fading in one by one with a stagger.
    // When the session changes the scheduler is rebuilt, and the existing messages of the new session do not animate in.
    val entryScheduler = remember(currentSessionId) { MessageEntryScheduler() }
    val messageEntryDelays = remember(messages, entryScheduler) { entryScheduler.schedule(messages) }
    val currentSessionState by viewModel.currentSessionState.collectAsStateWithLifecycle()
    val currentSession = currentSessionState
    val sessionTitle = currentSession?.title?.takeIf { it.isNotBlank() } ?: stringResource(R.string.chat_new_session_btn)
    val sessionInputTokens = currentSession?.totalInputTokens ?: 0
    val sessionOutputTokens = currentSession?.totalOutputTokens ?: 0
    val sessionLastInputTokens = currentSession?.lastInputTokens ?: 0
    val contextUsages by viewModel.contextUsages.collectAsStateWithLifecycle()
    val messagesReady = messagesState.loaded && messagesState.sessionId == currentSessionId
    val runningTool by viewModel.runningTool.collectAsStateWithLifecycle()
    val isCompacting by viewModel.isCompacting.collectAsStateWithLifecycle()
    val retryState by viewModel.retryState.collectAsStateWithLifecycle()
    val keySwitchState by viewModel.keySwitchState.collectAsStateWithLifecycle()
    val streamingText by viewModel.streamingText.collectAsStateWithLifecycle()
    val streamingReasoning by viewModel.streamingReasoning.collectAsStateWithLifecycle()
    val reasoningTiming by viewModel.reasoningTiming.collectAsStateWithLifecycle()
    val preparingTool by viewModel.preparingTool.collectAsStateWithLifecycle()
    // Status text while waiting for the model or a tool to run:
    // 1. If there is a tool currently running (runningTool) or a tool the model is preparing (preparingTool, the tool name arrives first while its arguments are still streaming),
    //    state plainly what is being done ("Reading file", "Editing file", etc., see toolRunningLabelRes);
    // 2. Otherwise (e.g. waiting for the model to emit its first characters, or a pure reasoning gap) fall back to showing "Thinking".
    // derivedStateOf isolation: runningTool is a List updated on every chunk of streaming output; if read directly during composition,
    // every line a tool emits would recompose the entire AIChatPanel (including the ChatHeader/LazyColumn parameter area) in full;
    // wrapped in a layer, a recomposition is only triggered when the "current tool name" actually changes (usually only once or twice per tool call).
    val activeToolName by remember {
        derivedStateOf { runningTool.lastOrNull()?.toolName?.takeIf { it.isNotBlank() } ?: preparingTool }
    }
    val thinkingLabel = activeToolName?.let { stringResource(toolRunningLabelRes(it)) }
        ?: stringResource(R.string.chat_status_thinking)
    val pendingPermission by viewModel.pendingToolPermission.collectAsStateWithLifecycle()
    val pendingPermissionSessionTitle by viewModel.pendingToolPermissionSessionTitle.collectAsStateWithLifecycle()
    val pendingQuestion by viewModel.pendingUserQuestion.collectAsStateWithLifecycle()
    // Collected early: the todo overlay below the title bar needs to collapse in coordination when the plan approval dialog appears (it was originally collected inside the floating layer, too late)
    val planApproval by viewModel.pendingPlanApproval.collectAsStateWithLifecycle()
    val currentTodoItems by viewModel.currentSessionTodoItems.collectAsStateWithLifecycle()
    // Task todo display position: below the title bar / above the message bar (the default, the pre-rework behaviour) / off
    val todoDisplayPosition = settingsViewModel?.todoDisplayPosition?.collectAsStateWithLifecycle()?.value
        ?: TodoDisplayPosition.ABOVE_INPUT_BAR
    val todoBelowTitleBar = todoDisplayPosition == TodoDisplayPosition.BELOW_TITLE_BAR
    val todoAboveInputBar = todoDisplayPosition == TodoDisplayPosition.ABOVE_INPUT_BAR
    // Whether the todo overlay below the title bar is actually visible: decides how much extra height the message list's first screen must reserve for it
    val todoOverlayVisible = todoBelowTitleBar && currentTodoItems.isNotEmpty()
    val queuedRequests by viewModel.queuedRequests.collectAsStateWithLifecycle()
    val targetRewindMessageId by viewModel.targetRewindMessageId.collectAsStateWithLifecycle()
    val providers = (settingsViewModel?.providers?.collectAsStateWithLifecycle()?.value ?: emptyList()).filter { it.isEnabled }
    val modelMetadata = settingsViewModel?.modelMetadata?.collectAsStateWithLifecycle()?.value.orEmpty()
    val sessionProviderModel by viewModel.currentSessionProviderModel.collectAsStateWithLifecycle()
    val defaultProviderId = settingsViewModel?.defaultModelProviderId?.collectAsStateWithLifecycle()?.value ?: ""
    val defaultModelName = settingsViewModel?.defaultModel?.collectAsStateWithLifecycle()?.value ?: ""
    // Fallback for an unbound session: the default model for a new session (remembered after being selected in the empty home session), or null if unset (the UI shows the default icon / leaves the top bar model name empty).
    val defaultFallbackProvider = providers
        .find { it.id == defaultProviderId }
        ?.takeIf { it.hasUsableApiKey }
        ?.let { if (defaultModelName.isNotBlank() && defaultModelName in it.models) it.copy(selectedModel = defaultModelName) else it }
    val activeProvider = run {
        val (boundProviderId, boundModel) = sessionProviderModel
        if (!boundProviderId.isNullOrBlank()) {
            // Consistent with workflow.resolveProviderConfig: a bound provider must be enabled and have an apiKey filled in, otherwise fall back to the default model
            providers.find { it.id == boundProviderId }
                ?.takeIf { it.hasUsableApiKey }
                // When the bound model has been removed from the list the binding is invalid; fall back to the default model (consistent with workflow.resolveProviderConfig)
                ?.takeIf { boundModel.isNullOrBlank() || boundModel in it.models }
                ?.let {
                    if (!boundModel.isNullOrBlank()) it.copy(selectedModel = boundModel) else it
                } ?: defaultFallbackProvider
        } else {
            defaultFallbackProvider
        }
    }
    val currentWorkspace = workspaceViewModel?.current?.collectAsStateWithLifecycle()?.value
    val projectRoot = currentWorkspace?.path ?: ""
    val currentMode by viewModel.currentSessionMode.collectAsStateWithLifecycle()
    val slashCommands by viewModel.slashCommands.collectAsStateWithLifecycle()

    var inputText by remember { mutableStateOf("") }
    val inputDraft by viewModel.inputDraft.collectAsStateWithLifecycle()
    LaunchedEffect(inputDraft) {
        if (inputText != inputDraft) inputText = inputDraft
    }
    // When typing "/" to open the command menu, rescan skills to reflect skills added, removed or changed on disk.
    LaunchedEffect(inputText) {
        if (inputText == "/") viewModel.refreshSlashCommands()
    }
    var pendingAttachments by remember { mutableStateOf<List<PendingUploadAttachment>>(emptyList()) }
    var uploadingCount by remember { mutableStateOf(0) }
    var messageForMenu by remember { mutableStateOf<AgentUIMessage?>(null) }
    var editingMessage by remember { mutableStateOf<AgentUIMessage?>(null) }
    var queuedEditing by remember { mutableStateOf<QueuedRequest?>(null) }
    val listState = rememberLazyListState()
    // Loading hint while messages are not ready: reading the local database usually takes a few tens of milliseconds, so showing it immediately would just flicker; only hint if still not ready after a short wait.
    var showMessagesLoading by remember(currentSessionId) { mutableStateOf(false) }
    LaunchedEffect(currentSessionId, messagesReady) {
        if (messagesReady) {
            showMessagesLoading = false
            return@LaunchedEffect
        }
        delay(MESSAGES_LOADING_HINT_DELAY_MS)
        showMessagesLoading = true
    }
    // Bottom-anchored scroll reserve: before the first frame is measured use a fallback value (about the input box + gap); after measuring the floating layer's height, switch to a dynamic value,
    // so in any form (banner/panel/input box) the last message stops above the floating layer and is not obscured.
    val inputBarBottomReserveDp = 156.dp
    var floatingLayerHeightPx by remember { mutableStateOf(0) }
    val inputBarReservePx = with(LocalDensity.current) {
        (if (floatingLayerHeightPx > 0) floatingLayerHeightPx + FLOATING_LAYER_GAP_DP.toPx()
        else inputBarBottomReserveDp.toPx()).toInt()
    }
    val markdownCache = remember { MarkdownRenderCache() }
    // Manual expanded state of tool calls (group header / single tool row): kept in the ViewModel, so it survives page switches and being recycled out of the viewport.
    // key = the group key (`toolgroup:<id of the first message in the group>`) or a single message id.
    val toolExpansionOverrides = viewModel.toolExpansionOverrides
    // Snapshot: derivedStateOf lazy evaluation — when AIChatPanel recomposes for other state, it does not repeat the toMap allocation;
    // only when the map content actually changes is a new snapshot produced (used as a remember key for the chatItems below, participating in equality comparison).
    val toolGroupOverrideSnapshot by remember { derivedStateOf { toolExpansionOverrides.toMap() } }
    // The manual expanded state for whole-turn collapsing, same kind of snapshot.
    val turnExpansionOverrides = viewModel.turnExpansionOverrides
    val turnOverrideSnapshot by remember { derivedStateOf { turnExpansionOverrides.toMap() } }
    // The expanded state of reasoning cards, same kind of snapshot.
    val reasoningExpansionOverrides = viewModel.reasoningExpansionOverrides
    val reasoningOverrideSnapshot by remember { derivedStateOf { reasoningExpansionOverrides.toMap() } }
    // The set of ids of tools currently running: only let the set content participate in the remember key, avoiding a rebuild of the whole list caused by live output refreshing character by character.
    val runningToolIds by remember { derivedStateOf { runningTool.mapTo(HashSet()) { it.messageId } } }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current

    val isBusy = agentState is AgentUIState.Loading || agentState is AgentUIState.Streaming
    // Chunking + tool grouping: an over-long assistant message expands into multiple bounded items (single scroll axis, continuous appearance),
    // and consecutive tool calls fold into one "N tool calls" group; the order of chatItems is the LazyColumn item order.
    // Lifted here (rather than inside the LazyColumn branch) because the isFarFromBottom "does the layout correspond to the current messages" check
    // needs it: grouping makes the item count != message count + 1, so the message count can no longer be used as the expected value.
    // The last turn with a busy agent = the turn currently running: its collapse header is expanded by default (showing "Running") and collapses automatically when it finishes.
    val activeTurnKey = remember(messages, isBusy) {
        if (!isBusy) {
            null
        } else {
            messages.lastOrNull { it.role == MessageRole.USER && !it.isBackgroundNotification }
                ?.let { turnKeyOf(it.id) }
        }
    }
    val chatItems = remember(messages, toolGroupOverrideSnapshot, turnOverrideSnapshot, activeTurnKey) {
        buildChatItems(
            messages = messages,
            groupOverrides = toolGroupOverrideSnapshot,
            turnOverrides = turnOverrideSnapshot,
            activeTurnKey = activeTurnKey,
        )
    }
    // The total duration of each turn's task (user sends -> this turn's AI finishes) and the token total are both hung below the last assistant bubble of the turn only
    val taskDurations = remember(messages, isBusy) {
        computeTaskDurations(messages, lastTurnFinished = !isBusy)
    }
    val turnUsages = remember(messages, isBusy) {
        computeTurnUsage(messages, lastTurnFinished = !isBusy)
    }
    val reasoningDurations = viewModel.reasoningDurations
    // Approach A: the final reply of every normally completed turn (including replies from all historical turns) gets an action row (copy/more),
    // completely fixing the Bug where relying on an in-memory set caused the action buttons of historical messages to be lost after restarting the App.
    // It is only withheld while the newest turn is generating (isBusy == true), to avoid buttons jumping around mid-generation.
    val actionableMessageIds = remember(messages, isBusy) {
        val (leading, turns) = splitChatTurns(messages)
        val ids = mutableSetOf<String>()
        leading.filter { it.rendersActionRow() }.forEach { ids += it.id }
        for (i in turns.indices) {
            val turn = turns[i]
            val isLastTurn = i == turns.lastIndex
            val turnFinished = !isLastTurn || !isBusy
            if (turnFinished) {
                val resultMsg = turn.messages.lastOrNull { it.isResultCandidate() && it.rendersActionRow() }
                    ?: turn.messages.lastOrNull { it.rendersActionRow() }
                if (resultMsg != null) {
                    ids += resultMsg.id
                }
            }
        }
        ids
    }
    val activeModel = activeProvider?.effectiveModel.orEmpty()
    val activeModelMetadata = activeProvider?.let { modelMetadata[modelMetadataKey(it.id, activeModel)] }
    val canUploadFiles = projectRoot.isNotBlank() && activeModelMetadata?.supportsTools == true
    val canUploadImages = projectRoot.isNotBlank()
    val reasoningEffort by viewModel.currentSessionReasoningEffort.collectAsStateWithLifecycle()

    val providerDashboards by (settingsViewModel?.providerDashboards?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(emptyMap()) })
    val currentDashboardState = activeProvider?.let { providerDashboards[it.id] } ?: ProviderDashboardState.Idle

    // Collapse the panel when the keyboard pops up, to avoid the input box being squeezed
    val imeBottomPx = WindowInsets.ime.getBottom(LocalDensity.current)
    val imeVisible = imeBottomPx > 0

    // Expanded state of the balance/todo panels: when expanded, the overlay panels collapse in coordination, to avoid the input box being pushed up twice
    var dashboardExpanded by rememberSaveable { mutableStateOf(false) }
    var todoExpanded by rememberSaveable { mutableStateOf(false) }
    // ProviderDashboardBar is only rendered for providers that have a panel script (see ChatInputBar). When that bar is absent,
    // dashboardExpanded may be left stuck at true: after a panel was expanded it is unmounted when the provider changes / the script is removed, and the LaunchedEffect
    // reporting chain is broken and cannot reset it; using it directly for forceCollapse would press the permission/question/plan panels into a permanently collapsed state.
    // So overlay the visibility: only fold overlay panels when the panel is currently visible and expanded.
    val dashboardBarVisible = activeProvider?.dashboardScriptPath?.isNotBlank() == true
    val dashboardCollapseActive = (dashboardExpanded && dashboardBarVisible) || (todoExpanded && currentTodoItems.isNotEmpty())

    fun buildDashboardContext(
        lastInput: Int = 0,
        lastOutput: Int = 0,
        lastCached: Int = 0,
        refreshReason: String = ""
    ): DashboardContext {
        val curSessionId = currentSessionId.orEmpty()
        val agentStateStr = when (agentState) {
            is AgentUIState.Idle -> "idle"
            is AgentUIState.Loading -> "loading"
            is AgentUIState.Streaming -> "streaming"
            is AgentUIState.Result -> "result"
            is AgentUIState.Error -> "error"
        }
        // When the most recent token is not passed explicitly (session switch / task completion / manual refresh, etc.), fall back to the last assistant message containing tokens:
        // otherwise the panel's "most recent input/output/cache" would show 0. Refreshes triggered by llm events still use the live values carried by the event (more timely).
        val lastTokenMsg = messages.asReversed().firstOrNull {
            it.role == MessageRole.ASSISTANT && (it.inputTokens > 0 || it.outputTokens > 0)
        }
        val effLastInput = if (lastInput > 0) lastInput else (lastTokenMsg?.inputTokens ?: 0)
        val effLastOutput = if (lastOutput > 0) lastOutput else (lastTokenMsg?.outputTokens ?: 0)
        val effLastCached = if (lastCached > 0) lastCached else (lastTokenMsg?.cachedInputTokens ?: 0)
        return DashboardContext(
            model = activeModel,
            // The panel script runs inside the container, so the workspace path must be the container's view (~/workspace); the host's real path does not exist inside the container
            workspacePath = WorkspacePathMapper.CONTAINER_ROOT,
            workspaceName = if (projectRoot.isNotBlank()) File(projectRoot).name else "",
            sessionId = curSessionId,
            lastInputTokens = effLastInput,
            lastOutputTokens = effLastOutput,
            lastCachedTokens = effLastCached,
            totalInputTokens = currentSession?.totalInputTokens ?: 0,
            totalOutputTokens = currentSession?.totalOutputTokens ?: 0,
            modelContextTokens = activeModelMetadata?.contextTokens ?: 0,
            modelMaxInputTokens = activeModelMetadata?.inputTokens ?: 0,
            modelMaxOutputTokens = activeModelMetadata?.outputTokens ?: 0,
            modelInputCostUsdPerM = activeModelMetadata?.inputCostUsdPerM ?: 0.0,
            modelOutputCostUsdPerM = activeModelMetadata?.outputCostUsdPerM ?: 0.0,
            modelCacheReadCostUsdPerM = activeModelMetadata?.cacheReadCostUsdPerM ?: 0.0,
            modelSupportsTools = activeModelMetadata?.supportsTools ?: false,
            modelSupportsVision = activeModelMetadata?.supportsVision ?: false,
            modelSupportsReasoning = activeModelMetadata?.supportsReasoning ?: false,
            messageCount = messages.size,
            agentState = agentStateStr,
            sessionMode = currentMode.name.lowercase(),
            reasoningEffort = reasoningEffort.apiValue,
            refreshReason = refreshReason
        )
    }

    // Fetch the panel once on first entry, provider switch, script path switch or session switch.
    // On a session switch, wait until currentSession / messages have both landed on the new session (sessionReady) before refreshing:
    // otherwise buildDashboardContext reads a snapshot of the old session from the switch instant, and the panel would show old data or empty tokens.
    val sessionReady = currentSession?.id == currentSessionId && messagesReady
    LaunchedEffect(activeProvider?.id, activeProvider?.dashboardScriptPath, currentSessionId, sessionReady) {
        val provider = activeProvider ?: return@LaunchedEffect
        if (provider.dashboardScriptPath.isBlank()) return@LaunchedEffect
        if (!sessionReady) return@LaunchedEffect
        val context = buildDashboardContext(refreshReason = "session")
        settingsViewModel?.refreshProviderDashboard(provider, context = context, force = true)
    }

    // Each time a single LLM request returns, immediately refresh the panel live with the latest Token and context
    // Note: LaunchedEffect(Unit) only starts once, and the collect closure captures a snapshot of the variables from the first composition;
    // rememberUpdatedState must be used to get the latest values, otherwise the session's cumulative Token/message count, etc. would stay stuck at old values forever.
    val latestBuildDashboardContext by rememberUpdatedState(
        { lastInput: Int, lastOutput: Int, lastCached: Int, refreshReason: String ->
            buildDashboardContext(lastInput, lastOutput, lastCached, refreshReason)
        }
    )
    val latestActiveProvider by rememberUpdatedState(activeProvider)
    LaunchedEffect(Unit) {
        viewModel.llmCallEvents.collect { callEvent ->
            val provider = latestActiveProvider ?: return@collect
            if (provider.dashboardScriptPath.isBlank()) return@collect
            val context = latestBuildDashboardContext(
                callEvent.inputTokens,
                callEvent.outputTokens,
                callEvent.cachedTokens,
                "llm"
            )
            settingsViewModel?.refreshProviderDashboard(provider, context = context, force = true)
        }
    }

    // Refresh the panel after an AI turn's task completes: let the script get the final agentState (result/idle/error),
    // otherwise the panel state would stay stuck at "Generating". busy->non-busy happens only once per turn (setting Idle after Result is
    // non-busy->non-busy and will not re-trigger), so the refresh triggered here lands exactly on the final state.
    var lastAgentStateForPanel by remember { mutableStateOf<AgentUIState?>(null) }
    LaunchedEffect(agentState) {
        val prev = lastAgentStateForPanel
        lastAgentStateForPanel = agentState
        val wasBusy = prev is AgentUIState.Loading || prev is AgentUIState.Streaming
        val nowDone = agentState !is AgentUIState.Loading && agentState !is AgentUIState.Streaming
        if (wasBusy && nowDone) {
            val provider = latestActiveProvider ?: return@LaunchedEffect
            if (provider.dashboardScriptPath.isBlank()) return@LaunchedEffect
            val context = latestBuildDashboardContext(0, 0, 0, "done")
            settingsViewModel?.refreshProviderDashboard(provider, context = context, force = true)
        }
    }

    LaunchedEffect(activeProvider?.type, activeModel) {
        val provider = activeProvider ?: return@LaunchedEffect
        if (activeModel.isNotBlank()) {
            settingsViewModel?.resolveModelMetadata(provider.id, provider.type, listOf(activeModel))
        }
    }

    // The home model selection panel reuses ModelSelectionSheet, and the capability Tags depend on the full model metadata;
    // resolving only the currently active model would leave models that were never selected without Tags (the settings page already loads them all when entered; the home page fills the gap).
    // providers load asynchronously and are empty at first composition, so wait until non-empty before resolving (later provider changes also trigger this; the cost is low with caching).
    LaunchedEffect(providers) {
        if (providers.isNotEmpty()) {
            settingsViewModel?.loadAllModelMetadata()
        }
    }

    fun removePendingAttachment(index: Int) {
        pendingAttachments = pendingAttachments.filterIndexed { i, _ -> i != index }
    }

    fun handlePickedAttachments(uris: List<Uri>, images: Boolean) {
        if (uris.isEmpty()) return
        if (projectRoot.isBlank()) {
            Toast.makeText(context, emptyWorkspaceMessage(context), Toast.LENGTH_SHORT).show()
            return
        }
        if (!hasAttachmentSlots(pendingAttachments.size)) {
            Toast.makeText(context, maxAttachmentMessage(context, MAX_PENDING_ATTACHMENTS), Toast.LENGTH_SHORT).show()
            return
        }
        val selected = selectedAttachments(uris, pendingAttachments.size)
        scope.launch {
            var successCount = 0
            val failures = mutableListOf<String>()
            uploadingCount = selected.size
            try {
                selected.forEach { uri ->
                    runCatching {
                        copyUriToWorkspace(context, uri, viewModel.fileAccess, includeImageData = images)
                    }.onSuccess { uploaded ->
                        pendingAttachments = pendingAttachments + uploaded.toPendingAttachment()
                        successCount += 1
                    }.onFailure { error ->
                        failures += (error.message ?: uploadFallbackError(context))
                    }
                }
            } finally {
                uploadingCount = 0
            }
            // Result hint: if everything failed, show the first error; if some files were truncated by the cap or uploads failed, use the partial text; if all succeeded, use the success text.
            val skipped = uris.size - selected.size
            when {
                successCount == 0 && failures.isNotEmpty() ->
                    Toast.makeText(context, failures.first(), Toast.LENGTH_LONG).show()
                skipped > 0 || failures.isNotEmpty() ->
                    Toast.makeText(context, partialUploadMessage(context, successCount), Toast.LENGTH_LONG).show()
                else ->
                    Toast.makeText(context, uploadSuccessMessage(context, successCount), Toast.LENGTH_SHORT).show()
            }
        }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        handlePickedAttachments(uris, images = false)
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        handlePickedAttachments(uris, images = true)
    }

    // Taking a photo: output to a temporary file in cache (a FileProvider-authorised uri), then handle it as an image attachment once taken.
    var cameraPhotoUri by remember { mutableStateOf<Uri?>(null) }
    val takePictureLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val uri = cameraPhotoUri
        cameraPhotoUri = null
        if (success && uri != null) {
            handlePickedAttachments(listOf(uri), images = true)
        }
    }
    fun takePhoto() {
        val photoFile = File(context.cacheDir, "camera_${System.currentTimeMillis()}.jpg")
        val uri = runCatching {
            androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                photoFile
            )
        }.getOrNull()
        if (uri == null) {
            Toast.makeText(context, context.getString(R.string.chat_camera_file_failed), Toast.LENGTH_SHORT).show()
            return
        }
        cameraPhotoUri = uri
        takePictureLauncher.launch(uri)
    }

    // The state computation for retaining the streaming text/reasoning buffer, the settled-in-database check (isAssistantSettled), the typewriter advance and the tail rendering,
    // is all sunk into the LazyColumn tail item (key="__active__") to run there:
    // streamingText / streamingReasoning get a new value on every output chunk; if read during composition of this function body,
    // every emission from the model would recompose the entire AIChatPanel (including ChatHeader, the LazyColumn parameter area, and the registration and comparison of all itemContents)
    // in full — this is the main amplifier of frame drops during streaming output. After sinking it, the invalidation scope narrows to the tail
    // item's single itemContent; the typewriter progress is backed by its internal rememberSaveable, so it can resume after scrolling out of the viewport and back.
    // (The snapshotFlow { Triple(streamingText?.length, ...) } in the auto-scroll following does not subscribe to a composition scope,
    // so it is a safe read and is kept where it was.)

    // Auto-scroll following
    // Both states must be saveable: opening the editor / terminal / Git / settings in a narrow window are all full-screen routes, and the whole chat page composition tree
    // is disposed. With a plain remember, on return positionedSession would go back to null (mistaken for a session switch and re-anchored to the bottom) and
    // followBottom would go back to true (the calibration loop would pull the restored position back to the bottom), and the browsing history position would be lost.
    var positionedSession by rememberSaveable { mutableStateOf<String?>(null) }
    var followBottom by rememberSaveable { mutableStateOf(true) }
    var userScrollGesture by remember { mutableStateOf(false) }
    var pointerDown by remember { mutableStateOf(false) }
    var nestedFling by remember { mutableStateOf(false) }
    val userScrollConnection = remember(listState) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y != 0f) {
                    userScrollGesture = true
                    followBottom = false
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                nestedFling = true
                return Velocity.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                nestedFling = false
                return Velocity.Zero
            }
        }
    }

    // The key must include inputBarReservePx: the closure captures the value at creation time, and a remember without a key would make
    // the check stay forever on the fallback reserve of the first frame (156dp), still computing the safe area from the old value after a panel expansion raises the floating layer.
    val isAtBottom by remember(inputBarReservePx) {
        derivedStateOf {
            val layout = listState.layoutInfo
            // When the list has not been measured yet (first frame, or LazyColumn not mounted while messages are not ready) layoutInfo is empty and
            // canScrollForward is always false. At that point it must not be judged "at the bottom" — otherwise the first frame when returning from another page would
            // restore following and pull the scroll position just restored by listState back to the bottom.
            val lastVisible = layout.visibleItemsInfo.lastOrNull()
                ?: return@derivedStateOf false
            if (!listState.canScrollForward) return@derivedStateOf true
            val lastIndex = layout.totalItemsCount - 1
            // At the bottom = the last content's bottom stops near the safe area (the floating layer's top edge), not the viewport bottom:
            // the check is stricter, avoiding the user dragging a short way and still being judged "at the bottom", restoring following and being pulled straight back.
            val safeBottom = layout.viewportEndOffset - inputBarReservePx
            lastVisible.index >= lastIndex &&
                (lastVisible.offset + lastVisible.size) <= safeBottom + AUTO_SCROLL_TOLERANCE_PX
        }
    }

    LaunchedEffect(pointerDown, userScrollGesture, nestedFling, listState.isScrollInProgress) {
        if (!pointerDown && userScrollGesture && !nestedFling && !listState.isScrollInProgress) {
            delay(150)
            userScrollGesture = false
            followBottom = isAtBottom
        }
    }

    // Threshold for showing the back-to-bottom button: using only "not at the bottom" would make the one or two frames of streaming growth (before the calibration loop pulls the viewport back)
    // also count as away from the bottom, making the button flicker along with it. Require being more than half a viewport away from the bottom, so it only appears when the user really scrolls up to read history.
    // messagesReady / messages.isEmpty() and totalItemsCount are two sides of the same coin: layoutInfo is
    // the product of the "most recent layout pass"; after the LazyColumn is unmounted (empty session WelcomeState, loading placeholder) it is not automatically
    // cleared — after browsing history in an old session and switching to an empty session, the leftover layout would leave the button hanging over the new session. totalItemsCount further intercepts
    // the frame where "the new list has not yet been re-measured against the current messages" (before layout starts, layoutInfo is still the old session's).
    val isFarFromBottom by remember(inputBarReservePx, messagesReady, messages.size, chatItems.size) {
        derivedStateOf {
            if (!messagesReady || messages.isEmpty()) return@derivedStateOf false
            if (!listState.canScrollForward) return@derivedStateOf false
            val layout = listState.layoutInfo
            val lastVisible = layout.visibleItemsInfo.lastOrNull()
                ?: return@derivedStateOf false
            if (layout.totalItemsCount != chatItems.size + 1) return@derivedStateOf false
            if (lastVisible.index < layout.totalItemsCount - 1) return@derivedStateOf true
            val safeBottom = layout.viewportEndOffset - inputBarReservePx
            (lastVisible.offset + lastVisible.size) - safeBottom > layout.viewportEndOffset / 2
        }
    }

    // The user starts dragging: stop following. On release, if already at the bottom, resume following.
    // Extra: during streaming output the content keeps growing, so the user may be "pushed" away from the bottom after releasing —
    // use snapshotFlow { isAtBottom } to monitor continuously, resuming following as soon as they scroll to the bottom,
    // satisfying "scroll to the bottom during streaming and automatically keep following".
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> followBottom = false
                is DragInteraction.Stop, is DragInteraction.Cancel -> {
                    // Delay the check for resuming following after release: wait for the inertial scrolling to settle,
                    // avoiding "released at the bottom but inertially scrolling up" being pulled straight back.
                    scope.launch {
                        delay(150)
                        if (!userScrollGesture) followBottom = isAtBottom
                    }
                }
            }
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { isAtBottom && !userScrollGesture }.collect { atBottom ->
            if (atBottom) followBottom = true
        }
    }

    // Bottom-anchored positioning (sending a message, switching sessions): scroll straight to the anchor — the target offset of scrollToItem makes
    // the last item's bottom stop exactly above the floating layer (contentPadding reserves the reserve; scrolling to that position is the list's
    // maximum scrollable bottom, with no need to rely on animation or gradual alignment).
    val snapToBottom: suspend () -> Unit = {
        val lastIndex = listState.layoutInfo.totalItemsCount - 1
        if (lastIndex >= 0) {
            // Scroll to the list's maximum scrollable bottom: scrollToItem's offset is clamped to maxScroll, and
            // the last item's bottom stops exactly at the contentPadding bottom (= the reserve for the floating layer's top edge), with no need to compute item heights by hand.
            listState.scrollToItem(lastIndex, Int.MAX_VALUE)
        }
    }

    // Anchor-based standing calibration loop: the anchor = the last content's bottom stopping exactly at the floating layer's (input box's) top edge.
    // scrollToItem(last item, Int.MAX_VALUE) is clamped by LazyColumn to the maximum scrollable bottom
    // (guaranteed by the contentPadding bottom reserve), i.e. the last item's bottom stops at the floating layer's top edge;
    // this holds mathematically at all times — with enough messages, the last message never falls below the input box,
    // and it does not depend on the height assumption "last visible item == last item" (a height jump will not compute the wrong target either).
    // Check the last visible item every frame: when the last content is pushed down by growth (its bottom past the safe area) or content is pushed out below the viewport
    // (the last visible item is not the last item, i.e. tracking is lost), scroll back to the anchor; height jumps from async md parsing will also be
    // detected on the next frame, with no misalignment between signal and rendering.
    // Calibrate only downward: when the content gets shorter (streaming ends, collapsing) keep the current position, avoiding "scrolling back" and tug-of-war.
    // The reserve is passed via State: the lambda below is created only once, and directly capturing a local Int would keep using the first frame's fallback value forever.
    val reservePxState = rememberUpdatedState(inputBarReservePx)
    val busyState = rememberUpdatedState(isBusy)
    val calibrateToAnchor: suspend () -> Unit = remember(listState) {
        {
            // No downward scroll room (the content does not fill the screen or has already scrolled to the anchor): the last content is necessarily above the safe area, so no calibration is needed.
            if (followBottom && !userScrollGesture && listState.canScrollForward) {
                val layout = listState.layoutInfo
                val lastIndex = layout.totalItemsCount - 1
                if (lastIndex >= 0) {
                    val lastVisible = layout.visibleItemsInfo.lastOrNull()
                    val safeBottom = layout.viewportEndOffset - reservePxState.value
                    // The last item is pushed below the viewport (tracking lost) or the last content's bottom passes the safe area: scroll back to the anchor.
                    // When the user is browsing elsewhere followBottom is already false, so this is not reached.
                    val lost = lastVisible == null || lastVisible.index < lastIndex
                    val pushedDown = lastVisible != null &&
                        lastVisible.offset + lastVisible.size > safeBottom + AUTO_SCROLL_TOLERANCE_PX
                    if (lost || pushedDown) listState.scrollToItem(lastIndex, Int.MAX_VALUE)
                }
            }
        }
    }

    // Calibrate frame by frame only while "following and the content may still be moving". Originally it was an unconditional while(true), and with followBottom
    // false it only continued while the frame callback stayed registered, which meant the main thread woke every frame the whole time (draining power even when idle).
    LaunchedEffect(listState, messagesReady) {
        if (!messagesReady) return@LaunchedEffect
        snapshotFlow { followBottom && (busyState.value || listState.isScrollInProgress) }
            .collectLatest { active ->
                if (active) {
                    while (true) {
                        withFrameNanos { }
                        calibrateToAnchor()
                    }
                } else {
                    // At the moment of finishing, the content may not yet be stable (async md parsing often lands later), so keep covering for a short while before stopping.
                    val deadline = System.nanoTime() + CALIBRATE_TAIL_MS * 1_000_000L
                    while (System.nanoTime() < deadline) {
                        withFrameNanos { }
                        calibrateToAnchor()
                    }
                }
            }
    }

    // Content change signal bypass: when the text/reasoning/message count changes, calibrate once immediately without waiting for the next frame —
    // complementary to the standing calibration loop, covering the gaps with "no animation frame" and eliminating tracking-loss windows.
    LaunchedEffect(listState, messagesReady) {
        if (!messagesReady) return@LaunchedEffect
        snapshotFlow {
            Triple(streamingText?.length, streamingReasoning?.length, messages.size)
        }.collect { calibrateToAnchor() }
    }

    val sendMessage: () -> Unit = {
        val text = inputText.trim()
        if (text.isNotEmpty() || pendingAttachments.isNotEmpty()) {
            val attachments = pendingAttachments
            val modelSupportsVision = activeModelMetadata?.supportsVision == true
            val promptAttachments = if (modelSupportsVision) attachments.filterNot { it.isImage } else attachments
            val modelRequest = appendAttachmentsToRequest(context, text, promptAttachments)
            val images = if (modelSupportsVision) attachments.toAgentImages() else emptyList()
            // Go through the queue uniformly: queue while the AI is busy (sent automatically after this turn ends), send directly when idle.
            // Slash commands are dispatched inside the ViewModel (before the agent workflow), so there is no need to distinguish them here.
            viewModel.enqueueAgentRequest(
                request = text,
                modelRequest = modelRequest,
                currentFile = currentFile,
                selectedCode = selectedCode,
                projectRoot = projectRoot,
                inputImages = images,
                inputAttachments = attachments.toAgentAttachments()
            )
            inputText = ""
            viewModel.clearInputDraft()
            pendingAttachments = emptyList()
            followBottom = true
            scope.launch {
                kotlinx.coroutines.delay(0)
                snapToBottom()
            }
        }
    }

    // Session switch / opening a search hit: prefer positioning to the target message, otherwise anchor to the bottom and resume following.
    LaunchedEffect(currentSessionId, messagesReady, pendingScroll, chatItems) {
        if (!messagesReady) return@LaunchedEffect
        val pending = pendingScroll
        if (pending != null && pending.first == currentSessionId) {
            val index = chatItems.indexOfFirst { it.message.id == pending.second }
            if (index >= 0) {
                // Wait one frame for the LazyColumn to recompose for the new session, then jump instantly to the target message.
                withFrameNanos { }
                followBottom = false
                listState.scrollToItem(index)
                positionedSession = currentSessionId
                viewModel.consumePendingScroll()
            } else if (!messagesState.hasMore) {
                // All messages loaded and still not found (the message may have been deleted): give up, to avoid getting stuck in the to-be-positioned state.
                viewModel.consumePendingScroll()
            }
            return@LaunchedEffect
        }
        if (positionedSession != currentSessionId) {
            // Wait one frame for the LazyColumn to recompose for the new session, then scroll to the anchor (maxScroll).
            withFrameNanos { }
            snapToBottom()
            positionedSession = currentSessionId
            followBottom = true
        }
    }

    // After expanding/collapsing an item (tool card, tool group header) do **no active scrolling at all**: expand in place, collapse in place,
    // the viewport does not move at all; only pause bottom following (when the user scrolls back to the bottom, the isAtBottom monitor automatically resumes it).
    //
    // Previously this would reposition the viewport by "exposing the item's bottom in the safe area", with a fallback of "if the item is completely invisible, scrollToItem";
    // when a card is taller than one screen, the former's computed target is clamped to 0 and the latter simply pushes the item to the top of the viewport — both paths make
    // the tapped row jump wholesale to the top (probabilistically, depending on which frame the layout had stabilised to at the moment of the tap). That jump is
    // far less acceptable than "a bit of the bottom is covered by the input box after expanding", so the whole repositioning logic is removed.
    val onToolItemToggled: () -> Unit = { followBottom = false }

    // Load more history when the top is nearly reached. firstVisibleItemIndex must never be used as an effect key read during composition:
    // during a fling the index changes every time an item is crossed, making the whole large AIChatPanel function recompose along with it, together with all visible
    // items — the main amplifier of frame drops during fast scrolling (and hence fling compensation jumps and jitter). Instead, observe the index inside a coroutine with
    // snapshotFlow; the composition body does not read it at all.
    val latestMessagesState = rememberUpdatedState(messagesState)
    LaunchedEffect(listState, messagesReady) {
        if (!messagesReady) return@LaunchedEffect
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { index ->
                val ms = latestMessagesState.value
                if (index <= 3 && ms.hasMore && !ms.isLoadingMore) {
                    viewModel.loadMoreMessages()
                }
            }
    }

    val executionMode = settingsViewModel?.executionMode?.collectAsStateWithLifecycle()?.value
    val connectionState = settingsViewModel?.connectionState?.collectAsStateWithLifecycle()?.value
    val isRemote = executionMode == com.aicode.feature.settings.data.repository.ExecutionMode.REMOTE_SSH

    val markdownImageTransformer = remember(viewModel.fileAccess) {
        MarkdownImageTransformer(viewModel.fileAccess)
    }
    val imageViewerState = rememberImageViewerState()
    val viewerDecodeSpec = rememberViewerDecodeSpec()
    val chatImageLoad = remember(viewModel.fileAccess, viewerDecodeSpec) {
        chatImageLoader(viewModel.fileAccess, viewerDecodeSpec.maxEdge, viewerDecodeSpec.maxPixels)
    }

    // Opening a file card with a system app: re-fetching/copying has to run on the IO thread (remote mode has to download), so a coroutine is injected from here.
    val attachmentOpener = remember(viewModel.fileAccess, context) {
        AttachmentOpener { attachment ->
            scope.launch { openSentAttachment(context, attachment, viewModel.fileAccess) }
        }
    }

    // Render one item. The expanded content of a turn header / tool group expands together with the collapse header in the same Composable using AnimatedVisibility
    // as a whole (rather than each list item having its own placement animation): the height opens smoothly, the content moves with it, and it feels like a single whole.
    @Composable
    fun RenderChatItem(item: ChatRenderItem) {
        val header = item.turnHeader
        val group = item.toolGroup
        when {
            header != null -> {
                TurnCollapseHeader(
                    running = header.running,
                    durationMs = header.endMessageId?.let { taskDurations[it] },
                    expanded = header.expanded,
                    onToggle = {
                        viewModel.setTurnExpanded(header.key, !header.expanded)
                        onToolItemToggled()
                    }
                )
                AnimatedVisibility(
                    visible = header.expanded,
                    enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                    exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        item.turnProcess.forEach { child -> RenderChatItem(child) }
                    }
                }
            }
            item.contentType == REASONING_CONTENT_TYPE -> {
                // The assistant's reasoning is extracted as a process item: the expanded state is persisted by the host, so it is kept when scrolled out of the viewport / returning from another page.
                ReasoningBubble(
                    text = item.message.reasoning.orEmpty(),
                    cache = markdownCache,
                    durationMs = reasoningDurations[item.message.id],
                    expandedOverride = reasoningOverrideSnapshot[item.message.id],
                    onExpandedChange = { viewModel.setReasoningExpanded(item.message.id, it) }
                )
            }
            group != null -> {
                ToolCallGroupHeader(
                    count = group.size,
                    running = group.any { it.id in runningToolIds || it.isToolRunning(null) },
                    expanded = item.groupExpanded,
                    onToggle = {
                        viewModel.setToolExpanded(item.key, !item.groupExpanded)
                        onToolItemToggled()
                    }
                )
                AnimatedVisibility(
                    visible = item.groupExpanded,
                    enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                    exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
                ) {
                    Column {
                        group.forEach { member ->
                            // derivedStateOf isolated by id: the tool live output gets a new List on every chunk;
                            // reading it directly would invalidate and rerun the composition of all visible items; wrapped in a layer, the recomputation only affects
                            // the one item whose text actually changed (the tool row currently streaming output).
                            val live by remember(member.id) {
                                derivedStateOf { runningTool.firstOrNull { it.messageId == member.id }?.text }
                            }
                            AgentMessageItem(
                                message = member,
                                showActions = member.id in actionableMessageIds,
                                liveOutput = live,
                                markdownCache = markdownCache,
                                onRewindClick = { viewModel.openRewindMenu(it) },
                                onMoreClick = { messageForMenu = it },
                                toolExpandedOverride = toolGroupOverrideSnapshot[member.id],
                                onToolExpandedChange = { isExpanded -> viewModel.setToolExpanded(member.id, isExpanded) },
                                onToolToggle = onToolItemToggled,
                                // When a group is expanded, member rows indent one level to distinguish the hierarchy from the group header
                                contentPadding = ToolGroupMemberPadding,
                                entryDelayMs = messageEntryDelays[member.id]
                            )
                        }
                    }
                }
            }
            else -> {
                val message = item.message
                // Same as above: isolate the live output subscription by id, avoiding an update of runningTool on every chunk
                // triggering a recomposition of all visible message items (the source of the recomposition storm during streaming).
                val live by remember(message.id) {
                    derivedStateOf { runningTool.firstOrNull { it.messageId == message.id }?.text }
                }
                AgentMessageItem(
                    message = message,
                    showActions = message.id in actionableMessageIds,
                    liveOutput = live,
                    markdownCache = markdownCache,
                    contentSlice = item.slice,
                    isChunkHeader = item.isChunkHeader,
                    isChunkFooter = item.isChunkFooter,
                    reasoningVisible = item.reasoningVisible,
                    onRewindClick = { viewModel.openRewindMenu(it) },
                    onMoreClick = { messageForMenu = it },
                    toolExpandedOverride = toolGroupOverrideSnapshot[message.id],
                    onToolExpandedChange = { isExpanded -> viewModel.setToolExpanded(message.id, isExpanded) },
                    onToolToggle = onToolItemToggled,
                    reasoningDurationMs = reasoningDurations[message.id],
                    taskDurationMs = taskDurations[message.id],
                    turnUsage = turnUsages[message.id],
                    entryDelayMs = messageEntryDelays[message.id]
                )
            }
        }
    }

    // Liquid Glass: only this one backdrop layer for the whole page, shared by the title bar / input area / todo overlay,
    // avoiding multiple full-screen captures dragging down the GPU and memory.
    val glassLayer = rememberChatGlassLayer()
    val glassLuminance = rememberGlassLuminance(glassLayer)
    // The floating title bar no longer occupies layout height; instead the message list's contentPadding reserves it:
    // status bar height + title bar content height (plus one more row of connection status for remote sessions).
    val density = LocalDensity.current
    val topReserveDp = with(density) {
        WindowInsets.statusBars.getTop(density).toDp()
    } + CHAT_HEADER_HEIGHT_DP.dp + (if (isRemote && connectionState != null) CHAT_HEADER_CONNECTION_ROW_DP.dp else 0.dp)

    CompositionLocalProvider(
        LocalMarkdownImageTransformer provides markdownImageTransformer,
        LocalImageViewer provides imageViewerState,
        LocalAttachmentOpener provides attachmentOpener
    ) {
        Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(padding)
        ) {
        // Large-screen body column width-limited and centred: filling the whole tablet width would make a line of text too long and bubbles span the full screen, which is tiring to read.
        // In a narrow window readableContentMaxWidth() returns Dp.Unspecified, and widthIn imposes no constraint.
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .widthIn(max = readableContentMaxWidth())
                .fillMaxSize()
        ) {
            // Content layer: the message list extends to the bottom of the screen, the input box floats over it, and when scrolling, cards can slide behind the input box.
            // The one and only backdrop sampling point: the glass of the title bar / input area / todo overlay all read this layer,
            // avoiding multiple full-screen backdrop captures existing at the same time.
            Column(modifier = Modifier.fillMaxSize().glassBackdrop(glassLayer)) {
            Box(modifier = Modifier.weight(1f)) {
                if (!messagesReady) {
                    // When the remote mode connection is not ready, show a connection status placeholder to avoid flickering of a blank screen or old workspace records
                    if (isRemote && connectionState != null && connectionState != com.aicode.feature.agent.domain.container.ConnectionState.CONNECTED) {
                        RemoteConnectingPlaceholder(state = connectionState)
                    } else if (showMessagesLoading) {
                        // Placeholder for slow local database reads: previously nothing was drawn here, so switching sessions flashed a blank screen first.
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            androidx.compose.material3.CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else if (messages.isEmpty()) {
                    WelcomeState(
                        bottomReserve = with(LocalDensity.current) { inputBarReservePx.toDp() },
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .nestedScroll(userScrollConnection)
                            .pointerInput(listState) {
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                    pointerDown = true
                                    try {
                                        do {
                                            val event = awaitPointerEvent(PointerEventPass.Final)
                                        } while (event.changes.any { it.pressed })
                                    } finally {
                                        pointerDown = false
                                    }
                                }
                            },
                        contentPadding = PaddingValues(
                            start = Spacing.lg,
                            end = Spacing.lg,
                            // On the first screen, move the first message below the floating title bar (and the todo overlay below the title bar);
                            // after scrolling, the content passes behind the glass
                            top = topReserveDp + Spacing.md +
                                (if (todoOverlayVisible) CHAT_TODO_BAR_RESERVE_DP.dp else 0.dp),
                            bottom = with(LocalDensity.current) { inputBarReservePx.toDp() }
                        )
                    ) {
                        itemsIndexed(chatItems, key = { _, item -> item.key }, contentType = { _, item -> item.contentType }) { _, item ->
                            RenderChatItem(item)
                        }
                        // Tail item: the reasoning bubble and the status tail are merged into the same permanently mounted item; neither is added or removed based on state.
                        // If the start/end of reasoning or the start/end of streaming made totalItemsCount jump up or down, LazyColumn would clamp
                        // firstVisibleItemIndex downward -> viewport jump (root cause of old symptom 2). The item count is always 1, so the
                        // anchor is never clamped: when showReasoning, the reasoning bubble is rendered (with its own internal collapsing), otherwise it is empty;
                        // when tailKind is NONE the tail is an empty Box (0 height). After the stream finishes and lands in the database, the following effect anchors the new message to the bottom.
                        //
                        // Streaming state (streamingText / streamingReasoning / retained buffer / settled-in-database check /
                        // typewriter advance / tailKind) is all computed and subscribed inside this itemContent: these values change on every output
                        // chunk, so if read in the AIChatPanel function body or the LazyListScope content, the model would
                        // make the whole panel recompose in full on every emission (the main amplifier of frame drops during streaming); after narrowing it to here, the invalidation
                        // scope is only this item, and all other items are skipped entirely.
                        item(key = "__active__", contentType = "tail") {
                            // Buffer retention: during the period after streaming has just ended but the database has not yet finished dispatching (the end of messages is still
                            // USER/TOOL messages), keep retaining this turn's full text and reasoning to hold up the bottom bubble, completely eliminating
                            // "the characters are almost typed and then the whole passage suddenly evaporates (a blank flashback)" or "the reasoning block suddenly disappears and reappears";
                            // once the end of messages formally accepts this turn's assistant message, clear immediately to make way.
                            // rememberSaveable: after this item scrolls out of the viewport and is disposed, retained is not lost, and it is still there when scrolled back.
                            var retainedStreamingText by rememberSaveable { mutableStateOf<String?>(null) }
                            var retainedStreamingReasoning by rememberSaveable { mutableStateOf<String?>(null) }
                            LaunchedEffect(currentSessionId) {
                                retainedStreamingText = null
                                retainedStreamingReasoning = null
                            }
                            val lastMsg = messages.lastOrNull()
                            // Whether this turn's assistant message is already formally in place and rendered in the message list:
                            // only when the last message is ASSISTANT and its body and reasoning prefixes match does it mean this turn's output has landed in the database in the messages list
                            val isAssistantSettled = lastMsg?.role == MessageRole.ASSISTANT && run {
                                val currentText = streamingText ?: retainedStreamingText
                                val currentReasoning = streamingReasoning ?: retainedStreamingReasoning
                                val textSettled = if (currentText.isNullOrBlank()) {
                                    true
                                } else {
                                    val prefix = currentText.trimStart().take(20)
                                    prefix.isEmpty() || lastMsg.content.trimStart().startsWith(prefix)
                                }
                                val reasoningSettled = if (currentReasoning.isNullOrBlank()) {
                                    true
                                } else {
                                    val prefix = currentReasoning.trimStart().take(20)
                                    prefix.isEmpty() || (lastMsg.reasoning?.trimStart()?.startsWith(prefix) == true)
                                }
                                textSettled && reasoningSettled
                            }
                            LaunchedEffect(streamingText, isAssistantSettled) {
                                val st = streamingText
                                if (st != null && st.hasVisibleContent()) {
                                    retainedStreamingText = st
                                } else if (isAssistantSettled) {
                                    retainedStreamingText = null
                                }
                            }
                            LaunchedEffect(streamingReasoning, isAssistantSettled) {
                                val sr = streamingReasoning
                                if (sr != null && sr.hasVisibleContent()) {
                                    retainedStreamingReasoning = sr
                                } else if (isAssistantSettled) {
                                    retainedStreamingReasoning = null
                                }
                            }
                            val displayStreamingText = if (isAssistantSettled) null else (streamingText ?: retainedStreamingText)
                            val showStreaming = displayStreamingText?.hasVisibleContent() == true

                            val displayStreamingReasoning = if (isAssistantSettled) null else (streamingReasoning ?: retainedStreamingReasoning)
                            val reasoning = displayStreamingReasoning
                            val showReasoning = reasoning?.hasVisibleContent() == true

                            // Typewriter rendering progress: the progress is backed by rememberTypewriterStreamingText's internal
                            // rememberSaveable, so it can resume after scrolling out of the viewport and back. Hold the whole object:
                            // the settled flag is used to tell MarkdownContent "still typing" -> parse throttling + do not write to the cache.
                            val typewriter = rememberTypewriterStreamingText(
                                text = displayStreamingText ?: "",
                                active = streamingText != null,
                                sessionKey = currentSessionId
                            )
                            val typewriterRenderText = typewriter.text
                            // The reasoning process also goes through the typewriter: sharing the same rate-adaptive logic as the reply text.
                            // As soon as the body starts outputting (streamingText non-empty) the reasoning is considered finished: the reasoning typewriter wraps up immediately,
                            // avoiding the reasoning not yet being fully typed while the body has already started, leaving the two typing slowly on top of each other.
                            val typewriterReasoningText = rememberTypewriterStreamingText(
                                text = displayStreamingReasoning ?: "",
                                active = streamingReasoning != null && streamingText == null,
                                sessionKey = currentSessionId
                            ).text

                            // Busy status indicator: shown when there is no body/reasoning streaming output, it is not compacting, and it is not suspended by a permission dialog or a question.
                            // When a tool is running or the model is preparing a tool, the text is presented dynamically by thinkingLabel as the corresponding scenario (e.g. "Reading file");
                            // with neither streaming nor a specific tool, it falls back to showing "Thinking".
                            val showThinking = !showReasoning && !showStreaming && !isCompacting && isBusy && pendingPermission == null && pendingQuestion == null
                            val showRetrying = retryState != null && isBusy && !isCompacting && !showStreaming && !showReasoning
                            val showKeySwitched = keySwitchState != null && isBusy && !isCompacting && !showStreaming && !showReasoning
                            val tailKind = when {
                                showStreaming -> TailKind.STREAMING
                                isCompacting -> TailKind.COMPACTING
                                showKeySwitched -> TailKind.KEY_SWITCHED
                                showRetrying -> TailKind.RETRYING
                                showThinking -> TailKind.THINKING
                                else -> TailKind.NONE
                            }
                            Column {
                                if (showReasoning) {
                                    // Live streaming: collapsed by default, the collapsed row scrolls with the line currently being written; tap to open and read the full text
                                    val reasoningLive = reasoningTiming != null && reasoningTiming?.second == null &&
                                        !showStreaming && preparingTool == null
                                    ReasoningBubble(
                                        text = typewriterReasoningText,
                                        cache = markdownCache,
                                        showTimer = reasoningLive,
                                        durationMs = reasoningTiming?.let { (start, end) -> end?.minus(start) },
                                        timerStartMs = reasoningTiming?.first,
                                        preRendered = true,
                                        sessionKey = currentSessionId,
                                        live = reasoningLive,
                                    )
                                }
                                when (tailKind) {
                                    TailKind.THINKING -> ThinkingBubble(label = thinkingLabel)
                                    TailKind.STREAMING -> StreamingBubble(
                                        text = typewriterRenderText,
                                        cache = markdownCache,
                                        // Typewriter not caught up = streaming: Markdown parsing is throttled incrementally, and intermediate snapshots do not enter the LRU
                                        streaming = !typewriter.settled
                                    )
                                    TailKind.COMPACTING -> CompactionProgressBubble()
                                    TailKind.RETRYING -> {
                                        val rs = retryState
                                        if (rs != null) RetryingBubble(rs.attempt, rs.maxRetries, rs.error) else Box(Modifier)
                                    }
                                    TailKind.KEY_SWITCHED -> {
                                        val ks = keySwitchState
                                        if (ks != null) KeySwitchedBubble(ks.newIndex, ks.total) else Box(Modifier)
                                    }
                                    TailKind.NONE -> Box(Modifier)
                                }
                            }
                        }
                    }
                }
            }
            } // End of content layer

            // Task todos (display position = below the title bar): like the title bar, a "floating glass layer", with the same width and the same horizontal margins,
            // snug below the title bar. By default a single-line summary (x/y completed + in-progress task); when tapped to expand, it covers the messages as an overlay,
            // no longer occupying layout height in the message flow (the old version was embedded in the content layer, which neither opened up the glass nor aligned the width with the title bar).
            if (todoBelowTitleBar && currentTodoItems.isNotEmpty()) {
                TodoDashboardBar(
                    items = currentTodoItems,
                    sessionId = currentSessionId.orEmpty(),
                    // Mutual-exclusion collapsing only collapses "the others": collapse the todos when a permission/question/plan panel appears or the keyboard pops up.
                    // todoExpanded itself must never be counted into forceCollapse — that would form
                    // an "expand -> report -> force collapse -> reset -> expand again" infinite loop (symptom: cannot expand, frenzied flickering when returning to the page).
                    forceCollapse = pendingPermission != null || pendingQuestion != null ||
                        planApproval != null || imeVisible,
                    onExpandedChange = { todoExpanded = it },
                    glassBackdrop = glassLayer,
                    glassLuminance = { glassLuminance.luminance },
                    glassContentColor = glassLuminance.contentColor,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .widthIn(max = readableContentMaxWidth())
                        .padding(start = Spacing.xs, end = Spacing.xs, top = topReserveDp)
                )
            }

            // Floating glass title bar: no longer the Scaffold's topBar (that would permanently deduct a chunk of height),
            // but overlaid on the message list; the list only uses contentPadding to reserve first-screen space, and content passes through the glass when scrolling.
            ChatHeader(
                sessionTitle = sessionTitle,
                modelName = activeProvider?.effectiveModel,
                inputTokens = sessionInputTokens,
                outputTokens = sessionOutputTokens,
                onOpenDrawer = {
                    keyboardController?.hide()
                    onOpenDrawer()
                },
                onNewChat = { viewModel.newSession() },
                onNavigateToTerminal = onNavigateToTerminal,
                onNavigateToGit = onNavigateToGit,
                onNavigateToBrowser = onNavigateToBrowser,
                currentMode = currentMode,
                onToggleMode = { viewModel.setSessionMode(it) },
                connectionState = connectionState?.takeIf { isRemote },
                showMenuButton = showMenuButton,
                terminalActive = terminalActive,
                gitActive = gitActive,
                browserActive = browserActive,
                glassBackdrop = glassLayer,
                glassLuminance = { glassLuminance.luminance },
                glassContentColor = glassLuminance.contentColor,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .widthIn(max = readableContentMaxWidth())
            )

            // Floating layer: error bubble / panel / input box (the mask is inside ChatInputBar and moves up with the keyboard)
            val floatingPanelAlpha by animateFloatAsState(
                targetValue = if (listState.isScrollInProgress) 0.4f else 1f,
                animationSpec = tween(200),
                label = "floating-panel-alpha"
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    // Use the floating layer's measured actual height as the scroll reserve, so a banner/panel/input box in any form never obscures the last message
                    .onGloballyPositioned { if (it.size.height > 0) floatingLayerHeightPx = it.size.height }
            ) {
            Box(modifier = Modifier.fillMaxWidth().graphicsLayer { alpha = floatingPanelAlpha }) {
                StatusBanner(state = agentState)
            }

            // During the exit animation uploadingCount has already returned to zero; reading it directly would fade out an "Uploading 0 files",
            // so, like StatusBanner, use a remembered non-null value to hold the exit animation.
            val lastUploadingCount = rememberLastNonNull(uploadingCount.takeIf { it > 0 })
            AnimatedVisibility(
                visible = uploadingCount > 0,
                modifier = Modifier.graphicsLayer { alpha = floatingPanelAlpha },
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                lastUploadingCount?.let { UploadingBanner(count = it) }
            }

            val questionForPanel = rememberLastNonNull(pendingQuestion)
            AnimatedVisibility(
                visible = pendingQuestion != null,
                modifier = Modifier.graphicsLayer { alpha = floatingPanelAlpha },
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                questionForPanel?.let { question ->
                    AskUserQuestionPanel(
                        question = question,
                        onConfirm = { answer -> viewModel.resolveUserQuestion(question.id, answer) },
                        onSkip = { viewModel.resolveUserQuestion(question.id, UserQuestionAnswer(emptyList())) },
                        forceCollapse = dashboardCollapseActive
                    )
                }
            }

            val planForPanel = rememberLastNonNull(planApproval)
            AnimatedVisibility(
                visible = planApproval != null,
                modifier = Modifier.graphicsLayer { alpha = floatingPanelAlpha },
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                planForPanel?.let { state ->
                    PlanApprovalPanel(
                        state = state,
                        onApprove = { viewModel.approvePlanAndBuild() },
                        onRefine = { viewModel.refinePlan() },
                        forceCollapse = dashboardCollapseActive
                    )
                }
            }

            ChatInputBar(
                value = inputText,
                onValueChange = { inputText = it; viewModel.updateInputDraft(it) },
                onSend = sendMessage,
                enterToSend = settingsViewModel?.enterToSend?.collectAsStateWithLifecycle()?.value ?: false,
                onStop = { viewModel.stopAgent() },
                isBusy = isBusy,
                workspaceViewModel = workspaceViewModel,
                hasRunningSessions = { viewModel.hasRunningSessionsInCurrentWorkspace() },
                onSwitchWorkspaceConfirmed = { viewModel.stopAllAndCloseTerminal() },
                activeProvider = activeProvider,
                providers = providers,
                modelMetadata = modelMetadata,
                onSelectModel = { p, m ->
                    viewModel.setSessionProviderModel(p, m)
                },
                currentMode = currentMode,
                onToggleMode = { viewModel.setSessionMode(it) },
                reasoningEffort = reasoningEffort,
                onReasoningEffortChange = { viewModel.setSessionReasoningEffort(it) },
                pendingAttachments = pendingAttachments,
                onRemoveAttachment = ::removePendingAttachment,
                canUploadFiles = canUploadFiles,
                canUploadImages = canUploadImages,
                onUploadFile = { filePicker.launch(arrayOf("*/*")) },
                onUploadImage = {
                    imagePicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                onTakePhoto = ::takePhoto,
                slashCommands = slashCommands,
                queuedRequests = queuedRequests,
                onRemoveQueued = { viewModel.removeQueuedRequest(it) },
                onMoveQueued = { from, to -> viewModel.moveQueuedRequest(from, to) },
                onEditQueued = { queuedEditing = it },
                onInterjectQueued = { viewModel.interjectQueuedRequest(it) },
                dashboardState = currentDashboardState,
                // Only when it is "above the message bar" are the todos rendered by the input bar; when below the title bar they are rendered separately above, and when off they are not rendered.
                todoItems = if (todoAboveInputBar) currentTodoItems else emptyList(),
                glassBackdrop = glassLayer,
                glassLuminance = { glassLuminance.luminance },
                sessionId = currentSessionId.orEmpty(),
                onTodoExpandedChange = { todoExpanded = it },
                forceCollapseDashboard = pendingPermission != null || pendingQuestion != null || planApproval != null || imeVisible,
                onDashboardExpandedChange = { dashboardExpanded = it },
                onRefreshDashboard = {
                    activeProvider?.let {
                        val context = buildDashboardContext(refreshReason = "manual")
                        settingsViewModel?.refreshProviderDashboard(it, context = context, force = true)
                    }
                },
                onRefreshDashboardByButton = {
                    activeProvider?.let {
                        val context = buildDashboardContext(refreshReason = "button")
                        settingsViewModel?.refreshProviderDashboard(it, context = context, force = true)
                    }
                },
                tokenEstimated = contextUsages[currentSessionId]?.estimated == true,
                tokenProgress = run {
                    val contextLimit = activeModelMetadata?.let {
                        com.aicode.feature.settings.domain.model.ModelContextPolicy.effectiveInputBudget(it)
                    } ?: 0
                    val usage = contextUsages[currentSessionId]?.takeIf { sessionLastInputTokens > 0 || isBusy }
                    if (contextLimit > 0) {
                        (usage?.inputTokens ?: sessionLastInputTokens).toFloat() / contextLimit
                    } else 0f
                },
                isScrolling = listState.isScrollInProgress,
                forceOpenModelSheet = onboardingStep == OnboardingStep.SIMULATE_CHOOSE_MODEL,
                onSelectModelInOnboarding = onSelectModelInOnboarding,
                onModelSheetDismiss = onDismissModelSheetInOnboarding,
                modifier = Modifier
                    .fillMaxWidth()
                    .onboardingTarget(OnboardingStep.SEND_MESSAGE)
            )
            } // End of floating layer

            // Floating permission dialog: independent of the bottom bar layout flow, floating above the input box, avoiding inflating the bottom bar height and pushing the message list up.
            // When the user scrolls the message list up and down, it follows with a transparent fade, without obscuring the reading view.
            val permissionForPanel = rememberLastNonNull(pendingPermission)
            var permissionPanelHeightPx by remember { mutableStateOf(0) }
            AnimatedVisibility(
                visible = pendingPermission != null,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(bottom = with(LocalDensity.current) { floatingLayerHeightPx.toDp() } + Spacing.xs)
                    .onGloballyPositioned { permissionPanelHeightPx = if (pendingPermission != null) it.size.height else 0 },
                enter = fadeIn(tween(220)) +
                        slideInVertically(tween(220)) { it / 3 } +
                        scaleIn(initialScale = 0.96f, animationSpec = tween(220)),
                exit = fadeOut(tween(160)) +
                       slideOutVertically(tween(160)) { it / 3 } +
                       scaleOut(targetScale = 0.96f, animationSpec = tween(160))
            ) {
                permissionForPanel?.let { request ->
                    ToolPermissionPanel(
                        request = request,
                        onChoice = { choice -> viewModel.resolveToolPermission(request.id, choice) },
                        sessionTitle = pendingPermissionSessionTitle,
                        forceCollapse = dashboardCollapseActive,
                        isScrolling = listState.isScrollInProgress
                    )
                }
            }

            // Scroll-to-bottom button: floating above the top-right corner of the input box (avoiding the bottom bar and any floating permission dialog), shown when more than half a screen away from the bottom;
            // it does not look at the scroll direction — stopping after scrolling up through history is exactly the moment a one-tap return to the bottom is most needed; when scrolling, it fades out with the input box.
            val permissionOffsetPx = with(LocalDensity.current) {
                if (pendingPermission != null) permissionPanelHeightPx + Spacing.xs.toPx() else 0f
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = isFarFromBottom,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = Spacing.lg)
                    .padding(bottom = with(LocalDensity.current) { (floatingLayerHeightPx + permissionOffsetPx + FLOATING_LAYER_GAP_DP.toPx()).toDp() })
                    .graphicsLayer { alpha = if (listState.isScrollInProgress) 0.4f else 1f },
                enter = fadeIn() + scaleIn(),
                exit = fadeOut() + scaleOut()
            ) {
                ScrollToBottomButton(
                    onClick = {
                        followBottom = true
                        scope.launch { snapToBottom() }
                    }
                )
            }

            targetRewindMessageId?.let { targetId ->
                val targetMsg = messages.find { it.id == targetId }
                RewindOptionsBottomSheet(
                    promptSnippet = targetMsg?.content ?: "",
                    onOptionSelected = { option ->
                        viewModel.executeRewindOption(targetId, option) { text, attachments ->
                            inputText = text
                            pendingAttachments = attachments.map { it.toPendingAttachment() }
                        }
                    },
                    onDismissRequest = { viewModel.dismissRewindMenu() }
                )
            }

            messageForMenu?.let { message ->
                val clipboard = LocalClipboard.current
                val copyScope = rememberCoroutineScope()
                MessageActionsBottomSheet(
                    message = message,
                    onDismiss = { messageForMenu = null },
                    onEditClick = { editingMessage = message },
                    onCopyClick = {
                        copyScope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("message", message.content)))
                        }
                    },
                    onDeleteClick = { viewModel.deleteMessage(message.id) }
                )
            }

            editingMessage?.let { message ->
                EditMessageDialog(
                    initialText = message.content,
                    onDismiss = { editingMessage = null },
                    onConfirm = { newContent ->
                        viewModel.updateMessageContent(message.id, newContent)
                        editingMessage = null
                    }
                )
            }

            queuedEditing?.let { req ->
                EditMessageDialog(
                    initialText = req.request,
                    onDismiss = { queuedEditing = null },
                    onConfirm = { newContent ->
                        viewModel.updateQueuedRequest(req.id, newContent)
                        queuedEditing = null
                    }
                )
            }
        }
        }
    }

        // Dialog is an independent window and does not occupy the parent layout size, so hanging it after the Scaffold covers the whole screen —
        // in a tablet two-pane layout it will not cover only the chat column, nor will it be covered by the global background watermark drawn on the topmost layer by MainActivity.
        ImageViewerHost(state = imageViewerState, load = chatImageLoad)
    }
}

/**
 * Remember the last non-null value, so that [AnimatedVisibility]'s exit animation keeps rendering the old content.
 *
 * Use a plain object rather than [androidx.compose.runtime.MutableState] to hold it: here we only need to keep the previous value across recompositions,
 * and it does not need to trigger recomposition itself (a change in the source value itself recomposes the reading point); entering the snapshot system would instead cause an extra useless recomposition.
 */
@Composable
internal fun <T : Any> rememberLastNonNull(value: T?): T? {
    val holder = remember { LastNonNullHolder<T>() }
    if (value != null) holder.value = value
    return holder.value
}

private class LastNonNullHolder<T : Any>(var value: T? = null)

/**
 * Scheduler for tool card entry animations: decides which messages should play an entry animation and how much each one is staggered.
 *
 * The criterion is "newly appended to the tail during this scheduler's lifetime", not "within N seconds of the timestamp" —
 * the latter would, when returning from another page, judge that batch of messages still within the time window as new messages again and replay the animation.
 * On the first call, messages already in the list are all recorded as existing stock; history loaded by paging up is older than the maximum seen timestamp,
 * and likewise does not animate in. When the session changes the whole scheduler is rebuilt, and the existing messages of the new session do not animate in either.
 */
private class MessageEntryScheduler {
    private val seen = mutableSetOf<String>()
    private val delays = mutableMapOf<String, Long>()
    private var initialized = false
    private var maxSeenTimestamp = Long.MIN_VALUE

    /** Returns "message id -> entry delay (ms)"; messages not in the table are displayed directly. */
    fun schedule(messages: List<AgentUIMessage>): Map<String, Long> {
        if (!initialized) {
            initialized = true
            messages.forEach { seen += it.id }
            maxSeenTimestamp = messages.maxOfOrNull { it.timestamp } ?: Long.MIN_VALUE
            return emptyMap()
        }
        var consecutive = 0
        for (message in messages) {
            if (message.id in seen) {
                consecutive = 0
                continue
            }
            seen += message.id
            val appendedAtTail = message.timestamp >= maxSeenTimestamp
            maxSeenTimestamp = maxOf(maxSeenTimestamp, message.timestamp)
            if (appendedAtTail && message.role == MessageRole.TOOL) {
                delays[message.id] = (consecutive * MESSAGE_ENTRY_STAGGER_MS)
                    .coerceAtMost(MESSAGE_ENTRY_MAX_STAGGER_MS)
                consecutive++
            } else {
                consecutive = 0
            }
        }
        return delays.toMap()
    }
}

/** Scroll-to-bottom button: circular, floating at the top-right corner of the input box, shown when not at the bottom. */
@Composable
private fun ScrollToBottomButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 6.dp,
        modifier = modifier
            .size(SCROLL_TO_BOTTOM_BTN_SIZE.dp)
            .clickable(onClick = onClick)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = FeatherIcons.ArrowDown,
                contentDescription = stringResource(R.string.common_scroll_to_bottom),
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

