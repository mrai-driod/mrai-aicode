package com.aicode.feature.agent.domain.workflow

import android.os.SystemClock
import android.util.Base64
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.data.local.dao.LlmCallRecordDao
import com.aicode.feature.agent.data.local.entity.LlmCallRecordEntity
import com.aicode.feature.agent.data.remote.anthropic.AnthropicApi
import com.aicode.feature.agent.data.remote.gemini.GeminiApi
import com.aicode.feature.agent.data.remote.openai.OpenAIApi
import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.model.AgentImage
import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.model.AgentMode
import com.aicode.feature.agent.domain.notification.AgentEventInjector
import com.aicode.feature.agent.domain.notification.AgentNotificationCenter
import com.aicode.feature.agent.domain.notification.AgentNotificationKind
import com.aicode.feature.agent.domain.notification.PendingNotification
import com.aicode.feature.agent.domain.session.SessionUseCase
import com.aicode.feature.agent.domain.session.MessagePersistenceUseCase
import com.aicode.feature.agent.domain.checkpoint.CheckpointManager
import com.aicode.feature.agent.domain.permission.PermissionChoice
import com.aicode.feature.agent.domain.permission.PermissionScope
import com.aicode.feature.agent.domain.permission.ToolPermissionPolicyEngine
import com.aicode.feature.agent.domain.prompt.SystemPromptProvider
import com.aicode.feature.agent.domain.provider.AIProvider
import com.aicode.feature.agent.domain.provider.AIResponse
import com.aicode.feature.agent.domain.provider.AIStreamChunk
import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.StreamingAgentTool
import com.aicode.feature.agent.domain.tool.ToolCall
import com.aicode.feature.agent.domain.tool.mode.PlanApprovalChoice
import com.aicode.feature.agent.domain.tool.mode.PlanApprovalManager
import com.aicode.feature.agent.domain.tool.ToolPermissionManager
import com.aicode.feature.agent.domain.tool.ToolPermissionPolicy
import com.aicode.feature.agent.domain.tool.ToolRegistry
import com.aicode.feature.agent.domain.tool.ToolResult
import com.aicode.feature.agent.domain.tool.ToolInvoker
import com.aicode.feature.agent.domain.tool.ToolRunResult

import com.aicode.feature.agent.domain.tool.modelToolResultText
import com.aicode.feature.agent.domain.tool.toTransportString
import com.aicode.feature.agent.presentation.AgentAttachment
import com.aicode.feature.settings.data.remote.ModelMetadataService
import com.aicode.feature.settings.data.repository.CompactionModelSettingsRepository
import com.aicode.feature.settings.data.repository.DefaultModelSettingsRepository
import com.aicode.feature.settings.data.repository.GeneralSettingsRepository
import com.aicode.feature.settings.data.repository.LanguageSettingsRepository
import com.aicode.feature.settings.data.repository.ProviderKeyRotator
import com.aicode.feature.settings.data.repository.ReviewerModelSettingsRepository
import com.aicode.feature.settings.data.repository.TitleModelSettingsRepository
import com.aicode.feature.settings.domain.model.AIProviderConfig
import com.aicode.feature.agent.domain.provider.AnthropicAdapter
import com.aicode.feature.agent.domain.provider.fixedTemperature
import com.aicode.feature.agent.domain.provider.GeminiAdapter
import com.aicode.feature.agent.domain.provider.AllKeysFailedException
import com.aicode.feature.agent.domain.provider.KeySwitchOutcome
import com.aicode.feature.agent.domain.provider.isKeySwitchFailure
import com.aicode.feature.agent.domain.provider.MraiChainAdapter
import com.aicode.feature.agent.domain.provider.OpenAIAdapter
import com.aicode.feature.settings.domain.model.ProviderType
import com.aicode.feature.settings.domain.model.ModelContextPolicy
import com.aicode.feature.agent.domain.provider.StreamApiException
import com.aicode.feature.settings.domain.repository.AIProviderRepository
import com.aicode.feature.workspace.domain.FileAccessProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.Locale
import java.util.UUID
import javax.inject.Inject

/**
 * Phase Three refactor (complete version): an Agent workflow engine based on immutable state (Immutable State) and the MVI architecture.
 * By defining explicit AgentSessionState, AgentAction and AgentSideEffect,
 * and using a Reducer to transition state, pure-function business logic is completely decoupled from external-environment operations that have side effects.
 */
