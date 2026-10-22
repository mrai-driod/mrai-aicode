package com.aicode.feature.settings.presentation

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.core.net.AppProxy
import com.aicode.core.util.FileLogger
import com.aicode.core.util.LogLevel
import com.aicode.feature.agent.data.local.dao.LlmCallRecordDao
import com.aicode.feature.agent.data.local.dao.RecentCallRecord
import com.aicode.feature.agent.data.local.entity.LlmCallRecordEntity
import com.aicode.feature.agent.domain.container.ConnectionState
import com.aicode.feature.agent.domain.container.ContainerImageCatalog
import com.aicode.feature.agent.domain.container.ContainerImageEntry
import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.agent.domain.container.ContainerOsDetector
import com.aicode.feature.agent.domain.container.ContainerProfile
import com.aicode.feature.agent.domain.container.RemoteSshConnection
import com.aicode.feature.agent.domain.container.RootfsSource
import com.aicode.feature.agent.domain.device.DeviceRootEngine
import com.aicode.feature.agent.domain.mcp.McpConfigRepository
import com.aicode.feature.agent.domain.mcp.McpManager
import com.aicode.feature.agent.domain.mcp.McpScope
import com.aicode.feature.agent.domain.mcp.McpServerConfig
import com.aicode.feature.agent.domain.mcp.McpServerEntry
import com.aicode.feature.agent.domain.mcp.McpServerStatus
import com.aicode.feature.agent.domain.mcp.McpToolDescriptor
import com.aicode.feature.agent.domain.model.AgentMode
import com.aicode.feature.agent.domain.permission.HighRiskRule
import com.aicode.feature.agent.domain.permission.HighRiskRulesRepository
import com.aicode.feature.agent.domain.permission.PermissionRule
import com.aicode.feature.agent.domain.permission.PermissionRulesRepository
import com.aicode.feature.agent.domain.skill.SkillConfigRepository
import com.aicode.feature.agent.domain.skill.SkillForm
import com.aicode.feature.agent.domain.skill.SkillImportError
import com.aicode.feature.agent.domain.skill.SkillImportReport
import com.aicode.feature.agent.domain.skill.SkillRepository
import com.aicode.feature.agent.domain.skill.SkillSaveError
import com.aicode.feature.agent.domain.skill.SkillScope
import com.aicode.feature.agent.domain.subagent.AgentDefinitionConfigRepository
import com.aicode.feature.agent.domain.subagent.AgentDefinitionForm
import com.aicode.feature.agent.domain.subagent.AgentDefinitionRepository
import com.aicode.feature.agent.domain.subagent.AgentDefinitionScope
import com.aicode.feature.agent.domain.subagent.AgentSaveError
import com.aicode.feature.agent.domain.subagent.InjectPart
import com.aicode.feature.agent.domain.tool.ToolRegistry
import com.aicode.feature.settings.data.remote.ModelApiService
import com.aicode.feature.settings.data.remote.ContainerImageDownloader
import com.aicode.feature.settings.data.remote.ModelMetadataService
import com.aicode.feature.settings.data.remote.ModelTestResult
import com.aicode.feature.settings.data.remote.UpdateCheckResult
import com.aicode.feature.settings.data.remote.UpdateCheckService
import com.aicode.feature.settings.data.repository.UpdateCheckSettingsRepository
import com.aicode.feature.settings.data.repository.UpdateChannel
import dagger.hilt.android.qualifiers.ApplicationContext
import com.aicode.feature.settings.data.repository.AppThemeMode
import com.aicode.feature.settings.data.repository.ContainerSettingsRepository
import com.aicode.feature.settings.data.repository.DownloadedImageRecord
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.CommandAuthLevel
import com.aicode.feature.settings.data.repository.CompactionModelSettingsRepository
import com.aicode.feature.settings.data.repository.DefaultModelSettingsRepository
import com.aicode.feature.settings.data.repository.TitleModelSettingsRepository
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.settings.data.repository.ExecutionModeRepository
import com.aicode.feature.settings.data.repository.GeneralSettingsRepository
import com.aicode.feature.settings.data.repository.AgentSoundSettingsRepository
import com.aicode.feature.settings.data.repository.KeepaliveSettingsRepository
import com.aicode.feature.settings.data.repository.LanguageSettingsRepository
import com.aicode.feature.settings.data.repository.LogSettingsRepository
import com.aicode.feature.settings.data.repository.ReviewerModelSettingsRepository
import com.aicode.feature.settings.data.repository.ContextWindowOverrideRepository
import com.aicode.feature.settings.data.repository.ProxyConfig
import com.aicode.feature.settings.data.repository.ProxySettingsRepository
import com.aicode.feature.settings.data.repository.ScreenOnSettingsRepository
import com.aicode.feature.settings.data.repository.StartupSessionMode
import com.aicode.feature.settings.data.repository.TodoDisplayPosition
import com.aicode.feature.settings.data.repository.ThemeSettingsRepository
import com.aicode.feature.settings.data.repository.ToolSafetySettingsRepository
import com.aicode.feature.settings.data.repository.BackgroundSettingsRepository
import com.aicode.feature.settings.data.repository.ImageGenModelSettingsRepository
import com.aicode.feature.settings.data.repository.VisionModelSettingsRepository
import com.aicode.feature.workspace.domain.model.RemoteConnection
import com.aicode.feature.workspace.domain.repository.RemoteRepository
import com.aicode.feature.settings.domain.model.AIProviderConfig
import com.aicode.feature.settings.domain.model.DashboardContext
import com.aicode.feature.settings.domain.model.ModelMetadata
import com.aicode.feature.settings.domain.model.modelMetadataKey
import com.aicode.feature.settings.domain.model.ProviderDashboardResult
import com.aicode.feature.settings.domain.model.ProviderDashboardState
import com.aicode.feature.settings.domain.service.ModelCostCalculator
import com.aicode.feature.settings.domain.service.ProviderDashboardRunner
import com.aicode.feature.settings.domain.model.ProviderType
import com.aicode.feature.settings.domain.model.ProxyType
import com.aicode.R
import com.aicode.feature.settings.domain.repository.AIProviderRepository
import com.aicode.feature.terminal.data.repository.TerminalSettings
import com.aicode.feature.terminal.data.repository.TerminalSettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

sealed class FetchState {
    object Idle : FetchState()
    object Loading : FetchState()
    data class Success(val models: List<String>, val debugInfo: ModelTestResult? = null) : FetchState()
    data class Error(val message: String, val debugInfo: ModelTestResult? = null) : FetchState()
}

/** Complete UI state for the image download page: idle / downloading (with progress) / download complete (installable) / failed. */
sealed interface ContainerImageDownloadUiState {
    data object Idle : ContainerImageDownloadUiState
    data class Downloading(
        val entryId: String,
        val name: String,
        val version: String,
        val sourceId: String,
        val bytesRead: Long,
        val totalBytes: Long
    ) : ContainerImageDownloadUiState
    data class Done(
        val entryId: String,
        val name: String,
        val version: String,
        val fileUri: String
    ) : ContainerImageDownloadUiState
    data class Error(val entryId: String, val message: String) : ContainerImageDownloadUiState
}

/** In-progress state for a container reset: only set while resetting, [deleted] is the number of files and directories deleted so far. */
data class ContainerResetUiState(val name: String, val deleted: Int)

/** Real-device ROOT (su) probe state: the 'Command Execution Channel' page uses this to display the status and decide whether switching is allowed. */
enum class RootProbeState { UNKNOWN, PROBING, AVAILABLE, UNAVAILABLE }

data class LogViewerUiState(
    val files: List<String> = emptyList(),
    val selectedFileName: String? = null,
    val filterServerName: String? = null,
    val content: String = "",
    val totalLines: Int = 0,
    val shownLines: Int = 0,
    val loading: Boolean = false,
    val error: String? = null
)

/** Update-check dialog state (the global dialog host is rendered at the AppNavigation layer of the main page, shared by automatic/manual checks). */
sealed interface UpdateCheckUiState {
    data object Idle : UpdateCheckUiState
    data object Checking : UpdateCheckUiState
    data object UpToDate : UpdateCheckUiState
    data class NewVersion(val latestTag: String, val changelog: String) : UpdateCheckUiState
    data class Error(val message: String) : UpdateCheckUiState
}

/** Token statistics period: determines the statistics start time and trend granularity (today = hourly granularity, the rest = daily granularity). */
enum class TokenStatsPeriod(val labelRes: Int, val startMillis: (Long) -> Long) {
    TODAY(R.string.settings_token_stats_period_today, { now ->
        java.util.Calendar.getInstance().apply {
            timeInMillis = now
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
    }),
    LAST_7_DAYS(R.string.settings_token_stats_period_7d, { now -> now - 7 * 86_400_000L }),
    LAST_30_DAYS(R.string.settings_token_stats_period_30d, { now -> now - 30 * 86_400_000L }),
    ALL(R.string.settings_token_stats_period_all, { 0L })
}

/** Complete UI state for the Token statistics page, combined from the period + 5 aggregate Flows. */
data class TokenStatsUiState(
    val period: TokenStatsPeriod = TokenStatsPeriod.LAST_7_DAYS,
    val summary: com.aicode.feature.agent.data.local.dao.CallSummary? = null,
    val trend: List<com.aicode.feature.agent.data.local.dao.DayCallStats> = emptyList(),
    val providers: List<com.aicode.feature.agent.data.local.dao.ProviderCallStats> = emptyList(),
    val models: List<com.aicode.feature.agent.data.local.dao.ModelCallStats> = emptyList(),
    val recentCalls: List<RecentCallRecord> = emptyList(),
    /** Current page of call details (0-based). */
    val callsPage: Int = 0,
    /** Total number of calls in the current period, for displaying the total number of pages. */
    val callsTotal: Int = 0,
    /** Current page of providers (0-based). */
    val providersPage: Int = 0,
    /** Total number of providers. */
    val providersTotal: Int = 0,
    /** Current page of models (0-based). */
    val modelsPage: Int = 0,
    /** Total number of models. */
    val modelsTotal: Int = 0,
    /** Total cost for the current period (USD, provider custom unit price takes precedence, otherwise falls back to models.dev unit price estimate). */
    val totalCostUsd: Double = 0.0,
    /** Cost of each record within the call details page (key = record id, null = model has no unit price). */
    val recentCallCosts: Map<Long, Double?> = emptyMap(),
    /** Filter: specified provider ID (null means unrestricted). */
    val filterProviderId: String? = null,
    /** Filter: specified model name (null means unrestricted). */
    val filterModel: String? = null,
    /** List of providers available for filtering (id to name). */
    val availableProviders: List<Pair<String, String>> = emptyList(),
    /** List of models available for filtering. */
    val availableModels: List<String> = emptyList(),
    /** Number of keys/credentials corresponding to each provider ID. */
    val providerKeyCounts: Map<String, Int> = emptyMap()
)

private data class ModelStatsPaging(
    val paged: List<com.aicode.feature.agent.data.local.dao.ModelCallStats>,
    val page: Int,
    val total: Int
)

/** UI state for the skill list page: skill + source scope + enabled/disabled state. */
data class SkillUiEntry(
    val name: String,
    val description: String,
    val scope: SkillScope,
    val disabled: Boolean,
    val instructions: String,
    /** The `required_tools` in a hand-written skill, not shown on the edit page and written back as-is on save. */
    val requiredTools: List<String> = emptyList()
)

/** UI state for the sub-agent list page: definition content + source scope + enabled/disabled state. */
data class SubAgentUiEntry(
    val name: String,
    val description: String,
    val scope: AgentDefinitionScope,
    val disabled: Boolean,
    val providerId: String?,
    val model: String?,
    val reasoningEffort: String?,
    val mode: AgentMode?,
    val allowedTools: List<String>,
    val disallowedTools: List<String>,
    val inject: Set<InjectPart>,
    val prompt: String,
    val filePath: String?
)

/** Save result for the skill edit page: the UI uses this to decide whether to return to the list or report an error in place. */
sealed interface SkillSaveState {
    data object Idle : SkillSaveState
    data object Saving : SkillSaveState
    data object Saved : SkillSaveState
    data class Failed(val error: SkillSaveError) : SkillSaveState
}

/** UI state for skill import: idle / importing / done (with result report). */
sealed interface SkillImportState {
    data object Idle : SkillImportState
    data object Running : SkillImportState
    data class Done(val report: SkillImportReport) : SkillImportState
}

/** Save result for the sub-agent edit page: the UI uses this to decide whether to return to the list or report an error in place. */
sealed interface SubAgentSaveState {
    data object Idle : SubAgentSaveState
    data object Saving : SubAgentSaveState
    data object Saved : SubAgentSaveState
    data class Failed(val error: AgentSaveError) : SubAgentSaveState
}

/**
 * Pads the trend aggregation to a complete timeline within the period: days/hours with no records are filled with 0, to avoid calls in the period being concentrated on the same day
 * leaving the trend with only a single point and unable to draw a line chart. The 'All' period is padded from the earliest recorded day to today.
 */
private fun padTrend(
    period: TokenStatsPeriod,
    trend: List<com.aicode.feature.agent.data.local.dao.DayCallStats>,
    tzOffsetMillis: Long
): List<com.aicode.feature.agent.data.local.dao.DayCallStats> {
    if (trend.isEmpty()) return emptyList()
    val now = System.currentTimeMillis()
    val byDay = trend.associateBy { it.day }
    val isHourly = period == TokenStatsPeriod.TODAY
    val bucketMillis = if (isHourly) 3_600_000L else 86_400_000L
    val endIndex = (now + tzOffsetMillis) / bucketMillis
    val startIndex = when (period) {
        TokenStatsPeriod.TODAY -> (period.startMillis(now) + tzOffsetMillis) / bucketMillis
        TokenStatsPeriod.ALL -> trend.first().day
        else -> (period.startMillis(now) + tzOffsetMillis) / bucketMillis
    }
    if (startIndex > endIndex) return trend
    return (startIndex..endIndex).map { index ->
        byDay[index] ?: com.aicode.feature.agent.data.local.dao.DayCallStats(index, 0, 0, 0, 0, 0, null, null)
    }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class SettingsViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val repository: AIProviderRepository,
    private val modelApiService: ModelApiService,
    private val modelMetadataService: ModelMetadataService,
    private val logSettingsRepository: LogSettingsRepository,
    private val themeSettingsRepository: ThemeSettingsRepository,
    private val backgroundSettingsRepository: BackgroundSettingsRepository,
    private val keepaliveSettingsRepository: KeepaliveSettingsRepository,
    private val screenOnSettingsRepository: ScreenOnSettingsRepository,
    private val agentSoundSettingsRepository: AgentSoundSettingsRepository,
    private val generalSettingsRepository: GeneralSettingsRepository,
    private val languageSettingsRepository: LanguageSettingsRepository,
    private val mcpConfigRepository: McpConfigRepository,
    private val mcpManager: McpManager,
    private val permissionRulesRepository: PermissionRulesRepository,
    private val highRiskRulesRepository: HighRiskRulesRepository,
    private val toolSafetySettingsRepository: ToolSafetySettingsRepository,
    private val skillRepository: SkillRepository,
    private val agentDefinitionRepository: AgentDefinitionRepository,
    private val agentDefinitionConfigRepository: AgentDefinitionConfigRepository,
    private val toolRegistry: ToolRegistry,
    private val skillConfigRepository: SkillConfigRepository,
    private val visionModelSettingsRepository: VisionModelSettingsRepository,
    private val imageGenModelSettingsRepository: ImageGenModelSettingsRepository,
    private val compactionModelSettingsRepository: CompactionModelSettingsRepository,
    private val titleModelSettingsRepository: TitleModelSettingsRepository,
    private val reviewerModelSettingsRepository: ReviewerModelSettingsRepository,
    private val contextWindowOverrideRepository: ContextWindowOverrideRepository,
    private val defaultModelSettingsRepository: DefaultModelSettingsRepository,
    private val containerSettingsRepository: ContainerSettingsRepository,
    private val containerImageCatalog: ContainerImageCatalog,
    private val containerImageDownloader: ContainerImageDownloader,
    private val containerInstaller: ContainerInstaller,
    private val containerOsDetector: ContainerOsDetector,
    private val executionModeRepository: ExecutionModeRepository,
    private val executionModeHolder: ExecutionModeHolder,
    private val deviceRootEngine: DeviceRootEngine,
    private val remoteSshConnection: RemoteSshConnection,
    private val remoteRepository: RemoteRepository,
    private val llmCallRecordDao: LlmCallRecordDao,
    private val modelCostCalculator: ModelCostCalculator,
    private val updateCheckSettingsRepository: UpdateCheckSettingsRepository,
    private val updateCheckService: UpdateCheckService,
    private val providerDashboardRunner: ProviderDashboardRunner,
    private val terminalSettingsRepository: TerminalSettingsRepository,
    private val proxySettingsRepository: ProxySettingsRepository
) : ViewModel() {
    private companion object {
        const val MAX_LOG_LINES = 1200
        const val CALLS_PAGE_SIZE = 10
        const val STATS_PAGE_SIZE = 5
        /** Write-to-disk delay after background opacity dragging stops. */
        const val BACKGROUND_ALPHA_WRITE_DEBOUNCE_MS = 80L

        /** Extensions accepted for skill file import (lowercase, without dot). */
        val MARKDOWN_EXTENSIONS = setOf("md", "markdown", "txt")

        /** Extensions accepted for skill archive import (lowercase, without dot). */
        val ZIP_EXTENSIONS = setOf("zip")
    }

    /** Terminal personalization settings. */
    val terminalSettings: StateFlow<TerminalSettings> = terminalSettingsRepository.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, TerminalSettings())

