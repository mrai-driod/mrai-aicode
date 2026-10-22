package com.aicode.feature.agent.presentation

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import androidx.compose.runtime.mutableStateMapOf
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.MainActivity
import com.aicode.R
import com.aicode.core.util.FileLogger
import com.aicode.core.util.GitIgnoreMatcher
import com.aicode.core.util.toUserMessage
import com.aicode.core.util.formatCostUsd
import com.aicode.feature.agent.data.local.dao.AgentMessageDao
import com.aicode.feature.agent.domain.checkpoint.CheckpointManager
import com.aicode.feature.agent.domain.device.RootNetworkGuard
import com.aicode.feature.agent.data.local.dao.CheckpointDao
import com.aicode.feature.agent.data.local.dao.ChatSessionDao
import com.aicode.feature.agent.data.local.dao.LlmCallRecordDao
import com.aicode.feature.agent.data.local.dao.TodoItemDao
import com.aicode.feature.agent.domain.model.TodoItem
import com.aicode.feature.agent.data.local.entity.ChatSessionEntity
import com.aicode.feature.agent.domain.container.ContainerInitState
import com.aicode.feature.agent.domain.container.LinuxContainerEngine
import com.aicode.feature.settings.domain.model.ProviderType
import com.aicode.feature.settings.domain.service.ModelCostCalculator
import com.aicode.feature.settings.domain.repository.AIProviderRepository
import com.aicode.feature.settings.data.repository.AgentSoundSettingsRepository
import com.aicode.feature.settings.data.repository.DefaultModelSettingsRepository
import com.aicode.feature.settings.data.repository.GeneralSettingsRepository
import com.aicode.feature.settings.data.repository.KeepaliveSettingsRepository
import com.aicode.feature.settings.data.repository.ModelReasoningEffortRepository
import com.aicode.feature.settings.data.repository.StartupSessionMode
import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.model.AgentImage
import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.model.AgentMode
import com.aicode.feature.agent.domain.model.ChatSession
import com.aicode.feature.agent.domain.model.ReasoningEffort
import com.aicode.feature.agent.domain.notification.AgentNotificationCenter
import com.aicode.feature.agent.domain.notification.AgentNotificationFormatter
import com.aicode.feature.agent.domain.notification.AgentNotificationKind
import com.aicode.feature.agent.domain.notification.NotificationOutcome
import com.aicode.feature.agent.domain.notification.PendingNotification
import com.aicode.feature.agent.domain.permission.PermissionChoice
import com.aicode.feature.agent.domain.mcp.McpManager
import com.aicode.feature.agent.domain.subagent.AgentDefinition
import com.aicode.feature.agent.domain.subagent.AgentDefinitionRepository
import com.aicode.feature.agent.domain.subagent.SubAgentEvent
import com.aicode.feature.agent.domain.subagent.SubAgentEventBus
import com.aicode.core.watch.FileChangeBatch
import com.aicode.core.watch.FileChangeHub
import com.aicode.core.watch.asDirtySignal
import com.aicode.feature.agent.domain.subagent.SubAgentEventType
import com.aicode.feature.agent.domain.workflow.AgentWorkflow
import com.aicode.feature.terminal.domain.TabFinishedEvent
import com.aicode.feature.terminal.domain.TerminalKeepaliveService
import com.aicode.feature.terminal.domain.TerminalSessionManager
import com.aicode.feature.terminal.domain.takeTailLines
import com.aicode.feature.workspace.domain.DirectoryListingCache
import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.FileEntry
import com.aicode.feature.workspace.domain.WorkspacePathMapper
import com.aicode.feature.workspace.domain.isValidFileEntryName
import com.aicode.feature.workspace.domain.planBrowseInvalidation
import com.aicode.feature.agent.domain.workflow.AgentEvent
import com.aicode.feature.agent.domain.tool.ToolPermissionManager
import com.aicode.feature.agent.domain.tool.ToolRegistry
import com.aicode.feature.agent.domain.tool.mode.PlanApprovalChoice
import com.aicode.feature.agent.domain.tool.mode.PlanApprovalManager
import com.aicode.feature.agent.domain.tool.mode.PlanApprovalRequest
import com.aicode.feature.agent.domain.tool.question.AskUserQuestionManager
import com.aicode.feature.agent.domain.tool.question.UserQuestionAnswer
import com.aicode.feature.agent.domain.session.SessionUseCase
import com.aicode.feature.agent.domain.session.MessagePersistenceUseCase
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import com.aicode.feature.backup.domain.BackupManager
import com.aicode.feature.agent.domain.command.SlashCommand
import com.aicode.feature.agent.domain.command.SlashCommandContext
import com.aicode.feature.agent.domain.command.SlashCommandRegistry
import com.aicode.feature.agent.domain.command.SlashCommandRegistry.ResolvedCommand
import com.aicode.feature.agent.domain.skill.Skill
import com.aicode.feature.agent.presentation.AgentAttachment
import com.aicode.feature.agent.presentation.component.RewindOption
import com.aicode.feature.agent.presentation.component.formatTokenCount
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.OutputStream
import java.util.Calendar
import java.util.UUID
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class AIAgentViewModel @Inject constructor(
    private val agentWorkflow: AgentWorkflow,
    private val toolRegistry: ToolRegistry,
    private val agentMessageDao: AgentMessageDao,
    private val chatSessionDao: ChatSessionDao,
    private val llmCallRecordDao: LlmCallRecordDao,
    private val modelCostCalculator: ModelCostCalculator,
    private val aiProviderRepository: AIProviderRepository,
    private val defaultModelSettingsRepository: DefaultModelSettingsRepository,
    private val modelReasoningEffortRepository: ModelReasoningEffortRepository,
    private val toolPermissionManager: ToolPermissionManager,
    private val askUserQuestionManager: AskUserQuestionManager,
    private val containerEngine: LinuxContainerEngine,
    private val sessionUseCase: SessionUseCase,
    private val messagePersistenceUseCase: MessagePersistenceUseCase,
    private val planApprovalManager: PlanApprovalManager,
    private val terminalSessionManager: TerminalSessionManager,
    private val slashCommandRegistry: SlashCommandRegistry,
    private val checkpointManager: CheckpointManager,
    private val checkpointDao: CheckpointDao,
    private val backupManager: BackupManager,
    private val mcpManager: McpManager,
    private val agentSoundSettings: AgentSoundSettingsRepository,
    private val generalSettingsRepository: GeneralSettingsRepository,
    private val keepaliveSettings: KeepaliveSettingsRepository,
    private val subAgentEventBus: SubAgentEventBus,
    private val agentNotificationCenter: AgentNotificationCenter,
    private val agentDefinitionRepository: AgentDefinitionRepository,
    private val todoItemDao: TodoItemDao,
    val fileAccess: FileAccessProvider,
    private val fileChangeHub: FileChangeHub,
    private val rootNetworkGuard: RootNetworkGuard,
    @param:ApplicationContext private val context: Context
) : ViewModel(), SlashCommandContext {

    private val sessionJobs = mutableMapOf<String, Job>()

    /**
     * The CPU wake lock held while the agent is running: after the screen turns off, the system suspends the process, interrupting streaming responses and stalling tool calls.
     * Not reference-counted (setReferenceCounted(false)): multiple sessions share a single lock, released once when the last task finishes.
     */
    private val wakeLock by lazy {
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AiCode:AgentWorkflow")
            .apply { setReferenceCounted(false) }
    }

    /** The persistent keepalive the user manually enabled in Settings; finishing an agent task must not turn it off along the way. */
    @Volatile
    private var userKeepaliveEnabled = false

    private val _currentSessionId = MutableStateFlow<String?>(null)
    val currentSessionId: StateFlow<String?> = _currentSessionId.asStateFlow()

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val currentSessionTodoItems: StateFlow<List<TodoItem>> = _currentSessionId
        .flatMapLatest { id ->
            if (id.isNullOrBlank()) flowOf(emptyList())
            else todoItemDao.getBySession(id).map { entities -> entities.map { it.toDomain() } }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _agentStates = MutableStateFlow<Map<String, AgentUIState>>(emptyMap())
    val agentStates: StateFlow<Map<String, AgentUIState>> = _agentStates.asStateFlow()

    /**
     * Whether each session's last turn completed normally: only a normal finish (receiving the completion marker) attaches the "Copy / More"
     * buttons below the end-of-turn reply; an active pause / error / not-yet-started state attaches none, so the buttons never sit on truncated output.
     */
    private val _completedSessions = MutableStateFlow<Set<String>>(emptySet())
    val completedSessions: StateFlow<Set<String>> = _completedSessions.asStateFlow()

    val agentState: StateFlow<AgentUIState> = _currentSessionId
        .flatMapLatest { id ->
            if (id == null) flowOf(AgentUIState.Idle)
            else _agentStates.map { it[id] ?: AgentUIState.Idle }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AgentUIState.Idle)

    private fun setAgentState(sessionId: String, state: AgentUIState) {
        _agentStates.value = _agentStates.value + (sessionId to state)
    }

    /**
     * Failure text: when the server provides a type code (e.g. refusal / context limit exceeded), use the localized explanation,
     * and append the server's specific reason (if any); with no type code, show the original error text directly.
     */
    private fun describeFailure(event: AgentEvent.Failed): String {
        val localized = when (event.reasonCode) {
            // Gemini's finishReason values are all uppercase; these are synonymous with Anthropic's refusal (content-policy interception).
            "refusal", "SAFETY", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII" ->
                context.getString(R.string.agent_stop_refusal)
            "model_context_window_exceeded" -> context.getString(R.string.agent_stop_context_exceeded)
            "input_budget_exceeded" -> context.getString(R.string.agent_input_budget_exceeded)
            else -> null
        } ?: return event.error
        return if (event.error.isBlank()) localized else "$localized\n${event.error}"
    }

    private val _messageLimit = MutableStateFlow<Map<String, Int>>(emptyMap())
    private val defaultLimit = 30

    /** Chat-history search: cap on hit count, input debounce, snippet context width, and pagination headroom reserved when locating a result. */
    private val chatSearchLimit = 50
    private val chatSearchDebounceMs = 300L
    private val snippetContext = 40
    private val messageLimitMargin = 5

    /**
     * Each session's own input draft, persisted to disk per session: drafts survive a process restart.
     * Previously there was a single global draft, so half-typed text in A would follow you when switching to B.
     */
    private val draftPrefs = context.getSharedPreferences("agent_input_drafts", Context.MODE_PRIVATE)
    private val _inputDrafts = MutableStateFlow<Map<String, String>>(
        draftPrefs.all.mapNotNull { (k, v) ->
            (v as? String)?.takeIf { it.isNotEmpty() }?.let { k to it }
        }.toMap()
    )
    val inputDraft: StateFlow<String> = _currentSessionId
        .flatMapLatest { id ->
            if (id == null) flowOf("") else _inputDrafts.map { it[id].orEmpty() }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    fun updateInputDraft(text: String) {
        val id = _currentSessionId.value ?: return
        val editor = draftPrefs.edit()
        if (text.isEmpty()) {
            _inputDrafts.value = _inputDrafts.value - id
            editor.remove(id)
        } else {
            _inputDrafts.value = _inputDrafts.value + (id to text)
            editor.putString(id, text)
        }
        editor.apply()
    }

    fun clearInputDraft() {
        val id = _currentSessionId.value ?: return
        _inputDrafts.value = _inputDrafts.value - id
        draftPrefs.edit().remove(id).apply()
    }

    /**
     * Manual expansion state for tool calls (group headers and individual tool cards): key = the group key (`toolgroup:<id of the first message in the group>`)
     * or an individual message id.
     *
     * Kept in the ViewModel rather than in composition: on narrow windows, opening Settings / Terminal / Git / Editor is a full-screen route, and the whole chat-page
     * composition is disposed, so a `remember` map and per-message.id remembers are lost together -- an expanded tool
     * collapses back to its default state as soon as it leaves view (scrolled off screen and recycled, or returning from another page). This is kept in memory for the App process lifetime,
     * sessions do not affect each other (the key is the message id, globally unique), and it is not persisted to disk.
     */
    val toolExpansionOverrides = mutableStateMapOf<String, Boolean>()

    /** Records one manual expand/collapse (the caller passes the negation of the currently visible state). */
    fun setToolExpanded(key: String, expanded: Boolean) {
        toolExpansionOverrides[key] = expanded
    }

    /**
     * Manual expansion state for whole-turn task folding (the outer "Running / Completed" header): key = `turn:<id of the first message of the turn>`.
     *
     * Same approach as [toolExpansionOverrides]: kept in memory for the App process lifetime, not persisted to disk. With no record, the default is inferred from "the last turn is expanded while running,
     * everything else collapsed by default" (see activeTurnKey in AIChatPanel.buildChatItems); once the user taps it manually,
     * their choice wins, and the automatic collapse on task completion does not override it.
     */
    val turnExpansionOverrides = mutableStateMapOf<String, Boolean>()

    /** Records one manual expand/collapse of a whole-turn fold. */
    fun setTurnExpanded(key: String, expanded: Boolean) {
        turnExpansionOverrides[key] = expanded
    }

    /**
     * Manual choice for reasoning-process expansion state: key = assistant message id. Same approach as [toolExpansionOverrides] (in memory for the process, not persisted to disk),
     * so a reasoning card keeps its expanded/collapsed state after being recycled out of the viewport or after returning from another page.
     */
    val reasoningExpansionOverrides = mutableStateMapOf<String, Boolean>()

    /** Records one expand/collapse of a reasoning card. */
    fun setReasoningExpanded(messageId: String, expanded: Boolean) {
        reasoningExpansionOverrides[messageId] = expanded
    }

    /** Reasoning-process duration (milliseconds): key = assistant message id. Records the actual duration of streamed reasoning, so the persisted card can show seconds to one decimal place. */
    val reasoningDurations = mutableStateMapOf<String, Long>()

    fun loadMoreMessages() {
        val sid = _currentSessionId.value ?: return
        val currentLimit = _messageLimit.value[sid] ?: defaultLimit
        _messageLimit.value = _messageLimit.value + (sid to (currentLimit + 30))
    }

    /** Real-time container initialization progress (extracting/deploying/installing packages), shown in a bubble at the bottom of the AI page. */
    val containerInit: StateFlow<ContainerInitState> = containerEngine.initProgress

    private val _currentWorkspace = MutableStateFlow<String>("")
    fun setWorkspace(path: String) {
        if (path.isBlank() || _currentWorkspace.value == path) return
        _currentWorkspace.value = path
        // Switching to a new workspace: container paths have the same names but different host directories, so the directory listing cache and gitignore rules are all invalidated.
        dirListingCache.clear()
        gitignoreCache = null
        // Restore the expansion state last persisted for this workspace (if there is no record, expand only the root).
        _expandedPaths.value = loadExpansion(path)
        // Search is limited to the current workspace; after switching, the old results are meaningless, so clear them too.
        _chatSearchQuery.value = ""
    }

    val sessions: StateFlow<List<ChatSession>> = _currentWorkspace
        .flatMapLatest { path ->
            if (path.isBlank()) flowOf(emptyList())
            else chatSessionDao.getRootSessionsByWorkspace(path)
                .map { list -> list.map { it.toDomain() } }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * Sub-agents of all root sessions, grouped by parent session id, so sidebar session rows can expand in place.
     * Uses a single full query rather than lazy loading row by row: one read of the same table avoids a subscription storm of opening one Flow per expanded row.
     */
    val subSessionsByParent: StateFlow<Map<String, List<ChatSession>>> = _currentWorkspace
        .flatMapLatest { path ->
            if (path.isBlank()) flowOf(emptyMap())
            else chatSessionDao.getAllSessionsByWorkspace(path).map { list ->
                list.filter { it.parentId != null }
                    .groupBy({ it.parentId!! }, { it.toDomain() })
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    // -- Global chat-history search (limited to the current workspace) --
    private val _chatSearchQuery = MutableStateFlow("")
    val chatSearchQuery: StateFlow<String> = _chatSearchQuery.asStateFlow()

    /** The message to locate (session id to message id); consumed and cleared once the chat page has scrolled to it or confirmed it cannot be located. */
    private val _pendingScrollMessage = MutableStateFlow<Pair<String, String>?>(null)
    val pendingScrollMessage: StateFlow<Pair<String, String>?> = _pendingScrollMessage.asStateFlow()

    /**
     * Search state: queries after a debounce when the workspace or keyword changes, mapping hits to UI models with snippets.
     * Queries run on the IO dispatcher, so a LIKE full-table scan does not block the main thread.
     */
    val chatSearchState: StateFlow<ChatSearchState> =
        combine(_currentWorkspace, _chatSearchQuery) { ws, q -> ws to q }
            .debounce(chatSearchDebounceMs)
            .flatMapLatest { (workspace, query) ->
                val keyword = query.trim()
                if (workspace.isBlank() || keyword.isEmpty()) {
                    flowOf(ChatSearchState(query = query))
                } else {
                    flow {
                        emit(ChatSearchState(query = query, loading = true))
                        val hits = withContext(Dispatchers.IO) {
                            agentMessageDao.searchInWorkspace(
                                workspace,
                                escapeLike(keyword),
                                chatSearchLimit
                            ).map { m ->
                                ChatSearchHit(
                                    sessionId = m.sessionId,
                                    sessionTitle = m.sessionTitle,
                                    messageId = m.messageId,
                                    snippet = buildSnippet(m.content, keyword),
                                    timestamp = m.timestamp
                                )
                            }
                        }
                        emit(ChatSearchState(query = query, hits = hits))
                    }
                }
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, ChatSearchState())

    fun updateChatSearchQuery(query: String) {
        _chatSearchQuery.value = query
    }

    fun clearChatSearch() {
        _chatSearchQuery.value = ""
        _pendingScrollMessage.value = null
    }

    /**
     * Opens a search hit: switches to the corresponding session, raises that session's pagination limit enough to include the target message, and registers the message to locate.
     * The actual scrolling is done by the chat page once the message is ready. The search term and results are kept, so reopening the sidebar still shows the result list.
     */
    fun openChatSearchHit(hit: ChatSearchHit) {
        selectSession(hit.sessionId)
        viewModelScope.launch {
            val timestamp = withContext(Dispatchers.IO) {
                agentMessageDao.getMessageById(hit.messageId)?.timestamp
            }
            if (timestamp != null) {
                val needed = withContext(Dispatchers.IO) {
                    agentMessageDao.countMessagesFromTimestamp(hit.sessionId, timestamp)
                } + messageLimitMargin
                val current = _messageLimit.value[hit.sessionId] ?: defaultLimit
                if (needed > current) {
                    _messageLimit.value = _messageLimit.value + (hit.sessionId to needed)
                }
            }
            _pendingScrollMessage.value = hit.sessionId to hit.messageId
        }
    }

    fun consumePendingScroll() {
        _pendingScrollMessage.value = null
    }

    /** Escapes LIKE wildcards, for use with ESCAPE '!' in SQL (the escape character itself must be handled first). */
    private fun escapeLike(raw: String): String =
        raw.replace("!", "!!").replace("%", "!%").replace("_", "!_")

    /** Extracts a snippet centred on the hit term: collapses newline whitespace, adds ellipses at either end as needed; with no hit, falls back to a leading excerpt. */
    private fun buildSnippet(content: String, keyword: String): String {
        val flat = content.replace(Regex("\\s+"), " ").trim()
        val index = flat.indexOf(keyword, ignoreCase = true)
        if (index < 0) return flat.take(snippetContext * 2)
        val start = (index - snippetContext).coerceAtLeast(0)
        val end = (index + keyword.length + snippetContext).coerceAtMost(flat.length)
        return buildString {
            if (start > 0) append('\u2026')
            append(flat, start, end)
            if (end < flat.length) append('\u2026')
        }
    }

    /** The set of expanded directories (container paths) in the sidebar "Files" tab. Includes the workspace root: the root can also be collapsed, and is expanded by default; persisted per workspace. */
    private val _expandedPaths = MutableStateFlow(setOf(WorkspacePathMapper.CONTAINER_ROOT))

    /** File-tree expansion state is persisted per workspace (kept across restarts); the key is the workspace path and the value is the set of expanded container paths. */
    private val expansionPrefs = context.getSharedPreferences("file_tree_expansion", Context.MODE_PRIVATE)

    /** Reads the persisted expansion set for a workspace; with no record, defaults to expanding only the workspace root. */
    private fun loadExpansion(workspace: String): Set<String> =
        expansionPrefs.getStringSet(workspace, null)?.toSet()
            ?: setOf(WorkspacePathMapper.CONTAINER_ROOT)

    /** Persists the current workspace's expansion set (pass a copy of the new set; SharedPreferences must not reuse a stored instance). */
    private fun saveExpansion(workspace: String, paths: Set<String>) {
        if (workspace.isBlank()) return
        expansionPrefs.edit().putStringSet(workspace, HashSet(paths)).apply()
    }
    val expandedPaths: StateFlow<Set<String>> = _expandedPaths.asStateFlow()

    /** Path of a directory that is expanding and waiting for its listing to return (under remote SSH, listFiles can take several seconds); the UI shows a waiting animation on that row. */
    private val _expandingPath = MutableStateFlow<String?>(null)
    val expandingPath: StateFlow<String?> = _expandingPath.asStateFlow()

    /** Set of entry paths currently undergoing a write operation (create/rename/delete/copy/move); the UI shows a waiting animation on the corresponding rows and toolbar. */
    private val _fileOpPaths = MutableStateFlow<Set<String>>(emptySet())
    val fileOpPaths: StateFlow<Set<String>> = _fileOpPaths.asStateFlow()

    /** Manual refresh signal: remote mode has no inotify, so it relies on this; local mode uses it as a fallback. */
    private val _browseRefresh = MutableStateFlow(0)

    /**
     * Directory listing cache (container path -> entry snapshot): lets "expand -> collapse -> expand again" and unaffected directories cost zero IO.
     * Invalidation is driven entirely by change events (see [applyBrowseInvalidation]); it is fully cleared on workspace switches and manual refreshes.
     */
    private val dirListingCache = DirectoryListingCache()

    /** Cache of the parsed root `.gitignore`, null means not cached; invalidated by gitignoreTouched and cleared on workspace switches. */
    private var gitignoreCache: List<String>? = null

    /**
     * The flattened visible file tree. listFiles is blocking IO locally and a network call remotely, so it must run on the IO dispatcher.
     * Watches the workspace root and all expanded directories: any change (not limited to the AI -- the terminal and other apps count too) or a manual refresh rebuilds the tree.
     * Watches only expanded directories and does not recurse the whole tree, avoiding opening many inotify handles on large repositories.
     *
     * Rebuilding is not re-reading: the tree is assembled only from [dirListingCache], and listFiles really runs only on a miss; inotify's single-level semantics
     * (direct children) covers exactly every case where "the list of an expanded directory changes", so FileChangeHub's snapshot-polling fallback is explicitly turned off here
     * (fallbackPoll=false) -- polling listFiles plus a stat per item for every expanded directory every 2 seconds is a continuous
     * IO tax, and the drawer tree is inherently insensitive to deep recursive changes. Under ROOT/SSH the host watch is unavailable anyway, so manual refresh is the fallback.
     */
    val browseState: StateFlow<FileBrowseState> = _expandedPaths
        .flatMapLatest { expanded ->
            val watched = expanded + WorkspacePathMapper.CONTAINER_ROOT
            val triggers = merge(
                watched.map {
                    fileChangeHub.watchWorkspace(it, fallbackPoll = false)
                        .onEach { batch -> applyBrowseInvalidation(batch) }
                        .asDirtySignal()
                }.merge().debounce(BROWSE_DEBOUNCE_MS),
                // drop(1) discards the current value when the StateFlow is rebuilt, otherwise a directory would be read once more just after expanding
                _browseRefresh.drop(1).map { }
            )
            flow {
                // Before the first emission, the stateIn initial value Loading is the placeholder; later expands/collapses/refreshes do not return to Loading,
                // the StateFlow keeps the previous Success until the new tree is ready, avoiding a flashing loading animation and loss of scroll position.
                emit(buildBrowseTree(expanded))
                triggers.collect { emit(buildBrowseTree(expanded)) }
            }.flowOn(Dispatchers.IO)
        }
        // The new tree is ready: clear the expanding-wait state (clear it on success or error alike, so a spinner cannot get stuck).
        .onEach { _expandingPath.value = null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FileBrowseState.Loading)

    /**
     * Precise invalidation of [dirListingCache] and the gitignore rules per change batch. Within the debounce window every batch runs immediately,
     * guaranteeing the cache is already in its post-invalidation state when the rebuild is assembled (onEach runs before the downstream collect rebuild, in order).
     */
    private fun applyBrowseInvalidation(batch: FileChangeBatch) {
        val plan = planBrowseInvalidation(batch, WorkspacePathMapper.CONTAINER_ROOT)
        if (plan.fullRefresh) {
            dirListingCache.clear()
            gitignoreCache = null
            return
        }
        plan.relistDirs.forEach { dirListingCache.invalidate(it) }
        plan.dropTrees.forEach { dirListingCache.invalidateTree(it) }
        if (plan.gitignoreTouched) gitignoreCache = null
    }

    /** Reads the workspace root and recursively expands it per [expanded], producing a flat list of visible nodes; if reading the root fails, the whole result is an error. */
    private fun buildBrowseTree(expanded: Set<String>): FileBrowseState {
        val startedAt = System.currentTimeMillis()
        listingCounter.set(0)
        val root = WorkspacePathMapper.CONTAINER_ROOT
        val rootEntries = listDirCached(root) ?: run {
            FileLogger.w(TAG, "Failed to list directory: $root")
            return FileBrowseState.Error("Unable to read the workspace root directory")
        }
        val ignorePatterns = cachedRootGitignore(root)
        val rootExpanded = root in expanded
        val nodes = mutableListOf<FileTreeNode>()
        nodes += FileTreeNode(
            entry = FileEntry(name = "workspace", isDirectory = true, size = 0, lastModified = 0),
            path = root,
            depth = 0,
            isRoot = true,
            isExpanded = rootExpanded
        )
        if (rootExpanded) {
            appendBrowseChildren(root, rootEntries, depth = 1, expanded = expanded, ignorePatterns = ignorePatterns, relParts = emptyList(), out = nodes)
        }
        val listed = listingCounter.get()
        FileLogger.d(TAG, "File tree rebuild: cached directories hit=${dirListingCache.size()} actually listed=$listed nodes=${nodes.size} elapsed=${System.currentTimeMillis() - startedAt}ms")
        return FileBrowseState.Success(nodes)
    }

    /** Counted from 0 on each rebuild, only for diagnostic logging (see [buildBrowseTree]); isolated per thread, so concurrent rebuilds do not interfere. */
    private val listingCounter = ThreadLocal.withInitial { 0 }

    /**
     * Directory listing with caching: a hit returns the snapshot directly; only a miss really runs listFiles and backfills the cache.
     * A failed listing returns null and is **not cached** (the next rebuild retries naturally); the caller decides how to present the error.
     */
    private fun listDirCached(path: String): List<FileEntry>? {
        dirListingCache.get(path)?.let { return it }
        val entries = runCatching { fileAccess.listFiles(path) }.getOrNull() ?: return null
        dirListingCache.put(path, entries)
        listingCounter.set(listingCounter.get() + 1)
        return entries
    }

    /**
     * Reads the workspace root .gitignore (local and remote both work); drops blank lines/comments/negations, and returns empty if it cannot be read. Only the root .gitignore; nested ones are not handled.
     * The result is cached until invalidated: cleared when a change batch touches the root .gitignore ([applyBrowseInvalidation]), on workspace switches,
     * and on manual refresh; this avoids an exists+readFile on every rebuild (under ROOT mode that is two su forks).
     */
    private fun cachedRootGitignore(root: String): List<String> {
        gitignoreCache?.let { return it }
        val patterns = runCatching {
            val path = "$root/.gitignore"
            if (!fileAccess.exists(path)) return emptyList()
            fileAccess.readFile(path).lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("!") }
                .map { it.removeSuffix("/") }
                .toList()
        }.getOrDefault(emptyList())
        gitignoreCache = patterns
        return patterns
    }

    private fun appendBrowseChildren(
        parent: String,
        entries: List<FileEntry>,
        depth: Int,
        expanded: Set<String>,
        ignorePatterns: List<String>,
        relParts: List<String>,
        out: MutableList<FileTreeNode>
    ) {
        // Deduplicate by name: the filesystem's readdir on FUSE storage (external workspaces / emulated storage) can return the same entry twice,
        // and AOSP's ReaddirHelper does the same. Entries with the same name produce duplicate node paths, which break LazyColumn keys.
        for (entry in entries.distinctBy { it.name }.sortedWith(BROWSE_ORDER)) {
            val path = "$parent/${entry.name}"
            val parts = relParts + entry.name
            val ignored = ignorePatterns.isNotEmpty() &&
                GitIgnoreMatcher.isIgnored(ignorePatterns, parts)
            val open = entry.isDirectory && path in expanded
            if (!open) {
                out += FileTreeNode(entry, path, depth, isRoot = false, isExpanded = false, ignored = ignored)
                continue
            }
            val children = listDirCached(path)
            out += FileTreeNode(entry, path, depth, isRoot = false, isExpanded = true, hasError = children == null, ignored = ignored)
            if (children != null) appendBrowseChildren(path, children, depth + 1, expanded, ignorePatterns, parts, out)
        }
    }

    /** Manual full refresh: clears the directory cache, then triggers a rebuild, guaranteeing what is shown matches the disk (the only source of truth in remote mode). */
    fun refreshBrowse() {
        dirListingCache.clear()
        gitignoreCache = null
        _browseRefresh.value++
    }

    /** Triggers a rebuild without clearing the cache (for precise-invalidation cases after write operations; user-visible full refresh goes through [refreshBrowse]). */
    private fun rebuildBrowseAfterWrite() {
        _browseRefresh.value++
    }

    /** Expands/collapses a directory; when collapsing, all its descendants are removed from the expanded set too, avoiding leftover watches and unexpectedly deep expansion on re-expanding. Persisted per workspace after the change.
     * Expanding has to wait for the directory listing to return; meanwhile [expandingPath] is marked so the UI shows a waiting animation, and repeated taps on the same directory are ignored (otherwise it would be collapsed again). */
    fun toggleExpand(path: String) {
        if (path == _expandingPath.value) return
        val current = _expandedPaths.value
        val updated = if (path in current) {
            current.filterNot { it == path || it.startsWith("$path/") }.toSet()
        } else {
            _expandingPath.value = path
            current + path
        }
        _expandedPaths.value = updated
        saveExpansion(_currentWorkspace.value, updated)
    }

    /**
     * Shared wrapper for file-browsing write operations: runs on the IO dispatcher, and on success actively re-reads the directory (remote mode has no inotify).
     * [busyPaths] are the entry paths involved in this operation; during the operation they are added to [fileOpPaths] so the UI shows a waiting animation.
     * [block] returning false means the name is invalid or an entry with the same name already exists; throwing means an IO failure; both are reported as failures.
     */
    private fun mutateBrowse(
        busyPaths: Set<String> = emptySet(),
        onResult: (Boolean) -> Unit,
        block: () -> Boolean
    ) = viewModelScope.launch {
        if (busyPaths.isNotEmpty()) _fileOpPaths.value = _fileOpPaths.value + busyPaths
        try {
            val success = withContext(Dispatchers.IO) {
                runCatching(block)
                    .onFailure { FileLogger.w(TAG, "File operation failed", it) }
                    .getOrDefault(false)
            }
            if (success) {
                // Precise invalidation: only the directories involved in this operation are re-read (especially important when ROOT/SSH has no inotify fallback),
                // the rest of the cache is left as-is -- one small operation no longer triggers a re-listing of the whole tree.
                busyPaths.forEach { path ->
                    dirListingCache.invalidate(path.substringBeforeLast('/', ""))
                    dirListingCache.invalidateTree(path)
                }
                rebuildBrowseAfterWrite()
            }
            onResult(success)
        } finally {
            if (busyPaths.isNotEmpty()) _fileOpPaths.value = _fileOpPaths.value - busyPaths
        }
    }

    /** The child path under the [parent] directory; returns null if the name is invalid. */
    private fun browseChildPath(parent: String, name: String): String? =
        if (isValidFileEntryName(name)) "$parent/${name.trim()}" else null

    /** Creates a new empty file in the [parent] directory. */
    fun createBrowseFile(parent: String, name: String, onResult: (Boolean) -> Unit) {
        val target = browseChildPath(parent, name)
        mutateBrowse(busyPaths = target?.let { setOf(it) } ?: emptySet(), onResult = onResult) {
            if (target == null || fileAccess.exists(target)) {
                false
            } else {
                fileAccess.writeFile(target, "", overwrite = false)
                true
            }
        }
    }

    /** Creates a new folder in the [parent] directory. */
    fun createBrowseFolder(parent: String, name: String, onResult: (Boolean) -> Unit) {
        val target = browseChildPath(parent, name)
        mutateBrowse(busyPaths = target?.let { setOf(it) } ?: emptySet(), onResult = onResult) {
            if (target == null || fileAccess.exists(target)) {
                false
            } else {
                fileAccess.mkdirs(target)
                fileAccess.isDirectory(target)
            }
        }
    }

    /** Renames an entry (rename within the same directory only; does not move across directories). */
    fun renameBrowseEntry(path: String, newName: String, onResult: (Boolean) -> Unit) =
        mutateBrowse(busyPaths = setOf(path), onResult = onResult) {
            val parent = path.substringBeforeLast('/', "")
            if (parent.isEmpty() || !isValidFileEntryName(newName)) {
                false
            } else {
                fileAccess.rename(path, "$parent/${newName.trim()}")
                true
            }
        }

    /** Deletes an entry; a directory is deleted recursively together with its contents. */
    fun deleteBrowseEntry(path: String, onResult: (Boolean) -> Unit) =
        mutateBrowse(busyPaths = setOf(path), onResult = onResult) {
            fileAccess.deleteRecursively(path)
            true
        }

    // region File copy / cut / paste

    /** File-browsing clipboard: temporarily stores the source entry after a copy or cut, for pasting into another directory. */
    private val _browseClipboard = MutableStateFlow<BrowseClipboard?>(null)
    val browseClipboard: StateFlow<BrowseClipboard?> = _browseClipboard.asStateFlow()

    /** Copies an entry into the clipboard (the clipboard holds only one item, so the old one is overwritten directly). */
    fun copyBrowseEntry(path: String, name: String) {
        _browseClipboard.value = BrowseClipboard(path, name, isCut = false)
    }

    /** Cuts an entry into the clipboard (the source is deleted after a successful paste, and it can be pasted only once). */
    fun cutBrowseEntry(path: String, name: String) {
        _browseClipboard.value = BrowseClipboard(path, name, isCut = true)
    }

    /** Clears the clipboard. */
    fun clearBrowseClipboard() {
        _browseClipboard.value = null
    }

    /** A paste conflict (an entry with the same name already exists at the target) awaiting user confirmation to overwrite. Holds the source path and target directory; after confirmation, calls [pasteBrowseEntryOverwrite]. */
    private val _pasteConflict = MutableStateFlow<Pair<String, String>?>(null)
    val pasteConflict: StateFlow<Pair<String, String>?> = _pasteConflict.asStateFlow()

    /** Pastes the clipboard contents into [targetDir]. If an entry with the same name already exists at the target, emits [pasteConflict] so the UI can ask whether to overwrite,
     * and does not paste; otherwise pastes directly. A successful paste clears the clipboard (whether copy or cut, it expires after one paste); a failure keeps it for a retry. */
    fun pasteBrowseEntry(targetDir: String, onResult: (Boolean) -> Unit) = viewModelScope.launch {
        val clip = _browseClipboard.value ?: return@launch onResult(false)
        if (clip.sourcePath == targetDir) return@launch onResult(false)
        val name = clip.sourceName
        if (!isValidFileEntryName(name)) return@launch onResult(false)
        val target = "$targetDir/$name"
        if (target == clip.sourcePath || target.startsWith("${clip.sourcePath.trimEnd('/')}/")) {
            return@launch onResult(false)
        }
        if (withContext(Dispatchers.IO) { fileAccess.exists(target) }) {
            _pasteConflict.value = clip.sourcePath to target
            return@launch
        }
        performPaste(clip, target, overwrite = false, onResult)
    }

    /** Force-pastes after the user confirms overwriting (overwrites even if an entry with the same name already exists at the target). */
    fun pasteBrowseEntryOverwrite() {
        val conflict = _pasteConflict.value ?: return
        val clip = _browseClipboard.value ?: return
        _pasteConflict.value = null
        performPaste(clip, conflict.second, overwrite = true) {}
    }

    /** Clears the pending paste-conflict state (when the user taps "Cancel"). */
    fun clearPasteConflict() {
        _pasteConflict.value = null
    }

    private fun performPaste(clip: BrowseClipboard, target: String, overwrite: Boolean, onResult: (Boolean) -> Unit) {
        val busyPaths = if (clip.isCut) setOf(clip.sourcePath, target) else setOf(target)
        mutateBrowse(busyPaths = busyPaths, onResult = { success ->
            // Whether copy or cut, a successful paste clears the clipboard to avoid repeated pasting; a failure keeps it, allowing a retry.
            if (success) _browseClipboard.value = null
            onResult(success)
        }) {
            if (clip.isCut) {
                fileAccess.move(clip.sourcePath, target, overwrite)
                true
            } else {
                fileAccess.copy(clip.sourcePath, target, overwrite)
                true
            }
        }
    }

    // endregion

    /** Full information about the current session (shared by root and sub-sessions; null means no session has been resolved yet). */
    val currentSessionState: StateFlow<ChatSession?> = _currentSessionId
        .flatMapLatest { id ->
            if (id == null) flowOf(null)
            else chatSessionDao.getByIdFlow(id).map { it?.toDomain() }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val currentSessionMode: StateFlow<AgentMode> = currentSessionState.map { it?.mode ?: AgentMode.BUILD }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AgentMode.BUILD)

    /** The current session's reasoning effort (default MEDIUM). */
    val currentSessionReasoningEffort: StateFlow<ReasoningEffort> =
        currentSessionState.map { it?.reasoningEffort ?: ReasoningEffort.MEDIUM }
            .stateIn(viewModelScope, SharingStarted.Eagerly, ReasoningEffort.MEDIUM)

    /** The providerId/model bound to the current session (null means unbound, falling back to the global active provider). */
    val currentSessionProviderModel: StateFlow<Pair<String?, String?>> =
        currentSessionState.map { s -> (s?.providerId ?: "") to (s?.model ?: "") }
            .stateIn(viewModelScope, SharingStarted.Eagerly, Pair(null, null))

    /**
     * Message state of the current session: on a session switch it automatically switches to the corresponding history, carrying the owning session id and a loaded flag,
     * so the UI can distinguish "switching / cold-start loading" from "empty session" -- avoiding flashing Welcome or the previous session's messages before a sudden refresh.
     * Filters out empty assistant rows that are "pure tool calls" (empty content, only for replay pairing, and should not be shown as bubbles).
     */
    val messagesState: StateFlow<ChatMessagesState> = combine(
        _currentSessionId,
        _messageLimit
    ) { id, limitMap -> id to (limitMap[id] ?: defaultLimit) }
        .flatMapLatest { (id, limit) ->
            if (id == null) flowOf(ChatMessagesState(null, emptyList(), loaded = false))
            else agentMessageDao.getMessagesBySessionPaged(id, limit).map { list ->
                ChatMessagesState(
                    sessionId = id,
                    messages = list.asSequence()
                        .filterNot {
                            it.role == MessageRole.ASSISTANT.name &&
                                !it.content.hasVisibleContent() &&
                                it.reasoning.isNullOrEmpty()
                        }
                        .map { entity -> entity.toUIMessage() }
                        .toList(),
                    loaded = true,
                    hasMore = list.size >= limit,
                    isLoadingMore = false
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ChatMessagesState(null, emptyList(), loaded = false))


    private val _runningTools = MutableStateFlow<Map<String, Map<String, RunningToolOutput>>>(emptyMap())
    val runningTool: StateFlow<List<RunningToolOutput>> = _currentSessionId
        .flatMapLatest { id ->
            if (id == null) flowOf(emptyList())
            else _runningTools.map { it[id]?.values?.toList() ?: emptyList() }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Adds/updates a running tool (located by msgId; multiple tools can run in parallel). */
    private fun setRunningTool(sessionId: String, msgId: String, tool: RunningToolOutput) {
        val sessionTools = _runningTools.value[sessionId] ?: emptyMap()
        _runningTools.value = _runningTools.value + (sessionId to (sessionTools + (msgId to tool)))
    }

    /** Removes a running tool; when a session has no running tools left, its entry is cleared. */
    private fun removeRunningTool(sessionId: String, msgId: String) {
        val sessionTools = _runningTools.value[sessionId] ?: return
        val updated = sessionTools - msgId
        _runningTools.value = if (updated.isEmpty()) {
            _runningTools.value - sessionId
        } else {
            _runningTools.value + (sessionId to updated)
        }
    }

    private val _preparingTools = MutableStateFlow<Map<String, String>>(emptyMap())

    /**
     * The name of a tool the model is streaming out and has not started executing yet (e.g. `editFile`).
     *
     * For tools with long arguments (writing a whole file, long commands), argument streaming can last several seconds, during which there is neither body text nor a reasoning delta,
     * so the UI can only show a generic "Thinking". The upstream reports as soon as a tool name appears ([AgentEvent.ToolCallPreparing]),
     * and the UI uses that to switch the status to a specific scene ("Editing file"). Once the tool really starts executing, [runningTool] takes over.
     */
    val preparingTool: StateFlow<String?> = _currentSessionId
        .flatMapLatest { id ->
            if (id == null) flowOf(null)
            else _preparingTools.map { it[id] }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private fun setPreparingTool(sessionId: String, toolName: String?) {
        val current = _preparingTools.value
        if (toolName == null) {
            if (current.containsKey(sessionId)) _preparingTools.value = current - sessionId
        } else if (current[sessionId] != toolName) {
            _preparingTools.value = current + (sessionId to toolName)
        }
    }

    private val _compactingSessions = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    private val _contextUsages = MutableStateFlow<Map<String, AgentEvent.ContextUsage>>(emptyMap())
    val contextUsages: StateFlow<Map<String, AgentEvent.ContextUsage>> = _contextUsages.asStateFlow()

    private val _llmCallEvents = MutableSharedFlow<LlmCallEvent>(extraBufferCapacity = 16)
    /** Event for each individual LLM request returning (carrying that request's token statistics). */
    val llmCallEvents: SharedFlow<LlmCallEvent> = _llmCallEvents.asSharedFlow()
    val isCompacting: StateFlow<Boolean> = _currentSessionId
        .flatMapLatest { id ->
            if (id == null) flowOf(false)
            else _compactingSessions.map { it[id] == true }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private fun setCompacting(sessionId: String, compacting: Boolean) {
        _compactingSessions.value = if (compacting) {
            _compactingSessions.value + (sessionId to true)
        } else {
            _compactingSessions.value - sessionId
        }
    }

    private val _streamingTexts = MutableStateFlow<Map<String, String?>>(emptyMap())
    val streamingText: StateFlow<String?> = _currentSessionId
        .flatMapLatest { id ->
            if (id == null) flowOf(null)
            else _streamingTexts.map { it[id] }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private fun setStreamingText(sessionId: String, text: String?) {
        _streamingTexts.value = if (text == null) _streamingTexts.value - sessionId else _streamingTexts.value + (sessionId to text)
    }

    private val _reasoningTimings = MutableStateFlow<Map<String, Pair<Long, Long?>>>(emptyMap())
    val reasoningTiming: StateFlow<Pair<Long, Long?>?> = _currentSessionId
        .flatMapLatest { id ->
            if (id == null) flowOf(null) else _reasoningTimings.map { it[id] }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _streamingReasonings = MutableStateFlow<Map<String, String?>>(emptyMap())
    val streamingReasoning: StateFlow<String?> = _currentSessionId
        .flatMapLatest { id ->
            if (id == null) flowOf(null)
            else _streamingReasonings.map { it[id] }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private fun setStreamingReasoning(sessionId: String, text: String?) {
        if (text == null) _reasoningTimings.value = _reasoningTimings.value - sessionId
        _streamingReasonings.value = if (text == null) _streamingReasonings.value - sessionId else _streamingReasonings.value + (sessionId to text)
    }

    /** Retry state maintained per sessionId; set to null when streaming resumes or ends. */
    private val _retryStates = MutableStateFlow<Map<String, RetryState?>>(emptyMap())
    val retryState: StateFlow<RetryState?> = _currentSessionId
        .flatMapLatest { id ->
            if (id == null) flowOf(null)
            else _retryStates.map { it[id] }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private fun setRetryState(sessionId: String, state: RetryState?) {
        _retryStates.value = if (state == null) _retryStates.value - sessionId else _retryStates.value + (sessionId to state)
    }

    /** Multi-key switching hint maintained per sessionId; set to null when content appears again or the turn ends. */
    private val _keySwitchStates = MutableStateFlow<Map<String, KeySwitchState?>>(emptyMap())
    val keySwitchState: StateFlow<KeySwitchState?> = _currentSessionId
        .flatMapLatest { id ->
            if (id == null) flowOf(null)
            else _keySwitchStates.map { it[id] }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private fun setKeySwitchState(sessionId: String, state: KeySwitchState?) {
        _keySwitchStates.value = if (state == null) _keySwitchStates.value - sessionId else _keySwitchStates.value + (sessionId to state)
    }

    val pendingToolPermission = toolPermissionManager.pendingRequest

    /** Title of the session the currently shown permission dialog belongs to (so the dialog can label its owner when multiple sessions run in parallel). Falls back to an empty string if the session does not exist. */
    val pendingToolPermissionSessionTitle: StateFlow<String> = combine(
        toolPermissionManager.pendingRequest,
        sessions,
        subSessionsByParent
    ) { req, roots, subs ->
        val sid = req?.sessionId.orEmpty()
        if (sid.isBlank()) return@combine ""
        val title = roots.firstOrNull { it.id == sid }?.title
            ?: subs.values.asSequence().flatten().firstOrNull { it.id == sid }?.title
        title.orEmpty()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, "")

    /** Set of session ids waiting for user permission, so sidebar session rows can light an orange indicator. */
    val awaitingPermissionSessionIds: StateFlow<Set<String>> = toolPermissionManager.awaitingSessionIds

    val pendingUserQuestion = askUserQuestionManager.pendingQuestion

    private val _queuedRequests = MutableStateFlow<Map<String, List<QueuedRequest>>>(emptyMap())
    // Set of sessions currently executing a slash command: during command execution they also count as busy (
    // sessionJobs is not registered, otherwise self-checks inside commands such as /compress would misjudge them as running),
    // used by enqueueAgentRequest to decide that a new message should be queued rather than executed in parallel.
    private val _runningCommandSessions = MutableStateFlow<Set<String>>(emptySet())
    val queuedRequests: StateFlow<List<QueuedRequest>> = _currentSessionId
        .flatMapLatest { id ->
            if (id == null) flowOf(emptyList())
            else _queuedRequests.map { it[id] ?: emptyList() }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val pendingPlanApproval: StateFlow<PlanApprovalRequest?> = planApprovalManager.pendingApproval

    // Tool-call input arguments (argsPreview) are temporarily stored by persisted message id: after ToolCallStarted is persisted,
    // ToolCallFinished / a user stop replaces the whole row under the same id, so the arguments must be carried here into later persistence.
    private val toolArgsByMsgId = mutableMapOf<String, String>()

    /** Whether there is a running agent task that can be interrupted. */
    val isRunning: Boolean get() {
        val sid = _currentSessionId.value ?: return false
        return sessionJobs[sid]?.isActive == true
    }

    fun hasRunningSessionsInCurrentWorkspace(): Boolean {
        if (sessions.value.any { sessionJobs[it.id]?.isActive == true }) return true
        // Sub-agent sessions are not in sessions (which holds only root sessions): missing them would, at the end of the parent agent's turn,
        // release the wake lock and foreground service early, and a still-running sub-agent would be suspended by the system.
        return subSessionsByParent.value.values.any { subs ->
            subs.any { sessionJobs[it.id]?.isActive == true }
        }
    }

    /**
     * Task start: brings up the foreground keepalive service (registering as an active session) and acquires the CPU wake lock,
     * ensuring the process is not suspended by the system and network I/O does not drop when switching to the background / turning the screen off.
     *
     * Uses startSession (ACTION_START_SESSION) rather than enablePersistent:
     * - startSession increments sessionCount, so the foreground service knows an active session is running;
     * - startSession starts via startForegroundService, so it can also be brought up correctly when returning from the background on Android 8+;
     * - ensureForeground shows "N sessions running" based on sessionCount > 0, rather than a generic "Keepalive is on".
     */
    private fun acquireKeepalive() {
        TerminalKeepaliveService.startSession(context)
        // With ROOT, also lift the system-side background network restrictions (netpolicy/standby/appops);
        // without ROOT, silently skip. Runs on the IO thread: internally it must fork a su child process and must not touch the main thread.
        viewModelScope.launch(Dispatchers.IO) { rootNetworkGuard.applyIfRooted() }
        if (wakeLock.isHeld) return
        runCatching { wakeLock.acquire(KEEPALIVE_TIMEOUT_MS) }
            .onFailure { FileLogger.e(TAG, "acquire wakeLock failed", it) }
    }

    /**
     * Task finish: releases the wake lock; ends the active-session count (sessionCount decrements).
     * Stops the foreground service only when there is neither a background terminal task nor a user-enabled manual keepalive.
     */
    private fun releaseKeepalive() {
        if (wakeLock.isHeld) {
            runCatching { wakeLock.release() }
                .onFailure { FileLogger.e(TAG, "release wakeLock failed", it) }
        }
        TerminalKeepaliveService.stopSession(context)
        if (!userKeepaliveEnabled && !terminalSessionManager.hasBackgroundTabs()) {
            TerminalKeepaliveService.disablePersistent(context)
        }
    }

    /** When the agent completes while the App is in the background, shows a tappable system notification (title = task completed, body = user message). */
    private fun showAgentCompletedNotification(userRequest: String) {
        val openAppIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, AGENT_COMPLETE_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle(context.getString(R.string.agent_complete_notification_title))
            .setContentText(agentCompleteNotificationBody(userRequest))
            .setContentIntent(openAppIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(AGENT_COMPLETE_NOTIFICATION_ID, notification)
        }.onFailure { FileLogger.e(TAG, "Failed to send agent completion notification", it) }
    }

    /**
     * System notification body: an ordinary user message is shown directly; for a turn triggered by a background callback, the internally constructed
     * "[System notification - not user input]..." text is not exposed raw; the task title is shown instead.
     */
    private fun agentCompleteNotificationBody(userRequest: String): String {
        if (userRequest.startsWith(BACKGROUND_NOTIFICATION_PREFIX)) {
            val titles = TASK_NOTIFICATION_TITLE_REGEX.findAll(userRequest)
                .map { it.groupValues[1] }
                .filter { it.isNotBlank() }
                .toList()
            return when {
                titles.isEmpty() -> context.getString(R.string.agent_complete_notification_body)
                titles.size == 1 -> context.getString(
                    R.string.agent_complete_notification_background_body, titles.first()
                )
                else -> context.getString(
                    R.string.agent_complete_notification_background_multi_body, titles.size
                )
            }
        }
        return userRequest.ifBlank { context.getString(R.string.agent_complete_notification_body) }
    }

    private companion object {
        const val TAG = "AIAgentViewModel"
        const val AGENT_COMPLETE_CHANNEL = "agent_complete"
        const val AGENT_COMPLETE_NOTIFICATION_ID = 100
        /** wakeLock timeout safeguard: tools such as building and installing dependencies routinely take over ten minutes, so allow a full 60 minutes; a normally finishing task releases it actively. */
        const val KEEPALIVE_TIMEOUT_MS = 60 * 60 * 1000L
        /** Extracts the <title> content from background-task notification text, for display in the system notification body. */
        val TASK_NOTIFICATION_TITLE_REGEX = Regex("<title>([^<]+)</title>")
        /** File-browsing sort order: directories first, and within the same kind, by name case-insensitively. */
        val BROWSE_ORDER: Comparator<FileEntry> =
            compareByDescending<FileEntry> { it.isDirectory }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }

        /** Writing a file triggers multiple inotify events in quick succession; they are merged before the directory is re-read. */
        const val BROWSE_DEBOUNCE_MS = 300L
    }

    init {
        // Cold-start cleanup: tools left "running" when the previous process was killed are backfilled as "Interrupted". Runs in the background and does not block the first screen or session display.
        viewModelScope.launch(Dispatchers.IO) {
            sessionUseCase.initColdStartCleanup()
        }

        // Re-scans skills on startup and workspace switches (project-level skills change with the workspace), refreshing the `/` command menu.
        viewModelScope.launch {
            _currentWorkspace.collect { slashCommandRegistry.refresh() }
        }

        viewModelScope.launch {
            _currentWorkspace.collectLatest { path ->
                if (path.isBlank()) return@collectLatest
                val recent = sessionUseCase.getMostRecentSessionOfWorkspace(path)
                // Determine and set the current session immediately, ensuring the first UI frame opens instantly and the new session appears in the sidebar at once, completely eliminating spinner lag.
                // "Open the most recent session": if there is history, go straight to the most recent one (by updatedAt, unaffected by pinning);
                // "Start a new session": reuse an empty session that has not sent a message yet (avoiding piling up an empty session on every startup); otherwise create a new one.
                val targetId = when {
                    recent == null -> createAndUpsertSession(path)
                    generalSettingsRepository.startupSessionMode() == StartupSessionMode.RECENT_SESSION -> recent.id
                    sessionUseCase.isSessionEmpty(recent.id) -> recent.id
                    else -> createAndUpsertSession(path)
                }
                _currentSessionId.value = targetId

                // Asynchronously reclaim surplus empty sessions (keeping the current targetId), in the background, without blocking the main thread or first-frame rendering.
                if (recent != null) {
                    launch(Dispatchers.IO) {
                        sessionUseCase.recycleEmptySessions(path, keepId = targetId)
                    }
                }
            }
        }

        // Subscribes to background command completion events: when a command with notify=true ends, a message is automatically injected and a new AI turn is triggered.
        // Events arriving while a session is busy are cached and merged into a single message after the turn ends (see [flushMergedNotifications]).
        viewModelScope.launch {
            terminalSessionManager.tabFinishedEvents.collect { event ->
                handleBackgroundCommandFinished(event)
            }
        }

        viewModelScope.launch {
            keepaliveSettings.enabledFlow.collect { userKeepaliveEnabled = it }
        }

        // Subscribes to sub-agent lifecycle events (analogous to the terminal's notify=true asynchronous callbacks):
        // - SPAWNED: the task tool has created a sub-session and sent a message on the user's behalf; the AI workflow is started automatically on the sub-session here;
        // - STOPPED: task(action="stop") requests stopping a sub-agent, cancelling the AI task of the corresponding session;
        // - COMPLETED/FAILED: emitted when a sub-session workflow ends (see handleSubAgentFinished), injecting a notification into the parent session.
        viewModelScope.launch {
            subAgentEventBus.events.collect { event ->
                when (event.type) {
                    SubAgentEventType.SPAWNED -> spawnSubAgentWorkflow(event)
                    SubAgentEventType.STOPPED -> stopAgentSession(event.subSessionId)
                    SubAgentEventType.COMPLETED, SubAgentEventType.FAILED -> {
                        enqueueSubAgentNotification(event)
                    }
                    SubAgentEventType.MESSAGE_FROM_PARENT -> deliverMessageToSubAgent(event)
                    SubAgentEventType.MESSAGE_FROM_SUB -> deliverMessageToParent(event)
                }
            }
        }
    }

    /**
     * A sub-agent has been created (the task tool has created a sub-session): starts the AI workflow on the sub-session.
     * The message is persisted uniformly by executeAgentRequestStream (consistent with a user sending a message manually),
     * and the title keeps the description passed by task (skipTitleUpdate).
     */
    private suspend fun spawnSubAgentWorkflow(event: SubAgentEvent) {
        val parentSession = sessionUseCase.getSessionById(event.parentSessionId)
        if (parentSession == null) {
            FileLogger.w(TAG, "Sub-agent parent session does not exist: ${event.parentSessionId}")
            return
        }
        // The sub-session may already have been deleted by the user; skip
        if (sessionUseCase.getSessionById(event.subSessionId) == null) {
            FileLogger.w(TAG, "Sub-agent session has been deleted, skipping start: ${event.subSessionId}")
            return
        }
        // A running sub-agent must not be started again (the same session already has an active job)
        if (sessionJobs[event.subSessionId]?.isActive == true) return

        executeAgentRequestStream(
            request = event.detail,
            projectRoot = parentSession.workspacePath,
            targetSessionId = event.subSessionId,
            skipTitleUpdate = true
        )
    }

    /**
     * Sub-agent completion/failure notification: if the parent session is busy it is queued first (delivered piggybacked on the next batch of tool results in this turn, see
     * [com.aicode.feature.agent.domain.workflow.StatefulAgentWorkflow]); if idle, a system notification message is injected immediately to trigger a new turn.
     * The parent agent thereby learns the sub-agent has finished and can retrieve the result with task(action="read"). Same mechanism as terminal background notifications.
     */
    private fun enqueueSubAgentNotification(event: SubAgentEvent) {
        viewModelScope.launch {
            val title = sessionUseCase.getSessionById(event.subSessionId)?.title ?: "Sub-agent"
            notifyParentSubAgentFinished(
                parentSessionId = event.parentSessionId,
                subSessionId = event.subSessionId,
                title = title,
                outcome = if (event.type == SubAgentEventType.FAILED) {
                    NotificationOutcome.FAILED
                } else {
                    NotificationOutcome.COMPLETED
                },
                detail = event.detail.takeIf { it.isNotBlank() && event.type == SubAgentEventType.FAILED }
            )
        }
    }

    /**
     * A message from the main session to a running/completed sub-agent: if the recipient is busy it is queued to piggyback; if idle it triggers a new turn.
     * Same dispatch logic as [notifyParentSubAgentFinished], in the opposite direction.
     */
    private suspend fun deliverMessageToSubAgent(event: SubAgentEvent) {
        val senderTitle = sessionUseCase.getSessionById(event.parentSessionId)?.title ?: "Main session"
        deliverAgentMessage(
            recipientSessionId = event.subSessionId,
            senderSessionId = event.parentSessionId,
            senderTitle = senderTitle,
            message = event.detail,
            fromParent = true
        )
    }

    /** A message from a sub-agent to the main session: same as above, with the main session as recipient. */
    private suspend fun deliverMessageToParent(event: SubAgentEvent) {
        val senderTitle = sessionUseCase.getSessionById(event.subSessionId)?.title ?: "Sub-agent"
        deliverAgentMessage(
            recipientSessionId = event.parentSessionId,
            senderSessionId = event.subSessionId,
            senderTitle = senderTitle,
            message = event.detail,
            fromParent = false
        )
    }

    /**
     * Delivers an inter-agent message: if the recipient is busy it goes into [AgentNotificationCenter] (piggybacked on tool results within the turn, or as a fallback after the whole turn ends),
     * if idle, a notification message triggers its new turn. The message body is delivered with the notification, so the recipient does not need to read it separately.
     */
    private suspend fun deliverAgentMessage(
        recipientSessionId: String,
        senderSessionId: String,
        senderTitle: String,
        message: String,
        fromParent: Boolean
    ) {
        if (message.isBlank()) return
        if (sessionUseCase.getSessionById(recipientSessionId) == null) return
        val item = PendingNotification(
            kind = AgentNotificationKind.AGENT_MESSAGE,
            sourceId = senderSessionId,
            title = senderTitle,
            outcome = NotificationOutcome.COMPLETED,
            message = message,
            fromParent = fromParent
        )
        deliverSystemEvent(recipientSessionId, item)
    }

    /**
     * Delivers a sub-agent finished notification to the parent session: if the parent session is busy it is queued to piggyback; if idle it is injected immediately to trigger a new turn.
     * Both failure and termination must carry the reason, otherwise the parent agent only knows "it did not work" and has to read again to possibly get a clue.
     */
    private suspend fun notifyParentSubAgentFinished(
        parentSessionId: String,
        subSessionId: String,
        title: String,
        outcome: NotificationOutcome,
        detail: String?
    ) {
        val item = PendingNotification(
            kind = AgentNotificationKind.SUBAGENT,
            sourceId = subSessionId,
            title = title,
            outcome = outcome,
            detail = detail
        )
        deliverSystemEvent(parentSessionId, item)
    }

    /**
     * Callback after a background command (notify=true) ends.
     *
     * - Session busy: queued in [agentNotificationCenter] and delivered piggybacked on the next batch of tool results in this turn (the AI perceives it in the same turn,
     *   saving the LLM round trip of "wait for this turn to end, then start another"); if there are no more tool calls in the whole turn, [flushPendingNotifications] is the fallback.
     * - Session idle: immediately triggers a new Agent turn with a system notification message.
     *
     * The fallback path uses a user message rather than an assistant(tool_call) + tool_result message pair: the latter would be out of order in persistence order relative to the original terminal tool call's
     * tool result (a background callback fires asynchronously and may persist before the original terminal result), causing the messages
     * to violate OpenAI's pairing constraint "assistant(tool_calls) -> tool results immediately follow", and the upstream returns 400. A user message needs to pair with
     * no tool_call, so it naturally does not break ordering. The notification text carries a fenced explanation, preventing the AI from mistaking it for a new user instruction or approval;
     * the AI uses terminal(read) to retrieve the full output accordingly.
     *
     * Does not persist the notification itself and, with isAutoTrigger=false, goes through the normal enqueueAgentRequest flow:
     * executeAgentRequestStream persists this user message uniformly, and the same UserMessage appended by the workflow's InitRequest
     * is that message, avoiding duplicate persistence or an empty placeholder message.
     */
    private fun handleBackgroundCommandFinished(event: TabFinishedEvent) {
        val sessionId = event.sourceSessionId ?: return
        val jobActive = sessionJobs[sessionId]?.isActive == true
        val currentSid = _currentSessionId.value
        FileLogger.d(TAG, "handleBgFinished: eventSid=$sessionId currentSid=$currentSid jobActive=$jobActive state=${_agentStates.value[sessionId]}")
        val item = event.toPendingNotification()
        deliverSystemEvent(sessionId, item)
    }

    /**
     * Uniformly delivers a system event to a given session: if busy it goes into [agentNotificationCenter] and is delivered piggybacked on tool results in this turn;
     * if idle, a system notification message triggers a new turn. Background-task completion, sub-agent finishing, inter-agent messages, and mode switches share this dispatch,
     * avoiding duplicated busy/idle checks everywhere.
     */
    private fun deliverSystemEvent(sessionId: String, item: PendingNotification) {
        if (sessionJobs[sessionId]?.isActive == true) {
            agentNotificationCenter.enqueue(sessionId, item)
            return
        }
        viewModelScope.launch {
            enqueueAgentRequest(
                request = AgentNotificationFormatter.buildMessage(listOf(item)),
                projectRoot = _currentWorkspace.value,
                targetSessionId = sessionId
            )
        }
    }

    private fun TabFinishedEvent.toPendingNotification() = PendingNotification(
        kind = AgentNotificationKind.BACKGROUND_TASK,
        sourceId = tabId,
        title = title,
        outcome = if (exitCode == 0) NotificationOutcome.COMPLETED else NotificationOutcome.FAILED,
        command = command,
        exitCode = exitCode,
        tailOutput = tailOutput
    )

    /**
     * Fallback delivery after a turn ends: merges the notifications still in the queue into a single message and sends it.
     * Notifications already delivered piggybacked on tool results have been acked and removed by then; if nothing is retrieved, do nothing.
     */
    private fun flushPendingNotifications(sessionId: String) {
        val items = agentNotificationCenter.drain(sessionId)
        if (items.isEmpty()) return
        FileLogger.d(TAG, "flushPendingNotifications: sid=$sessionId items=${items.size} state=${_agentStates.value[sessionId]}")
        viewModelScope.launch {
            enqueueAgentRequest(
                request = AgentNotificationFormatter.buildMessage(items),
                projectRoot = _currentWorkspace.value,
                targetSessionId = sessionId
            )
        }
    }

    fun enqueueAgentRequest(
        request: String,
        modelRequest: String = request,
        currentFile: String? = null,
        selectedCode: String? = null,
        projectRoot: String = "",
        inputImages: List<AgentImage> = emptyList(),
        inputAttachments: List<AgentAttachment> = emptyList(),
        isAutoTrigger: Boolean = false,
        targetSessionId: String? = null
    ) {
        val sid = targetSessionId ?: _currentSessionId.value
        val isCurrentRunning = sid != null &&
            (sessionJobs[sid]?.isActive == true || sid in _runningCommandSessions.value)
        if (isCurrentRunning) {
            val req = QueuedRequest(
                id = UUID.randomUUID().toString(),
                request = request,
                modelRequest = modelRequest,
                currentFile = currentFile,
                selectedCode = selectedCode,
                projectRoot = projectRoot,
                inputImages = inputImages,
                inputAttachments = inputAttachments,
                isAutoTrigger = isAutoTrigger
            )
            val currentList = _queuedRequests.value[sid] ?: emptyList()
            _queuedRequests.value = _queuedRequests.value + (sid to (currentList + req))
        } else {
            executeAgentRequestStream(
                request = request,
                modelRequest = modelRequest,
                currentFile = currentFile,
                selectedCode = selectedCode,
                projectRoot = projectRoot,
                inputImages = inputImages,
                inputAttachments = inputAttachments,
                targetSessionId = sid,
                isAutoTrigger = isAutoTrigger
            )
        }
    }

    /** Removes a given entry from the current session queue (queue panel delete button). */
    fun removeQueuedRequest(id: String) {
        val sid = _currentSessionId.value ?: return
        val queue = _queuedRequests.value[sid] ?: return
        _queuedRequests.value = _queuedRequests.value + (sid to queue.filterNot { it.id == id })
    }

    /** Reorders an entry in the current session queue (queue panel drag sorting). */
    fun moveQueuedRequest(fromIndex: Int, toIndex: Int) {
        val sid = _currentSessionId.value ?: return
        val queue = _queuedRequests.value[sid] ?: return
        if (fromIndex !in queue.indices || toIndex !in queue.indices || fromIndex == toIndex) return
        val reordered = queue.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
        _queuedRequests.value = _queuedRequests.value + (sid to reordered)
    }

    /** Edits the text of an entry in the current session queue (queue panel edit). */
    fun updateQueuedRequest(id: String, newText: String) {
        val text = newText.trim()
        if (text.isEmpty()) return
        val sid = _currentSessionId.value ?: return
        val queue = _queuedRequests.value[sid] ?: return
        _queuedRequests.value = _queuedRequests.value + (sid to queue.map { req ->
            if (req.id != id) req
            else req.copy(request = text, modelRequest = syncQueuedModelRequest(req, text))
        })
    }

    /**
     * Rebuilds modelRequest from the edited text: with no attachment injection it stays in sync with request; with attachment injection
     * (assembled as `request + "\n\n" + attachment list`) only the text prefix is replaced, keeping the attachment part.
     */
    private fun syncQueuedModelRequest(req: QueuedRequest, newText: String): String {
        if (req.modelRequest == req.request) return newText
        val prefix = req.request.trimEnd()
        return if (req.modelRequest.startsWith(prefix)) {
            newText.trimEnd() + req.modelRequest.removePrefix(prefix)
        } else {
            newText
        }
    }

    /**
     * Immediately inserts a message from the current session queue into the running turn: removes it from the queue and turns it into a user interjection notification handed to
     * [deliverSystemEvent] -- while busy it is injected piggybacked into this batch of tool results, so the AI perceives it in the current turn; while idle it is sent as
     * a new-turn user message.
     */
    fun interjectQueuedRequest(id: String) {
        val sid = _currentSessionId.value ?: return
        val queue = _queuedRequests.value[sid] ?: return
        val req = queue.firstOrNull { it.id == id } ?: return
        _queuedRequests.value = _queuedRequests.value + (sid to queue.filterNot { it.id == id })
        deliverSystemEvent(
            sid,
            PendingNotification(
                kind = AgentNotificationKind.USER_MESSAGE,
                sourceId = sid,
                title = "",
                outcome = NotificationOutcome.COMPLETED,
                message = req.request
            )
        )
    }

    private fun processNextInQueue(sessionId: String) {
        // If there is already an active job (possibly started by an entry point such as notification merging/flush before this finish), do not consume,
        // avoiding the queue being consumed twice by multiple finish entry points and two jobs running concurrently for the same session.
        if (sessionJobs[sessionId]?.isActive == true) return
        val queue = _queuedRequests.value[sessionId] ?: return
        val next = queue.firstOrNull() ?: return
        _queuedRequests.value = _queuedRequests.value + (sessionId to queue.drop(1))
        executeAgentRequestStream(
            request = next.request,
            modelRequest = next.modelRequest,
            currentFile = next.currentFile,
            selectedCode = next.selectedCode,
            projectRoot = next.projectRoot,
            inputImages = next.inputImages,
            inputAttachments = next.inputAttachments,
            targetSessionId = sessionId,
            isAutoTrigger = next.isAutoTrigger
        )
    }

    /**
     * Executes a slash command: first persists the command text as a user message (entering the conversation context), then dispatches by type --
     * built-in commands run local actions, while a skill uses its body as this turn's instruction to trigger an agent turn.
     * During execution it is marked as command-occupied (preventing new messages from executing in parallel), and afterwards the next queued item in the queue continues.
     */
    private fun runResolvedCommand(resolved: ResolvedCommand, input: String, sessionId: String) {
        viewModelScope.launch {
            _runningCommandSessions.value = _runningCommandSessions.value + sessionId
            try {
                messagePersistenceUseCase.persist(sessionId, MessageRole.USER, input)
                sessionUseCase.touch(sessionId, messagePersistenceUseCase.nextTimestamp())
                when (resolved) {
                    is ResolvedCommand.Action -> resolved.handler.execute(this@AIAgentViewModel, resolved.args)
                    is ResolvedCommand.SkillCommand -> runSkill(resolved.skill, resolved.args)
                }
            } finally {
                _runningCommandSessions.value = _runningCommandSessions.value - sessionId
                processNextInQueue(sessionId)
            }
        }
    }

    fun executeAgentRequestStream(
        request: String,
        modelRequest: String = request,
        currentFile: String? = null,
        selectedCode: String? = null,
        projectRoot: String = "",
        inputImages: List<AgentImage> = emptyList(),
        inputAttachments: List<AgentAttachment> = emptyList(),
        targetSessionId: String? = null,
        isAutoTrigger: Boolean = false,
        /** Cases such as sub-agents: the session title is preset, so title derivation/generation from the first message is skipped and the preset title is kept. */
        skipTitleUpdate: Boolean = false
    ): Job = viewModelScope.launch {
        val sessionId = targetSessionId ?: ensureSession()
        if (sessionId.isBlank()) {
            FileLogger.w(TAG, "Workspace not ready, skipping request")
            return@launch
        }
        // Command routing: on hitting a slash command (built-in command or skill), the agent workflow is not used; the command operation is executed directly
        // (the command text has already been persisted as a user message, entering the conversation context). sessionJobs is not registered,
        // so isRunning stays false, and self-checks inside commands such as /compress can work normally.
        if (request.startsWith("/")) {
            slashCommandRegistry.resolve(request)?.let { command ->
                runResolvedCommand(command, request, sessionId)
                return@launch
            }
        }
        // Compatibility with historical sessions: if a session has not yet persistently bound a providerId/model, pin it when sending a message, so later changes to the default model do not affect existing sessions
        val currentSession = sessionUseCase.getSessionById(sessionId)
        if (currentSession != null && (currentSession.providerId.isNullOrBlank() || currentSession.model.isNullOrBlank())) {
            val defaultProviderId = defaultModelSettingsRepository.getDefaultProviderId().takeIf { it.isNotBlank() }
            val defaultModel = defaultModelSettingsRepository.getDefaultModel().takeIf { it.isNotBlank() }
            if (defaultProviderId != null && defaultModel != null) {
                sessionUseCase.updateProviderModel(sessionId, defaultProviderId, defaultModel)
            }
        }

        coroutineContext[Job]?.let { sessionJobs[sessionId] = it }
        FileLogger.d(TAG, "stream start: sid=$sessionId prevState=${_agentStates.value[sessionId]} isAutoTrigger=$isAutoTrigger")
        setAgentState(sessionId, AgentUIState.Streaming)
        acquireKeepalive()

        try {
            var failed = false
            // History must be read before inserting this user message: the workflow adds (userRequest) itself, avoiding duplication.
            val history = messagePersistenceUseCase.buildHistory(sessionId, SessionUseCase.PENDING_TOOL_MARKER)
            val isFirst = history.isEmpty()

            val userMsgId = UUID.randomUUID().toString()
            if (!isAutoTrigger) {
                messagePersistenceUseCase.persist(sessionId, MessageRole.USER, request, id = userMsgId, attachments = inputAttachments)
                checkpointManager.createCheckpoint(sessionId, userMsgId, request)
                if (isFirst && !skipTitleUpdate) {
                    sessionUseCase.updateTitle(sessionId, sessionUseCase.deriveTitle(request))
                    // Asynchronously in the background, use the LLM to generate a more fitting title to replace the temporary title; on failure / unavailability, keep the temporary title
                    viewModelScope.launch {
                        agentWorkflow.generateTitle(sessionId, request)?.let { sessionUseCase.updateTitle(sessionId, it) }
                    }
                }
            } else {
                messagePersistenceUseCase.persist(sessionId, MessageRole.USER, modelRequest, id = userMsgId,
                    attachments = inputAttachments)
            }
            messagePersistenceUseCase.invalidateHistory(sessionId)
            sessionUseCase.touch(sessionId, messagePersistenceUseCase.nextTimestamp())

            val sessionEntity = sessionUseCase.getSessionById(sessionId)
            val sessionDomain = sessionEntity?.toDomain()
            val mode = sessionDomain?.mode ?: AgentMode.BUILD
            // A sub-session's subagentType stores the custom agent name; when its definition can be found, both the prompt and the tool set are assembled from it.
            val agentDefinition = sessionEntity?.takeIf { it.parentId != null }
                ?.subagentType
                ?.let { agentDefinitionRepository.findIncludingDisabled(it) }

            val agentContext = AgentContext(
                currentFile = currentFile,
                selectedCode = selectedCode,
                projectRoot = projectRoot,
                language = currentFile?.let { detectLanguage(it) },
                history = history,
                inputImages = inputImages,
                sessionId = sessionId,
                inputMessageId = userMsgId,
                lastInputTokens = sessionEntity?.lastInputTokens ?: 0,
                mode = mode,
                modeBeforePlan = sessionDomain?.modeBeforePlan,
                reasoningEffort = sessionDomain?.reasoningEffort?.apiValue,
                agentDefinition = agentDefinition
            )

            val allTools = toolRegistry.getAvailableTools()
            val isSub = sessionEntity?.parentId != null
            val tools = when {
                agentDefinition != null -> {
                    val allowed = agentDefinition.filterToolNames(allTools.map { it.name }).toSet()
                    allTools.filter { it.name in allowed }
                }
                isSub -> allTools.filterNot { it.name == AgentDefinition.NESTED_TOOL }
                else -> allTools.filterNot { it.name == AgentDefinition.PARENT_MESSAGE_TOOL }
            }

            var currentReasoningStart: Long? = null
            var currentReasoningEnd: Long? = null
            fun finishReasoning() {
                val start = currentReasoningStart ?: return
                if (currentReasoningEnd == null) {
                    val end = System.currentTimeMillis()
                    currentReasoningEnd = end
                    _reasoningTimings.value = _reasoningTimings.value + (sessionId to (start to end))
                }
            }
            agentWorkflow.executeEvents(
                userRequest = modelRequest,
                context = agentContext,
                tools = tools
            ).collect { event ->
                when (event) {
                    is AgentEvent.AssistantDelta -> {
                        if (event.accumulated.hasVisibleContent()) finishReasoning()
                        setRetryState(sessionId, null)
                        setKeySwitchState(sessionId, null)
                        setStreamingText(sessionId, event.accumulated)
                    }
                    is AgentEvent.ReasoningDelta -> {
                        setRetryState(sessionId, null)
                        setKeySwitchState(sessionId, null)
                        if (currentReasoningStart == null) {
                            currentReasoningStart = System.currentTimeMillis()
                            currentReasoningEnd = null
                        }
                        _reasoningTimings.value = _reasoningTimings.value +
                            (sessionId to (currentReasoningStart!! to currentReasoningEnd))
                        setStreamingReasoning(sessionId, event.accumulated)
                    }
                    is AgentEvent.ToolCallPreparing -> {
                        finishReasoning()
                        // The tool name arrives before the arguments: let the UI switch "Thinking" to a specific scene ("Editing file").
                        // Once argument streaming finishes and the tool really starts executing, ToolCallStarted clears this and the tool row takes over expressing it.
                        setPreparingTool(sessionId, event.toolName)
                    }
                    is AgentEvent.Retrying -> {
                        currentReasoningStart = null
                        currentReasoningEnd = null
                        setRetryState(sessionId, RetryState(event.attempt, event.maxRetries, event.error))
                        // A retry re-streams output from the start: clear the body/reasoning bubbles already shown,
                        // otherwise, after reconnecting, the reasoning is regenerated while the old body text remains (the workflow has synchronously cleared its accumulator).
                        setStreamingText(sessionId, null)
                        setStreamingReasoning(sessionId, null)
                        setKeySwitchState(sessionId, null)
                    }
                    is AgentEvent.KeySwitched -> {
                        currentReasoningStart = null
                        currentReasoningEnd = null
                        setKeySwitchState(sessionId, KeySwitchState(event.newIndex, event.total))
                        setStreamingText(sessionId, null)
                        setStreamingReasoning(sessionId, null)
                    }
                    is AgentEvent.CompactionStarted -> {
                        currentReasoningStart = null
                        currentReasoningEnd = null
                        setRetryState(sessionId, null)
                        setKeySwitchState(sessionId, null)
                        setStreamingText(sessionId, null)
                        setStreamingReasoning(sessionId, null)
                        setCompacting(sessionId, true)
                    }
                    AgentEvent.CompactionFinished -> {
                        setCompacting(sessionId, false)
                        _contextUsages.value = _contextUsages.value - sessionId
                    }
                    is AgentEvent.ContextUsage -> {
                        _contextUsages.value = _contextUsages.value + (sessionId to event)
                    }
                    is AgentEvent.CompactionFailed -> {
                        setCompacting(sessionId, false)
                        // Persisted as an unpaired TOOL message: the UI renders a failure card, buildHistory replay discards it automatically, and it does not enter the model context.
                        messagePersistenceUseCase.persist(
                            sessionId,
                            MessageRole.TOOL,
                            event.reason,
                            toolName = COMPACTION_FAILURE_TOOL_NAME,
                            isError = true
                        )
                    }
                    is AgentEvent.AssistantText -> {
                        finishReasoning()
                        val reasoningDuration = currentReasoningStart?.let { start ->
                            currentReasoningEnd?.minus(start)
                        }
                        currentReasoningStart = null
                        currentReasoningEnd = null

                        // Streaming finish: before persisting and triggering the UI messages update, first synchronously clear the streaming state,
                        // avoiding a timing gap where the persisted message is emitted first and the UI shows "persisted message and streaming bubble on screen together".
                        setStreamingReasoning(sessionId, null)
                        setStreamingText(sessionId, null)
                        if (event.toolCalls.isEmpty()) {
                            setPreparingTool(sessionId, null)
                        }

                        val normalized = if (event.content.hasVisibleContent()) event.content else ""
                        val reasoning = event.reasoning.takeIf { it.hasVisibleContent() }
                        val msgId = event.messageId
                        if (reasoningDuration != null && reasoningDuration > 0) {
                            reasoningDurations[msgId] = reasoningDuration
                        }
                        messagePersistenceUseCase.persist(
                            sessionId,
                            MessageRole.ASSISTANT,
                            normalized,
                            id = msgId,
                            toolCalls = event.toolCalls,
                            reasoning = reasoning,
                            signature = event.signature.ifEmpty { null },
                            thinkingBlocksJson = event.thinkingBlocksJson.ifEmpty { null },
                            attachments = event.attachments,
                            inputTokens = event.inputTokens,
                            outputTokens = event.outputTokens,
                            cachedInputTokens = event.cachedInputTokens
                        )
                        if (event.inputTokens > 0 || event.outputTokens > 0) {
                            _llmCallEvents.tryEmit(LlmCallEvent(sessionId, event.inputTokens, event.outputTokens, event.cachedInputTokens))
                            // Synchronous database write: before the next CallLlm in the tool loop, lastInputTokens is re-read to judge compression;
                            // an asynchronous write could read the old large pre-compression value and trigger compression repeatedly.
                            runCatching {
                                chatSessionDao.addTokenUsage(sessionId, event.inputTokens, event.outputTokens)
                                if (event.inputTokens > 0) {
                                    chatSessionDao.updateLastInputTokens(sessionId, event.inputTokens)
                                }
                            }
                        }
                        event.persisted?.complete(Unit)
                    }
                    is AgentEvent.ToolCallStarted -> {
                        val msgId = "tool_${event.id}"
                        setStreamingText(sessionId, null)
                        setPreparingTool(sessionId, null)
                        toolArgsByMsgId[msgId] = event.argsPreview
                        messagePersistenceUseCase.persist(
                            sessionId,
                            MessageRole.TOOL,
                            "${SessionUseCase.PENDING_TOOL_MARKER} ${context.getString(R.string.agent_tool_executing, event.toolName)}",
                            id = msgId,
                            toolCallId = event.id,
                            toolName = event.toolName,
                            toolArgs = event.argsPreview,
                            isError = false
                        )
                        setRunningTool(sessionId, msgId, RunningToolOutput(msgId, "", event.toolName, event.argsPreview))
                    }
                    is AgentEvent.ToolCallProgress -> {
                        val msgId = "tool_${event.id}"
                        setRunningTool(sessionId, msgId, RunningToolOutput(
                            msgId,
                            event.accumulated,
                            event.toolName,
                            toolArgsByMsgId[msgId] ?: ""
                        ))
                    }
                    is AgentEvent.ToolCallFinished -> {
                        val msgId = "tool_${event.id}"
                        messagePersistenceUseCase.persist(
                            sessionId,
                            MessageRole.TOOL,
                            event.result,
                            id = msgId,
                            toolCallId = event.id,
                            toolName = event.toolName,
                            toolArgs = event.argsPreview ?: toolArgsByMsgId[msgId],
                            isError = event.isError,
                            attachments = event.attachments
                        )
                        toolArgsByMsgId.remove(msgId)
                        removeRunningTool(sessionId, msgId)
                        event.persisted?.complete(Unit)
                    }
                    is AgentEvent.Failed -> {
                        failed = true
                        setCompacting(sessionId, false)
                        setAgentState(sessionId, AgentUIState.Error(describeFailure(event)))
                        // Notify the parent session when a sub-agent session fails
                        if (isSub) {
                            sessionUseCase.getSessionById(sessionId)?.parentId?.let { parentId ->
                                subAgentEventBus.emit(
                                    SubAgentEvent(
                                        subSessionId = sessionId,
                                        parentSessionId = parentId,
                                        type = SubAgentEventType.FAILED,
                                        detail = event.error
                                    )
                                )
                            }
                        }
                    }
                    AgentEvent.Completed -> {
                        setRetryState(sessionId, null)
                        setKeySwitchState(sessionId, null)
                        setCompacting(sessionId, false)
                        // Notify the parent session when a sub-agent session completes (asynchronous callback)
                        if (isSub) {
                            sessionUseCase.getSessionById(sessionId)?.parentId?.let { parentId ->
                                subAgentEventBus.emit(
                                    SubAgentEvent(
                                        subSessionId = sessionId,
                                        parentSessionId = parentId,
                                        type = SubAgentEventType.COMPLETED
                                    )
                                )
                            }
                        }
                        // Send the agent completion notification only when the App is not in the foreground (to avoid disturbing a user who is watching the conversation).
                        val inForeground = ProcessLifecycleOwner.get().lifecycle.currentState
                            .isAtLeast(Lifecycle.State.STARTED)
                        if (!inForeground && agentSoundSettings.isEnabled()) {
                            showAgentCompletedNotification(modelRequest)
                        }
                    }
                    is AgentEvent.ModeChanged -> {
                        // Mode-switch event: PlanApprovalManager has already suspended at the workflow level, waiting for user approval
                        // Here only the streamingText display is updated
                    }
                }
            }

            sessionUseCase.touch(sessionId, messagePersistenceUseCase.nextTimestamp())
            // Set the completed state only if this job still holds the busy state: in concurrent cases the queue/notifications may have started a new job
            // and changed the state to Streaming, which must not be overwritten back to Result by the job that finished first (the button would turn back to Send early).
            val finishedState = _agentStates.value[sessionId]
            if (!failed && (finishedState is AgentUIState.Loading || finishedState is AgentUIState.Streaming)) {
                setAgentState(sessionId, AgentUIState.Result(WorkflowStatus.SUCCESS))
                _completedSessions.value = _completedSessions.value + sessionId
            }
            setStreamingText(sessionId, null)

        } catch (e: CancellationException) {
            val cancelledState = _agentStates.value[sessionId]
            val isOwnJob = sessionJobs[sessionId] == coroutineContext[Job]
            FileLogger.d(TAG, "stream cancelled: sid=$sessionId isOwnJob=$isOwnJob state=$cancelledState")
            if (isOwnJob &&
                (cancelledState is AgentUIState.Loading || cancelledState is AgentUIState.Streaming)
            ) {
                setAgentState(sessionId, AgentUIState.Idle)
                _completedSessions.value = _completedSessions.value - sessionId
            }
            throw e
        } catch (e: Exception) {
             FileLogger.e(TAG, "executeAgentRequestStream failed: request=$request", e)
             setAgentState(sessionId, AgentUIState.Error(e.toUserMessage()))
             _completedSessions.value = _completedSessions.value - sessionId
        } finally {
            val isOwnJob = sessionJobs[sessionId] == coroutineContext[Job]
            FileLogger.d(TAG, "stream finally: sid=$sessionId isOwnJob=$isOwnJob state=${_agentStates.value[sessionId]}")
            if (isOwnJob) {
                sessionJobs.remove(sessionId)
            }
            _runningTools.value = _runningTools.value - sessionId
            setStreamingText(sessionId, null)
            setStreamingReasoning(sessionId, null)
            setPreparingTool(sessionId, null)
            setCompacting(sessionId, false)
            setRetryState(sessionId, null)
            setKeySwitchState(sessionId, null)

            // Background notifications that could not be delivered piggybacked this turn: after this turn ends and the job has been removed, merge them into one message and send
            flushPendingNotifications(sessionId)

            // On normal completion, return to Idle first, then process the queue; if the queue has a next turn it will set Streaming again
            val currentState = _agentStates.value[sessionId]
            if (currentState !is AgentUIState.Error && currentState !is AgentUIState.Loading && currentState !is AgentUIState.Streaming) {
                setAgentState(sessionId, AgentUIState.Idle)
            }
            if (currentState !is AgentUIState.Loading && currentState !is AgentUIState.Streaming) {
                processNextInQueue(sessionId)
            }
            // Placed after queue processing: flushPendingNotifications / processNextInQueue synchronously register the succeeding job,
            // so only then does hasRunningSessions reflect the real state, without releasing and immediately re-acquiring.
            if (!hasRunningSessionsInCurrentWorkspace()) {
                releaseKeepalive()
            }
        }
    }.also { job ->
        // Register the job synchronously: the sessionJobs assignment inside launch is asynchronous, and flushPendingNotifications in finally
        // and processNextInQueue would both see isActive=false before the assignment and double-consume, starting two jobs,
        // with the job that finishes first setting the state to Idle/Result and overwriting the still-running job's Streaming.
        if (targetSessionId != null && slashCommandRegistry.resolve(request) == null) {
            sessionJobs[targetSessionId] = job
        }
    }

    fun resolveToolPermission(id: String, choice: PermissionChoice) {
        toolPermissionManager.resolve(id, choice)
    }

    fun resolveUserQuestion(id: String, answer: UserQuestionAnswer) {
        askUserQuestionManager.resolve(id, answer)
    }

    /** Stops all running AI sessions in the current workspace and closes all terminal tabs (called before switching workspaces). */
    fun stopAllAndCloseTerminal() {
        stopAllAgents()
        terminalSessionManager.tabs.value.map { it.id }.forEach { terminalSessionManager.closeTab(it) }
    }

    /** Stops all running AI sessions in the current workspace (called before switching workspaces). */
    fun stopAllAgents() {
        val jobs = sessionJobs.values.filter { it.isActive }
        jobs.forEach { it.cancel() }
        sessionJobs.clear()
        agentNotificationCenter.clearAll()
        _queuedRequests.value = emptyMap()
        _runningCommandSessions.value = emptySet()
        _agentStates.value = _agentStates.value.mapValues { AgentUIState.Idle }
        _streamingTexts.value = emptyMap()
        _streamingReasonings.value = emptyMap()
        _reasoningTimings.value = emptyMap()
        _runningTools.value = emptyMap()
        _retryStates.value = emptyMap()
        releaseKeepalive()
    }

    /**
     * Actively interrupts the agent running in the current session: cancels the coroutine (which also cancels suspended network requests and container command processes),
     * and finishes "running" tool placeholder rows as "Stopped", avoiding hanging spinners and orphan records.
     */
    fun stopAgent() {
        val sessionId = _currentSessionId.value ?: return
        stopAgentSession(sessionId)
    }

    /**
     * Stops the AI task of a given session (shared by sub-agent stop / manual user stop).
     * Cancels the job and persists unfinished streaming content as "Stopped"; the next item in the queue executes as usual.
     */
    fun stopAgentSession(sessionId: String) {
        val job = sessionJobs[sessionId] ?: return
        if (!job.isActive) return
        // The user manually stops a running sub-agent in the UI: return the concurrency slot and notify the parent agent, otherwise the slot leaks until a process restart,
        // and the parent agent waits forever for a completion notification that never arrives. TaskTool's stop/del already released it when emitting (STOPPED);
        // on that path release returns false, so it does not notify again.
        if (subAgentEventBus.release(sessionId)) {
            viewModelScope.launch {
                val sub = sessionUseCase.getSessionById(sessionId)
                val parentId = sub?.parentId
                if (parentId != null) {
                    notifyParentSubAgentFinished(
                        parentSessionId = parentId,
                        subSessionId = sessionId,
                        title = sub.title,
                        outcome = NotificationOutcome.STOPPED,
                        detail = "The user manually stopped this sub-agent in the UI; the task was not completed."
                    )
                }
            }
        }
        val runningTools = _runningTools.value[sessionId]?.values?.toList() ?: emptyList()
        val streamingText = _streamingTexts.value[sessionId]
        val streamingReasoning = _streamingReasonings.value[sessionId]
        val pendingPermission = toolPermissionManager.pendingForSession(sessionId)
        val stoppedText = context.getString(R.string.agent_stopped_by_user)
        val pendingNotifs = agentNotificationCenter.pendingCount(sessionId)
        FileLogger.d(TAG, "stopAgent: sid=$sessionId runningTools=${runningTools.size} pendingPerm=${pendingPermission?.id} pendingNotifs=$pendingNotifs state=${_agentStates.value[sessionId]}")
        // cancel() on Dispatchers.Main.immediate may immediately resume a suspended coroutine
        // (such as CompletableDeferred.await in awaitApproval), and the old job's finally ->
        // flushPendingNotifications runs synchronously inside the cancel() call stack and may start a new job.
        // Do not clear pending notifications in advance -- they should be flushed normally by finally for the new job to handle.
        job.cancel()
        // cancel may already have run finally synchronously (flush started a new job and registered it in sessionJobs),
        // in which case the new job's state must not be overwritten; clean up only if no new job has taken over.
        if (sessionJobs[sessionId]?.isActive != true) {
            agentNotificationCenter.clear(sessionId)
            setAgentState(sessionId, AgentUIState.Idle)
        }
        _runningTools.value = _runningTools.value - sessionId
        setStreamingText(sessionId, null)
        setStreamingReasoning(sessionId, null)
        setCompacting(sessionId, false)
        setRetryState(sessionId, null)
        viewModelScope.launch {
            if (runningTools.isNotEmpty()) {
                // Parallel execution aborted: all unfinished tools are persisted as "Stopped"
                runningTools.forEach { running ->
                    val partial = running.text.trimEnd()
                    val content = if (partial.isNotEmpty()) "$partial\n\n$stoppedText" else stoppedText
                    messagePersistenceUseCase.persist(
                        sessionId = sessionId,
                        role = MessageRole.TOOL,
                        content = content,
                        id = running.messageId,
                        toolCallId = running.messageId.removePrefix("tool_"),
                        toolName = running.toolName.ifBlank { null },
                        toolArgs = running.toolArgs.ifBlank { toolArgsByMsgId[running.messageId] },
                        isError = true
                    )
                    toolArgsByMsgId.remove(running.messageId)
                }
            } else if (!streamingText.isNullOrEmpty() || !streamingReasoning.isNullOrEmpty()) {
                val partial = (streamingText ?: "").trimEnd()
                val content = if (partial.isNotEmpty()) "$partial\n\n$stoppedText" else stoppedText
                val reasoning = streamingReasoning?.takeIf { it.hasVisibleContent() }
                messagePersistenceUseCase.persist(
                    sessionId = sessionId,
                    role = MessageRole.ASSISTANT,
                    content = content,
                    reasoning = reasoning
                )
            }
            // Tool call suspended in the permission dialog: while awaitApproval is suspended, _runningTools is empty
            // (ToolCallStarted is emitted only after permission is granted), but AssistantText has already persisted an assistant message carrying a
            // tool_call declaration. Not supplying a result would make that tool_call an
            // orphan record, filtered out by buildHistory's validIds intersection, and the AI would not know it had called it.
            if (pendingPermission != null) {
                val msgId = "tool_${pendingPermission.id}"
                messagePersistenceUseCase.persist(
                    sessionId = sessionId,
                    role = MessageRole.TOOL,
                    content = stoppedText,
                    id = msgId,
                    toolCallId = pendingPermission.id,
                    toolName = pendingPermission.toolName,
                    isError = true
                )
            }
            setStreamingText(sessionId, null)
            setStreamingReasoning(sessionId, null)
            setCompacting(sessionId, false)
            setRetryState(sessionId, null)
            // Tapping "Stop" = skip the current turn and immediately execute the next item in the queue
            processNextInQueue(sessionId)
        }
    }

    // region Session management

    /** Creates a new session; if the current session is still empty, reuses it directly, avoiding a pile-up of empty sessions. */
    fun newSession() = viewModelScope.launch {
        if (_currentWorkspace.value.isBlank()) return@launch
        val curId = _currentSessionId.value
        if (curId != null && sessionUseCase.isSessionEmpty(curId)) {
            setAgentState(curId, AgentUIState.Idle)
            return@launch
        }
        // On a new session, asynchronously reconnects unconnected MCP servers, so a configuration newly added by manageMcp really takes effect;
        // it does not block session creation -- "New session" must not be stuck if the MCP environment is not ready / times out.
        mcpManager.reconnectUnconnectedAsync()
        val sid = createAndUpsertSession(_currentWorkspace.value)
        _currentSessionId.value = sid
    }

    fun setCurrentSessionId(id: String) {
        if (_currentSessionId.value == id) return
        _currentSessionId.value = id
    }

    fun setSessionMode(mode: AgentMode) {
        val sid = _currentSessionId.value ?: return
        viewModelScope.launch {
            sessionUseCase.updateMode(sid, mode.name)
            // Switching while working: deliver the new mode as a system event to the running agent, taking effect this turn (see StatefulAgentWorkflow).
            // When idle there is no need to deliver -- the next turn injects a reminder once, per the new mode, when the turn starts.
            if (sessionJobs[sid]?.isActive == true) {
                agentNotificationCenter.enqueue(
                    sid,
                    PendingNotification(
                        kind = AgentNotificationKind.MODE_CHANGE,
                        sourceId = "user",
                        title = mode.name,
                        outcome = NotificationOutcome.COMPLETED,
                        newMode = mode
                    )
                )
            }
        }
    }

    fun setSessionReasoningEffort(effort: ReasoningEffort) {
        val sid = _currentSessionId.value ?: return
        viewModelScope.launch {
            sessionUseCase.updateReasoningEffort(sid, effort.name)
            // Also remembers it as the model-level default tier, for later new sessions to inherit
            val s = sessionUseCase.getSessionById(sid)?.toDomain()
            val pid = s?.providerId
            val model = s?.model
            if (!pid.isNullOrBlank() && !model.isNullOrBlank()) {
                modelReasoningEffortRepository.set(pid, model, effort.name)
            }
        }
    }

    fun setSessionProviderModel(providerId: String, model: String) {
        val sid = _currentSessionId.value ?: return
        viewModelScope.launch {
            sessionUseCase.updateProviderModel(sid, providerId, model)
            _contextUsages.value = _contextUsages.value - sid
            // A selection in an empty session counts as the "default model for new sessions", for the next new session to inherit
            if (sessionUseCase.isSessionEmpty(sid)) {
                defaultModelSettingsRepository.setDefaultModel(providerId, model)
            }
        }
    }

    /** Exposed to the UI: the command list shown in the input box `/` menu (built-in commands + enabled skills). */
    val slashCommands: StateFlow<List<SlashCommand>> get() = slashCommandRegistry.commands

    /** Re-scans skills and refreshes the command menu; called when entering the `/` menu, reflecting skill additions, deletions, and changes. */
    fun refreshSlashCommands() {
        viewModelScope.launch { slashCommandRegistry.refresh() }
    }

    /** /init -- Triggers an agent turn, analysing the codebase and generating/improving the project rules file. */
    override fun initProject(prompt: String) {
        val sid = _currentSessionId.value ?: return
        executeAgentRequestStream(
            request = prompt,
            projectRoot = _currentWorkspace.value,
            targetSessionId = sid,
            isAutoTrigger = true
        )
    }

    /** Skill trigger -- Executes the skill body as this turn's instruction. */
    override fun runSkill(skill: Skill, args: String) {
        val sid = _currentSessionId.value ?: return
        executeAgentRequestStream(
            request = SlashCommandRegistry.buildSkillPrompt(skill, args),
            projectRoot = _currentWorkspace.value,
            targetSessionId = sid,
            isAutoTrigger = true
        )
    }

    /** /usage -- Outputs today's and the cumulative call counts, token usage, and estimated cost as a Markdown table. */
    override fun showUsage() {
        val sid = _currentSessionId.value ?: return
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val todayStart = Calendar.getInstance().apply {
                timeInMillis = now
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            val today = loadUsageSummary(todayStart)
            val allTime = loadUsageSummary(0L)
            val table = buildString {
                appendLine("| Item | Today | Total |")
                appendLine("|---|---|---|")
                appendLine("| Calls | ${today.calls} | ${allTime.calls} |")
                appendLine("| Input tokens | ${formatTokenCount(today.inputTokens)} | ${formatTokenCount(allTime.inputTokens)} |")
                appendLine("| Output tokens | ${formatTokenCount(today.outputTokens)} | ${formatTokenCount(allTime.outputTokens)} |")
                appendLine("| Cached input tokens | ${formatTokenCount(today.cachedInputTokens)} | ${formatTokenCount(allTime.cachedInputTokens)} |")
                appendLine("| Estimated cost | ${formatCostUsd(today.costUsd)} | ${formatCostUsd(allTime.costUsd)} |")
            }
            sessionUseCase.touch(sid, messagePersistenceUseCase.nextTimestamp())
            messagePersistenceUseCase.persist(sid, MessageRole.ASSISTANT, table.trimEnd(), isContextExcluded = true)
        }
    }

    private data class UsageSummary(
        val calls: Int,
        val inputTokens: Long,
        val outputTokens: Long,
        val cachedInputTokens: Long,
        val costUsd: Double
    )

    /** Summarises call counts, tokens, and estimated cost since [start]; the cost is estimated group by group by "provider + model" so custom unit prices can be obtained. */
    private suspend fun loadUsageSummary(start: Long): UsageSummary = withContext(Dispatchers.IO) {
        val summary = llmCallRecordDao.getSummary(start).first()
        val providerTypes = aiProviderRepository.getAllProviders().first().associate { it.id to it.type }
        val cost = llmCallRecordDao.getModelProviderCostStats(start).first().sumOf { stat ->
            val model = stat.model ?: return@sumOf 0.0
            modelCostCalculator.costUsd(
                providerId = stat.providerId.orEmpty(),
                providerType = stat.providerId?.let { providerTypes[it] } ?: ProviderType.OPENAI,
                model = model,
                inputTokens = stat.inputTokens,
                cachedInputTokens = stat.cachedInputTokens,
                outputTokens = stat.outputTokens,
                cacheCreationTokens = stat.cacheCreationTokens
            ) ?: 0.0
        }
        UsageSummary(summary.calls, summary.inputTokens, summary.outputTokens, summary.cachedInputTokens, cost)
    }

    /** /compress -- Manually triggers context compression for the current session. */
    override fun compactCurrentSession() {
        val sid = _currentSessionId.value ?: return
        if (isRunning) return
        sessionJobs[sid]?.let { if (it.isActive) return }
        val job = viewModelScope.launch {
            setCompacting(sid, true)
            var failed = false
            try {
                val changed = agentWorkflow.compactSession(sid) { event ->
                    when (event) {
                        is AgentEvent.CompactionStarted -> setCompacting(sid, true)
                        AgentEvent.CompactionFinished -> {
                            setCompacting(sid, false)
                            _contextUsages.value = _contextUsages.value - sid
                        }
                        is AgentEvent.CompactionFailed -> {
                            failed = true
                            setCompacting(sid, false)
                            // Consistent with automatic compression: persists an unpaired TOOL message to render a failure card; buildHistory replay discards it.
                            messagePersistenceUseCase.persist(
                                sessionId = sid,
                                role = MessageRole.TOOL,
                                content = event.reason,
                                toolName = COMPACTION_FAILURE_TOOL_NAME,
                                isError = true
                            )
                        }
                        else -> {}
                    }
                }
                // On successful compression, the marker divider + summary card already provide feedback, so no duplicate hint bubble is persisted;
                // only when "no compression needed" (head is empty / there is nothing compressible) is a hint given.
                if (!failed && !changed) {
                    messagePersistenceUseCase.persist(
                        sessionId = sid,
                        role = MessageRole.ASSISTANT,
                        content = context.getString(R.string.agent_context_no_compaction)
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                FileLogger.e(TAG, "Manual compression failed: session=$sid", e)
                messagePersistenceUseCase.persist(
                    sessionId = sid,
                    role = MessageRole.ASSISTANT,
                    content = context.getString(R.string.agent_compaction_failed, e.message)
                )
            } finally {
                setCompacting(sid, false)
                // Compression is an asynchronous flow; after it ends, continue with the next queued item
                processNextInQueue(sid)
            }
        }
        sessionJobs[sid] = job
    }

    /** The user approves the plan, waking the workflow to continue executing in BUILD mode. */
    fun approvePlanAndBuild() {
        planApprovalManager.resolve(PlanApprovalChoice.APPROVE)
    }

    /** The user chooses to give more feedback, waking the workflow to roll back to PLAN mode. */
    fun refinePlan() {
        planApprovalManager.resolve(PlanApprovalChoice.REFINE)
    }

    fun selectSession(id: String) {
        if (_currentSessionId.value == id) return
        _currentSessionId.value = id
    }

    fun deleteSessions(ids: Set<String>) = viewModelScope.launch {
        if (ids.isEmpty()) return@launch
        val allDeletedIds = mutableSetOf<String>()
        ids.forEach { id ->
            val deletedSession = sessionUseCase.getSessionById(id)
            if (deletedSession?.parentId != null) {
                subAgentEventBus.release(id)
            }
            checkpointManager.clearSessionCheckpoints(id)
            val deleted = sessionUseCase.deleteSession(id)
            allDeletedIds.addAll(deleted)
        }

        allDeletedIds.forEach { sid ->
            subAgentEventBus.release(sid)
            sessionJobs[sid]?.cancel()
            sessionJobs.remove(sid)
            _agentStates.value = _agentStates.value - sid
            _streamingTexts.value = _streamingTexts.value - sid
            setStreamingReasoning(sid, null)
            _runningTools.value = _runningTools.value - sid
            _retryStates.value = _retryStates.value - sid
            _queuedRequests.value = _queuedRequests.value - sid
            _inputDrafts.value = _inputDrafts.value - sid
            draftPrefs.edit().remove(sid).apply()
            agentNotificationCenter.clear(sid)
        }

        if (_currentSessionId.value in allDeletedIds) {
            val ws = _currentWorkspace.value
            if (ws.isBlank()) {
                _currentSessionId.value = null
            } else {
                val remaining = sessionUseCase.getFirstSessionOfWorkspace(ws)
                if (remaining != null) {
                    _currentSessionId.value = remaining.id
                } else {
                    _currentSessionId.value = createAndUpsertSession(ws)
                }
            }
        }
    }

    fun deleteSession(id: String) = viewModelScope.launch {
        // When deleting a running sub-agent, first take its parent session and title (they cannot be obtained after the database deletion), to notify the parent agent later.
        // When the deleted item is the parent session itself, no notification is needed (the parent session itself is gone), but sub-sessions deleted by cascade must still return their concurrency slots.
        val deletedSession = sessionUseCase.getSessionById(id)
        val stoppedSubAgent = deletedSession?.takeIf { it.parentId != null && subAgentEventBus.release(id) }

        checkpointManager.clearSessionCheckpoints(id)
        val deletedIds = sessionUseCase.deleteSession(id)

        deletedIds.forEach { sid ->
            subAgentEventBus.release(sid)
            sessionJobs[sid]?.cancel()
            sessionJobs.remove(sid)
            _agentStates.value = _agentStates.value - sid
            _streamingTexts.value = _streamingTexts.value - sid
            setStreamingReasoning(sid, null)
            _runningTools.value = _runningTools.value - sid
            _retryStates.value = _retryStates.value - sid
            _queuedRequests.value = _queuedRequests.value - sid
            _inputDrafts.value = _inputDrafts.value - sid
            draftPrefs.edit().remove(sid).apply()
            agentNotificationCenter.clear(sid)
        }

        if (_currentSessionId.value in deletedIds) {
            val ws = _currentWorkspace.value
            if (ws.isBlank()) {
                _currentSessionId.value = null
            } else {
                val remaining = sessionUseCase.getFirstSessionOfWorkspace(ws)
                if (remaining != null) {
                    _currentSessionId.value = remaining.id
                } else {
                    _currentSessionId.value = createAndUpsertSession(ws)
                }
            }
        }

        stoppedSubAgent?.let { sub ->
            notifyParentSubAgentFinished(
                parentSessionId = sub.parentId!!,
                subSessionId = sub.id,
                title = sub.title,
                outcome = NotificationOutcome.STOPPED,
                detail = "The user deleted this sub-agent session; the task was not completed, and its conversation record can no longer be read."
            )
        }
    }

    // Target message id selected for Checkpoint Rewind
    private val _targetRewindMessageId = MutableStateFlow<String?>(null)
    val targetRewindMessageId: StateFlow<String?> = _targetRewindMessageId.asStateFlow()

    fun openRewindMenu(messageId: String) {
        _targetRewindMessageId.value = messageId
    }

    fun dismissRewindMenu() {
        _targetRewindMessageId.value = null
    }

    fun executeRewindOption(
        messageId: String,
        option: RewindOption,
        onFillPrompt: (String, List<AgentAttachment>) -> Unit
    ) = viewModelScope.launch {
        val sessionId = _currentSessionId.value ?: return@launch
        dismissRewindMenu()

        // 1. Stop the currently running Agent task and subsequent queued items
        _queuedRequests.value = _queuedRequests.value + (sessionId to emptyList())
        agentNotificationCenter.clear(sessionId)
        val runningJob = sessionJobs[sessionId]
        if (runningJob != null && runningJob.isActive) {
            runningJob.cancelAndJoin()
        }
        sessionJobs.remove(sessionId)

        // 2. Reset session running, streaming, and checkpoint state
        setAgentState(sessionId, AgentUIState.Idle)
        _runningTools.value = _runningTools.value - sessionId
        setStreamingText(sessionId, null)
        setStreamingReasoning(sessionId, null)
        setCompacting(sessionId, false)
        _contextUsages.value = _contextUsages.value - sessionId
        setRetryState(sessionId, null)
        checkpointManager.setActiveCheckpointId(sessionId, null)

        val checkpoint = checkpointDao.getCheckpointBySessionAndMessage(sessionId, messageId)
        val targetMsgEntity = agentMessageDao.getMessageById(messageId) ?: return@launch
        val attachments = targetMsgEntity.toUIMessage().attachments

        when (option) {
            RewindOption.RESTORE_CODE_AND_CONVERSATION -> {
                if (checkpoint != null) {
                    checkpointManager.restoreCodeToCheckpoint(sessionId, checkpoint.id)
                }
                messagePersistenceUseCase.rewindConversation(sessionId, targetMsgEntity.timestamp)
                withContext(Dispatchers.Main) { onFillPrompt(targetMsgEntity.content, attachments) }
            }
            RewindOption.RESTORE_CONVERSATION -> {
                messagePersistenceUseCase.rewindConversation(sessionId, targetMsgEntity.timestamp)
                withContext(Dispatchers.Main) { onFillPrompt(targetMsgEntity.content, attachments) }
            }
            RewindOption.RESTORE_CODE -> {
                if (checkpoint != null) {
                    checkpointManager.restoreCodeToCheckpoint(sessionId, checkpoint.id)
                }
            }
        }
    }

    /** Renames a session title. Updates only title, not updatedAt, so list order stays unchanged. */
    fun renameSession(id: String, newTitle: String) = viewModelScope.launch {
        val trimmed = newTitle.trim()
        if (trimmed.isEmpty()) return@launch
        sessionUseCase.updateTitle(id, trimmed)
    }

    /** Pins/unpins a session. A pinned session is placed at the front of the list (pinned group); updatedAt is not changed. */
    fun togglePinSession(id: String) = viewModelScope.launch {
        val pinned = sessions.value.find { it.id == id }?.isPinned ?: return@launch
        sessionUseCase.updatePinned(id, !pinned)
    }

    /** Exports a single session as a passwordless backup format (tar.gz), writing streamed to [output] (opened by the caller; this method closes it). Calls back true on success, false on failure. */
    fun exportSession(sessionId: String, output: OutputStream, onResult: (Boolean) -> Unit) = viewModelScope.launch {
        try {
            backupManager.exportSession(sessionId, output)
            onResult(true)
        } catch (e: Exception) {
            FileLogger.e("AIAgentViewModel", "exportSession failed", e)
            onResult(false)
        } finally {
            runCatching { output.close() }
        }
    }

    private suspend fun ensureSession(): String {
        _currentSessionId.value?.let { return it }
        val ws = _currentWorkspace.value
        if (ws.isBlank()) return ""
        val id = sessionUseCase.getFirstSessionOfWorkspace(ws)?.id ?: createAndUpsertSession(ws)
        _currentSessionId.value = id
        return id
    }

    /** Creates a new session and persists it, returning the id. */
    private suspend fun createAndUpsertSession(workspacePath: String): String {
        val s = createSession(workspacePath)
        sessionUseCase.upsertSession(s)
        return s.id
    }

    /**
     * Creates a new session and binds provider/model per the "default model for new sessions"; with no default set, falls back to the global active provider.
     * Every entry point for creating a new session (cold start, new, delete fallback, ensureSession) goes through here.
     */
    private suspend fun createSession(workspacePath: String): ChatSessionEntity {
        val providerId = defaultModelSettingsRepository.getDefaultProviderId().takeIf { it.isNotBlank() }
        val model = defaultModelSettingsRepository.getDefaultModel().takeIf { it.isNotBlank() }
        val effort = if (providerId != null && model != null) {
            modelReasoningEffortRepository.get(providerId, model) ?: ReasoningEffort.MEDIUM.name
        } else {
            ReasoningEffort.MEDIUM.name
        }
        return sessionUseCase.newSessionEntity(
            workspacePath = workspacePath,
            providerId = providerId,
            model = model,
            reasoningEffort = effort
        )
    }

    // endregion

    fun updateMessageContent(messageId: String, newContent: String) = viewModelScope.launch {
        try {
            messagePersistenceUseCase.updateContent(messageId, newContent)
        } catch (e: Exception) {
            FileLogger.e(TAG, "Failed to update message", e)
        }
    }

    fun deleteMessage(messageId: String) = viewModelScope.launch {
        try {
            val msg = agentMessageDao.getMessageById(messageId)
            if (msg != null && msg.role == MessageRole.USER.name) {
                agentMessageDao.deleteMessagesAfterTimestamp(msg.sessionId, msg.timestamp)
            }
            agentMessageDao.deleteMessageById(messageId)
        } catch (e: Exception) {
            FileLogger.e(TAG, "Failed to delete message", e)
        }
    }

    private fun detectLanguage(filePath: String): String {
        return when (filePath.substringAfterLast(".").lowercase()) {
            "kt", "kotlin" -> "kotlin"
            "java" -> "java"
            "dart" -> "dart"
            "py" -> "python"
            "js" -> "javascript"
            "ts" -> "typescript"
            "tsx" -> "typescript"
            "jsx" -> "javascript"
            "go" -> "go"
            "rs" -> "rust"
            else -> "text"
        }
    }
}