class StatefulAgentWorkflow @Inject constructor(
    private val toolRegistry: ToolRegistry,
    private val toolInvoker: ToolInvoker,
    private val aiProviderRepository: AIProviderRepository,
    private val openAIApi: OpenAIApi,
    private val anthropicApi: AnthropicApi,
    private val geminiApi: GeminiApi,
    private val promptProvider: SystemPromptProvider,
    private val permissionManager: ToolPermissionManager,
    private val policyEngine: ToolPermissionPolicyEngine,
    private val contextCompactor: ContextCompactor,
    private val planApprovalManager: PlanApprovalManager,
    private val modelMetadataService: ModelMetadataService,
    private val compactionModelSettingsRepository: CompactionModelSettingsRepository,
    private val titleModelSettingsRepository: TitleModelSettingsRepository,
    private val reviewerModelSettingsRepository: ReviewerModelSettingsRepository,
    private val languageSettingsRepository: LanguageSettingsRepository,
    private val defaultModelSettingsRepository: DefaultModelSettingsRepository,
    private val generalSettingsRepository: GeneralSettingsRepository,
    private val sessionUseCase: SessionUseCase,
    private val messagePersistenceUseCase: MessagePersistenceUseCase,
    private val checkpointManager: CheckpointManager,
    private val llmCallRecordDao: LlmCallRecordDao,
    private val keyRotator: ProviderKeyRotator,
    private val agentNotificationCenter: AgentNotificationCenter,
    private val eventInjector: AgentEventInjector,
    private val fileAccess: FileAccessProvider
) : AgentWorkflow {

    /**
     * Dedicated scope for persistence: independent of the caller's coroutine tree and non-cancellable.
     *
     * Purpose: to take "writing LLM call records to disk" off the coroutine cancellation-unwinding path.
     * See the comments at each finally -- calling any suspend during cancellation unwinding (including withContext)
     * corrupts the coroutine state machine during suspension/resumption, triggering a native ART abort with "No pending exception
     * expected" / "Unexpected instruction" and crashing outright.
     * Dispatch with [CoroutineScope.launch]: launch is not a suspend function, so it does not suspend the current coroutine.
     */
    private val recordScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private companion object {
        const val TAG = "StatefulAgentWorkflow"
        const val USER_REJECTED_CODE = "USER_REJECTED"
        const val TITLE_GENERATOR_FILE = "agent/title-generator.md"
        const val TITLE_MAX_CHARS = 50
        const val COMMIT_GENERATOR_FILE = "agent/commit-generator.md"
        /** AI command reviewer prompt: in the "autonomous execution" tier, reviews shell commands one by one. */
        const val COMMAND_REVIEWER_FILE = "agent/command-reviewer.md"
        /** Mode reminder prompt: reuses files from the prompts directory (user-customizable overrides); on switching it is injected with the message rather than entering system. */
        const val MODE_REMINDER_PLAN_FILE = "agent/plan-mode.md"
        const val MODE_REMINDER_ASK_FILE = "agent/ask-mode.md"
        val LEADING_COMMENT = Regex("(?s)^\\s*<!--.*?-->\\s*")
        /** Directory for saving images output directly by the model (kept consistent with GenerateImageTool). */
        const val GENERATED_IMAGE_DIR = "~/.aicode/generated-images"
        const val MAX_GENERATED_IMAGE_BYTES = 20L * 1024 * 1024
    }

    /**
     * The "mode for which a mode reminder was last injected" for each session. The mode reminder is only injected when it changes, avoiding appending the same text
     * to the latest user message on every round -- otherwise, when history is rebuilt next round, the content at that position (the Anthropic cache breakpoint) would be inconsistent,
     * and a stretch of prefix cache would be wasted. Cached in-process; injected once more on the first round after a restart (harmless).
     */
    private val lastInjectedMode = java.util.concurrent.ConcurrentHashMap<String, AgentMode>()

    /** Immutable state tree */
    data class AgentSessionState(
        val messages: List<AgentMessage> = emptyList(),
        val iterations: Int = 0,
        val isFinished: Boolean = false,
        val error: String? = null,
        /** Error type code (e.g. the server-side stop_reason), for the UI to replace with localized copy; null means show [error] directly. */
        val errorCode: String? = null,
        /** The toolCalls returned by the model in this batch (in original order, used to assemble tool responses in order at the end) */
        val batchToolCalls: List<ToolCall> = emptyList(),
        /** The toolCall awaiting a permission request (collected one dialog at a time) */
        val pendingPermissionCalls: List<ToolCall> = emptyList(),
        /** The toolCalls that have been approved and await parallel execution */
        val approvedToolCalls: List<ToolCall> = emptyList(),
        /** Tool results rejected by policy/system (not rejected by the user), key = toolCall.id */
        val rejectedToolResults: Map<String, ToolBatchResult> = emptyMap()
    )

    /** Actions that change state (Action) */
    sealed interface AgentAction {
        data class InitRequest(val initialMessages: List<AgentMessage>) : AgentAction
        data class LlmResponse(val response: AIResponse, val messageId: String) : AgentAction
        data class LlmError(val error: String, val reasonCode: String? = null) : AgentAction
        data class PermissionEvaluated(
            val toolCall: ToolCall,
            val approved: Boolean,
            val argsPreview: String,
            val denyReason: String = "The user declined to execute this tool",
            val errorCode: String = "USER_REJECTED"
        ) : AgentAction
        data class ToolBatchFinished(
            val results: List<ToolBatchResult>
        ) : AgentAction
    }

    private data class PermissionCheckResult(
        val approved: Boolean,
        val denyReason: String = "The user declined to execute this tool",
        val errorCode: String = "USER_REJECTED"
    )

    /** Batch tool execution result: carries toolCall metadata, used to assemble a ToolResultMessage in the original order at the end. */
    data class ToolBatchResult(
        val id: String,
        val toolName: String,
        val result: String,
        val isError: Boolean,
        /** Display-only tools such as sendFile only: file card metadata attached with the result, for UI rendering, not played back into the model context. */
        val attachments: List<AgentAttachment> = emptyList(),
        val images: List<AgentImage> = emptyList()
    )

    /** Side effects that need to be executed in the external environment (SideEffect) */
    sealed interface AgentSideEffect {
        object CallLlm : AgentSideEffect
        data class PersistUser(val message: AgentMessage.UserMessage) : AgentSideEffect
        data class RequestPermission(val toolCall: ToolCall) : AgentSideEffect
        /** Executes the approved tools in parallel as a batch; an empty list means there are no tools to execute in this batch and it goes straight to wrap-up. */
        data class ExecuteToolBatch(val toolCalls: List<ToolCall>) : AgentSideEffect
        /** Cancels the whole batch (the user rejected one call in the batch): re-issues completion events for tools that had started, and clears the UI "executing" state. */
        data class CancelToolBatch(val toolCalls: List<ToolCall>) : AgentSideEffect
    }

    private suspend fun getEffectiveProvider(sessionId: String?): AIProvider {
        val config = resolveProviderConfig(sessionId)
            ?: throw IllegalStateException("No AI provider configured yet; add and select one in Settings")
        if (!config.hasUsableApiKey) throw IllegalStateException("'${config.name}' has no API key set")
        if (config.effectiveModel.isBlank()) throw IllegalStateException("'${config.name}' has no model selected")
        return createStandaloneProvider(config, sessionId)
    }

    /**
     * Resolves the currently effective provider configuration: prefers the providerId/model bound to the session, falling back to the global active provider.
     * When the provider bound to the session does not exist or is disabled, it falls back to the global one, ensuring old sessions and abnormal data are not interrupted.
     */
    private suspend fun resolveProviderConfig(sessionId: String?): AIProviderConfig? {
        if (sessionId != null) {
            val session = sessionUseCase.getSessionById(sessionId)
            val boundProviderId = session?.providerId
            val boundModel = session?.model
            if (!boundProviderId.isNullOrBlank()) {
                val config = aiProviderRepository.getProviderById(boundProviderId)
                if (config != null && config.isEnabled && config.hasUsableApiKey) {
                    // The bound model may already have been removed from that provider's model list; in that case the binding is invalid and resolution continues by falling back to the default model
                    if (boundModel.isNullOrBlank()) return config
                    if (boundModel in config.models) return config.copy(selectedModel = boundModel)
                }
            }
        }
        // Fallback: the default model for new sessions (remembered after selection in an empty session on the home page); if unset, returns null and the caller reports an error with guidance.
        val defaultProviderId = defaultModelSettingsRepository.getDefaultProviderId()
        val defaultModel = defaultModelSettingsRepository.getDefaultModel()
        if (defaultProviderId.isNotBlank() && defaultModel.isNotBlank()) {
            val config = aiProviderRepository.getProviderById(defaultProviderId)
            if (config != null && config.isEnabled && config.hasUsableApiKey && defaultModel in config.models) {
                return config.copy(selectedModel = defaultModel)
            }
        }
        return null
    }

    override suspend fun compactSession(sessionId: String, onEvent: suspend (AgentEvent) -> Unit): Boolean {
        val config = resolveProviderConfig(sessionId)
            ?: throw IllegalStateException("No AI provider configured yet; add and select one in Settings")
        if (!config.hasUsableApiKey) throw IllegalStateException("'${config.name}' has no API key set")
        if (config.effectiveModel.isBlank()) throw IllegalStateException("'${config.name}' has no model selected")
        val provider = createStandaloneProvider(config, sessionId)
        val history = messagePersistenceUseCase.buildHistory(sessionId, "__manual_compress__")
        if (history.size <= 2) return false
        val compactionProvider = resolveCompactionFallbackProvider(sessionId) ?: provider
        // The summary still uses the dedicated compaction model (respecting the user's choice), but the window budget for retaining recent messages is calculated using the main chat model,
        // consistent with automatic compaction -- otherwise a compaction model with a small window would cause manual compaction to retain less recent context.
        val result = contextCompactor.compactIfNeeded(history, compactionProvider, sessionId, force = true, windowProvider = provider, onEvent = onEvent)
        return result.compacted
    }

    /**
     * Creates a brand-new, independent [AIProvider] instance from [config].
     * Used for independent request scenarios such as context compaction, without occupying or modifying the Provider singleton used by the main conversation at all.
     * Also applies the provider-level LLM cache switches (Anthropic breakpoints / OpenAI cache key) to the instance.
     */
    private suspend fun createStandaloneProvider(config: AIProviderConfig, sessionId: String?): AIProvider {
        val provider: AIProvider = when (config.type) {
            ProviderType.ANTHROPIC -> AnthropicAdapter(anthropicApi).also {
                it.cacheBreakpointsEnabled = config.anthropicCacheBreakpoints
            }
            ProviderType.GEMINI -> GeminiAdapter(geminiApi)
            else -> if (config.id == MraiChainAdapter.PROVIDER_ID) {
                MraiChainAdapter(openAIApi)
            } else {
                OpenAIAdapter(openAIApi).also {
                    it.chatCacheKeyEnabled = config.openaiChatCacheKey
                }
            }
        }
        // In multi-Key mode the rotator decides which Key to use this time (sticks within a session; when a Key is unavailable it switches to the next one and resends).
        val activeKeys = config.effectiveApiKeys
        provider.apiKey = keyRotator.activeKey(config, sessionId)
            ?: throw IllegalStateException("All keys for '${config.name}' are cooling down; please retry later")
        // Only genuine multi-Key setups need automatic switching: after a single Key cools down there is no target to switch to, and it would only lock the user out for a period of time.
        val keySwitcher: (suspend (Throwable, String, Set<String>) -> KeySwitchOutcome?)? =
            if (activeKeys.size > 1) {
                { error, failedKey, triedKeys ->
                    if (!error.isKeySwitchFailure(config.effectiveKeySwitchStatusCodes)) {
                        null
                    } else {
                        val switched = keyRotator.reportFailure(config.id, sessionId, failedKey, triedKeys)
                        if (switched == null) {
                            throw AllKeysFailedException(
                                "All ${activeKeys.size} keys for '${config.name}' failed: ${error.message ?: error.javaClass.simpleName}",
                                error
                            )
                        }
                        KeySwitchOutcome(switched.newKey, switched.newIndex, switched.total)
                    }
                }
            } else {
                null
            }
        provider.keySwitcher = keySwitcher
        provider.baseUrl = config.baseUrl
        provider.model = config.effectiveModel
        provider.useFullUrl = config.useFullUrl
        provider.useResponseApi = config.useResponseApi
        provider.providerId = config.id
        provider.logSessionId = sessionId
        provider.customHeaders = config.customHeaders
        val metadata = modelMetadataService.resolve(config.id, config.type, config.effectiveModel)
        // The output limit from the model metadata (models.dev limit.output): when not passed, Anthropic caps the output at the adapter's fallback value.
        provider.maxOutputTokens = metadata.outputTokens
        // When the metadata says custom temperatures are not accepted, this field is not sent at all (the kimi-k3 and gpt-5 families return 400 outright when it is included); where allowed, only the official fixed value is sent.
        provider.temperature = if (metadata.supportsCustomTemperature) fixedTemperature(config.effectiveModel) else null
        provider.firstByteTimeoutMs = generalSettingsRepository.firstByteTimeoutMs()
        provider.streamIdleTimeoutMs = generalSettingsRepository.streamIdleTimeoutMs()
        provider.maxNetworkRetries = generalSettingsRepository.maxNetworkRetries()
        return provider
    }

    /** The core Reducer: receives the old state and an Action and returns the new state together with the list of side effects to trigger (pure function) */
    private fun reduce(
        state: AgentSessionState,
        action: AgentAction
    ): Pair<AgentSessionState, List<AgentSideEffect>> {
        var newState = state
        val effects = mutableListOf<AgentSideEffect>()

        when (action) {
            is AgentAction.InitRequest -> {
                newState = state.copy(messages = action.initialMessages)
                effects.add(AgentSideEffect.CallLlm)
            }
            is AgentAction.LlmResponse -> {
                val assistantMsg = AgentMessage.AssistantMessage(
                    id = action.messageId,
                    content = action.response.content,
                    toolCalls = action.response.toolCalls,
                    reasoning = action.response.reasoning ?: "",
                    signature = action.response.signature ?: "",
                    thinkingBlocksJson = action.response.thinkingBlocksJson ?: "",
                    images = action.response.images,
                    inputTokens = action.response.inputTokens
                )
                newState = state.copy(
                    messages = state.messages + assistantMsg,
                    iterations = state.iterations + 1
                )
                
                if (action.response.toolCalls.isEmpty()) {
                    if (action.response.isAborted) {
                        // Refusal / context limit exceeded: the body may be empty, and ending silently would leave the user looking at a blank bubble and thinking it is stuck.
                        newState = newState.copy(
                            isFinished = true,
                            error = action.response.stopDetail ?: "",
                            errorCode = action.response.stopReason
                        )
                    } else if (action.response.isTruncated) {
                        val continuation = AgentMessage.UserMessage(content = "Your response was truncated. Continue from where it stopped.")
                        newState = newState.copy(messages = newState.messages + continuation)
                        effects.add(AgentSideEffect.PersistUser(continuation))
                        effects.add(AgentSideEffect.CallLlm)
                    } else {
                        newState = newState.copy(isFinished = true)
                    }
                } else {
                    // Multiple tool_calls in this batch: all enter the pending-permission queue, with approval collected one dialog at a time;
                    // only after all are approved does it enter the parallel execution phase (see PermissionEvaluated / ToolBatchFinished).
                    val toolCalls = action.response.toolCalls.toList()
                    newState = newState.copy(
                        batchToolCalls = toolCalls,
                        pendingPermissionCalls = toolCalls,
                        approvedToolCalls = emptyList(),
                        rejectedToolResults = emptyMap()
                    )
                    effects.add(AgentSideEffect.RequestPermission(toolCalls.first()))
                }
            }
            is AgentAction.LlmError -> {
                newState = state.copy(isFinished = true, error = action.error, errorCode = action.reasonCode)
            }
            is AgentAction.PermissionEvaluated -> {
                if (action.approved) {
                    // Approved: the current toolCall moves into the approved set; if there are still pending permission requests, continue showing dialogs, otherwise start parallel execution.
                    val remaining = newState.pendingPermissionCalls.filterNot { it.id == action.toolCall.id }
                    val approved = newState.approvedToolCalls + action.toolCall
                    newState = newState.copy(
                        pendingPermissionCalls = remaining,
                        approvedToolCalls = approved
                    )
                    if (remaining.isNotEmpty()) {
                        effects.add(AgentSideEffect.RequestPermission(remaining.first()))
                    } else {
                        effects.add(AgentSideEffect.ExecuteToolBatch(approved))
                    }
                } else {
                    val rawResult = ToolResult.Error(action.denyReason, action.errorCode).toTransportString()
                    if (action.errorCode == USER_REJECTED_CODE) {
                        // The model may return multiple tool_calls at once. If the user rejects any one in the batch -> the whole batch is cancelled:
                        // supply tool responses for all calls in the original order of batchToolCalls (no duplicates and no omissions),
                        // otherwise, after assistant(toolCalls=N), only some tool messages would be present and OpenAI would return 400
                        // "insufficient tool messages following tool_calls".
                        val cancelled = newState.batchToolCalls.map { call ->
                            AgentMessage.ToolResultMessage(
                                id = call.id,
                                toolName = call.name,
                                result = ToolResult.Error(
                                    "The user declined this round of tool calls; this call was not executed.",
                                    USER_REJECTED_CODE
                                ).toTransportString()
                            )
                        }
                        newState = state.copy(
                            messages = state.messages + cancelled,
                            batchToolCalls = emptyList(),
                            pendingPermissionCalls = emptyList(),
                            approvedToolCalls = emptyList(),
                            isFinished = true
                        )
                        // Tools that were approved but not executed (already received ToolCallStarted) need completion events re-issued,
                        // otherwise the UI and persisted messages would stay stuck in "executing" forever.
                        if (state.approvedToolCalls.isNotEmpty()) {
                            effects.add(AgentSideEffect.CancelToolBatch(state.approvedToolCalls))
                        }
                        return newState to effects
                    }
                    // Policy/system rejection (e.g. execution forbidden in PLAN mode): record the rejection result and continue collecting subsequent permissions.
                    val remaining = newState.pendingPermissionCalls.filterNot { it.id == action.toolCall.id }
                    newState = newState.copy(
                        pendingPermissionCalls = remaining,
                        rejectedToolResults = newState.rejectedToolResults + (
                            action.toolCall.id to ToolBatchResult(
                                id = action.toolCall.id,
                                toolName = action.toolCall.name,
                                result = rawResult,
                                isError = true
                            )
                        )
                    )
                    if (remaining.isNotEmpty()) {
                        effects.add(AgentSideEffect.RequestPermission(remaining.first()))
                    } else {
                        effects.add(AgentSideEffect.ExecuteToolBatch(newState.approvedToolCalls))
                    }
                }
            }
            is AgentAction.ToolBatchFinished -> {
                // After all tools in this batch have finished executing, assemble the tool responses in the original order of batchToolCalls:
                // prefer the policy-rejection result, otherwise take the parallel-execution result, ensuring consistency with the assistant(toolCalls) order.
                val resultsById = action.results.associateBy { it.id }
                val appendedMessages = mutableListOf<AgentMessage>()
                newState.batchToolCalls.forEach { call ->
                    val batchResult = newState.rejectedToolResults[call.id] ?: resultsById[call.id] ?: return@forEach
                    appendedMessages.add(
                        AgentMessage.ToolResultMessage(
                            id = batchResult.id,
                            toolName = batchResult.toolName,
                            result = batchResult.result,
                            images = batchResult.images,
                            modelResult = modelToolResultText(batchResult.toolName, batchResult.result)
                        )
                    )
                }
                newState = state.copy(
                    messages = state.messages + appendedMessages,
                    batchToolCalls = emptyList(),
                    pendingPermissionCalls = emptyList(),
                    approvedToolCalls = emptyList(),
                    rejectedToolResults = emptyMap()
                )
                effects.add(AgentSideEffect.CallLlm)
            }
        }
        
        return Pair(newState, effects)
    }

    override fun executeEvents(
        userRequest: String,
        context: AgentContext,
        tools: List<AgentTool>
    ): Flow<AgentEvent> = channelFlow {
        var currentContext = context
        var state = AgentSessionState()
        var currentTools = tools
        val actionQueue = ArrayDeque<AgentAction>()
        // The mode reminder is injected with the latest user message only when the mode changes (it does not enter system, avoiding breaking the cache when a switch changes the system prefix).
        val modeReminder = takeModeReminderIfChanged(currentContext.sessionId, currentContext.mode)
        actionQueue.addLast(
            AgentAction.InitRequest(
                currentContext.history + AgentMessage.UserMessage(
                    id = currentContext.inputMessageId,
                    content = if (modeReminder == null) userRequest else "$userRequest\n\n$modeReminder",
                    images = currentContext.inputImages
                )
            )
        )

        // Building the system prompt scans the Skill / rules / memory directories, and on a real device over the ROOT channel these file reads and writes go through
        // `su -c` subprocesses + Process.waitFor(), which is blocking IO and must be switched to the IO thread;
        // otherwise the main thread would be blocked by waitFor() until an ANR occurs and the process is killed by the system (appearing as a crash).
        val systemPrompt = withContext(Dispatchers.IO) { promptProvider.build(currentContext) }
        val aiProvider = getEffectiveProvider(currentContext.sessionId)
        // After a compaction failure, compaction is not attempted again during this round (within this user request), avoiding a wasted attempt on every LLM call.
        var compactionAttemptFailed = false
        val metadata = modelMetadataService.resolve(aiProvider.providerId, when (aiProvider) {
            is AnthropicAdapter -> ProviderType.ANTHROPIC
            is GeminiAdapter -> ProviderType.GEMINI
            else -> ProviderType.OPENAI
        }, aiProvider.model)
        val inputBudget = ModelContextPolicy.effectiveInputBudget(metadata)
        if (aiProvider is AnthropicAdapter) {
            aiProvider.maxOutputTokens = ModelContextPolicy.outputReserveTokens(metadata)
        }
        val lastAssistantIndex = currentContext.history.indexOfLast { it is AgentMessage.AssistantMessage && it.inputTokens > 0 }
        var baselineEstimate = ContextTokenEstimator.estimate(systemPrompt,
            currentContext.history.take(lastAssistantIndex.coerceAtLeast(0)), currentTools)
        var baselineUsage = if (currentContext.lastInputTokens > 0 && lastAssistantIndex >= 0) {
            (currentContext.history[lastAssistantIndex] as AgentMessage.AssistantMessage).inputTokens
        } else 0
        var overflowRecoveryAttempted = false

        while (!state.isFinished && actionQueue.isNotEmpty()) {
            val action = actionQueue.removeFirst()
            val (newState, effects) = reduce(state, action)
            state = newState

            for (effect in effects) {
                when (effect) {
                    is AgentSideEffect.PersistUser -> {
                        currentContext.sessionId?.let { sessionId ->
                            messagePersistenceUseCase.persist(sessionId, com.aicode.feature.agent.presentation.MessageRole.USER,
                                effect.message.content, id = effect.message.id)
                            messagePersistenceUseCase.invalidateHistory(sessionId)
                        }
                    }
                    is AgentSideEffect.CallLlm -> {
                        val providerInUse = aiProvider
                        // Compaction round: if a dedicated compaction model is configured, use the independent compaction model to compact
                        val compactionProvider = resolveCompactionFallbackProvider(currentContext.sessionId) ?: providerInUse
                        var compactedMessages = state.messages
                        if (!compactionAttemptFailed) {
                            val estimate = ContextTokenEstimator.estimate(systemPrompt, state.messages, currentTools)
                            val predictedInput = ContextTokenEstimator.calibrated(estimate, baselineEstimate, baselineUsage)
                            val compaction = contextCompactor.compactIfNeeded(state.messages, compactionProvider, context.sessionId,
                                windowProvider = aiProvider, systemPrompt = systemPrompt, tools = currentTools,
                                currentInputTokens = predictedInput) { event ->
                                if (event is AgentEvent.CompactionFailed) compactionAttemptFailed = true
                                send(event)
                            }
                            compactedMessages = compaction.messages
                            if (compaction.compacted) {
                                state = state.copy(messages = compaction.messages)
                                baselineUsage = 0
                                baselineEstimate = 0
                            }
                        }
                        val requestEstimate = ContextTokenEstimator.estimate(systemPrompt, compactedMessages, currentTools)
                        val predictedInput = ContextTokenEstimator.calibrated(requestEstimate, baselineEstimate, baselineUsage)
                        send(AgentEvent.ContextUsage(predictedInput, inputBudget, true))
                        if (predictedInput >= inputBudget) {
                            actionQueue.addLast(AgentAction.LlmError("", "input_budget_exceeded"))
                            continue
                        }

                        val acc = StringBuilder()
                        val reasoningAcc = StringBuilder()
                        var finalResponse: AIResponse? = null

                        // Call-statistics instrumentation: records the request-sent / first-token / end times and usage; failures and cancellations also leave a trace.
                        val callStartElapsed = SystemClock.elapsedRealtime()
                        val callStartWall = System.currentTimeMillis()
                        val callKind = "chat"
                        var ttfbElapsed: Long? = null
                        var callError: String? = null
                        var callCompleted = false

                        // Streaming delta throttling: every upstream chunk carries the full accumulated text, and sending each one would make
                        // the ViewModel side rebuild state dozens of times per second and the UI side repeatedly restart the typewriter coroutine. Merge over a time window:
                        // within the window only the latest accumulated text is kept and sent at the window boundary, pressing the downstream event rate down to ~16/s.
                        // Typewriter rendering itself has 100ms throttling, so sending fewer intermediate states is imperceptible; after collect finishes, the last pending item is sent.
                        val DELTA_THROTTLE_MS = 60L
                        var lastTextDeltaSentAt = 0L
                        var lastReasoningDeltaSentAt = 0L
                        var pendingTextDelta: String? = null
                        var pendingReasoningDelta: String? = null
                        var retryAttempts = 0
                        suspend fun flushPendingTextDelta() {
                            val text = pendingTextDelta ?: return
                            pendingTextDelta = null
                            send(AgentEvent.AssistantDelta(text))
                        }
                        suspend fun flushPendingReasoningDelta() {
                            val text = pendingReasoningDelta ?: return
                            pendingReasoningDelta = null
                            send(AgentEvent.ReasoningDelta(text))
                        }

                        try {
                            // Before sending, process images according to the actual model's vision capability (same as the execute path).
                            val supportsVision = activeModelSupportsVision(currentContext.sessionId)
                            val messagesToSend = withSkillHint(
                                sanitizeImagesForModel(compactedMessages, supportsVision),
                                currentContext,
                                currentTools
                            )
                            providerInUse.completeStream(systemPrompt, messagesToSend, currentTools, currentContext.reasoningEffort).collect { chunk ->
                                when (chunk) {
                                    is AIStreamChunk.TextDelta -> {
                                        if (ttfbElapsed == null) ttfbElapsed = SystemClock.elapsedRealtime() - callStartElapsed
                                        acc.append(chunk.text)
                                        pendingTextDelta = acc.toString()
                                        val now = SystemClock.elapsedRealtime()
                                        if (now - lastTextDeltaSentAt >= DELTA_THROTTLE_MS) {
                                            flushPendingTextDelta()
                                            lastTextDeltaSentAt = now
                                        }
                                    }
                                    is AIStreamChunk.ReasoningDelta -> {
                                        // Reasoning content also counts as the first token (reasoning models emit reasoning first and body text afterwards)
                                        if (ttfbElapsed == null) ttfbElapsed = SystemClock.elapsedRealtime() - callStartElapsed
                                        reasoningAcc.append(chunk.text)
                                        pendingReasoningDelta = reasoningAcc.toString()
                                        val now = SystemClock.elapsedRealtime()
                                        if (now - lastReasoningDeltaSentAt >= DELTA_THROTTLE_MS) {
                                            flushPendingReasoningDelta()
                                            lastReasoningDeltaSentAt = now
                                        }
                                    }
                                    is AIStreamChunk.Retrying -> {
                                        retryAttempts = maxOf(retryAttempts, chunk.attempt)
                                        acc.setLength(0)
                                        reasoningAcc.setLength(0)
                                        pendingTextDelta = null
                                        pendingReasoningDelta = null
                                        lastTextDeltaSentAt = 0L
                                        lastReasoningDeltaSentAt = 0L
                                        send(AgentEvent.Retrying(chunk.attempt, chunk.maxRetries, chunk.error))
                                    }
                                    is AIStreamChunk.KeySwitched -> {
                                        acc.setLength(0)
                                        reasoningAcc.setLength(0)
                                        pendingTextDelta = null
                                        pendingReasoningDelta = null
                                        lastTextDeltaSentAt = 0L
                                        lastReasoningDeltaSentAt = 0L
                                        send(AgentEvent.KeySwitched(chunk.newIndex, chunk.total))
                                    }
                                    is AIStreamChunk.Final -> {
                                        // A pure tool-call round has no text/reasoning increments; Final is the first content event, so it is recorded as a fallback for TTFB
                                        if (ttfbElapsed == null) ttfbElapsed = SystemClock.elapsedRealtime() - callStartElapsed
                                        finalResponse = chunk.response
                                    }
                                    is AIStreamChunk.ToolCallDeclared -> {
                                        // The tool name arrives before the arguments: tell the UI immediately "what the model is preparing to call",
                                        // so it does not stay on "thinking" while long arguments stream in.
                                        if (ttfbElapsed == null) ttfbElapsed = SystemClock.elapsedRealtime() - callStartElapsed
                                        send(AgentEvent.ToolCallPreparing(chunk.name))
                                    }
                                }
                            }
                            // The latest accumulated text may still be held inside the throttle window: send it, ensuring the UI tail gets the complete text before handing over to persistence.
                            flushPendingTextDelta()
                            flushPendingReasoningDelta()
                            val aiResponse = finalResponse ?: AIResponse(content = acc.toString())
                            // Images output directly by the model (Gemini image models) do not arrive with the streaming increments; they come whole in Final:
                            // first save the base64 to ~/.aicode/generated-images/ and build UI attachments (only the path is stored, not the base64),
                            // then hand the images with paths to reduce via LlmResponse, to attach to the AssistantMessage for the next playback.
                            val (persistedImages, attachments) =
                                if (aiResponse.images.isNotEmpty()) persistModelImages(aiResponse.images) else emptyList<AgentImage>() to emptyList()
                            callCompleted = true
                            // Attach this round's reasoning to the AIResponse, so reduce can store it in the AssistantMessage and pass it back in the next round
                            if (aiResponse.stopReason == "model_context_window_exceeded" && !overflowRecoveryAttempted) {
                                overflowRecoveryAttempted = true
                                val recovery = contextCompactor.compactIfNeeded(state.messages, compactionProvider,
                                    currentContext.sessionId, force = true, windowProvider = aiProvider,
                                    systemPrompt = systemPrompt, tools = currentTools, currentInputTokens = predictedInput) { send(it) }
                                if (recovery.compacted) {
                                    state = state.copy(messages = recovery.messages)
                                    baselineUsage = 0
                                    baselineEstimate = 0
                                    actionQueue.addLast(AgentAction.InitRequest(recovery.messages))
                                    continue
                                }
                            }
                            baselineEstimate = requestEstimate
                            baselineUsage = aiResponse.inputTokens
                            send(AgentEvent.ContextUsage(
                                if (baselineUsage > 0) baselineUsage else requestEstimate, inputBudget, baselineUsage <= 0
                            ))
                            val responseMessageId = UUID.randomUUID().toString()
                            val responseWithReasoning = if (reasoningAcc.isNotEmpty()) {
                                aiResponse.copy(reasoning = reasoningAcc.toString())
                            } else aiResponse

                            if (aiResponse.content.isNotBlank() || aiResponse.toolCalls.isNotEmpty() || attachments.isNotEmpty()) {
                                val persisted = kotlinx.coroutines.CompletableDeferred<Unit>()
                                send(
                                    AgentEvent.AssistantText(
                                        aiResponse.content,
                                        aiResponse.toolCalls,
                                        reasoningAcc.toString(),
                                        aiResponse.signature ?: "",
                                        aiResponse.inputTokens,
                                        aiResponse.outputTokens,
                                        aiResponse.cachedInputTokens,
                                        aiResponse.thinkingBlocksJson ?: "",
                                        attachments = attachments,
                                        messageId = responseMessageId,
                                        persisted = persisted
                                    )
                                )
                                persisted.await()
                            }
                            actionQueue.addLast(
                                AgentAction.LlmResponse(
                                    if (persistedImages.isNotEmpty()) responseWithReasoning.copy(images = persistedImages)
                                    else responseWithReasoning,
                                    messageId = responseMessageId
                                )
                            )
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            if (!overflowRecoveryAttempted && acc.isEmpty() && reasoningAcc.isEmpty() && isContextOverflow(e)) {
                                overflowRecoveryAttempted = true
                                val recovery = contextCompactor.compactIfNeeded(state.messages, compactionProvider,
                                    currentContext.sessionId, force = true, windowProvider = aiProvider,
                                    systemPrompt = systemPrompt, tools = currentTools, currentInputTokens = predictedInput) { send(it) }
                                if (recovery.compacted) {
                                    state = state.copy(messages = recovery.messages)
                                    baselineUsage = 0
                                    baselineEstimate = 0
                                    actionQueue.addLast(AgentAction.InitRequest(recovery.messages))
                                    callError = e.message
                                    continue
                                }
                            }
                            val partial = acc.toString()
                            val reasoning = reasoningAcc.toString()
                            // When streaming is interrupted, the reasoning already received must also be persisted: otherwise the finally below would clear the streaming reasoning bubble,
                            // and the relay message from persistence would never have been produced, appearing as "the reasoning showed and then vanished without any error".
                            // Persist if there is either body text or reasoning; if both are empty, do not write an empty message.
                            if (partial.isNotEmpty() || reasoning.isNotBlank()) {
                                send(AgentEvent.AssistantText(partial, emptyList(), reasoning))
                            }
                            // The automatic switching and resending for multi-Key setups is already done inside the adapter (see AIProvider.keySwitcher);
                            // reaching here means it is not a Key problem, or all candidate Keys have already failed, so report the original error directly.
                            val errorText = "LLM call failed: ${e.message}"
                            actionQueue.addLast(AgentAction.LlmError(errorText))
                            callError = e.message ?: e.javaClass.simpleName
                        } finally {
                            val durationMillis = (SystemClock.elapsedRealtime() - callStartElapsed).toInt()
                            val usage = finalResponse
                            // Critical: during cancellation unwinding (the user taps stop) a suspend must never be called.
                            // Even wrapping a Room insert in withContext(NonCancellable) still crashes --
                            // the device tombstones (2026-10-05, reproduced 8+ times) all land on the stack at
                            // StatefulAgentWorkflow$executeEvents$1.invokeSuspend+0, with abort messages
                            // "No pending exception expected: JobCancellationException" and
                            // "Unexpected instruction: unused-e5" -- i.e. the coroutine state machine, during suspension/resumption,
                            // gets its labels scrambled, and the ART interpreter then crashes on an illegal instruction.
                            // This is therefore changed to dispatch to an independent non-cancellable scope: launch is not a suspend function,
                            // so it does not suspend/resume the current coroutine, completely avoiding the state-machine jumps on the cancellation-unwinding path.
                            val record = LlmCallRecordEntity(
                                sessionId = currentContext.sessionId,
                                providerId = providerInUse.providerId.ifBlank { null },
                                model = providerInUse.model,
                                reasoningEffort = currentContext.reasoningEffort,
                                kind = callKind,
                                inputTokens = usage?.inputTokens ?: 0,
                                outputTokens = usage?.outputTokens ?: 0,
                                cachedInputTokens = usage?.cachedInputTokens ?: 0,
                                cacheCreationTokens = usage?.cacheCreationTokens ?: 0,
                                ttfbMillis = ttfbElapsed?.toInt(),
                                durationMillis = durationMillis,
                                status = when {
                                    callCompleted -> "success"
                                    callError != null -> "error"
                                    else -> "cancelled"
                                },
                                errorMessage = callError,
                                stopReason = usage?.stopReason,
                                retryCount = retryAttempts,
                                createdAt = callStartWall
                            )
                            recordScope.launch { runCatching { llmCallRecordDao.insert(record) } }
                        }
                    }
                    is AgentSideEffect.RequestPermission -> {
                        val tool = toolRegistry.getTool(effect.toolCall.name)
                        val argsPreview = JsonObject(effect.toolCall.arguments).toString().take(500)
                        val checkResult = requestPermissionIfNeeded(tool, effect.toolCall.id, effect.toolCall.arguments, argsPreview, currentContext.mode, currentContext.sessionId)

                        if (!checkResult.approved) {
                            val rawResult = ToolResult.Error(checkResult.denyReason, checkResult.errorCode).toTransportString()
                            send(AgentEvent.ToolCallFinished(effect.toolCall.id, effect.toolCall.name, rawResult, true, argsPreview))
                        } else {
                            send(AgentEvent.ToolCallStarted(effect.toolCall.id, effect.toolCall.name, argsPreview))
                        }
                        actionQueue.addLast(AgentAction.PermissionEvaluated(effect.toolCall, checkResult.approved, argsPreview, checkResult.denyReason, checkResult.errorCode))
                    }
                    is AgentSideEffect.CancelToolBatch -> {
                        // Whole-batch cancellation: re-issue completion events for approved but unexecuted tools (with content saying not executed),
                        // so the ViewModel clears runningTool and REPLACEs the "executing" placeholder message.
                        effect.toolCalls.forEach { toolCall ->
                            send(
                                AgentEvent.ToolCallFinished(
                                    id = toolCall.id,
                                    toolName = toolCall.name,
                                    result = ToolResult.Error(
                                        "The user declined this round of tool calls; this call was not executed.",
                                        USER_REJECTED_CODE
                                    ).toTransportString(),
                                    isError = true
                                )
                            )
                        }
                    }
                    is AgentSideEffect.ExecuteToolBatch -> {
                        // Execute this batch of approved tools in parallel. First uniformly record checkpoints (snapshots before editFile/writeFile modifications),
                        // then execute in parallel; mode-switch checks are handled serially on the main coroutine after results are collected (the planApproval singleton).
                        val toolCalls = effect.toolCalls
                        toolCalls.forEach { toolCall ->
                            if (toolCall.name == "editFile" || toolCall.name == "writeFile") {
                                (toolCall.arguments["path"] as? JsonPrimitive)?.contentOrNull?.let { path ->
                                    currentContext.sessionId?.let { sid ->
                                        checkpointManager.beforeFileModified(sid, path)
                                    }
                                }
                            }
                        }

                        val runResults = if (toolCalls.isEmpty()) {
                            emptyList()
                        } else {
                            coroutineScope {
                                toolCalls.map { toolCall ->
                                    async {
                                        val tool = toolRegistry.getTool(toolCall.name)
                                        if (tool is StreamingAgentTool) {
                                            toolInvoker.runStream(tool, toolCall, currentContext) { send(it) }
                                        } else {
                                            toolInvoker.runSync(tool, toolCall, currentContext)
                                        }
                                    }
                                }.awaitAll()
                            }
                        }

                        // Handle mode switches serially and assemble the batch results.
                        val batchResults = mutableListOf<ToolBatchResult>()
                        toolCalls.forEachIndexed { index, toolCall ->
                            val runResult = runResults.getOrNull(index)
                                ?: ToolRunResult(ToolResult.Error("Tool not executed", "TOOL_NOT_EXECUTED").toTransportString(), true)
                            var rawResult = runResult.raw
                            var isError = runResult.isError
                            val (newCtx, updated) = checkAndUpdateMode(toolCall, isError, currentContext)
                            if (updated) {
                                val reason = (toolCall.arguments["reason"] as? JsonPrimitive)?.content?.trim()
                                    ?: toolCall.arguments["reason"]?.toString()?.replace("\"", "")?.trim()
                                    ?: ""
                                send(AgentEvent.ModeChanged(newCtx.mode, reason))

                                // When leaving PLAN, suspend the workflow and wait until the user approves in the plan review panel before continuing
                                if (currentContext.mode == AgentMode.PLAN && newCtx.mode != AgentMode.PLAN) {
                                    val choice = planApprovalManager.awaitApproval(reason, currentContext.sessionId)
                                    if (choice == PlanApprovalChoice.APPROVE) {
                                        currentContext = newCtx
                                        // system and mode are now decoupled (SystemPromptProvider no longer injects the mode prompt),
                                        // so switching does not rebuild systemPrompt, avoiding breaking the cache with system-prefix changes; the mode state is conveyed through tool results and next-round message reminders.
                                        rawResult += buildModeSwitchNotice(newCtx.mode)
                                    } else {
                                        // The user chose to keep giving feedback, so roll back to PLAN mode and correct the tool result so the AI knows the switch was cancelled and should wait for the user's feedback
                                        currentContext = currentContext.copy(mode = AgentMode.PLAN)
                                        rawResult = ToolResult.Error(
                                            "The user wants to add notes or adjust the plan; stay in PLAN mode for now. Wait for the user's specific additions or changes; do not guess at revisions, and continue only after the user gives clear feedback.",
                                            "MODE_SWITCH_REJECTED"
                                        ).toTransportString()
                                        isError = true
                                    }
                                } else {
                                    currentContext = newCtx
                                    rawResult += buildModeSwitchNotice(newCtx.mode)
                                }
                            }
                            batchResults.add(ToolBatchResult(toolCall.id, toolCall.name, rawResult, isError, runResult.attachments, runResult.images))
                        }

                        // Background task / sub-agent completion notifications arriving within this round: deliver them immediately piggybacked on the last tool result of this batch,
                        // so the AI can perceive them in the same round without waiting for this round to end and starting a new one. The ack is placed after the completion event is emitted (the result has been persisted):
                        // if cancelled in between, the notification stays in the queue and is delivered by a later batch or the fallback path at the end of this round.
                        val notifySessionId = currentContext.sessionId
                        val notifications = if (notifySessionId != null && batchResults.isNotEmpty()) {
                            agentNotificationCenter.peek(notifySessionId)
                        } else {
                            emptyList()
                        }
                        if (notifications.isNotEmpty()) {
                            val modeChange = notifications.lastOrNull { it.kind == AgentNotificationKind.MODE_CHANGE }
                            val newMode = modeChange?.newMode
                            if (newMode != null) {
                                // The user switched the mode while work was in progress: permission judgments for subsequent batches in this round immediately use the new mode.
                                // Keeps the same semantics as SessionUseCase.updateMode: entering PLAN records the mode from before entry, and all other cases clear it.
                                val prevMode = currentContext.mode
                                currentContext = if (newMode == AgentMode.PLAN) {
                                    currentContext.copy(
                                        mode = AgentMode.PLAN,
                                        modeBeforePlan = if (prevMode != AgentMode.PLAN) prevMode else currentContext.modeBeforePlan
                                    )
                                } else {
                                    currentContext.copy(mode = newMode, modeBeforePlan = null)
                                }
                            }
                            val last = batchResults.last()
                            var injected = eventInjector.inject(last.result, notifications)
                            if (newMode != null) {
                                // The mode-constraint hint is persisted with the tool result and stays in the history for later rounds to carry forward.
                                injected += buildExternalModeSwitchNotice(newMode)
                            }
                            batchResults[batchResults.lastIndex] = last.copy(result = injected)
                        }

                        // Push completion events one by one (keeping the order consistent with batchToolCalls), then enter wrap-up.
                        batchResults.forEach { br ->
                            val persisted = kotlinx.coroutines.CompletableDeferred<Unit>()
                            send(AgentEvent.ToolCallFinished(br.id, br.toolName, br.result, br.isError,
                                attachments = br.attachments, persisted = persisted))
                            persisted.await()
                        }
                        if (notifySessionId != null && notifications.isNotEmpty()) {
                            agentNotificationCenter.ack(notifySessionId, notifications.map { it.seq })
                        }
                        actionQueue.addLast(AgentAction.ToolBatchFinished(batchResults))
                    }
                }
            }
        }
        
        state.error?.let { send(AgentEvent.Failed(it, state.errorCode)) }
        send(AgentEvent.Completed)
    }

    private fun isContextOverflow(error: Throwable): Boolean {
        if (error is StreamApiException && error.code == "context_window_exceeded") return true
        val text = error.message.orEmpty().lowercase()
        return listOf("context_length_exceeded", "context_window_exceeded", "maximum context length",
            "prompt is too long", "input token limit").any { it in text }
    }

    private suspend fun activeModelSupportsVision(sessionId: String?): Boolean {
        val config = resolveProviderConfig(sessionId) ?: return false
        val metadata = modelMetadataService.resolve(config.id, config.type, config.effectiveModel)
        return metadata.supportsVision
    }

    /**
     * Processes images in the messages according to the model's vision capability before sending:
     * - Supports vision: returns them unchanged.
     * - Does not support it: strips all images (affecting only this send, without touching persisted data), so images in the history/input are not sent as-is to
     *   a non-multimodal model and cause the request to fail; after switching back to a multimodal model the image context can still be used normally.
     */
    private fun sanitizeImagesForModel(
        messages: List<AgentMessage>,
        supportsVision: Boolean
    ): List<AgentMessage> {
        if (supportsVision) return messages
        return messages.map { msg ->
            when (msg) {
                is AgentMessage.UserMessage ->
                    if (msg.images.isEmpty()) msg
                    else msg.copy(images = emptyList(), content = msg.content.ifBlank { "(image omitted: the current model does not support image input)" })
                is AgentMessage.ToolResultMessage ->
                    if (msg.images.isEmpty()) msg else msg.copy(images = emptyList())
                is AgentMessage.AssistantMessage -> msg
            }
        }
    }

    /**
     * Injects a "relevant skills" reminder on every round (mirroring Claude Code's "Skills relevant to your task:"):
     * The skill list in the system prompt is injected only once per session, and after the model has run a few rounds of tools it gradually forgets it; before this round's request is sent,
     * the most relevant few skills, together with their descriptions, are pushed in front of it again based on the latest user message, raising the `loadSkill` call rate.
     *
     * Only appended to the end of the latest user message of this round and not persisted: the system prompt and history messages stay byte-for-byte unchanged,
     * and the provider prefix cache still hits as usual (KV Cache friendly).
     * Injected only for the main agent: a sub-agent's tool whitelist may not contain loadSkill, and its prompt may not have had the skill list injected either.
     * The first skill scan was completed and cached when the system prompt was built; here it is normally a pure in-memory computation;
     * defensively switch to the IO thread to avoid blocking IO over the ROOT channel on a real device stalling the main thread.
     */
    private suspend fun withSkillHint(
        messages: List<AgentMessage>,
        ctx: AgentContext,
        tools: List<AgentTool>
    ): List<AgentMessage> {
        if (ctx.agentDefinition != null) return messages
        if (tools.none { it.name == "loadSkill" }) return messages
        val lastUserIndex = messages.indexOfLast { it is AgentMessage.UserMessage }
        if (lastUserIndex < 0) return messages
        val lastUser = messages[lastUserIndex] as AgentMessage.UserMessage
        if (lastUser.content.isBlank()) return messages
        val hint = withContext(Dispatchers.IO) {
            runCatching { promptProvider.buildSkillHint(ctx, lastUser.content) }.getOrNull()
        } ?: return messages
        return messages.toMutableList().apply {
            this[lastUserIndex] = lastUser.copy(content = lastUser.content.trimEnd() + "\n\n" + hint)
        }
    }

    /**
     * Resolves the provider dedicated to the compaction round. If the user has configured a dedicated compaction model and the provider exists, is enabled and has an apiKey,
     * returns a brand-new independent AIProvider instance; otherwise returns null (using the current chat model).
     */
    private suspend fun resolveCompactionFallbackProvider(sessionId: String? = null): AIProvider? {
        val providerId = compactionModelSettingsRepository.getCompactionProviderId().trim()
        if (providerId.isEmpty()) return null
        val model = compactionModelSettingsRepository.getCompactionModel().trim()
        if (model.isEmpty()) return null
        val config = aiProviderRepository.getProviderById(providerId) ?: return null
        if (!config.isEnabled || !config.hasUsableApiKey) return null
        return createStandaloneProvider(config.copy(selectedModel = model), sessionId)
    }

    /**
     * Resolves the provider dedicated to title generation. If the user has configured a dedicated title summarization model and the provider exists, is enabled and has an apiKey,
     * returns a brand-new independent AIProvider instance; otherwise returns null (using the current chat model).
     */
    private suspend fun resolveTitleFallbackProvider(sessionId: String?): AIProvider? {
        val providerId = titleModelSettingsRepository.getTitleProviderId().trim()
        if (providerId.isEmpty()) return null
        val model = titleModelSettingsRepository.getTitleModel().trim()
        if (model.isEmpty()) return null
        val config = aiProviderRepository.getProviderById(providerId) ?: return null
        if (!config.isEnabled || !config.hasUsableApiKey) return null
        return createStandaloneProvider(config.copy(selectedModel = model), sessionId)
    }

    /**
     * Resolves the provider dedicated to the AI command reviewer. If the user has configured a dedicated reviewer model and the provider exists, is enabled and has an apiKey,
     * returns a brand-new independent AIProvider instance; otherwise returns null (using the current chat model).
     */
    private suspend fun resolveReviewerFallbackProvider(sessionId: String?): AIProvider? {
        val providerId = reviewerModelSettingsRepository.getReviewerProviderId().trim()
        if (providerId.isEmpty()) return null
        val model = reviewerModelSettingsRepository.getReviewerModel().trim()
        if (model.isEmpty()) return null
        val config = aiProviderRepository.getProviderById(providerId) ?: return null
        if (!config.isEnabled || !config.hasUsableApiKey) return null
        return createStandaloneProvider(config.copy(selectedModel = model), sessionId)
    }

    /** AI command review conclusion: [Allow] lets it through directly; [Escalate] falls back to a human dialog (with the reviewer's danger explanation, which may be empty). */
    private sealed interface AiReviewResult {
        data object Allow : AiReviewResult
        data class Escalate(val reason: String?) : AiReviewResult
    }

    /**
     * Independent review of commands in the "autonomous execution" tier: defaults to following the current chat model, and uses the dedicated reviewer model if one is configured.
     * The prompt comes from `agent/command-reviewer.md` in [SystemPromptProvider].
     * On call failure / parse failure / UNSURE it always returns [AiReviewResult.Escalate] to fall back to a human (safe fallback).
     */
    private suspend fun reviewCommandWithAi(command: String, sessionId: String?): AiReviewResult {
        return try {
            val provider = resolveReviewerFallbackProvider(sessionId) ?: getEffectiveProvider(sessionId)
            val prompt = promptProvider.resolvePrompt(COMMAND_REVIEWER_FILE)
                .replace(LEADING_COMMENT, "") + reviewerLanguageInstruction()
            val callStartWall = System.currentTimeMillis()
            val callStartElapsed = SystemClock.elapsedRealtime()
            var callCompleted = false
            var callError: String? = null
            var usage: AIResponse? = null
            val response = try {
                val resp = provider.complete(
                    systemPrompt = prompt,
                    messages = listOf(AgentMessage.UserMessage(content = "Command under review:\n```sh\n$command\n```")),
                    tools = emptyList()
                )
                usage = resp
                callCompleted = true
                resp
            } catch (e: CancellationException) {
                callError = "cancelled"
                throw e
            } catch (e: Exception) {
                callError = e.message ?: e.javaClass.simpleName
                throw e
            } finally {
                val durationMillis = (SystemClock.elapsedRealtime() - callStartElapsed).toInt()
                // Same as executeEvents: during cancellation unwinding do not call suspend; dispatch to the non-cancellable scope for persistence.
                val record = LlmCallRecordEntity(
                    sessionId = sessionId,
                    providerId = provider.providerId.ifBlank { null },
                    model = provider.model,
                    kind = "command_review",
                    inputTokens = usage?.inputTokens ?: 0,
                    outputTokens = usage?.outputTokens ?: 0,
                    cachedInputTokens = usage?.cachedInputTokens ?: 0,
                    cacheCreationTokens = usage?.cacheCreationTokens ?: 0,
                    ttfbMillis = null,
                    durationMillis = durationMillis,
                    status = if (callCompleted) "success" else "error",
                    errorMessage = callError,
                    stopReason = usage?.stopReason,
                    createdAt = callStartWall
                )
                recordScope.launch { runCatching { llmCallRecordDao.insert(record) } }
            }
            parseReviewVerdict(response.content)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.w(TAG, "AI command review failed; falling back to human confirmation", e)
            AiReviewResult.Escalate(null)
        }
    }

    /**
     * The additional instruction for the reviewer warning language: REASON is read by the person tapping the "Reject/Unsure" dialog,
     * so it must follow the app UI language (e.g. write in Chinese when the interface is in Chinese), otherwise an English warning is useless to the user.
     * It constrains only the natural-language part of REASON; the machine-parsed keywords VERDICT/ALLOW/DENY/UNSURE remain fixed in English.
     */
    private suspend fun reviewerLanguageInstruction(): String {
        val tag = languageSettingsRepository.snapshot() ?: Locale.getDefault().toLanguageTag()
        val name = when (tag.substringBefore('-').lowercase()) {
            "zh" -> "Simplified Chinese"
            "en" -> "English"
            "ja" -> "Japanese"
            "ko" -> "Korean"
            "ru" -> "Russian"
            "fr" -> "French"
            "de" -> "German"
            "es" -> "Spanish"
            else -> null
        }
        val lang = name ?: "the language identified by BCP-47 tag \"$tag\""
        return "\n\n<language>\nThe human approver reads $lang. " +
            "Write the REASON line in $lang. " +
            "Keep the literal keywords \"VERDICT:\", \"ALLOW\", \"DENY\", \"UNSURE\", \"REASON:\" in English exactly as specified.\n</language>"
    }

    /** Parses the reviewer output: the first line is `VERDICT: ALLOW|DENY|UNSURE`; DENY/UNSURE may carry a `REASON:` line. */
    private fun parseReviewVerdict(content: String): AiReviewResult {        val lines = content.lines()
        val verdictLine = lines.firstOrNull { it.trim().startsWith("VERDICT:", ignoreCase = true) }
            ?: return AiReviewResult.Escalate(null)
        val verdict = verdictLine.substringAfter(':').trim().uppercase()
        val reason = lines.firstOrNull { it.trim().startsWith("REASON:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()?.takeIf { it.isNotEmpty() }
        return when (verdict) {
            "ALLOW" -> AiReviewResult.Allow
            "DENY" -> AiReviewResult.Escalate(reason ?: "The AI reviewer judged this command to be risky")
            else -> AiReviewResult.Escalate(reason)
        }
    }

    /**
     * Generates a title for a newly created session: defaults to following the current chat model, and uses the dedicated title summarization model if one is configured.
     * The prompt comes from `agent/title-generator.md` in [SystemPromptProvider].
     * Returns null when generation fails or no title can be obtained (the caller keeps the temporary title).
     */
    override suspend fun generateTitle(sessionId: String, request: String): String? = runCatching {
        val provider = resolveTitleFallbackProvider(sessionId) ?: getEffectiveProvider(sessionId)
        val prompt = promptProvider.resolvePrompt(TITLE_GENERATOR_FILE)
            .replace(LEADING_COMMENT, "")
        val callStartWall = System.currentTimeMillis()
        val callStartElapsed = SystemClock.elapsedRealtime()
        var callCompleted = false
        var callError: String? = null
        var usage: AIResponse? = null
        val response = try {
            val resp = provider.complete(
                systemPrompt = prompt,
                messages = listOf(AgentMessage.UserMessage(content = request)),
                tools = emptyList()
            )
            usage = resp
            callCompleted = true
            resp
        } catch (e: CancellationException) {
            callError = "cancelled"
            throw e
        } catch (e: Exception) {
            callError = e.message ?: e.javaClass.simpleName
            throw e
        } finally {
            val durationMillis = (SystemClock.elapsedRealtime() - callStartElapsed).toInt()
            // Same as executeEvents: during cancellation unwinding do not call suspend; dispatch to the non-cancellable scope for persistence.
            val record = LlmCallRecordEntity(
                sessionId = sessionId,
                providerId = provider.providerId.ifBlank { null },
                model = provider.model,
                kind = "title",
                inputTokens = usage?.inputTokens ?: 0,
                outputTokens = usage?.outputTokens ?: 0,
                cachedInputTokens = usage?.cachedInputTokens ?: 0,
                cacheCreationTokens = usage?.cacheCreationTokens ?: 0,
                ttfbMillis = null,
                durationMillis = durationMillis,
                status = if (callCompleted) "success" else "error",
                errorMessage = callError,
                stopReason = usage?.stopReason,
                createdAt = callStartWall
            )
            recordScope.launch { runCatching { llmCallRecordDao.insert(record) } }
        }
        response.content.trim().take(TITLE_MAX_CHARS).ifBlank { null }
    }.onFailure { e ->
        FileLogger.w(TAG, "Failed to generate session title", e)
    }.getOrNull()

    override suspend fun generateCommitMessage(diff: String): String? = runCatching {
        if (diff.isBlank()) return@runCatching null
        val provider = getEffectiveProvider(sessionId = null)
        val prompt = promptProvider.resolvePrompt(COMMIT_GENERATOR_FILE)
            .replace(LEADING_COMMENT, "")
        val truncatedDiff = diff.take(12000)
        val resp = provider.complete(
            systemPrompt = prompt,
            messages = listOf(AgentMessage.UserMessage(content = "git diff:\n```diff\n$truncatedDiff\n```")),
            tools = emptyList()
        )
        val line = resp.content.lines().firstOrNull { it.isNotBlank() }?.trim()
            ?.removeSurrounding("`")
            ?.removePrefix("\"")
            ?.removeSuffix("\"")
            ?.trim()
        line?.take(100)?.ifBlank { null }
    }.onFailure { e ->
        FileLogger.w(TAG, "Failed to generate commit message", e)
    }.getOrNull()

    /**
     * Saves images output directly by the model (base64) to `~/.aicode/generated-images/`, returning images with container paths
     * and one-to-one corresponding UI attachments (attachments carry only the path, not the base64, so persistence does not bloat database rows).
     */
    private suspend fun persistModelImages(images: List<AgentImage>): Pair<List<AgentImage>, List<AgentAttachment>> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val persisted = mutableListOf<AgentImage>()
            val attachments = mutableListOf<AgentAttachment>()
            images.forEach { image ->
                runCatching {
                    val estimatedBytes = image.base64Data.length.toLong() * 3 / 4
                    if (estimatedBytes > MAX_GENERATED_IMAGE_BYTES) return@runCatching
                    val bytes = Base64.decode(image.base64Data, Base64.DEFAULT)
                    if (bytes.isEmpty() || bytes.size > MAX_GENERATED_IMAGE_BYTES) return@runCatching
                    val unique = "${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}"
                    val targetPath = "$GENERATED_IMAGE_DIR/gen_$unique.${extForMime(image.mimeType)}"
                    fileAccess.writeBytes(targetPath, bytes, overwrite = false)
                    val displayPath = fileAccess.toDisplayPath(targetPath)
                    val localFile = fileAccess.copyToLocal(targetPath)
                    persisted.add(image.copy(path = displayPath))
                    attachments.add(
                        AgentAttachment(
                            fileName = targetPath.substringAfterLast('/'),
                            containerPath = displayPath,
                            localPath = localFile.absolutePath,
                            mimeType = image.mimeType,
                            sizeBytes = bytes.size.toLong(),
                            isImage = true
                        )
                    )
                }
            }
            persisted to attachments
        }

    private fun extForMime(mime: String): String = when (mime.lowercase()) {
        "image/jpeg" -> "jpg"
        "image/webp" -> "webp"
        else -> "png"
    }

    private fun checkAndUpdateMode(toolCall: ToolCall, isError: Boolean, currentContext: AgentContext): Pair<AgentContext, Boolean> {
        if (toolCall.name == "planMode" && !isError) {
            val action = (toolCall.arguments["action"] as? JsonPrimitive)?.content?.trim()?.lowercase()
                ?: toolCall.arguments["action"]?.toString()?.replace("\"", "")?.trim()?.lowercase()
            when (action) {
                // When entering PLAN, remember the current mode; when exiting, restore to it (after a BUILD/ASK→PLAN planning round-trip it returns to the original mode rather than BUILD).
                "enter" -> if (currentContext.mode != AgentMode.PLAN) {
                    return currentContext.copy(mode = AgentMode.PLAN, modeBeforePlan = currentContext.mode) to true
                }
                "exit" -> if (currentContext.mode == AgentMode.PLAN) {
                    return currentContext.copy(mode = currentContext.modeBeforePlan ?: AgentMode.BUILD) to true
                }
            }
        }
        return currentContext to false
    }

    /**
     * Current mode reminder: injected with the latest user message (drawing on the idea of opencode's SessionReminders).
     * The mode prompt does not enter system -- once switched, system would have to be rebuilt, breaking the prefix cache;
     * Instead it is a message-level reminder: each user request is appended to the end of the latest user message, positioned at the tail of the message flow, keeping the prefix stable.
     */
    private fun buildModeReminder(mode: AgentMode): String? = when (mode) {
        AgentMode.PLAN -> promptProvider.resolvePrompt(MODE_REMINDER_PLAN_FILE)
            .replace(LEADING_COMMENT, "")
            .trim()
            .let { "[Mode reminder] $it" }
        AgentMode.ASK -> promptProvider.resolvePrompt(MODE_REMINDER_ASK_FILE)
            .replace(LEADING_COMMENT, "")
            .trim()
            .let { "[Mode reminder] $it" }
        AgentMode.BUILD -> null
    }

    /**
     * Injects pending notifications into a tool result: preferably as the top-level `notifications` field of the transport JSON, so the result is still valid JSON,
     * and the UI's formatToolResult (which only reads data/message) and various structured parsers are unaffected.
     * When raw has had plain text such as mode-switch hints appended and is no longer valid JSON, it degrades to a text append.
     */
    /**
     * Gets the mode reminder to inject this time: returns text and records it only when the mode differs from the last injection (including the first time); otherwise returns null.
     */
    private fun takeModeReminderIfChanged(sessionId: String?, mode: AgentMode): String? {
        if (sessionId == null) return buildModeReminder(mode)
        if (lastInjectedMode[sessionId] == mode) return null
        lastInjectedMode[sessionId] = mode
        return buildModeReminder(mode)
    }

    /** The mode-state notice appended to a planMode tool result after a successful tool call (visible in the same round, without waiting for the next user message). */
    private fun buildModeSwitchNotice(mode: AgentMode): String = when (mode) {
        AgentMode.PLAN -> "\n\n" + promptProvider.resolvePrompt(MODE_REMINDER_PLAN_FILE)
            .replace(LEADING_COMMENT, "")
            .trim()
        AgentMode.BUILD -> "\n\n[Mode switch] The plan has been approved by the user. You are now in BUILD mode and can start executing the plan."
        AgentMode.ASK -> "\n\n[Mode switch] The plan has been approved by the user. You are back in ASK mode: stay read-only, only answer the user's follow-up questions, and do not make changes."
    }

    /** The hint appended to a tool result when the user manually switches mode externally (in the UI); distinguished from the AI's own switching in [buildModeSwitchNotice] to avoid wrongly claiming "the plan has been approved". */
    private fun buildExternalModeSwitchNotice(mode: AgentMode): String = when (mode) {
        AgentMode.PLAN -> "\n\n" + promptProvider.resolvePrompt(MODE_REMINDER_PLAN_FILE)
            .replace(LEADING_COMMENT, "")
            .trim()
        AgentMode.BUILD -> "\n\n[Mode switch] The user switched the mode to BUILD; you may perform write operations normally."
        AgentMode.ASK -> "\n\n[Mode switch] The user switched the mode to ASK: stay read-only, only answer and explore, and do not make changes."
    }

    private suspend fun requestPermissionIfNeeded(
        tool: AgentTool?,
        callId: String,
        arguments: Map<String, JsonElement>,
        argsPreview: String,
        mode: AgentMode,
        sessionId: String?
    ): PermissionCheckResult {
        if (tool == null) {
            return PermissionCheckResult(true)
        }

        // When planMode exits PLAN, the plan review panel afterwards will backstop the user's decision,
        // so a permission dialog here would be redundant and it is let through directly; the direction of entering PLAN has no later review panel, so it still goes through a permission dialog.
        if (tool.name == "planMode" && mode == AgentMode.PLAN) {
            val action = (arguments["action"] as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase()
            if (action == "exit") {
                return PermissionCheckResult(true)
            }
        }

        val eval = policyEngine.evaluate(tool, tool.name, arguments, mode)
        if (eval.verdict == ToolPermissionPolicyEngine.Verdict.DENY) {
            val reason = eval.denyReason ?: "This tool is forbidden by the project's safety rule policy"
            val code = if (mode == AgentMode.PLAN) "PLAN_MODE_REJECTED" else "SYSTEM_DENIED"
            return PermissionCheckResult(false, reason, code)
        }

        if (tool.permissionPolicy == ToolPermissionPolicy.AUTO_APPROVE) {
            return PermissionCheckResult(true)
        }

        return when (eval.verdict) {
            ToolPermissionPolicyEngine.Verdict.ALLOW -> PermissionCheckResult(true)
            ToolPermissionPolicyEngine.Verdict.DENY -> PermissionCheckResult(false)
            ToolPermissionPolicyEngine.Verdict.ASK -> {
                // "Autonomous execution" tier: first hand it to the AI command reviewer for independent review; if it passes, let it through directly;
                // on rejection/uncertainty/failure, fall back to a human dialog (when rejected, with the danger explanation given by the reviewer).
                var aiReviewNote: String? = null
                if (eval.aiReviewable) {
                    val command = ((arguments["command"] ?: arguments["input"]) as? JsonPrimitive)?.contentOrNull
                    if (command != null) {
                        when (val review = reviewCommandWithAi(command, sessionId)) {
                            is AiReviewResult.Allow -> return PermissionCheckResult(true)
                            is AiReviewResult.Escalate -> aiReviewNote = review.reason
                        }
                    }
                }
                val base = tool.buildPermissionRequest(callId, arguments, argsPreview)
                val request = base.copy(
                    title = eval.askTitle ?: base.title,
                    rememberablePatterns = eval.rememberablePatterns,
                    rememberDisabledReason = eval.rememberDisabledReason,
                    sessionId = sessionId.orEmpty(),
                    reviewNote = aiReviewNote
                )
                when (permissionManager.awaitApproval(request)) {
                    PermissionChoice.REJECT -> PermissionCheckResult(false, "The user declined to execute this tool", "USER_REJECTED")
                    PermissionChoice.ONCE -> PermissionCheckResult(true)
                    PermissionChoice.ALWAYS -> {
                        if (eval.rememberablePatterns.isNotEmpty()) {
                            policyEngine.remember(tool.name, eval.rememberablePatterns, PermissionScope.PROJECT)
                        }
                        PermissionCheckResult(true)
                    }
                }
            }
        }
    }
}