    fun setTerminalTheme(themeId: String) {
        viewModelScope.launch { terminalSettingsRepository.setThemeId(themeId) }
    }

    fun setTerminalFontSize(sizeSp: Int) {
        viewModelScope.launch { terminalSettingsRepository.setFontSizeSp(sizeSp) }
    }

    fun setTerminalCursorStyle(style: Int) {
        viewModelScope.launch { terminalSettingsRepository.setCursorStyle(style) }
    }

    fun setTerminalFontPath(path: String) {
        viewModelScope.launch { terminalSettingsRepository.setFontPath(path) }
    }

    // ── Global proxy ──

    /** Global proxy config snapshot (SharedPreferences read/written immediately, changes take effect for new connections right away). */
    val proxyConfig: StateFlow<ProxyConfig> = proxySettingsRepository.config

    /** Proxy connectivity test state. */
    sealed interface ProxyTestUiState {
        data object Idle : ProxyTestUiState
        data object Testing : ProxyTestUiState
        data class Success(val message: String) : ProxyTestUiState
        data class Error(val message: String) : ProxyTestUiState
    }

    private val _proxyTestState = MutableStateFlow<ProxyTestUiState>(ProxyTestUiState.Idle)
    val proxyTestState: StateFlow<ProxyTestUiState> = _proxyTestState.asStateFlow()

    fun setProxyEnabled(enabled: Boolean) = proxySettingsRepository.setEnabled(enabled)
    fun setProxyType(type: ProxyType) = proxySettingsRepository.setType(type)
    fun setProxyHost(host: String) = proxySettingsRepository.setHost(host)
    fun setProxyPort(port: Int) = proxySettingsRepository.setPort(port)
    fun setProxyUsername(username: String) = proxySettingsRepository.setUsername(username)
    fun setProxyPassword(password: String) = proxySettingsRepository.setPassword(password)
    fun setProxyNoProxy(noProxy: String) = proxySettingsRepository.setNoProxy(noProxy)

    fun testProxy(probeUrl: String) = testProxy(proxySettingsRepository.config.value, probeUrl)

    /** Test any proxy config (for the provider proxy standalone page to reuse the form-state config). */
    fun testProxy(cfg: ProxyConfig, probeUrl: String) {
        if (_proxyTestState.value is ProxyTestUiState.Testing) return
        _proxyTestState.value = ProxyTestUiState.Testing
        viewModelScope.launch {
            val result = AppProxy.testProxy(context, cfg, probeUrl)
            _proxyTestState.value = if (result.ok) {
                ProxyTestUiState.Success(result.message)
            } else {
                ProxyTestUiState.Error(result.message)
            }
        }
    }

    private val _providers = MutableStateFlow<List<AIProviderConfig>>(emptyList())
    val providers: StateFlow<List<AIProviderConfig>> = _providers.asStateFlow()

    /** Default model for new sessions (remembered after selection in an empty session on the main page), for fallback of unbound sessions. */
    val defaultModelProviderId: StateFlow<String> = defaultModelSettingsRepository.providerIdFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val defaultModel: StateFlow<String> = defaultModelSettingsRepository.modelFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    /** Image-recognition model selection: empty providerId means 'follow the current chat model'. */
    private val _visionProviderId = MutableStateFlow("")
    val visionProviderId: StateFlow<String> = _visionProviderId.asStateFlow()

    private val _visionModel = MutableStateFlow("")
    val visionModel: StateFlow<String> = _visionModel.asStateFlow()

    /** Compaction-specific model selection: empty providerId means 'follow the current chat model'. */
    private val _compactionProviderId = MutableStateFlow("")
    val compactionProviderId: StateFlow<String> = _compactionProviderId.asStateFlow()

    private val _compactionModel = MutableStateFlow("")
    val compactionModel: StateFlow<String> = _compactionModel.asStateFlow()

    /** Title-summary-specific model selection: empty providerId means 'follow the current chat model'. */
    private val _titleProviderId = MutableStateFlow("")
    val titleProviderId: StateFlow<String> = _titleProviderId.asStateFlow()

    private val _titleModel = MutableStateFlow("")
    val titleModel: StateFlow<String> = _titleModel.asStateFlow()

    /** Image-generation-specific model selection: empty providerId means not configured (image generation does not follow the chat model). */
    private val _imageGenProviderId = MutableStateFlow("")
    val imageGenProviderId: StateFlow<String> = _imageGenProviderId.asStateFlow()

    private val _imageGenModel = MutableStateFlow("")
    val imageGenModel: StateFlow<String> = _imageGenModel.asStateFlow()

    /** Model dedicated to the AI command reviewer (empty = follow the chat model). */
    private val _reviewerProviderId = MutableStateFlow("")
    val reviewerProviderId: StateFlow<String> = _reviewerProviderId.asStateFlow()

    private val _reviewerModel = MutableStateFlow("")
    val reviewerModel: StateFlow<String> = _reviewerModel.asStateFlow()

    /** Unified context window advanced setting: [ContextWindowOverrideUiState.enabled] is the actually-effective state. */
    private val _contextWindowOverride = MutableStateFlow(ContextWindowOverrideUiState())
    val contextWindowOverride: StateFlow<ContextWindowOverrideUiState> = _contextWindowOverride.asStateFlow()

    private val _logLevel = MutableStateFlow(LogLevel.VERBOSE)
    val logLevel: StateFlow<LogLevel> = _logLevel.asStateFlow()

    private val _logViewerState = MutableStateFlow(LogViewerUiState())
    val logViewerState: StateFlow<LogViewerUiState> = _logViewerState.asStateFlow()

    private val _updateCheckState = MutableStateFlow<UpdateCheckUiState>(UpdateCheckUiState.Idle)
    val updateCheckState: StateFlow<UpdateCheckUiState> = _updateCheckState.asStateFlow()

    private val _updateCheckEnabled = MutableStateFlow(updateCheckSettingsRepository.autoCheckEnabled)
    val updateCheckEnabled: StateFlow<Boolean> = _updateCheckEnabled.asStateFlow()

    private val _updateCheckChannel = MutableStateFlow(updateCheckSettingsRepository.channel)
    val updateCheckChannel: StateFlow<UpdateChannel> = _updateCheckChannel.asStateFlow()

    private val _keepaliveEnabled = MutableStateFlow(false)
    val keepaliveEnabled: StateFlow<Boolean> = _keepaliveEnabled.asStateFlow()

    private val _screenOnEnabled = MutableStateFlow(false)
    val screenOnEnabled: StateFlow<Boolean> = _screenOnEnabled.asStateFlow()

    private val _agentSoundEnabled = MutableStateFlow(false)
    val agentSoundEnabled: StateFlow<Boolean> = _agentSoundEnabled.asStateFlow()

    private val _autoRemoveStaleModels = MutableStateFlow(true)
    val autoRemoveStaleModels: StateFlow<Boolean> = _autoRemoveStaleModels.asStateFlow()

    private val _startupSessionMode = MutableStateFlow(StartupSessionMode.NEW_SESSION)
    val startupSessionMode: StateFlow<StartupSessionMode> = _startupSessionMode.asStateFlow()

    private val _firstByteTimeoutSec = MutableStateFlow(300)
    val firstByteTimeoutSec: StateFlow<Int> = _firstByteTimeoutSec.asStateFlow()

    private val _streamIdleTimeoutSec = MutableStateFlow(0)
    val streamIdleTimeoutSec: StateFlow<Int> = _streamIdleTimeoutSec.asStateFlow()

    private val _maxNetworkRetries = MutableStateFlow(6)
    val maxNetworkRetries: StateFlow<Int> = _maxNetworkRetries.asStateFlow()

    private val _enterToSend = MutableStateFlow(false)
    val enterToSend: StateFlow<Boolean> = _enterToSend.asStateFlow()

    private val _compactionThresholdPercent = MutableStateFlow(90)
    val compactionThresholdPercent: StateFlow<Int> = _compactionThresholdPercent.asStateFlow()

    private val _sendFileMaxSizeMb = MutableStateFlow(100)
    val sendFileMaxSizeMb: StateFlow<Int> = _sendFileMaxSizeMb.asStateFlow()

    private val _deleteExternalWorkspaceSessions = MutableStateFlow(false)
    val deleteExternalWorkspaceSessions: StateFlow<Boolean> = _deleteExternalWorkspaceSessions.asStateFlow()

    private val _todoDisplayPosition = MutableStateFlow(TodoDisplayPosition.ABOVE_INPUT_BAR)
    val todoDisplayPosition: StateFlow<TodoDisplayPosition> = _todoDisplayPosition.asStateFlow()

    private val _themeMode = MutableStateFlow(AppThemeMode.AUTO)
    val themeMode: StateFlow<AppThemeMode> = _themeMode.asStateFlow()

    private val _themePresetId = MutableStateFlow<String?>(null)
    val themePresetId: StateFlow<String?> = _themePresetId.asStateFlow()

    private val _dynamicColorEnabled = MutableStateFlow(false)
    val dynamicColorEnabled: StateFlow<Boolean> = _dynamicColorEnabled.asStateFlow()

    /** Global custom background image file path (null = not set), for display and preview in the settings dialog. */
    private val _backgroundImagePath = MutableStateFlow<String?>(null)
    val backgroundImagePath: StateFlow<String?> = _backgroundImagePath.asStateFlow()

    /** Background image opacity (0~0.2, actual range determined by BackgroundSettingsRepository's MIN/MAX), written to DataStore in real time so the global background changes in sync. */
    private val _backgroundAlpha = MutableStateFlow(BackgroundSettingsRepository.DEFAULT_ALPHA)
    val backgroundAlpha: StateFlow<Float> = _backgroundAlpha.asStateFlow()

    /** Throttle job for persisting opacity, see [setBackgroundAlpha]. */
    private var backgroundAlphaWriteJob: Job? = null

    /** App language tag selected by the user (null means follow the system). */
    private val _languageTag = MutableStateFlow<String?>(null)
    val languageTag: StateFlow<String?> = _languageTag.asStateFlow()

    private val _mcpEntries = MutableStateFlow<List<McpServerEntry>>(emptyList())
    val mcpEntries: StateFlow<List<McpServerEntry>> = _mcpEntries.asStateFlow()

    private val _skills = MutableStateFlow<List<SkillUiEntry>>(emptyList())
    val skills: StateFlow<List<SkillUiEntry>> = _skills.asStateFlow()

    private val _skillSaveState = MutableStateFlow<SkillSaveState>(SkillSaveState.Idle)
    val skillSaveState: StateFlow<SkillSaveState> = _skillSaveState.asStateFlow()

    private val _skillImportState = MutableStateFlow<SkillImportState>(SkillImportState.Idle)
    val skillImportState: StateFlow<SkillImportState> = _skillImportState.asStateFlow()

    /** Name of the skill currently being deleted (non-null means deletion in progress, used for the dialog spinner and disabling buttons). */
    private val _skillDeleting = MutableStateFlow<String?>(null)
    val skillDeleting: StateFlow<String?> = _skillDeleting.asStateFlow()

    private val _subAgents = MutableStateFlow<List<SubAgentUiEntry>>(emptyList())
    val subAgents: StateFlow<List<SubAgentUiEntry>> = _subAgents.asStateFlow()

    private val _subAgentSaveState = MutableStateFlow<SubAgentSaveState>(SubAgentSaveState.Idle)
    val subAgentSaveState: StateFlow<SubAgentSaveState> = _subAgentSaveState.asStateFlow()

    /** Name of the sub-agent currently being deleted (non-null means deletion in progress, used for the dialog spinner and disabling buttons). */
    private val _subAgentDeleting = MutableStateFlow<String?>(null)
    val subAgentDeleting: StateFlow<String?> = _subAgentDeleting.asStateFlow()

    val mcpStatuses: StateFlow<List<McpServerStatus>> = mcpManager.statuses

    private val _mcpReloading = MutableStateFlow(false)
    val mcpReloading: StateFlow<Boolean> = _mcpReloading.asStateFlow()

    private val _fetchState = MutableStateFlow<FetchState>(FetchState.Idle)
    val fetchState: StateFlow<FetchState> = _fetchState.asStateFlow()

    private val _testResults = MutableStateFlow<Map<String, ModelTestResult>>(emptyMap())
    val testResults: StateFlow<Map<String, ModelTestResult>> = _testResults.asStateFlow()

    /** Model metadata cache, keyed by [modelMetadataKey] (provider + model) - both unit price and capabilities may vary by provider. */
    private val _modelMetadata = MutableStateFlow<Map<String, ModelMetadata>>(emptyMap())
    val modelMetadata: StateFlow<Map<String, ModelMetadata>> = _modelMetadata.asStateFlow()

    private val _testing = MutableStateFlow<Set<String>>(emptySet())
    val testing: StateFlow<Set<String>> = _testing.asStateFlow()

    private val _dashboardTestState = MutableStateFlow<ProviderDashboardState>(ProviderDashboardState.Idle)
    val dashboardTestState: StateFlow<ProviderDashboardState> = _dashboardTestState.asStateFlow()

    private val _dashboardScripts = MutableStateFlow<List<String>>(emptyList())
    /** Available dashboard script file names (loaded asynchronously via [loadDashboardScripts] when the script picker is opened). */
    val dashboardScripts: StateFlow<List<String>> = _dashboardScripts.asStateFlow()

    private val _providerDashboards = MutableStateFlow<Map<String, ProviderDashboardState>>(emptyMap())
    val providerDashboards: StateFlow<Map<String, ProviderDashboardState>> = _providerDashboards.asStateFlow()

    private val _globalRules = MutableStateFlow<List<PermissionRule>>(emptyList())
    val globalRules: StateFlow<List<PermissionRule>> = _globalRules.asStateFlow()

    private val _projectRules = MutableStateFlow<List<PermissionRule>>(emptyList())
    val projectRules: StateFlow<List<PermissionRule>> = _projectRules.asStateFlow()

    val currentProjectName: StateFlow<String?> = permissionRulesRepository.currentProjectNameFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _disableSafetyInterception = MutableStateFlow(false)
    val disableSafetyInterception: StateFlow<Boolean> = _disableSafetyInterception.asStateFlow()

    /** Command execution authorization level (autonomous execution / rule interception / full permission). */
    private val _commandAuthLevel = MutableStateFlow(CommandAuthLevel.RULE_BASED)
    val commandAuthLevel: StateFlow<CommandAuthLevel> = _commandAuthLevel.asStateFlow()

    /** High-risk interception rules (including built-in presets and user-created ones). */
    private val _highRiskRules = MutableStateFlow<List<HighRiskRule>>(emptyList())
    val highRiskRules: StateFlow<List<HighRiskRule>> = _highRiskRules.asStateFlow()

    private val _activeProfileId = MutableStateFlow(ContainerProfile.BUILTIN_ID)
    val activeProfileId: StateFlow<String> = _activeProfileId.asStateFlow()

    private val _defaultContainerId = MutableStateFlow(ContainerProfile.BUILTIN_ID)
    val defaultContainerId: StateFlow<String> = _defaultContainerId.asStateFlow()

    private val _customProfiles = MutableStateFlow<List<ContainerProfile>>(emptyList())
    val customProfiles: StateFlow<List<ContainerProfile>> = _customProfiles.asStateFlow()

    /** All profiles (the built-in Alpine is also persisted as a regular item in the list, automatically written on first launch). */
    val profiles: StateFlow<List<ContainerProfile>> = customProfiles
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, listOf(ContainerProfile.BUILTIN_ALPINE))

    /** OS type identified for each container (profile id -> os id), the UI uses this to show the corresponding OS icon. */
    val containerOsMap: StateFlow<Map<String, String>> = containerOsDetector.osMap

    /** Announcement content (follows the UI language), paired with [containerAnnouncementOutdated] for dialog rendering. */
    private val _containerAnnouncementText = MutableStateFlow("")
    val containerAnnouncementText: StateFlow<String> = _containerAnnouncementText.asStateFlow()

    /** Whether the announcement needs to pop up: true when no hash is stored locally or it does not match the current content hash (content has been updated). */
    private val _containerAnnouncementOutdated = MutableStateFlow(false)
    val containerAnnouncementOutdated: StateFlow<Boolean> = _containerAnnouncementOutdated.asStateFlow()

    /** Announcement content for the 'Prompts' page (follows the UI language), paired with [promptsAnnouncementOutdated] for dialog rendering. */
    private val _promptsAnnouncementText = MutableStateFlow("")
    val promptsAnnouncementText: StateFlow<String> = _promptsAnnouncementText.asStateFlow()

    /** Whether the 'Prompts' page announcement needs to pop up: true when no hash is stored locally or it does not match the current content hash (content has been updated). */
    private val _promptsAnnouncementOutdated = MutableStateFlow(false)
    val promptsAnnouncementOutdated: StateFlow<Boolean> = _promptsAnnouncementOutdated.asStateFlow()

    private val _imageCatalog = MutableStateFlow<List<ContainerImageEntry>>(emptyList())
    val imageCatalog: StateFlow<List<ContainerImageEntry>> = _imageCatalog.asStateFlow()

    /** List of global download source ids (from the sources key in the catalog JSON, order preserved), for switching in the top-right corner. */
    private val _imageSourceOptions = MutableStateFlow<List<String>>(emptyList())
    val imageSourceOptions: StateFlow<List<String>> = _imageSourceOptions.asStateFlow()

    /** Currently selected download source (official by default). */
    private val _selectedImageSource = MutableStateFlow("official")
    val selectedImageSource: StateFlow<String> = _selectedImageSource.asStateFlow()

    /** Records of downloaded/installed images (entryId -> record), retained across restarts. */
    private val _downloadedImages = MutableStateFlow<Map<String, DownloadedImageRecord>>(emptyMap())
    val downloadedImages: StateFlow<Map<String, DownloadedImageRecord>> = _downloadedImages.asStateFlow()

    /** Image ids with no download URL under the currently selected source (e.g. Tencent Cloud does not provide Ubuntu), the UI uses this to disable the download button. */
    private val _sourceUnavailableIds = MutableStateFlow<Set<String>>(emptySet())
    val sourceUnavailableIds: StateFlow<Set<String>> = _sourceUnavailableIds.asStateFlow()

    private val _containerImageDownload = MutableStateFlow<ContainerImageDownloadUiState>(ContainerImageDownloadUiState.Idle)
    val containerImageDownload: StateFlow<ContainerImageDownloadUiState> = _containerImageDownload.asStateFlow()

    /** Container currently being reset (including number of entries deleted), null means not resetting. The UI uses this to show a progress dialog. */
    private val _containerReset = MutableStateFlow<ContainerResetUiState?>(null)
    val containerReset: StateFlow<ContainerResetUiState?> = _containerReset.asStateFlow()

    /** Handle of the current download job, cancel it when cancelling the download (the underlying OkHttp call is interrupted synchronously). */
    private var downloadJob: kotlinx.coroutines.Job? = null

    /** Current execution mode (local PRoot / remote SSH), for the UI to decide whether to show the remote connection indicator. */
    val executionMode: StateFlow<ExecutionMode> = executionModeHolder.mode

    /** Remote SSH connection state, for the UI to show the indicator. */
    val connectionState: StateFlow<ConnectionState> = remoteSshConnection.connectionState

    /** Token statistics: currently selected statistics period. */
    private val _tokenStatsPeriod = MutableStateFlow(TokenStatsPeriod.LAST_7_DAYS)
    val tokenStatsPeriod: StateFlow<TokenStatsPeriod> = _tokenStatsPeriod.asStateFlow()

    /** Token statistics: combined state of summary, trend, providers, models and details within the period. */
    private val _tokenStats = MutableStateFlow(TokenStatsUiState())
    val tokenStats: StateFlow<TokenStatsUiState> = _tokenStats.asStateFlow()

    /** Token statistics: page number for call details pagination (0-based). */
    private val _tokenStatsPage = MutableStateFlow(0)

    /** Token statistics: page number for provider pagination (0-based). */
    private val _providerStatsPage = MutableStateFlow(0)

    /** Token statistics: page number for model pagination (0-based). */
    private val _modelStatsPage = MutableStateFlow(0)

    /** Token statistics: provider filter (null means all). */
    private val _filterProviderId = MutableStateFlow<String?>(null)
    val filterProviderId: StateFlow<String?> = _filterProviderId.asStateFlow()

    /** Token statistics: model filter (null means all). */
    private val _filterModel = MutableStateFlow<String?>(null)
    val filterModel: StateFlow<String?> = _filterModel.asStateFlow()

    /** Remote connection channels configured in the workspace, for reuse in the container image SSH mode dropdown. */
    val remoteConnections: StateFlow<List<RemoteConnection>> = remoteRepository.getConnections()
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, emptyList())

    init {
        _imageCatalog.value = containerImageCatalog.load()
        _imageSourceOptions.value = containerImageCatalog.sourceIds
        _selectedImageSource.value = containerImageCatalog.sourceIds.firstOrNull { it == "official" }
            ?: containerImageCatalog.sourceIds.firstOrNull() ?: "official"
        // Downloaded/installed records are retained across restarts
        viewModelScope.launch {
            containerSettingsRepository.downloadedImagesFlow.collect { records ->
                _downloadedImages.value = records.associateBy { it.entryId }
            }
        }
        // Set of images unavailable under the current source (automatically updated after source switching)
        viewModelScope.launch {
            combine(_imageCatalog, _selectedImageSource) { entries, sourceId ->
                entries.filter { containerImageCatalog.urlFor(it, sourceId, ContainerImageCatalog.CURRENT_ABI) == null }
                    .map { it.id }.toSet()
            }.collect { _sourceUnavailableIds.value = it }
        }
        // Whether the announcement pops up is judged by content hash comparison: it pops up on first time (no hash) or when content is updated (hash mismatch).
        viewModelScope.launch {
            combine(_languageTag, containerSettingsRepository.announcementShownHashFlow) { tag, storedHash ->
                val text = loadContainerAnnouncement(context, tag)
                _containerAnnouncementText.value = text
                _containerAnnouncementOutdated.value = storedHash != sha256(text)
            }.collect {}
        }
        // The 'Prompts' page announcement is likewise judged by content hash comparison for whether to pop up.
        viewModelScope.launch {
            combine(_languageTag, generalSettingsRepository.promptsAnnouncementShownHashFlow) { tag, storedHash ->
                val text = loadPromptsAnnouncement(context, tag)
                _promptsAnnouncementText.value = text
                _promptsAnnouncementOutdated.value = storedHash != sha256(text)
            }.collect {}
        }
        viewModelScope.launch {
            launch {
                repository.getAllProviders().collectLatest {
                    _providers.value = it
                }
            }

            launch {
                visionModelSettingsRepository.providerIdFlow.collectLatest {
                    _visionProviderId.value = it
                }
            }

            launch {
                visionModelSettingsRepository.modelFlow.collectLatest {
                    _visionModel.value = it
                }
            }

            launch {
                compactionModelSettingsRepository.providerIdFlow.collectLatest {
                    _compactionProviderId.value = it
                }
            }

            launch {
                compactionModelSettingsRepository.modelFlow.collectLatest {
                    _compactionModel.value = it
                }
            }

            launch {
                titleModelSettingsRepository.providerIdFlow.collectLatest {
                    _titleProviderId.value = it
                }
            }

            launch {
                titleModelSettingsRepository.modelFlow.collectLatest {
                    _titleModel.value = it
                }
            }

            launch {
                imageGenModelSettingsRepository.providerIdFlow.collectLatest {
                    _imageGenProviderId.value = it
                }
            }

            launch {
                imageGenModelSettingsRepository.modelFlow.collectLatest {
                    _imageGenModel.value = it
                }
            }

            launch {
                reviewerModelSettingsRepository.providerIdFlow.collectLatest {
                    _reviewerProviderId.value = it
                }
            }

            launch {
                reviewerModelSettingsRepository.modelFlow.collectLatest {
                    _reviewerModel.value = it
                }
            }

            launch {
                contextWindowOverrideRepository.overrideFlow.collectLatest { o ->
                    _contextWindowOverride.value = ContextWindowOverrideUiState(
                        enabled = o.enabled,
                        contextTokens = o.contextTokens,
                        inputTokens = o.inputTokens,
                        outputTokens = o.outputTokens
                    )
                }
            }

            launch {
                logSettingsRepository.levelFlow.collectLatest {
                    _logLevel.value = it
                }
            }

            launch {
                keepaliveSettingsRepository.enabledFlow.collectLatest {
                    _keepaliveEnabled.value = it
                }
            }

            launch {
                screenOnSettingsRepository.enabledFlow.collectLatest {
                    _screenOnEnabled.value = it
                }
            }

            launch {
                agentSoundSettingsRepository.enabledFlow.collectLatest {
                    _agentSoundEnabled.value = it
                }
            }

            launch {
                generalSettingsRepository.autoRemoveStaleModelsFlow.collectLatest {
                    _autoRemoveStaleModels.value = it
                }
            }

            launch {
                generalSettingsRepository.startupSessionModeFlow.collectLatest {
                    _startupSessionMode.value = it
                }
            }

            launch {
                generalSettingsRepository.firstByteTimeoutSecFlow.collectLatest {
                    _firstByteTimeoutSec.value = it
                }
            }

            launch {
                generalSettingsRepository.streamIdleTimeoutSecFlow.collectLatest {
                    _streamIdleTimeoutSec.value = it
                }
            }

            launch {
                generalSettingsRepository.maxNetworkRetriesFlow.collectLatest {
                    _maxNetworkRetries.value = it
                }
            }

            launch {
                generalSettingsRepository.enterToSendFlow.collectLatest {
                    _enterToSend.value = it
                }
            }

            launch {
                generalSettingsRepository.compactionThresholdPercentFlow.collectLatest {
                    _compactionThresholdPercent.value = it
                }
            }

            launch {
                generalSettingsRepository.sendFileMaxSizeMbFlow.collectLatest {
                    _sendFileMaxSizeMb.value = it
                }
            }

            launch {
                generalSettingsRepository.deleteExternalWorkspaceSessionsFlow.collectLatest {
                    _deleteExternalWorkspaceSessions.value = it
                }
            }

            launch {
                generalSettingsRepository.todoDisplayPositionFlow.collectLatest {
                    _todoDisplayPosition.value = it
                }
            }

            launch {
                themeSettingsRepository.themeModeFlow.collectLatest {
                    _themeMode.value = it
                }
            }

            launch {
                themeSettingsRepository.themePresetIdFlow.collectLatest {
                    _themePresetId.value = it
                }
            }

            launch {
                themeSettingsRepository.dynamicColorFlow.collectLatest {
                    _dynamicColorEnabled.value = it
                }
            }

            launch {
                backgroundSettingsRepository.imagePathFlow.collectLatest {
                    _backgroundImagePath.value = it
                }
            }

            launch {
                backgroundSettingsRepository.alphaFlow.collectLatest {
                    _backgroundAlpha.value = it
                }
            }

            launch {
                languageSettingsRepository.languageFlow.collectLatest {
                    _languageTag.value = it
                }
            }

            launch {
                containerSettingsRepository.activeProfileIdFlow.collectLatest {
                    _activeProfileId.value = it
                }
            }

            launch {
                containerSettingsRepository.defaultContainerIdFlow.collectLatest {
                    _defaultContainerId.value = it
                }
            }

            launch {
                // On first launch, write the built-in Alpine default item (once the flag is set it is not automatically added back)
                containerSettingsRepository.ensureBuiltinDefault()
            }

            launch {
                containerSettingsRepository.customProfilesFlow.collectLatest {
                    // Descending by time added (newest first); for old data where createdAt is all 0, preserve stored order
                    _customProfiles.value = it.sortedByDescending { profile -> profile.createdAt }
                }
            }

            launch {
                mcpConfigRepository.effectiveEntriesFlow.collectLatest {
                    _mcpEntries.value = it
                }
            }

            launch {
                skillConfigRepository.changes.collectLatest {
                    refreshSkills()
                }
            }

            launch {
                agentDefinitionConfigRepository.changes.collectLatest {
                    refreshSubAgents()
                }
            }

            launch {
                refreshSkills()
            }

            // In remote mode, global skills/sub-agents are sourced from the remote end, and the local directory listener (skills.changes) does not fire;
            // after execution mode switching or the remote connection becoming ready, a rescan is needed, otherwise the list stays with the old environment's content.
            launch {
                executionMode.collectLatest {
                    refreshSkills()
                    refreshSubAgents()
                }
            }

            launch {
                connectionState.collectLatest { state ->
                    if (state == ConnectionState.CONNECTED) {
                        refreshSkills()
                        refreshSubAgents()
                    }
                }
            }

            launch {
                permissionRulesRepository.globalRulesFlow.collectLatest {
                    _globalRules.value = it
                }
            }

            launch {
                permissionRulesRepository.currentProjectRulesFlow.collectLatest {
                    _projectRules.value = it
                }
            }

            launch {
                toolSafetySettingsRepository.disableSafetyInterceptionFlow.collectLatest {
                    _disableSafetyInterception.value = it
                }
            }

            launch {
                toolSafetySettingsRepository.commandAuthLevelFlow.collectLatest {
                    _commandAuthLevel.value = it
                }
            }

            launch {
                highRiskRulesRepository.rulesFlow.collectLatest {
                    _highRiskRules.value = it
                }
            }

            launch {
                combine(
                    _tokenStatsPeriod,
                    _filterProviderId,
                    _filterModel
                ) { period, filterProviderId, filterModel ->
                    Triple(period, filterProviderId, filterModel)
                }.flatMapLatest { (period, filterProviderId, filterModel) ->
                    val start = period.startMillis(System.currentTimeMillis())
                    val tz = java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()).toLong()
                    combine(
                        combine(
                            if (period == TokenStatsPeriod.TODAY) {
                                llmCallRecordDao.getHourStats(start, tz, filterProviderId, filterModel)
                            } else {
                                llmCallRecordDao.getDayStats(start, tz, filterProviderId, filterModel)
                            },
                            llmCallRecordDao.getSummary(start, filterProviderId, filterModel)
                        ) { rawTrend, summary -> rawTrend to summary },
                        combine(
                            llmCallRecordDao.getProviderStats(start, filterProviderId, filterModel),
                            _providerStatsPage
                        ) { list, page ->
                            val total = list.size
                            val lastPage = if (total == 0) 0 else (total - 1) / STATS_PAGE_SIZE
                            val safePage = page.coerceIn(0, lastPage)
                            val paged = list.drop(safePage * STATS_PAGE_SIZE).take(STATS_PAGE_SIZE)
                            Triple(paged, safePage, total)
                        },
                        combine(
                            combine(
                                llmCallRecordDao.getModelStats(start, filterProviderId, filterModel),
                                _modelStatsPage
                            ) { list, page ->
                                val total = list.size
                                val lastPage = if (total == 0) 0 else (total - 1) / STATS_PAGE_SIZE
                                val safePage = page.coerceIn(0, lastPage)
                                val paged = list.drop(safePage * STATS_PAGE_SIZE).take(STATS_PAGE_SIZE)
                                ModelStatsPaging(paged, safePage, total)
                            },
                            // Total cost must include provider aggregation: custom unit prices are stored per provider, dropping the provider would fetch the wrong price.
                            llmCallRecordDao.getModelProviderCostStats(start, filterProviderId, filterModel)
                        ) { modelPaging, costStats -> modelPaging to costStats },
                        // Details pagination: when page number or total changes, re-query the current page, other aggregates are not recomputed
                        combine(_tokenStatsPage, llmCallRecordDao.getCallsCount(start, filterProviderId, filterModel)) { page, total -> page to total }
                            .flatMapLatest { (page, total) ->
                                llmCallRecordDao.getRecentCalls(start, CALLS_PAGE_SIZE, page * CALLS_PAGE_SIZE, filterProviderId, filterModel)
                                    .map { calls -> Triple(calls, total, page) }
                            },
                        // Selectable filter option list (provider and model lists when globally unrestricted by filter conditions)
                        combine(
                            llmCallRecordDao.getProviderStats(start),
                            llmCallRecordDao.getModelStats(start),
                            _providers
                        ) { allProviders, allModels, configuredProviders ->
                            val providerList = (allProviders.mapNotNull { p ->
                                val id = p.providerId ?: return@mapNotNull null
                                id to (p.providerName ?: id)
                            } + configuredProviders.map { it.id to it.name }).distinctBy { it.first }
                            val modelList = allModels.mapNotNull { it.model }.distinct()
                            val keyCounts = configuredProviders.associate { it.id to it.apiKeys.size }
                            Triple(providerList, modelList, keyCounts)
                        }
                    ) { (rawTrend, summary), (pProviders, pPage, pTotal), (modelPaging, costStats), (calls, total, page), (availableProviders, availableModels, keyCounts) ->
                        val trend = padTrend(period, rawTrend, tz)
                        val costs = withContext(Dispatchers.Default) {
                            val perCall = calls.associate {
                                it.record.id to callCostUsd(it.record.providerId, it.record.model, it.record.inputTokens.toLong(), it.record.cachedInputTokens.toLong(), it.record.outputTokens.toLong(), it.record.cacheCreationTokens.toLong())
                            }
                            val periodTotal = costStats.sumOf { s ->
                                callCostUsd(s.providerId, s.model, s.inputTokens, s.cachedInputTokens, s.outputTokens, s.cacheCreationTokens) ?: 0.0
                            }
                            perCall to periodTotal
                        }
                        TokenStatsUiState(
                            period = period,
                            summary = summary,
                            trend = trend,
                            providers = pProviders,
                            models = modelPaging.paged,
                            recentCalls = calls,
                            callsPage = page,
                            callsTotal = total,
                            providersPage = pPage,
                            providersTotal = pTotal,
                            modelsPage = modelPaging.page,
                            modelsTotal = modelPaging.total,
                            totalCostUsd = costs.second,
                            recentCallCosts = costs.first,
                            filterProviderId = filterProviderId,
                            filterModel = filterModel,
                            availableProviders = availableProviders,
                            availableModels = availableModels,
                            providerKeyCounts = keyCounts
                        )
                    }
                }.collectLatest { _tokenStats.value = it }
            }
        }
    }

    fun upsertMcpServer(originalName: String?, initialScope: McpScope?, config: McpServerConfig, scope: McpScope) {
        viewModelScope.launch {
            // Scope migration: only when the save scope differs from the original, remove the old entry from the original scope,
            // to avoid leftover entries continuing to override the new scope's config when merged (project takes precedence).
            if (originalName != null && initialScope != null && initialScope != scope) {
                val oldBase = if (initialScope == McpScope.GLOBAL) mcpConfigRepository.getGlobalServers() else mcpConfigRepository.getProjectServers()
                val oldUpdated = oldBase.filterNot { it.name == originalName }
                if (initialScope == McpScope.GLOBAL) mcpConfigRepository.setGlobalServers(oldUpdated) else mcpConfigRepository.setProjectServers(oldUpdated)
            }
            val base = if (scope == McpScope.GLOBAL) mcpConfigRepository.getGlobalServers() else mcpConfigRepository.getProjectServers()
            val ordered = LinkedHashMap<String, McpServerConfig>()
            base.forEach { ordered[it.name] = it }
            if (originalName != null && originalName != config.name) {
                ordered.remove(originalName)
            }
            ordered[config.name] = config
            val updated = ordered.values.toList()
            if (scope == McpScope.GLOBAL) mcpConfigRepository.setGlobalServers(updated) else mcpConfigRepository.setProjectServers(updated)
            _mcpReloading.value = true
            try {
                // Only reconnect the changed server, other servers are unaffected; when renaming, disconnect the old name first.
                if (originalName != null && originalName != config.name) {
                    mcpManager.removeServer(originalName)
                }
                mcpManager.reloadServer(config.name)
            } finally {
                _mcpReloading.value = false
            }
        }
    }

    fun deleteMcpServer(name: String, scope: McpScope) {
        viewModelScope.launch {
            val base = if (scope == McpScope.GLOBAL) mcpConfigRepository.getGlobalServers() else mcpConfigRepository.getProjectServers()
            val updated = base.filterNot { it.name == name }
            if (scope == McpScope.GLOBAL) mcpConfigRepository.setGlobalServers(updated) else mcpConfigRepository.setProjectServers(updated)
            _mcpReloading.value = true
            try {
                mcpManager.removeServer(name)
            } finally {
                _mcpReloading.value = false
            }
        }
    }

    fun setMcpServerEnabled(name: String, enabled: Boolean, scope: McpScope) {
        viewModelScope.launch {
            val base = if (scope == McpScope.GLOBAL) mcpConfigRepository.getGlobalServers() else mcpConfigRepository.getProjectServers()
            val updated = base.map { if (it.name == name) it.copy(enabled = enabled) else it }
            if (scope == McpScope.GLOBAL) mcpConfigRepository.setGlobalServers(updated) else mcpConfigRepository.setProjectServers(updated)
            _mcpReloading.value = true
            try {
                mcpManager.reloadServer(name)
            } finally {
                _mcpReloading.value = false
            }
        }
    }

    fun reloadMcp() {
        viewModelScope.launch {
            _mcpReloading.value = true
            try {
                mcpManager.reload()
            } finally {
                _mcpReloading.value = false
            }
        }
    }

    /** Reconnect only the specified server (used by the refresh tool in the top-right of the edit dialog). */
    fun reloadMcpServer(name: String) {
        viewModelScope.launch {
            _mcpReloading.value = true
            try {
                mcpManager.reloadServer(name)
            } finally {
                _mcpReloading.value = false
            }
        }
    }

    /** Rescan skills (called on entering the settings page / after enable-disable toggling, to reflect additions, deletions and changes on disk). */
    fun refreshSkills() {
        viewModelScope.launch {
            _skills.value = withContext(Dispatchers.IO) {
                skillRepository.listAllSkills().map { entry ->
                    SkillUiEntry(
                        name = entry.skill.name,
                        description = entry.skill.description,
                        scope = entry.scope,
                        disabled = skillRepository.isSkillDisabled(entry.skill.name),
                        instructions = entry.skill.instructions,
                        requiredTools = entry.skill.requiredTools
                    )
                }
            }
        }
    }

    /** Toggle a skill's enabled/disabled state (writes to skills.json in the corresponding scope). */
    fun setSkillEnabled(name: String, enabled: Boolean, scope: SkillScope) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { skillRepository.setSkillDisabled(name, !enabled, scope) }
            refreshSkills()
        }
    }

    /**
     * Delete the skill in the specified scope (deletes its directory, unrecoverable), refresh the list when done.
     * The deletion body runs on the IO thread (in remote mode it recursively deletes via SFTP and cannot be on the main thread); during this [skillDeleting] is set to the name for the UI spinner.
     */
    fun deleteSkill(name: String, scope: SkillScope) {
        if (_skillDeleting.value != null) return
        _skillDeleting.value = name
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { skillRepository.deleteSkill(name, scope) }
                refreshSkills()
            } finally {
                _skillDeleting.value = null
            }
        }
    }

    /**
     * Save a skill. [originalName] is the name before editing, null when creating new;
     * the result is converted to [skillSaveState], and the edit page uses this to return to the list or report an error in place.
     */
    fun saveSkill(form: SkillForm, scope: SkillScope, originalName: String? = null) {
        if (_skillSaveState.value is SkillSaveState.Saving) return
        _skillSaveState.value = SkillSaveState.Saving
        viewModelScope.launch {
            val error = withContext(Dispatchers.IO) {
                skillRepository.save(form, scope, originalName)
            }
            _skillSaveState.value = if (error == null) {
                SkillSaveState.Saved
            } else {
                SkillSaveState.Failed(error)
            }
            if (error == null) refreshSkills()
        }
    }

    fun clearSkillSaveState() {
        _skillSaveState.value = SkillSaveState.Idle
    }

    /**
     * Import a skill from the selected Markdown file: read the text and write it to the specified scope.
     * If the extension is not in the whitelist, directly report 'unsupported type' and do not write to disk.
     */
    fun importSkillFromMarkdown(uri: Uri, scope: SkillScope) {
        if (_skillImportState.value is SkillImportState.Running) return
        _skillImportState.value = SkillImportState.Running
        viewModelScope.launch {
            val report = withContext(Dispatchers.IO) {
                val name = queryDisplayName(uri)
                if (!name.hasExtension(MARKDOWN_EXTENSIONS)) {
                    SkillImportReport(emptyList(), fatal = SkillImportError.UNSUPPORTED_FILE)
                } else {
                    runCatching {
                        val text = context.contentResolver.openInputStream(uri)
                            ?.bufferedReader()?.use { it.readText() }
                            ?: throw java.io.IOException("openInputStream returned null")
                        skillRepository.importMarkdown(text, name.substringBeforeLast('.'), scope)
                    }.getOrElse { SkillImportReport(emptyList(), fatal = SkillImportError.IO_FAILED) }
                }
            }
            finishSkillImport(report)
        }
    }

    /** Import skills from the selected zip archive (may contain multiple skills), writing to the specified scope. */
    fun importSkillsFromZip(uri: Uri, scope: SkillScope) {
        if (_skillImportState.value is SkillImportState.Running) return
        _skillImportState.value = SkillImportState.Running
        viewModelScope.launch {
            val report = withContext(Dispatchers.IO) {
                val name = queryDisplayName(uri)
                if (!name.hasExtension(ZIP_EXTENSIONS)) {
                    SkillImportReport(emptyList(), fatal = SkillImportError.UNSUPPORTED_FILE)
                } else {
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            skillRepository.importZip(input, name.substringBeforeLast('.'), scope)
                        } ?: throw java.io.IOException("openInputStream returned null")
                    }.getOrElse { SkillImportReport(emptyList(), fatal = SkillImportError.INVALID_ARCHIVE) }
                }
            }
            finishSkillImport(report)
        }
    }

    private suspend fun finishSkillImport(report: SkillImportReport) {
        _skillImportState.value = SkillImportState.Done(report)
        if (report.imported.isNotEmpty()) refreshSkills()
    }

    fun clearSkillImportState() {
        _skillImportState.value = SkillImportState.Idle
    }

    /** Query the display name of the selected file (including extension); if not available, fall back to the last segment of the URI. */
    private fun queryDisplayName(uri: Uri): String {
        val queried = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
        return queried ?: uri.lastPathSegment?.substringAfterLast('/').orEmpty()
    }

    /** Rescan sub-agent definitions (called on entering the settings page / after deletion, to reflect additions, deletions and changes on disk). */
    fun refreshSubAgents() {
        viewModelScope.launch {
            _subAgents.value = withContext(Dispatchers.IO) {
                agentDefinitionRepository.listAll().map { entry ->
                    SubAgentUiEntry(
                        name = entry.definition.name,
                        description = entry.definition.description,
                        scope = entry.scope,
                        disabled = agentDefinitionRepository.isDisabled(entry.definition.name),
                        providerId = entry.definition.providerId,
                        model = entry.definition.model,
                        reasoningEffort = entry.definition.reasoningEffort,
                        mode = entry.definition.mode,
                        allowedTools = entry.definition.allowedTools,
                        disallowedTools = entry.definition.disallowedTools,
                        inject = entry.definition.inject,
                        prompt = entry.definition.prompt,
                        filePath = entry.definition.filePath
                    )
                }
            }
        }
    }

    /** Delete the sub-agent definition file in the specified scope (unrecoverable), then refresh the list immediately. */
    /** Toggle a sub-agent's enabled/disabled state (writes to agents.json in the corresponding scope); after disabling it no longer enters the main agent's dispatchable list. */
    fun setSubAgentEnabled(name: String, enabled: Boolean, scope: AgentDefinitionScope) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { agentDefinitionRepository.setDisabled(name, !enabled, scope) }
            refreshSubAgents()
        }
    }

    /**
     * Save a sub-agent definition. [originalName] is the name before editing, null when creating new;
     * the result is converted to [subAgentSaveState], and the edit page uses this to return to the list or report an error in place.
     */
    fun saveSubAgent(
        form: AgentDefinitionForm,
        scope: AgentDefinitionScope,
        originalName: String? = null
    ) {
        if (_subAgentSaveState.value is SubAgentSaveState.Saving) return
        _subAgentSaveState.value = SubAgentSaveState.Saving
        viewModelScope.launch {
            val error = withContext(Dispatchers.IO) {
                agentDefinitionRepository.save(form, scope, originalName)
            }
            _subAgentSaveState.value = if (error == null) {
                SubAgentSaveState.Saved
            } else {
                SubAgentSaveState.Failed(error)
            }
            if (error == null) refreshSubAgents()
        }
    }

    fun clearSubAgentSaveState() {
        _subAgentSaveState.value = SubAgentSaveState.Idle
    }

    /** Names of all currently registered tools (including MCP dynamic tools), for the edit page to check tool whitelist and blacklist. */
    fun availableToolNames(): List<String> = toolRegistry.getAvailableTools().map { it.name }

    fun deleteSubAgent(name: String, scope: AgentDefinitionScope) {
        if (_subAgentDeleting.value != null) return
        _subAgentDeleting.value = name
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { agentDefinitionRepository.delete(name, scope) }
                refreshSubAgents()
            } finally {
                _subAgentDeleting.value = null
            }
        }
    }

    fun getMcpServerTools(serverName: String?): List<McpToolDescriptor> {
        if (serverName.isNullOrBlank()) return emptyList()
        return mcpManager.getServerTools(serverName)
    }

    fun setLogLevel(level: LogLevel) {
        viewModelScope.launch {
            logSettingsRepository.setLevel(level)
        }
    }

    // ── Check for updates ──

    /**
     * Check for updates. Manual mode (tap on About page) is not subject to the switch and daily limit; automatic mode (main page launch)
     * requires the switch to be on and no check yet today, fails silently (no error dialog), and records that today has been checked regardless of result.
     */
    fun checkUpdate(manual: Boolean) {
        if (!manual) {
            if (!updateCheckSettingsRepository.autoCheckEnabled) return
            if (updateCheckSettingsRepository.hasCheckedToday()) return
        }
        if (_updateCheckState.value == UpdateCheckUiState.Checking) return
        viewModelScope.launch {
            // Manual check shows 'checking' feedback; automatic check is imperceptible throughout, only popping up a dialog when a new version is detected
            if (manual) {
                _updateCheckState.value = UpdateCheckUiState.Checking
            }
            val result = updateCheckService.checkForUpdate(
                currentVersion = currentVersionName(),
                channel = updateCheckSettingsRepository.channel
            )
            _updateCheckState.value = when (result) {
                is UpdateCheckResult.UpToDate -> {
                    if (manual) UpdateCheckUiState.UpToDate else UpdateCheckUiState.Idle
                }
                is UpdateCheckResult.NewVersion -> UpdateCheckUiState.NewVersion(
                    latestTag = result.info.latestTag,
                    changelog = result.info.changelog
                )
                is UpdateCheckResult.Error -> {
                    if (manual) UpdateCheckUiState.Error(result.message) else UpdateCheckUiState.Idle
                }
            }
            // Regardless of result, refresh ~/.aicode/update-info.json for the AI to read
            updateCheckSettingsRepository.writeUpdateInfo(
                currentVersion = currentVersionName(),
                channel = updateCheckSettingsRepository.channel,
                result = result
            )
            if (!manual) {
                updateCheckSettingsRepository.markCheckedToday()
            }
        }
    }

    /** Close the update dialog. */
    fun dismissUpdateCheck() {
        _updateCheckState.value = UpdateCheckUiState.Idle
    }

    /** Automatic update check switch (on by default). */
    fun setUpdateCheckEnabled(enabled: Boolean) {
        updateCheckSettingsRepository.autoCheckEnabled = enabled
        _updateCheckEnabled.value = enabled
    }

    /** Update channel: stable / latest. */
    fun setUpdateCheckChannel(channel: UpdateChannel) {
        updateCheckSettingsRepository.channel = channel
        _updateCheckChannel.value = channel
    }

    private fun currentVersionName(): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
    }.getOrDefault("unknown")

    /**
     * Determine whether a log line belongs to a given MCP server: only recognize logs written by MCP components (tag starting with `Mcp`,
     * e.g. McpManager/McpClient/McpStdioTransport/McpTool), and the message references that server -
     * either the message prefix `[name]`, or the namespaced tool name `mcp__name__`.
     * Must not substring-match on server name alone: that would scoop in any unrelated logs that happen to mention that name.
     */
    private fun isMcpServerLogLine(line: String, serverName: String): Boolean {
        if (!line.contains("[Mcp")) return false
        return line.contains("[$serverName]") || line.contains("mcp__${serverName}__")
    }

    fun refreshLogs(filterServerName: String? = _logViewerState.value.filterServerName, silent: Boolean = false) {
        loadLogs(
            filterServerName = filterServerName?.takeIf { it.isNotBlank() },
            preferredFileName = _logViewerState.value.selectedFileName,
            silent = silent
        )
    }

    fun selectLogFile(fileName: String) {
        loadLogs(
            filterServerName = _logViewerState.value.filterServerName,
            preferredFileName = fileName
        )
    }

    private fun loadLogs(filterServerName: String?, preferredFileName: String?, silent: Boolean = false) {
        viewModelScope.launch {
            if (!silent) {
                _logViewerState.update {
                    it.copy(
                        loading = true,
                        filterServerName = filterServerName,
                        error = null
                    )
                }
            }
            val state = withContext(Dispatchers.IO) {
                runCatching {
                    val files = FileLogger.listLogFiles()
                    val todayName = "log-${java.time.LocalDate.now()}.txt"
                    val selected = files.firstOrNull { it.name == preferredFileName }
                        ?: files.firstOrNull { it.name == todayName }
                        ?: files.lastOrNull()
                    if (selected == null) {
                        return@runCatching LogViewerUiState(
                            filterServerName = filterServerName,
                            error = context.getString(R.string.settings_log_no_files)
                        )
                    }

                    val rawLines = selected.readLines(Charsets.UTF_8)
                    val filteredLines = if (filterServerName.isNullOrBlank()) {
                        rawLines
                    } else {
                        rawLines.filter { line -> isMcpServerLogLine(line, filterServerName) }
                    }
                    val visibleLines = filteredLines.takeLast(MAX_LOG_LINES)

                    LogViewerUiState(
                        files = files.map { it.name },
                        selectedFileName = selected.name,
                        filterServerName = filterServerName,
                        content = visibleLines.joinToString("\n"),
                        totalLines = filteredLines.size,
                        shownLines = visibleLines.size
                    )
                }.getOrElse { e ->
                    LogViewerUiState(
                        filterServerName = filterServerName,
                        error = context.getString(R.string.settings_log_read_failed, e.message ?: "")
                    )
                }
            }
            _logViewerState.value = state
        }
    }

    // Only persists the flag - starting/stopping the Service is handled uniformly by AIEditorApp listening to enabledFlow.
    fun setKeepaliveEnabled(enabled: Boolean) {
        viewModelScope.launch {
            keepaliveSettingsRepository.setEnabled(enabled)
        }
    }

    // Only persists the flag - adding/removing the window FLAG_KEEP_SCREEN_ON is handled uniformly by MainActivity listening to enabledFlow.
    fun setScreenOnEnabled(enabled: Boolean) {
        viewModelScope.launch {
            screenOnSettingsRepository.setEnabled(enabled)
        }
    }

    fun setAgentSoundEnabled(enabled: Boolean) {
        viewModelScope.launch {
            agentSoundSettingsRepository.setEnabled(enabled)
        }
    }

    /** Whether to automatically remove local models that no longer exist remotely after successfully fetching models. */
    fun setAutoRemoveStaleModels(enabled: Boolean) {
        viewModelScope.launch {
            generalSettingsRepository.setAutoRemoveStaleModels(enabled)
        }
    }

    /** Display position of the task to-do area on the chat page (below title bar / above message bar / off). */
    fun setTodoDisplayPosition(position: TodoDisplayPosition) {
        viewModelScope.launch {
            generalSettingsRepository.setTodoDisplayPosition(position)
        }
    }

    /** Whether to enter a new session or the most recent session on launch (including workspace switching). */
    fun setStartupSessionMode(mode: StartupSessionMode) {
        viewModelScope.launch {
            generalSettingsRepository.setStartupSessionMode(mode)
        }
    }

    /** First-byte timeout for streaming requests (seconds); 0 means unlimited. */
    fun setFirstByteTimeoutSec(sec: Int) {
        viewModelScope.launch {
            generalSettingsRepository.setFirstByteTimeoutSec(sec)
        }
    }

    /** Interval timeout between adjacent data chunks in a streaming response (seconds); 0 means unlimited. */
    fun setStreamIdleTimeoutSec(sec: Int) {
        viewModelScope.launch {
            generalSettingsRepository.setStreamIdleTimeoutSec(sec)
        }
    }

    /** Maximum retries for network requests; 0 means no retry. */
    fun setMaxNetworkRetries(count: Int) {
        viewModelScope.launch {
            generalSettingsRepository.setMaxNetworkRetries(count)
        }
    }

    /** Whether the Enter key sends a message directly. */
    fun setEnterToSend(enabled: Boolean) {
        viewModelScope.launch {
            generalSettingsRepository.setEnterToSend(enabled)
        }
    }

    /** Threshold that triggers automatic compaction (context window percentage, 1..100). */
    fun setCompactionThresholdPercent(percent: Int) {
        viewModelScope.launch {
            generalSettingsRepository.setCompactionThresholdPercent(percent)
        }
    }

    /** Maximum size of a single file for sendFile (MB, not less than 1). */
    fun setSendFileMaxSizeMb(mb: Int) {
        viewModelScope.launch {
            generalSettingsRepository.setSendFileMaxSizeMb(mb)
        }
    }

    /** Whether to also delete its chat history when removing an external local workspace. */
    fun setDeleteExternalWorkspaceSessions(enabled: Boolean) {
        viewModelScope.launch {
            generalSettingsRepository.setDeleteExternalWorkspaceSessions(enabled)
        }
    }

    fun setThemeMode(mode: AppThemeMode) {
        viewModelScope.launch {
            themeSettingsRepository.setThemeMode(mode)
        }
    }

    fun setThemePreset(id: String) {
        viewModelScope.launch {
            themeSettingsRepository.setThemePresetId(id)
        }
    }

    fun setDynamicColorEnabled(enabled: Boolean) {
        viewModelScope.launch {
            themeSettingsRepository.setDynamicColorEnabled(enabled)
        }
    }

    /** Select a new background image (replace after copying to the private directory). */
    fun setBackgroundImage(uri: Uri) {
        viewModelScope.launch {
            backgroundSettingsRepository.setBackgroundImage(uri)
        }
    }

    /** Remove the background image. */
    fun clearBackgroundImage() {
        viewModelScope.launch {
            backgroundSettingsRepository.clearBackground()
        }
    }

    /**
     * Adjust background image opacity (0~0.2, actual range determined by BackgroundSettingsRepository's MIN_ALPHA/MAX_ALPHA).
     *
     * Dragging the slider continuously fires dozens of values, DataStore writes are serial, persisting each one would accumulate a visibly noticeable delay,
     * and reading back would drag the whole settings page into recomposition. Here only the last value is kept, written once about 80ms after stopping.
     */
    fun setBackgroundAlpha(alpha: Float) {
        backgroundAlphaWriteJob?.cancel()
        backgroundAlphaWriteJob = viewModelScope.launch {
            delay(BACKGROUND_ALPHA_WRITE_DEBOUNCE_MS)
            backgroundSettingsRepository.setBackgroundAlpha(alpha)
        }
    }

    /** Set the app language; empty string or null tag means follow the system. */
    fun setLanguage(tag: String?) {
        viewModelScope.launch {
            languageSettingsRepository.setLanguage(tag?.takeIf { it.isNotBlank() })
        }
    }

    /**
     * Switch the currently selected container profile, and switch the global execution mode in sync according to its [ContainerProfile.mode].
     *
     * Local image -> [ExecutionMode.LOCAL_PROOT]; remote SSH image -> [ExecutionMode.REMOTE_SSH],
     * connection parameters are resolved in real time by ActiveRemoteConnectionResolver according to the active profile, and AIEditorApp's persistent subscription reconnects accordingly.
     * The delegation layer reads the holder on every call, switching takes effect immediately, no restart needed.
     */
    fun setActiveContainerProfile(id: String) {
        viewModelScope.launch {
            applyProfile(id)
        }
    }

    /** Set the default container in remote workspace mode (only local PRoot containers are selectable). */
    fun setDefaultContainerId(id: String) {
        viewModelScope.launch {
            containerSettingsRepository.setDefaultContainerId(id)
        }
    }

    /** Real-device ROOT probe state ('Command Execution Channel' page). */
    private val _rootProbeState = MutableStateFlow(RootProbeState.UNKNOWN)
    val rootProbeState: StateFlow<RootProbeState> = _rootProbeState.asStateFlow()

    /** Probe whether su is available (force re-probe, bypassing the engine's 30s cache; runs on IO thread, first time may trigger the su authorization dialog). */
    fun probeDeviceRoot() {
        if (_rootProbeState.value == RootProbeState.PROBING) return
        _rootProbeState.value = RootProbeState.PROBING
        viewModelScope.launch(Dispatchers.IO) {
            val ok = deviceRootEngine.reprobeRoot()
            _rootProbeState.value = if (ok) RootProbeState.AVAILABLE else RootProbeState.UNAVAILABLE
        }
    }

    /**
     * Switch the command execution channel ('Command Execution Channel' settings page).
     *
     * Real-device ROOT is decoupled from container profiles and switches ExecutionMode directly; sandbox/remote go via the container profile system
     * ([applyProfile] syncs ExecutionMode internally): switching to sandbox preferentially restores the currently active local image,
     * otherwise uses the default container, then falls back to the built-in Alpine; switching to remote uses the currently active remote image, otherwise the first remote image,
     * and does nothing when there is no remote image (the UI side has already disabled the entry).
     */
    fun switchExecutionChannel(target: ExecutionMode) {
        if (target == executionModeHolder.currentMode()) return
        viewModelScope.launch {
            when (target) {
                ExecutionMode.DEVICE_ROOT -> {
                    executionModeRepository.setExecutionMode(ExecutionMode.DEVICE_ROOT)
                    executionModeHolder.setMode(ExecutionMode.DEVICE_ROOT)
                }
                ExecutionMode.LOCAL_PROOT -> {
                    val local = _customProfiles.value.firstOrNull {
                        it.id == _activeProfileId.value && it.mode == ExecutionMode.LOCAL_PROOT
                    } ?: _customProfiles.value.firstOrNull {
                        it.id == _defaultContainerId.value && it.mode == ExecutionMode.LOCAL_PROOT
                    } ?: _customProfiles.value.firstOrNull { it.mode == ExecutionMode.LOCAL_PROOT }
                        ?: ContainerProfile.BUILTIN_ALPINE
                    applyProfile(local.id)
                    // Switching to the sandbox channel starts preparing the container immediately (extracting rootfs, idempotent: a fast path when already ready).
                    // This makes 'switching channel' itself the initialization entry point - previously only entering the terminal page triggered extraction,
                    // and in real-device ROOT mode the terminal page takes the su probe branch (TerminalSessionManager),
                    // so it never extracted, leaving the container permanently stuck at 'uninitialized'.
                    runCatching { containerInstaller.installRootfsIfNeed(local) }
                        .onFailure {
                            FileLogger.w("SettingsViewModel", "Failed to prepare container after switching to the sandbox channel: ${it.message}")
                        }
                }
                ExecutionMode.REMOTE_SSH -> {
                    val remote = _customProfiles.value.firstOrNull {
                        it.id == _activeProfileId.value && it.mode == ExecutionMode.REMOTE_SSH
                    } ?: _customProfiles.value.firstOrNull { it.mode == ExecutionMode.REMOTE_SSH }
                        ?: return@launch
                    applyProfile(remote.id)
                }
            }
        }
    }

    /** Apply [id] as the currently active profile: persist + switch execution mode by mode. When not found, fall back to the first in the list, then to the built-in. */
    private suspend fun applyProfile(id: String) {
        val profile = _customProfiles.value.firstOrNull { it.id == id }
            ?: ContainerProfile.BUILTIN_ALPINE.takeIf { it.id == id }
            ?: _customProfiles.value.firstOrNull()
            ?: return
        containerSettingsRepository.setActiveProfile(profile.id)
        when (profile.mode) {
            ExecutionMode.LOCAL_PROOT -> {
                executionModeRepository.setExecutionMode(ExecutionMode.LOCAL_PROOT)
                executionModeHolder.setMode(ExecutionMode.LOCAL_PROOT)
            }

            ExecutionMode.REMOTE_SSH -> {
                if (profile.rootfsSource !is RootfsSource.RemoteSsh) return
                // Connection parameters are resolved in real time by ActiveRemoteConnectionResolver according to the active profile, and AIEditorApp's persistent subscription reconnects accordingly.
                executionModeRepository.setExecutionMode(ExecutionMode.REMOTE_SSH)
                executionModeHolder.setMode(ExecutionMode.REMOTE_SSH)
            }

            // A profile can only be a local/remote image; the real-device ROOT channel switches ExecutionMode directly from the 'Command Execution Channel' settings page
            ExecutionMode.DEVICE_ROOT -> Unit
        }
    }

    /** Reset container: built-in restores factory (clears override config + deletes rootfs), custom local image deletes rootfs to re-extract next time. Remote SSH has no local data, the UI provides no entry. */
    fun resetContainer(profile: ContainerProfile) {
        if (_containerReset.value != null) return
        viewModelScope.launch {
            if (profile.isBuiltin) {
                containerSettingsRepository.upsertCustomProfile(ContainerProfile.BUILTIN_ALPINE)
            }
            withResetProgress(profile) { onProgress ->
                containerInstaller.resetRootfs(profile, onProgress)
            }
        }
    }

    /** Clean up the rootfs attached to deleting a profile, with progress; built-in and remote SSH have no local rootfs to delete, so no progress dialog is shown. */
    private suspend fun cleanRootfsWithProgress(profile: ContainerProfile) {
        if (profile.isBuiltin || profile.rootfsSource is RootfsSource.RemoteSsh) {
            containerInstaller.deleteCustomRootfs(profile)
            return
        }
        withResetProgress(profile) { onProgress ->
            containerInstaller.deleteCustomRootfs(profile, onProgress)
        }
    }

    /**
     * Wrap a single rootfs deletion: during it, write the number of deleted entries into [_containerReset] to drive the progress dialog, clear it when done.
     * Deleting a large container takes tens of seconds, the deletion body runs on the IO thread, the UI only waits without freezing.
     */
    private suspend fun withResetProgress(
        profile: ContainerProfile,
        block: suspend ((Int) -> Unit) -> Unit
    ) {
        _containerReset.value = ContainerResetUiState(profile.name, 0)
        try {
            block { deleted -> _containerReset.value = ContainerResetUiState(profile.name, deleted) }
        } finally {
            _containerReset.value = null
        }
    }

    /** Empty-state recovery: add the built-in Alpine default config back to the list. */
    fun restoreBuiltinAlpine() {
        viewModelScope.launch {
            containerSettingsRepository.upsertCustomProfile(ContainerProfile.BUILTIN_ALPINE)
        }
    }

    /** Save (add/overwrite) a custom container profile. */
    fun saveCustomContainerProfile(profile: ContainerProfile) {
        viewModelScope.launch {
            containerSettingsRepository.upsertCustomProfile(profile)
        }
    }

    /** Edit a custom profile: overwrite config; if the image source changed, delete the old rootfs to trigger re-extraction. */
    fun editCustomContainerProfile(profile: ContainerProfile) {
        viewModelScope.launch {
            val old = _customProfiles.value.firstOrNull { it.id == profile.id }
            val oldUri = (old?.rootfsSource as? RootfsSource.LocalFile)?.uri
            val newUri = (profile.rootfsSource as? RootfsSource.LocalFile)?.uri
            if (old != null && oldUri != newUri) {
                cleanRootfsWithProgress(profile)
            }
            containerSettingsRepository.upsertCustomProfile(profile)
        }
    }

    /** Delete a container profile (built-in Alpine can also be deleted, recovery via empty state after deleting all), along with cleaning up the local rootfs. */
    fun deleteContainerProfile(profile: ContainerProfile) {
        viewModelScope.launch {
            containerSettingsRepository.deleteCustomProfile(profile.id)
            cleanRootfsWithProgress(profile)
            if (_activeProfileId.value == profile.id) {
                // The deleted item is the currently active one: switch to the first remaining; if the list is empty, fall back to the built-in id (engine Alpine fallback)
                val remaining = _customProfiles.value.filterNot { it.id == profile.id }
                applyProfile(remaining.firstOrNull()?.id ?: ContainerProfile.BUILTIN_ID)
            }
        }
    }

    /** Mark the announcement as shown: write the current content hash to storage, so it does not pop up again next time if content unchanged; when content updates the hash changes and it naturally pops up again. */
    fun markContainerAnnouncementShown() {
        viewModelScope.launch {
            containerSettingsRepository.markAnnouncementShown(sha256(_containerAnnouncementText.value))
        }
    }

    /** Mark the 'Prompts' page announcement as shown; same principle as the container announcement, it automatically pops up again after content updates (hash changes). */
    fun markPromptsAnnouncementShown() {
        viewModelScope.launch {
            generalSettingsRepository.markPromptsAnnouncementShown(sha256(_promptsAnnouncementText.value))
        }
    }

    /** Switch the global download source (official/Huawei Cloud/Alibaba Cloud/Tencent Cloud, etc.). */
    fun setImageSource(sourceId: String) {
        _selectedImageSource.value = sourceId
    }

    /** Source display name: get the name defined in the catalog JSON according to UI language, when following system language use the system language. */
    fun sourceDisplayName(sourceId: String, languageTag: String?): String {
        val lang = languageTag?.takeIf { it.isNotBlank() } ?: java.util.Locale.getDefault().language
        val key = if (lang.startsWith("zh", ignoreCase = true)) "zh" else "en"
        return containerImageCatalog.sourceName(sourceId, key) ?: sourceId
    }

    /** Start downloading an image: build the URL from the current architecture and global source, progress is written in real time to [_containerImageDownload]. */
    fun startContainerImageDownload(entry: ContainerImageEntry, sourceId: String) {
        if (_containerImageDownload.value is ContainerImageDownloadUiState.Downloading) return
        val url = containerImageCatalog.urlFor(entry, sourceId, ContainerImageCatalog.CURRENT_ABI) ?: return
        val suffix = when {
            url.endsWith(".tar.gz") -> "tar.gz"
            url.endsWith(".tar.xz") || url.endsWith(".txz") -> "tar.xz"
            url.endsWith(".tgz") -> "tgz"
            else -> "tar.gz"
        }
        val fileName = "download_${entry.id}_${entry.version}_${System.currentTimeMillis()}.$suffix"
        downloadJob = viewModelScope.launch {
            _containerImageDownload.value =
                ContainerImageDownloadUiState.Downloading(entry.id, entry.name, entry.version, sourceId, 0, 0)
            try {
                val uri = containerImageDownloader.download(url, fileName) { read, total ->
                    _containerImageDownload.value = ContainerImageDownloadUiState.Downloading(
                        entry.id, entry.name, entry.version, sourceId, read, total
                    )
                }
                // Persist the record as soon as download completes, retained whether installed or not, visible across restarts.
                containerSettingsRepository.upsertDownloadedImage(DownloadedImageRecord(entry.id, uri))
                _containerImageDownload.value =
                    ContainerImageDownloadUiState.Done(entry.id, entry.name, entry.version, uri)
            } catch (e: kotlinx.coroutines.CancellationException) {
                _containerImageDownload.value = ContainerImageDownloadUiState.Idle
                throw e
            } catch (e: Exception) {
                _containerImageDownload.value =
                    ContainerImageDownloadUiState.Error(entry.id, e.message ?: context.getString(R.string.container_download_failed, ""))
            }
        }
    }

    /** Cancel the download in progress (partial files are cleaned up by the downloader). */
    fun cancelContainerImageDownload() {
        downloadJob?.cancel()
        downloadJob = null
    }

    /**
     * Import a downloaded image as a custom profile into the container list (can be repeatedly imported, each time creating a new profile), subsequent flow is consistent with manual import.
     */
    fun importDownloadedImage(entryId: String, fileUri: String) {
        val entry = _imageCatalog.value.firstOrNull { it.id == entryId } ?: return
        viewModelScope.launch {
            val profile = ContainerProfile(
                id = "custom-${System.currentTimeMillis()}",
                name = "${entry.name} ${entry.version}",
                rootfsSource = RootfsSource.LocalFile(fileUri),
                shellPath = null,
                extraArgs = ContainerProfile.DEFAULT_PROOT_ARGS,
                isBuiltin = false
            )
            containerSettingsRepository.upsertCustomProfile(profile)
            // Keep the downloaded record, only mark as imported; UI state does not disappear.
            containerSettingsRepository.upsertDownloadedImage(DownloadedImageRecord(entryId, fileUri, installed = true))
            _containerImageDownload.value = ContainerImageDownloadUiState.Idle
        }
    }

    /** Delete a downloaded image: clear record + delete download file (only in the rootfs_images directory, to prevent accidental deletion); if that entry is currently downloading, cancel first. */
    fun deleteDownloadedImage(entryId: String) {
        val record = _downloadedImages.value[entryId] ?: return
        if ((_containerImageDownload.value as? ContainerImageDownloadUiState.Downloading)?.entryId == entryId) {
            cancelContainerImageDownload()
        }
        viewModelScope.launch {
            runCatching {
                val file = java.io.File(Uri.parse(record.fileUri).path ?: return@runCatching)
                if (file.parentFile?.name == "rootfs_images") file.delete()
            }
            containerSettingsRepository.removeDownloadedImage(entryId)
        }
    }

    /** Set the image-recognition model; empty providerId is equivalent to [clearVisionModel] (follow the chat model). */
    fun setVisionModel(providerId: String, model: String) {
        viewModelScope.launch {
            visionModelSettingsRepository.setVisionModel(providerId, model)
        }
    }

    /** Clear the image-recognition model - fall back to following the current chat model. */
    fun clearVisionModel() {
        viewModelScope.launch {
            visionModelSettingsRepository.clear()
        }
    }

    /** Set the compaction-specific model; empty providerId is equivalent to [clearCompactionModel] (follow the chat model). */
    fun setCompactionModel(providerId: String, model: String) {
        viewModelScope.launch {
            compactionModelSettingsRepository.setCompactionModel(providerId, model)
        }
    }

    /** Clear the compaction-specific model - fall back to following the current chat model. */
    fun clearCompactionModel() {
        viewModelScope.launch {
            compactionModelSettingsRepository.clear()
        }
    }

    /** Set the title-summary-specific model; empty providerId is equivalent to [clearTitleModel] (follow the chat model). */
    fun setTitleModel(providerId: String, model: String) {
        viewModelScope.launch {
            titleModelSettingsRepository.setTitleModel(providerId, model)
        }
    }

    /** Clear the title-summary-specific model - fall back to following the current chat model. */
    fun clearTitleModel() {
        viewModelScope.launch {
            titleModelSettingsRepository.clear()
        }
    }

    /** Set the image-generation-specific model; empty providerId is equivalent to [clearImageGenModel] (not configured). */
    fun setImageGenModel(providerId: String, model: String) {
        viewModelScope.launch {
            imageGenModelSettingsRepository.setImageGenModel(providerId, model)
        }
    }

    /** Clear the image-generation-specific model - return to unconfigured state (the image generation tool will prompt to configure first). */
    fun clearImageGenModel() {
        viewModelScope.launch {
            imageGenModelSettingsRepository.clear()
        }
    }

    /** Set the AI command reviewer-specific model; empty providerId is equivalent to [clearReviewerModel] (follow the chat model). */
    fun setReviewerModel(providerId: String, model: String) {
        viewModelScope.launch {
            reviewerModelSettingsRepository.setReviewerModel(providerId, model)
        }
    }

    fun clearReviewerModel() {
        viewModelScope.launch {
            reviewerModelSettingsRepository.clear()
        }
    }

    /**
     * Save the 'unified context window value'. When required items (total context window, output window) are missing, the storage layer automatically
     * sets the switch back to off, and [ContextWindowOverrideUiState] refreshes accordingly - the UI does not need to judge this itself.
     */
    fun saveContextWindowOverride(contextTokens: Int, inputTokens: Int?, outputTokens: Int?) {
        viewModelScope.launch {
            contextWindowOverrideRepository.save(contextTokens, inputTokens, outputTokens)
        }
    }

    /** Directly toggle the 'unified context window value'; cannot be turned on when required items are incomplete. */
    fun setContextWindowOverrideEnabled(enabled: Boolean) {
        viewModelScope.launch {
            contextWindowOverrideRepository.setEnabled(enabled)
        }
    }

    fun setTokenStatsPeriod(period: TokenStatsPeriod) {
        _tokenStatsPeriod.value = period
        // After switching period, details and statistics slices return to the first page
        _tokenStatsPage.value = 0
        _providerStatsPage.value = 0
        _modelStatsPage.value = 0
    }

    /** Set the provider filter (null means all). */
    fun setFilterProviderId(providerId: String?) {
        if (_filterProviderId.value == providerId) return
        _filterProviderId.value = providerId
        _tokenStatsPage.value = 0
        _providerStatsPage.value = 0
        _modelStatsPage.value = 0
    }

    /** Set the model filter (null means all). */
    fun setFilterModel(model: String?) {
        if (_filterModel.value == model) return
        _filterModel.value = model
        _tokenStatsPage.value = 0
        _providerStatsPage.value = 0
        _modelStatsPage.value = 0
    }

    /** Clear all filter conditions. */
    fun clearTokenStatsFilters() {
        if (_filterProviderId.value == null && _filterModel.value == null) return
        _filterProviderId.value = null
        _filterModel.value = null
        _tokenStatsPage.value = 0
        _providerStatsPage.value = 0
        _modelStatsPage.value = 0
    }

    /**
     * Estimated cost of a single call (USD); returns null when the model has no unit price.
     * Unit price resolution and cached price fallback are unified in [ModelCostCalculator].
     */
    private suspend fun callCostUsd(
        providerId: String?,
        model: String?,
        inputTokens: Long,
        cachedInputTokens: Long,
        outputTokens: Long,
        cacheCreationTokens: Long = 0
    ): Double? {
        val modelId = model ?: return null
        val provider = _providers.value.firstOrNull { it.id == providerId }
        return modelCostCalculator.costUsd(
            providerId = providerId.orEmpty(),
            providerType = provider?.type ?: ProviderType.OPENAI,
            model = modelId,
            inputTokens = inputTokens,
            cachedInputTokens = cachedInputTokens,
            outputTokens = outputTokens,
            cacheCreationTokens = cacheCreationTokens
        )
    }

    /** Page through call details; clamps to the valid range when out of bounds. */
    fun setTokenStatsPage(page: Int) {
        val total = _tokenStats.value.callsTotal
        val lastPage = if (total == 0) 0 else (total - 1) / CALLS_PAGE_SIZE
        _tokenStatsPage.value = page.coerceIn(0, lastPage)
    }

    /** Page through provider statistics; clamps to the valid range when out of bounds. */
    fun setProviderStatsPage(page: Int) {
        val total = _tokenStats.value.providersTotal
        val lastPage = if (total == 0) 0 else (total - 1) / STATS_PAGE_SIZE
        _providerStatsPage.value = page.coerceIn(0, lastPage)
    }

    /** Page through model statistics; clamps to the valid range when out of bounds. */
    fun setModelStatsPage(page: Int) {
        val total = _tokenStats.value.modelsTotal
        val lastPage = if (total == 0) 0 else (total - 1) / STATS_PAGE_SIZE
        _modelStatsPage.value = page.coerceIn(0, lastPage)
    }

    /** Clear all call records (reset at top-right of Token statistics page); Room Flow automatically refreshes each aggregate to empty. */
    fun resetTokenStats() {
        viewModelScope.launch {
            llmCallRecordDao.deleteAll()
            _filterProviderId.value = null
            _filterModel.value = null
            _tokenStatsPage.value = 0
            _providerStatsPage.value = 0
            _modelStatsPage.value = 0
        }
    }

    fun setProviderEnabled(id: String, isEnabled: Boolean) {
        viewModelScope.launch {
            repository.setProviderEnabled(id, isEnabled)
        }
    }

    fun saveProvider(provider: AIProviderConfig) {
        viewModelScope.launch {
            val removedModels = (repository.getProviderById(provider.id)?.models ?: emptyList())
                .toSet() - provider.models.toSet()
            repository.saveProvider(provider)
            if (removedModels.isNotEmpty()) cleanupRemovedModels(provider, removedModels)
            // After saving, reset that provider's dashboard state in memory, so the main page reloads immediately with the latest script and config
            _providerDashboards.update { it - provider.id }
        }
    }

    /**
     * After a model is removed from the list, clear the selection states everywhere still pointing to it, otherwise the main page and dedicated models would still request with the deleted model.
     * Session-bound models are not cleaned up here, they are validated and fallen back on read by resolveProviderConfig / AIChatPanel, avoiding traversing all historical sessions.
     */
    private suspend fun cleanupRemovedModels(provider: AIProviderConfig, removed: Set<String>) {
        if (provider.selectedModel in removed) {
            repository.setSelectedModel(provider.id, provider.models.firstOrNull() ?: "")
        }
        if (defaultModelSettingsRepository.getDefaultProviderId() == provider.id &&
            defaultModelSettingsRepository.getDefaultModel() in removed
        ) {
            defaultModelSettingsRepository.clear()
        }
        if (visionModelSettingsRepository.getVisionProviderId() == provider.id &&
            visionModelSettingsRepository.getVisionModel() in removed
        ) {
            visionModelSettingsRepository.clear()
        }
        if (imageGenModelSettingsRepository.getImageGenProviderId() == provider.id &&
            imageGenModelSettingsRepository.getImageGenModel() in removed
        ) {
            imageGenModelSettingsRepository.clear()
        }
        if (compactionModelSettingsRepository.getCompactionProviderId() == provider.id &&
            compactionModelSettingsRepository.getCompactionModel() in removed
        ) {
            compactionModelSettingsRepository.clear()
        }
        if (titleModelSettingsRepository.getTitleProviderId() == provider.id &&
            titleModelSettingsRepository.getTitleModel() in removed
        ) {
            titleModelSettingsRepository.clear()
        }
        if (reviewerModelSettingsRepository.getReviewerProviderId() == provider.id &&
            reviewerModelSettingsRepository.getReviewerModel() in removed
        ) {
            reviewerModelSettingsRepository.clear()
        }
    }

    fun deleteProvider(id: String) {
        viewModelScope.launch {
            repository.deleteProvider(id)
        }
    }

    /** Long-press drag sorting of the provider list: synchronously update the in-memory order (the reorderable library requires the list to be updated before onMove returns, otherwise the dragged item flickers), then asynchronously persist sortOrder. */
    fun reorderProviders(fromIndex: Int, toIndex: Int) {
        if (fromIndex == toIndex) return
        val current = _providers.value
        if (fromIndex !in current.indices || toIndex !in current.indices) return
        val reordered = current.toMutableList().apply {
            add(toIndex, removeAt(fromIndex))
        }
        _providers.value = reordered
        viewModelScope.launch {
            repository.reorderProviders(reordered)
        }
    }

    fun fetchModels(provider: AIProviderConfig) {
        viewModelScope.launch {
            _fetchState.value = FetchState.Loading
            modelApiService.fetchModels(provider.baseUrl, provider.firstUsableApiKey, provider.type, provider.useFullUrl, provider.customHeaders)
                .onSuccess { result ->
                    _fetchState.value = FetchState.Success(result.models, result.debugInfo)
                    resolveModelMetadata(provider.id, provider.type, result.models)
                }
                .onFailure { error ->
                    val debug = (error as? com.aicode.feature.settings.data.remote.FetchModelsException)?.debugInfo
                    _fetchState.value = FetchState.Error(
                        error.message ?: context.getString(R.string.settings_models_fetch_failed),
                        debug
                    )
                }
        }
    }

    fun resolveModelMetadata(providerId: String, type: ProviderType, modelIds: List<String>) {
        val normalizedIds = modelIds.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (normalizedIds.isEmpty()) return
        viewModelScope.launch {
            val metadata = modelMetadataService.resolveAll(providerId, type, normalizedIds)
            _modelMetadata.update { current ->
                current + metadata.mapKeys { (model, _) -> modelMetadataKey(providerId, model) }
            }
        }
    }

    /**
     * Load all model metadata for all enabled providers, merge into [modelMetadata].
     * Called on entry by pages like 'Image Recognition Model' that need to show cross-provider model capability labels--
     * these pages, unlike ProviderEditor, do not incidentally resolve when editing a single provider,
     * so without actively loading, the map would be empty and all models would be misjudged as not supporting images.
     *
     * Implementation points (to avoid settings page stutter):
     * - Processes each provider sequentially in a single coroutine: the first resolveAll triggers catalog loading (memory/disk 24h cache/built-in assets) and writes to the in-memory cache,
     *   subsequent providers hit the cache. The resolve chain makes no network requests, models.dev refresh is uniformly triggered asynchronously at App startup.
     * - Update once after all parsing is done, avoiding multiple emits causing the settings page to recompose repeatedly.
     */
    fun loadAllModelMetadata() {
        val enabled = _providers.value.filter { it.isEnabled }
        if (enabled.isEmpty()) return
        viewModelScope.launch {
            val resolved = mutableMapOf<String, ModelMetadata>()
            for (provider in enabled) {
                val ids = provider.models.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
                if (ids.isEmpty()) continue
                modelMetadataService.resolveAll(provider.id, provider.type, ids)
                    .forEach { (model, meta) -> resolved[modelMetadataKey(provider.id, model)] = meta }
            }
            if (resolved.isNotEmpty()) {
                _modelMetadata.update { it + resolved }
            }
        }
    }

    fun resetFetchState() {
        _fetchState.value = FetchState.Idle
    }

    fun testModel(provider: AIProviderConfig, model: String) {
        viewModelScope.launch {
            _testing.update { it + model }
            val result = modelApiService.testModel(provider.baseUrl, provider.firstUsableApiKey, provider.type, provider.useFullUrl, provider.useResponseApi, model, provider.customHeaders)
            _testResults.update { it + (model to result) }
            _testing.update { it - model }
        }
    }

    fun clearTestResults() {
        _testResults.value = emptyMap()
        _testing.value = emptySet()
    }

    /**
     * Load the list of available dashboard scripts (called when opening the script picker). Enumerated via the engine on the IO thread,
     * in remote mode that is the remote `~/.aicode/scripts`, avoiding SFTP enumeration on the main thread.
     */
    fun loadDashboardScripts() {
        viewModelScope.launch {
            _dashboardScripts.value = withContext(Dispatchers.IO) {
                providerDashboardRunner.listAvailableScripts()
            }
        }
    }

    fun testDashboardScript(provider: AIProviderConfig, scriptPath: String) {
        viewModelScope.launch {
            _dashboardTestState.value = ProviderDashboardState.Loading
            providerDashboardRunner.runScript(provider, scriptPath)
                .onSuccess { result ->
                    _dashboardTestState.value = ProviderDashboardState.Success(result)
                }
                .onFailure { error ->
                    _dashboardTestState.value = ProviderDashboardState.Error(
                        error.message ?: context.getString(R.string.dashboard_exec_failed),
                        error.localizedMessage ?: ""
                    )
                }
        }
    }

    fun clearDashboardTestState() {
        _dashboardTestState.value = ProviderDashboardState.Idle
    }

    fun refreshProviderDashboard(
        provider: AIProviderConfig,
        context: DashboardContext? = null,
        force: Boolean = false
    ) {
        if (provider.dashboardScriptPath.isBlank()) {
            _providerDashboards.update { it - provider.id }
            return
        }
        val current = _providerDashboards.value[provider.id]
        if (!force && current is ProviderDashboardState.Success) return
        if (current is ProviderDashboardState.Loading) return

        // When there is already Success, do not switch to Loading, to avoid UI height collapsing then recovering (collapse->expand flicker)
        val showLoading = current !is ProviderDashboardState.Success
        viewModelScope.launch {
            if (showLoading) {
                _providerDashboards.update { it + (provider.id to ProviderDashboardState.Loading) }
            }
            providerDashboardRunner.runScript(provider, context = context)
                .onSuccess { result ->
                    _providerDashboards.update { it + (provider.id to ProviderDashboardState.Success(result)) }
                }
                .onFailure { error ->
                    // Function parameter context: DashboardContext shadows the class's ApplicationContext;
                    // and here inside launch{}, this has become CoroutineScope, so it must be qualified with the class label
                    _providerDashboards.update { it + (provider.id to ProviderDashboardState.Error(error.message ?: this@SettingsViewModel.context.getString(R.string.dashboard_query_failed))) }
                }
        }
    }

    fun selectModel(providerId: String, model: String) {
        viewModelScope.launch {
            repository.setSelectedModel(providerId, model)
        }
    }

    fun deleteGlobalRule(rule: PermissionRule) {
        viewModelScope.launch { permissionRulesRepository.removeGlobalRule(rule) }
    }

    fun deleteProjectRule(rule: PermissionRule) {
        val name = currentProjectName.value ?: return
        viewModelScope.launch { permissionRulesRepository.removeProjectRule(name, rule) }
    }

    fun promoteRuleToGlobal(rule: PermissionRule) {
        val name = currentProjectName.value ?: return
        viewModelScope.launch { permissionRulesRepository.promoteToGlobal(name, rule) }
    }

    fun setDisableSafetyInterception(disabled: Boolean) {
        viewModelScope.launch { toolSafetySettingsRepository.setDisableSafetyInterception(disabled) }
    }

    /** Switch the command authorization level. */
    fun setCommandAuthLevel(level: CommandAuthLevel) {
        viewModelScope.launch { toolSafetySettingsRepository.setCommandAuthLevel(level) }
    }

    /** Add a user-created high-risk interception rule. */
    fun addHighRiskRule(rule: HighRiskRule) {
        viewModelScope.launch { highRiskRulesRepository.addRule(rule) }
    }

    /** Update a high-risk interception rule (edit content or toggle). */
    fun updateHighRiskRule(rule: HighRiskRule) {
        viewModelScope.launch { highRiskRulesRepository.updateRule(rule) }
    }

    fun removeHighRiskRule(id: String) {
        viewModelScope.launch { highRiskRulesRepository.removeRule(id) }
    }

    /** Restore high-risk rule presets (user-created rules are kept). */
    fun resetHighRiskRules() {
        viewModelScope.launch { highRiskRulesRepository.resetToPresets() }
    }
}

/** Read the announcement md (zh uses the Chinese version, the rest the English version); when following system language, judge by system language. Returns empty string on read failure (empty content does not pop up). */
private fun loadContainerAnnouncement(context: Context, languageTag: String?): String {
    val lang = languageTag?.takeIf { it.isNotBlank() } ?: java.util.Locale.getDefault().language
    val zh = lang.startsWith("zh", ignoreCase = true)
    return runCatching {
        context.assets.open("announcements/container-guide.${if (zh) "zh" else "en"}.md")
            .bufferedReader().use { it.readText() }
    }.getOrDefault("")
}

/** Read the 'Prompts' page announcement md (zh uses the Chinese version, the rest the English version); returns empty string on read failure (empty content does not pop up). */
private fun loadPromptsAnnouncement(context: Context, languageTag: String?): String {
    val lang = languageTag?.takeIf { it.isNotBlank() } ?: java.util.Locale.getDefault().language
    val zh = lang.startsWith("zh", ignoreCase = true)
    return runCatching {
        context.assets.open("announcements/prompts-guide.${if (zh) "zh" else "en"}.md")
            .bufferedReader().use { it.readText() }
    }.getOrDefault("")
}

/** SHA-256 hexadecimal digest, used for announcement content comparison: when content updates the hash changes, and it automatically pops up again. */
private fun sha256(text: String): String =
    java.security.MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray())
        .joinToString("") { "%02x".format(it) }

/** Whether the file name's extension (lowercase, without dot) is in the [exts] whitelist. */
private fun String.hasExtension(exts: Set<String>): Boolean {
    val dot = lastIndexOf('.')
    if (dot < 0 || dot == length - 1) return false
    return substring(dot + 1).lowercase() in exts
}
