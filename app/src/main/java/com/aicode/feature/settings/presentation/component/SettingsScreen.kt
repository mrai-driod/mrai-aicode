package com.aicode.feature.settings.presentation.component

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicode.core.theme.AppThemePreset
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.isDynamicColorSupported
import com.aicode.core.theme.semanticColors
import com.aicode.core.ui.pageEnter
import com.aicode.core.ui.pageExit
import com.aicode.core.util.LogLevel
import com.aicode.R
import com.aicode.feature.agent.domain.mcp.McpServerEntry
import com.aicode.feature.agent.domain.mcp.McpServerConfig
import com.aicode.feature.agent.domain.prompt.PromptFragment
import com.aicode.feature.agent.domain.prompt.PromptFragmentSource
import com.aicode.feature.agent.domain.mcp.McpServerStatus
import com.aicode.feature.agent.presentation.component.MarkdownContent
import com.aicode.feature.agent.presentation.component.MarkdownRenderCache
import com.aicode.feature.backup.presentation.BackupSection
import com.aicode.feature.settings.data.repository.AppThemeMode
import com.aicode.feature.settings.data.repository.BackgroundSettingsRepository
import com.aicode.feature.settings.domain.model.AIProviderConfig
import com.aicode.feature.settings.domain.model.ModelMetadata
import com.aicode.feature.settings.presentation.SettingsViewModel
import com.aicode.feature.settings.presentation.ShizukuViewModel
import com.aicode.feature.settings.presentation.SkillImportState
import com.aicode.feature.settings.presentation.SkillUiEntry
import com.aicode.feature.agent.domain.skill.SkillImportError
import com.aicode.feature.agent.domain.skill.SkillScope
import com.aicode.feature.settings.presentation.SubAgentUiEntry
import compose.icons.FeatherIcons
import compose.icons.feathericons.Activity
import compose.icons.feathericons.ArrowLeft
import compose.icons.feathericons.BarChart2
import compose.icons.feathericons.Book
import compose.icons.feathericons.BookOpen
import compose.icons.feathericons.Box
import compose.icons.feathericons.Cloud
import compose.icons.feathericons.Cpu
import compose.icons.feathericons.Download
import compose.icons.feathericons.FileText
import compose.icons.feathericons.Globe
import compose.icons.feathericons.HardDrive
import compose.icons.feathericons.Image
import compose.icons.feathericons.Info
import compose.icons.feathericons.Lock
import compose.icons.feathericons.Moon
import compose.icons.feathericons.MessageSquare
import compose.icons.feathericons.Edit2
import compose.icons.feathericons.PieChart
import compose.icons.feathericons.Plus
import compose.icons.feathericons.RefreshCw
import compose.icons.feathericons.Save
import compose.icons.feathericons.Server
import compose.icons.feathericons.Shield
import compose.icons.feathericons.Sliders
import compose.icons.feathericons.Terminal
import compose.icons.feathericons.Trash2
import compose.icons.feathericons.Users
import compose.icons.feathericons.Zap
import com.aicode.feature.onboarding.domain.OnboardingStep
import com.aicode.feature.onboarding.presentation.onboardingTarget
import com.aicode.feature.settings.data.local.ProviderPreset
import com.aicode.feature.terminal.data.repository.TerminalSettings
import com.aicode.feature.terminal.presentation.component.TerminalSettingsSheet

/** Online documentation site address for the user guide. */
private const val USER_GUIDE_DOCS_URL = "https://github.com/mrai-driod/mrai-aicode/tree/main/docs-site/docs"

/** Width of the persistent left menu on the large-screen settings page. */
private val SettingsMenuWidth = 320.dp

/**
 * The minimum width of the settings page **itself** required for the two-pane layout.
 *
 * Cannot be judged directly from the window width: on large screens there is also a persistent session sidebar to the left of the settings page, so calculating from the window
 * would fan out to three panes on a small tablet (about 840dp), leaving less than 200dp for the detail area, which is completely unusable.
 */
private val SettingsTwoPaneMinWidth = 840.dp

/** Internal secondary menu sections of the settings page. Menu is the home menu, the rest are their respective secondary pages. */
internal enum class SettingsSection(@param:StringRes val titleRes: Int) {
    Menu(R.string.settings_title),
    General(R.string.settings_general),
    Providers(R.string.settings_providers),
    ProviderEditor(R.string.settings_provider_editor),
    DefaultModels(R.string.settings_default_models),
    ContextWindow(R.string.settings_context_window),
    Mcp(R.string.settings_mcp),
    Skills(R.string.settings_skills),
    Prompts(R.string.prompts_title),
    PromptDetail(R.string.prompts_title),
    PromptEditor(R.string.prompts_title),
    SkillDetail(R.string.settings_skills),
    SkillEditor(R.string.settings_skills),
    SubAgents(R.string.settings_subagents),
    SubAgentDetail(R.string.settings_subagents),
    SubAgentEditor(R.string.settings_subagents),
    ExecutionChannel(R.string.settings_execution_channel),
    Container(R.string.settings_container),
    ContainerDownloads(R.string.container_download_image),
    Proxy(R.string.proxy_title),
    Log(R.string.settings_log),
    Permissions(R.string.settings_permissions),
    AppPermissions(R.string.settings_app_permissions),
    BackgroundRun(R.string.settings_category_background),
    RemoteServers(R.string.settings_remote_servers),
    Storage(R.string.settings_storage),
    TokenStats(R.string.settings_token_stats_title),
    PerfPanel(R.string.perf_panel_title),
    Backup(R.string.settings_backup),
    About(R.string.settings_about)
}

/**
 * Section hierarchy: 0 home menu, 1 first-level section, 2 detail / edit page one level deeper inside a section, 3 edit page on top of a detail page.
 *
 * Only used to determine the transition direction (shallower counts as back). The parent of [SettingsSection.Log] is dynamic (see logReturnSection),
 * uniformly counted as level 2: entering it directly from the menu is still a 0 → 2 "forward", so the direction will not reverse.
 */
private fun SettingsSection.depth(): Int = when (this) {
    SettingsSection.Menu -> 0
    // Skill/sub-agent edit pages can be entered from both the list (1) and the detail page (2), so they must be deeper than the detail page:
    // Same depth would make "edit → detail" also count as forward, and when returning the page would slide in from the right, which is the wrong direction.
    SettingsSection.SkillEditor,
    SettingsSection.SubAgentEditor,
    SettingsSection.PromptEditor -> 3
    SettingsSection.ProviderEditor,
    SettingsSection.PromptDetail,
    SettingsSection.SkillDetail,
    SettingsSection.SubAgentDetail,
    SettingsSection.ContainerDownloads,
        SettingsSection.ContextWindow,
        SettingsSection.PerfPanel,
        SettingsSection.Log -> 2
    else -> 1
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onNavigateBack: () -> Unit,
    onStopAllAndCloseTerminal: () -> Unit = {},
    onRerunOnboarding: () -> Unit = {},
    onboardingStep: OnboardingStep? = null,
    onOnboardingModelAdded: (() -> Unit)? = null,
    onOnboardingDismissFetchDialog: (() -> Unit)? = null
) {
    val providers by viewModel.providers.collectAsStateWithLifecycle()
    val logLevel by viewModel.logLevel.collectAsStateWithLifecycle()
    val logViewerState by viewModel.logViewerState.collectAsStateWithLifecycle()
    val mcpEntries by viewModel.mcpEntries.collectAsStateWithLifecycle()
    val mcpStatuses by viewModel.mcpStatuses.collectAsStateWithLifecycle()
    val mcpReloading by viewModel.mcpReloading.collectAsStateWithLifecycle()
    val skills by viewModel.skills.collectAsStateWithLifecycle()
    val skillSaveState by viewModel.skillSaveState.collectAsStateWithLifecycle()
    val skillImportState by viewModel.skillImportState.collectAsStateWithLifecycle()
    val skillDeleting by viewModel.skillDeleting.collectAsStateWithLifecycle()
    val subAgents by viewModel.subAgents.collectAsStateWithLifecycle()
    val subAgentSaveState by viewModel.subAgentSaveState.collectAsStateWithLifecycle()
    val subAgentDeleting by viewModel.subAgentDeleting.collectAsStateWithLifecycle()
    val globalRules by viewModel.globalRules.collectAsStateWithLifecycle()
    val projectRules by viewModel.projectRules.collectAsStateWithLifecycle()
    val currentProjectName by viewModel.currentProjectName.collectAsStateWithLifecycle()
    val disableSafetyInterception by viewModel.disableSafetyInterception.collectAsStateWithLifecycle()
    val commandAuthLevel by viewModel.commandAuthLevel.collectAsStateWithLifecycle()
    val highRiskRules by viewModel.highRiskRules.collectAsStateWithLifecycle()
    val executionMode by viewModel.executionMode.collectAsStateWithLifecycle()
    val rootProbeState by viewModel.rootProbeState.collectAsStateWithLifecycle()
    val keepaliveEnabled by viewModel.keepaliveEnabled.collectAsStateWithLifecycle()
    val screenOnEnabled by viewModel.screenOnEnabled.collectAsStateWithLifecycle()
    val agentSoundEnabled by viewModel.agentSoundEnabled.collectAsStateWithLifecycle()
    val autoRemoveStaleModels by viewModel.autoRemoveStaleModels.collectAsStateWithLifecycle()
    val startupSessionMode by viewModel.startupSessionMode.collectAsStateWithLifecycle()
    val firstByteTimeoutSec by viewModel.firstByteTimeoutSec.collectAsStateWithLifecycle()
    val streamIdleTimeoutSec by viewModel.streamIdleTimeoutSec.collectAsStateWithLifecycle()
    val maxNetworkRetries by viewModel.maxNetworkRetries.collectAsStateWithLifecycle()
    val enterToSend by viewModel.enterToSend.collectAsStateWithLifecycle()
    val compactionThresholdPercent by viewModel.compactionThresholdPercent.collectAsStateWithLifecycle()
    val sendFileMaxSizeMb by viewModel.sendFileMaxSizeMb.collectAsStateWithLifecycle()
    val deleteExternalWorkspaceSessions by viewModel.deleteExternalWorkspaceSessions.collectAsStateWithLifecycle()
    val todoDisplayPosition by viewModel.todoDisplayPosition.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val themePresetId by viewModel.themePresetId.collectAsStateWithLifecycle()
    val dynamicColorEnabled by viewModel.dynamicColorEnabled.collectAsStateWithLifecycle()
    val backgroundImagePath by viewModel.backgroundImagePath.collectAsStateWithLifecycle()
    val backgroundAlpha by viewModel.backgroundAlpha.collectAsStateWithLifecycle()
    val languageTag by viewModel.languageTag.collectAsStateWithLifecycle()
    val visionProviderId by viewModel.visionProviderId.collectAsStateWithLifecycle()
    val visionModel by viewModel.visionModel.collectAsStateWithLifecycle()
    val compactionProviderId by viewModel.compactionProviderId.collectAsStateWithLifecycle()
    val compactionModel by viewModel.compactionModel.collectAsStateWithLifecycle()
    val titleProviderId by viewModel.titleProviderId.collectAsStateWithLifecycle()
    val titleModel by viewModel.titleModel.collectAsStateWithLifecycle()
    val imageGenProviderId by viewModel.imageGenProviderId.collectAsStateWithLifecycle()
    val imageGenModel by viewModel.imageGenModel.collectAsStateWithLifecycle()
    val reviewerProviderId by viewModel.reviewerProviderId.collectAsStateWithLifecycle()
    val reviewerModel by viewModel.reviewerModel.collectAsStateWithLifecycle()
    val contextWindowOverride by viewModel.contextWindowOverride.collectAsStateWithLifecycle()
    val modelMetadata by viewModel.modelMetadata.collectAsStateWithLifecycle()
    val containerProfiles by viewModel.profiles.collectAsStateWithLifecycle()
    val activeProfileId by viewModel.activeProfileId.collectAsStateWithLifecycle()
    val defaultContainerId by viewModel.defaultContainerId.collectAsStateWithLifecycle()
    val containerOsMap by viewModel.containerOsMap.collectAsStateWithLifecycle()
    val remoteConnections by viewModel.remoteConnections.collectAsStateWithLifecycle()
    val tokenStats by viewModel.tokenStats.collectAsStateWithLifecycle()
    val updateCheckEnabled by viewModel.updateCheckEnabled.collectAsStateWithLifecycle()
    val updateCheckChannel by viewModel.updateCheckChannel.collectAsStateWithLifecycle()
    val containerAnnouncementText by viewModel.containerAnnouncementText.collectAsStateWithLifecycle()
    val containerAnnouncementOutdated by viewModel.containerAnnouncementOutdated.collectAsStateWithLifecycle()
    val promptsAnnouncementText by viewModel.promptsAnnouncementText.collectAsStateWithLifecycle()
    val promptsAnnouncementOutdated by viewModel.promptsAnnouncementOutdated.collectAsStateWithLifecycle()
    val imageCatalog by viewModel.imageCatalog.collectAsStateWithLifecycle()
    val imageDownload by viewModel.containerImageDownload.collectAsStateWithLifecycle()
    val containerReset by viewModel.containerReset.collectAsStateWithLifecycle()
    val imageSourceOptions by viewModel.imageSourceOptions.collectAsStateWithLifecycle()
    val selectedImageSource by viewModel.selectedImageSource.collectAsStateWithLifecycle()
    val downloadedImages by viewModel.downloadedImages.collectAsStateWithLifecycle()
    val sourceUnavailableIds by viewModel.sourceUnavailableIds.collectAsStateWithLifecycle()
    val terminalSettings by viewModel.terminalSettings.collectAsStateWithLifecycle()
    val proxyConfig by viewModel.proxyConfig.collectAsStateWithLifecycle()
    val proxyTestState by viewModel.proxyTestState.collectAsStateWithLifecycle()
    var showTerminalSettingsSheet by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val currentLanguageDisplayName = if (languageTag.isNullOrBlank()) {
        stringResource(R.string.language_follow_system)
    } else {
        com.aicode.core.util.LanguageRegistry.languages.firstOrNull { it.tag == languageTag }?.displayName
            ?: stringResource(R.string.language_follow_system)
    }


    var section by remember { mutableStateOf(SettingsSection.Menu) }
    var logReturnSection by remember { mutableStateOf(SettingsSection.Menu) }
    var editingProvider by remember { mutableStateOf<AIProviderConfig?>(null) }
    var showAddProviderSheet by remember { mutableStateOf(false) }
    var providerPresetPrefill by remember { mutableStateOf<ProviderPreset?>(null) }
    var showMcpDialog by remember { mutableStateOf(false) }
    var editingMcp by remember { mutableStateOf<McpServerEntry?>(null) }

    LaunchedEffect(onboardingStep) {
        when (onboardingStep) {
            OnboardingStep.CONFIG_PROVIDER -> {
                section = SettingsSection.Menu
            }
            OnboardingStep.PROVIDER_ADD -> {
                section = SettingsSection.Providers
            }
            OnboardingStep.PROVIDER_CONFIG_INFO -> {
                if (section != SettingsSection.ProviderEditor) {
                    val deepseek = providers.firstOrNull { it.name.contains("deepseek", ignoreCase = true) }
                        ?: providers.firstOrNull()
                    editingProvider = deepseek
                    if (deepseek == null && providerPresetPrefill == null) {
                        providerPresetPrefill = ProviderPreset(
                            id = "deepseek",
                            name = "DeepSeek",
                            type = "DEEPSEEK",
                            baseUrl = "https://api.deepseek.com",
                            models = emptyList<String>()
                        )
                    }
                    section = SettingsSection.ProviderEditor
                }
            }
            OnboardingStep.PROVIDER_FETCH_MODELS,
            OnboardingStep.SIMULATE_FETCH_DIALOG -> {
                if (section != SettingsSection.ProviderEditor) {
                    val deepseek = providers.firstOrNull { it.name.contains("deepseek", ignoreCase = true) }
                        ?: providers.firstOrNull()
                    editingProvider = deepseek
                    if (deepseek == null && providerPresetPrefill == null) {
                        providerPresetPrefill = ProviderPreset(
                            id = "deepseek",
                            name = "DeepSeek",
                            type = "DEEPSEEK",
                            baseUrl = "https://api.deepseek.com",
                            models = emptyList<String>()
                        )
                    }
                    section = SettingsSection.ProviderEditor
                }
            }
            else -> {}
        }
    }
    var selectedSkill by remember { mutableStateOf<SkillUiEntry?>(null) }
    var skillToDelete by remember { mutableStateOf<SkillUiEntry?>(null) }

    // Custom prompts: visibility of the "+" sheet in the top-right corner + edit target (new/edit/pinned fragment)
    var showPromptsAddSheet by remember { mutableStateOf(false) }
    var promptEditTarget by remember { mutableStateOf<PromptEditTarget?>(null) }
    var selectedPrompt by remember { mutableStateOf<PromptFragment?>(null) }
    var showPromptsAnnouncement by remember { mutableStateOf(false) }
    // Edit page return target: if entered from detail, return to detail; if entered from the list "+", return to the list.
    var promptEditorReturn by remember { mutableStateOf(SettingsSection.Prompts) }
    // Skill edit target: null means create a new one; when editing an existing skill, points to the entry being edited.
    var editingSkill by remember { mutableStateOf<SkillUiEntry?>(null) }
    // Return target of the edit page: if entered from the detail page, return to the detail page; if entered from the "+" in the list top bar, return to the list.
    var skillEditorReturn by remember { mutableStateOf(SettingsSection.Skills) }
    // Name of the skill to show on the detail page after saving: the list refresh is asynchronous, so remember the name first and swap the snapshot once the refresh completes.
    var pendingSkillName by remember { mutableStateOf<String?>(null) }
    // "Add Skill" bottom sheet: after selecting the scope, proceed with manual creation / file import / archive import.
    var showSkillAddSheet by remember { mutableStateOf(false) }
    var skillImportScope by remember { mutableStateOf(SkillScope.GLOBAL) }
    // Skill file / archive picker: the result is handed to the ViewModel to read and save to the selected scope.
    val skillFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importSkillFromMarkdown(uri, skillImportScope)
    }
    val skillZipLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importSkillsFromZip(uri, skillImportScope)
    }
    var selectedSubAgent by remember { mutableStateOf<SubAgentUiEntry?>(null) }
    var subAgentToDelete by remember { mutableStateOf<SubAgentUiEntry?>(null) }
    // Sub-agent edit target: null means create a new one; when editing an existing definition, points to the entry being edited.
    var editingSubAgent by remember { mutableStateOf<SubAgentUiEntry?>(null) }
    // Return target of the edit page: if entered from the detail page, return to the detail page; if entered from the "+" in the list top bar, return to the list.
    var subAgentEditorReturn by remember { mutableStateOf(SettingsSection.SubAgents) }
    // Name of the sub-agent to show on the detail page after saving: the list refresh is asynchronous, so remember the name first and swap the snapshot once the refresh completes.
    var pendingSubAgentName by remember { mutableStateOf<String?>(null) }
    // Markdown parsing cache for sub-agent prompts: same rationale as skill details, to avoid repeated parsing jank
    val subAgentMarkdownCache = remember { MarkdownRenderCache() }
    // Markdown parsing cache for skill body text: reused across multiple visits to the detail page, to avoid repeated parsing jank
    val skillMarkdownCache = remember { MarkdownRenderCache() }
    var showContainerAddSheet by remember { mutableStateOf(false) }
    var showContainerAnnouncement by remember { mutableStateOf(false) }
    var showImageSourceSheet by remember { mutableStateOf(false) }
    var showThemeSheet by remember { mutableStateOf(false) }
    var showBackgroundSheet by remember { mutableStateOf(false) }
    var showLanguageSheet by remember { mutableStateOf(false) }
    var showResetTokenStats by remember { mutableStateOf(false) }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
    val expanded = maxWidth >= SettingsTwoPaneMinWidth

    // Return target: null means there is no parent level, so the back action is handed back to the upper navigation (exit the settings page).
    // On large screens the left menu is persistent, and first-level section pages have no "parent level" — returning to the menu would only give an empty placeholder.
    // The back key and the top bar back arrow share this single determination, instead of each having its own (previously the two were inconsistent, and returning from the image download page would jump straight back to the menu).
    val parentSection: SettingsSection? = when (section) {
        SettingsSection.Menu -> null
        SettingsSection.ProviderEditor -> SettingsSection.Providers
        SettingsSection.Log -> logReturnSection.takeUnless { expanded && it == SettingsSection.Menu }
        SettingsSection.SkillDetail -> SettingsSection.Skills
        SettingsSection.PromptEditor -> promptEditorReturn
        SettingsSection.PromptDetail -> SettingsSection.Prompts
        SettingsSection.SkillEditor -> skillEditorReturn
        SettingsSection.SubAgentDetail -> SettingsSection.SubAgents
        SettingsSection.SubAgentEditor -> subAgentEditorReturn
        SettingsSection.ContainerDownloads -> SettingsSection.Container
        else -> if (expanded) null else SettingsSection.Menu
    }

    // When there is a parent level, the system back key returns to the parent level first; otherwise it is handed back to the upper navigation.
    BackHandler(enabled = parentSection != null) {
        parentSection?.let { section = it }
    }

    // settingsViewModel is an Activity-level shared instance; rescan skills every time the settings page is entered, to reflect additions, deletions, and changes on disk.
    LaunchedEffect(Unit) {
        viewModel.refreshSkills()
        viewModel.refreshSubAgents()
    }

    // After saving from the editor, return to the detail page: wait for the list to refresh with a new snapshot before swapping, to avoid the detail page staying on the pre-save value (when renamed, look up by the new name).
    LaunchedEffect(skills, pendingSkillName) {
        val target = pendingSkillName ?: return@LaunchedEffect
        skills.firstOrNull { it.name.equals(target, ignoreCase = true) }?.let { fresh ->
            selectedSkill = fresh
            pendingSkillName = null
        }
    }

    // After saving from the editor, return to the detail page: wait for the list to refresh with a new snapshot before swapping, to avoid the detail page staying on the pre-save value (when renamed, look up by the new name).
    LaunchedEffect(subAgents, pendingSubAgentName) {
        val target = pendingSubAgentName ?: return@LaunchedEffect
        subAgents.firstOrNull { it.name.equals(target, ignoreCase = true) }?.let { fresh ->
            selectedSubAgent = fresh
            pendingSubAgentName = null
        }
    }

    // When entering the "Container & Images" page for the first time (or after the announcement content is updated), automatically show the usage instructions; the hash comparison is done in the ViewModel.
    LaunchedEffect(section, containerAnnouncementOutdated) {
        if (section == SettingsSection.Container && containerAnnouncementOutdated && containerAnnouncementText.isNotBlank()) {
            showContainerAnnouncement = true
        }
    }

    // When entering the "Prompts" page for the first time (or after the announcement content is updated), automatically show the usage announcement; the hash comparison is done in the ViewModel.
    LaunchedEffect(section, promptsAnnouncementOutdated) {
        if (section == SettingsSection.Prompts && promptsAnnouncementOutdated && promptsAnnouncementText.isNotBlank()) {
            showPromptsAnnouncement = true
        }
    }

    // Menu content: used as the home page in a narrow window and as the persistent left column on large screens, sharing the same instance.
    // The scroll position must be hoisted and held outside AnimatedContent: when the menu page is switched away as a section branch it is disposed,
    // and the internal rememberScrollState is discarded with it, so returning from a secondary page would jump back to the top.
    val menuScrollState = rememberScrollState()
    val menuBody: @Composable () -> Unit = {
        SettingsMenu(
            scrollState = menuScrollState,
            themeMode = themeMode,
            themePresetId = themePresetId,
            dynamicColorEnabled = dynamicColorEnabled,
            terminalSettings = terminalSettings,
            currentLanguageDisplayName = currentLanguageDisplayName,
            backgroundImagePath = backgroundImagePath,
            backgroundAlpha = backgroundAlpha,
            onOpenThemeSheet = { showThemeSheet = true },
            onOpenTerminalSettingsSheet = { showTerminalSettingsSheet = true },
            onOpenBackgroundSheet = { showBackgroundSheet = true },
            onOpenLanguageSheet = { showLanguageSheet = true },
            onOpenManual = {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(USER_GUIDE_DOCS_URL))
                )
            },
            onRerunOnboarding = onRerunOnboarding,
            onOpen = {
                if (it == SettingsSection.Log) {
                    logReturnSection = SettingsSection.Menu
                    viewModel.refreshLogs(filterServerName = null)
                }
                section = it
            }
        )
    }

    Row(modifier = Modifier.fillMaxSize()) {
        // Large screen: the menu stays in the left column, the right column only swaps details, no longer switching the whole page back and forth.
        if (expanded) {
            Column(
                modifier = Modifier
                    .width(SettingsMenuWidth)
                    .fillMaxHeight()
                    .background(settingsPageBackground())
            ) {
                TopAppBar(
                    title = { Text(stringResource(R.string.settings_title)) },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = settingsPageBackground(),
                        titleContentColor = MaterialTheme.colorScheme.onBackground
                    ),
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(
                                FeatherIcons.ArrowLeft,
                                contentDescription = stringResource(R.string.common_back)
                            )
                        }
                    }
                )
                menuBody()
            }
            VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }

        // During the transition the exiting page moves left out of its own area (when sizeTransform is null, AnimatedContent does not clip),
        // and without clipping it would be drawn over the persistent menu on the left side of a large screen.
        Box(modifier = Modifier.weight(1f).clipToBounds()) {
        // Section switching uses a full-page transition (the top bar slides together), rather than an instant cut; the direction is determined by hierarchy depth: entering a deeper level comes from the right, returning comes from the left.
        AnimatedContent(
            targetState = section,
            modifier = Modifier.fillMaxSize(),
            transitionSpec = {
                val forward = targetState.depth() >= initialState.depth()
                // sizeTransform must be null: the old and new content sizes are already the same, and the default size tween would only cause container clipping ghosting.
                ContentTransform(pageEnter(forward), pageExit(forward), sizeTransform = null)
            },
            label = "settings-section"
        ) { current ->
        when {
            // These two pages have their own top bar and cannot be nested inside the Scaffold below (that would create a double top bar): in a narrow window they occupy the full screen, on a large screen they occupy the right column.
            current == SettingsSection.ProviderEditor -> ProviderEditorScreen(
                viewModel = viewModel,
                initialProvider = editingProvider,
                presetPrefill = providerPresetPrefill,
                initialTab = if (onboardingStep == OnboardingStep.PROVIDER_FETCH_MODELS || onboardingStep == OnboardingStep.SIMULATE_FETCH_DIALOG) 1 else 0,
                onboardingStep = onboardingStep,
                onOnboardingModelAdded = onOnboardingModelAdded,
                onOnboardingDismissFetchDialog = onOnboardingDismissFetchDialog,
                onNavigateBack = {
                    section = SettingsSection.Providers
                    providerPresetPrefill = null
                },
                onSave = { provider ->
                    viewModel.saveProvider(provider)
                }
            )

            current == SettingsSection.SkillEditor -> SkillEditorScreen(
                initial = editingSkill,
                saveState = skillSaveState,
                defaultScope = skillImportScope,
                onSave = { form, scope -> viewModel.saveSkill(form, scope, editingSkill?.name) },
                onSaved = { savedName ->
                    viewModel.clearSkillSaveState()
                    // If entered from the detail page, return to the detail page after editing, but wait until the new snapshot is ready before showing it
                    if (skillEditorReturn == SettingsSection.SkillDetail) {
                        pendingSkillName = savedName
                    }
                    section = skillEditorReturn
                },
                onNavigateBack = {
                    viewModel.clearSkillSaveState()
                    section = skillEditorReturn
                }
            )

            current == SettingsSection.SubAgentEditor -> SubAgentEditorScreen(
                initial = editingSubAgent,
                providers = providers,
                modelMetadata = modelMetadata,
                availableTools = remember { viewModel.availableToolNames() },
                saveState = subAgentSaveState,
                onLoadMetadata = { viewModel.loadAllModelMetadata() },
                onSave = { form, scope -> viewModel.saveSubAgent(form, scope, editingSubAgent?.name) },
                onSaved = { savedName ->
                    viewModel.clearSubAgentSaveState()
                    // If entered from the detail page, return to the detail page after editing, but wait until the new snapshot is ready before showing it
                    if (subAgentEditorReturn == SettingsSection.SubAgentDetail) {
                        pendingSubAgentName = savedName
                    }
                    section = subAgentEditorReturn
                },
                onNavigateBack = {
                    viewModel.clearSubAgentSaveState()
                    section = subAgentEditorReturn
                }
            )

            current == SettingsSection.PromptEditor -> {
                val promptsViewModel: com.aicode.feature.settings.presentation.PromptsViewModel =
                    androidx.hilt.navigation.compose.hiltViewModel()
                val promptsState by promptsViewModel.state.collectAsStateWithLifecycle()
                val number = promptEditTarget?.number
                val fragment = number?.let { value -> promptsState.fragments.firstOrNull { it.number == value } }
                PromptEditorScreen(
                    isNew = number == null,
                    initialNumber = number ?: 0,
                    initialTitle = fragment?.title.orEmpty(),
                    initialDescription = fragment?.description.orEmpty(),
                    initialContent = fragment?.body.orEmpty(),
                    initialScope = fragment?.source ?: PromptFragmentSource.GLOBAL,
                    hasWorkspace = promptsState.hasWorkspace,
                    onSave = { savedNumber, title, scope, content ->
                        promptsViewModel.saveFragment(savedNumber, title, scope, content, previousNumber = number)
                        section = promptEditorReturn
                    },
                    onNavigateBack = { section = promptEditorReturn }
                )
            }

            current == SettingsSection.RemoteServers ->
                com.aicode.feature.workspace.presentation.remote.RemoteServerScreen(
                    onNavigateBack = { section = SettingsSection.Menu }
                )

            else -> {
            // The storage page's top bar refresh button and page content must share the same ViewModel, so it is created in this branch;
            // its construction triggers a full disk scan, so it cannot be hoisted to the top level of SettingsScreen (that would scan the disk every time the settings page is entered).
            val storageViewModel: com.aicode.feature.settings.presentation.StorageViewModel? =
                if (current == SettingsSection.Storage) androidx.hilt.navigation.compose.hiltViewModel() else null
            Scaffold(
        containerColor = settingsPageBackground(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (current == SettingsSection.SkillDetail) {
                            selectedSkill?.name ?: stringResource(current.titleRes)
                        } else if (current == SettingsSection.SubAgentDetail) {
                            selectedSubAgent?.name ?: stringResource(current.titleRes)
                        } else if (expanded && current == SettingsSection.Menu) {
                            // The top of the left column on large screens already says "Settings", so the empty state in the right column does not repeat it
                            ""
                        } else {
                            stringResource(current.titleRes)
                        }
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = settingsPageBackground(),
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                ),
                navigationIcon = {
                    // First-level section pages on large screens do not get a back arrow: the left column menu can be switched at any time, and the entry to exit settings is at the top of the left column.
                    if (!expanded || parentSection != null) {
                        IconButton(onClick = {
                            val parent = parentSection
                            if (parent != null) section = parent else onNavigateBack()
                        }) {
                            Icon(FeatherIcons.ArrowLeft, contentDescription = stringResource(R.string.common_back))
                        }
                    }
                },
                actions = {
                    when (current) {
                        SettingsSection.Providers -> IconButton(
                            onClick = {
                                providerPresetPrefill = null
                                showAddProviderSheet = true
                            },
                            modifier = Modifier.onboardingTarget(OnboardingStep.PROVIDER_ADD)
                        ) {
                            Icon(FeatherIcons.Plus, contentDescription = stringResource(R.string.settings_add_provider))
                        }
                        SettingsSection.Mcp -> {
                            IconButton(onClick = { viewModel.reloadMcp() }) {
                                if (mcpReloading) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                } else {
                                    Icon(
                                        FeatherIcons.RefreshCw,
                                        contentDescription = stringResource(R.string.settings_reconnect),
                                        tint = MaterialTheme.colorScheme.onBackground,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                            IconButton(onClick = {
                                editingMcp = null
                                showMcpDialog = true
                            }) {
                                Icon(
                                    FeatherIcons.Plus,
                                    contentDescription = stringResource(R.string.settings_add_mcp_server),
                                    tint = MaterialTheme.colorScheme.onBackground,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                        SettingsSection.Container -> {
                            IconButton(onClick = { showContainerAnnouncement = true }) {
                                Icon(
                                    FeatherIcons.Info,
                                    contentDescription = stringResource(R.string.container_announcement_title),
                                    tint = MaterialTheme.colorScheme.onBackground,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            IconButton(onClick = { section = SettingsSection.ContainerDownloads }) {
                                Icon(
                                    FeatherIcons.Download,
                                    contentDescription = stringResource(R.string.container_download_image),
                                    tint = MaterialTheme.colorScheme.onBackground,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            IconButton(onClick = { showContainerAddSheet = true }) {
                                Icon(FeatherIcons.Plus, contentDescription = stringResource(R.string.container_add_image))
                            }
                        }
                        SettingsSection.ContainerDownloads -> {
                            IconButton(onClick = { showImageSourceSheet = true }) {
                                Icon(
                                    FeatherIcons.Globe,
                                    contentDescription = stringResource(R.string.container_download_current_source_title),
                                    tint = MaterialTheme.colorScheme.onBackground,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                        SettingsSection.Skills -> IconButton(onClick = {
                            showSkillAddSheet = true
                        }) {
                            Icon(
                                FeatherIcons.Plus,
                                contentDescription = stringResource(R.string.skills_add),
                                tint = MaterialTheme.colorScheme.onBackground,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        SettingsSection.SkillDetail -> selectedSkill?.let { entry ->
                            IconButton(onClick = {
                                editingSkill = entry
                                skillEditorReturn = SettingsSection.SkillDetail
                                section = SettingsSection.SkillEditor
                            }) {
                                Icon(
                                    FeatherIcons.Edit2,
                                    contentDescription = stringResource(R.string.skills_edit),
                                    tint = MaterialTheme.colorScheme.onBackground,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        SettingsSection.SubAgents -> IconButton(onClick = {
                            editingSubAgent = null
                            subAgentEditorReturn = SettingsSection.SubAgents
                            section = SettingsSection.SubAgentEditor
                        }) {
                            Icon(
                                FeatherIcons.Plus,
                                contentDescription = stringResource(R.string.subagent_add),
                                tint = MaterialTheme.colorScheme.onBackground,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        SettingsSection.SubAgentDetail -> selectedSubAgent?.let { entry ->
                            IconButton(onClick = {
                                editingSubAgent = entry
                                subAgentEditorReturn = SettingsSection.SubAgentDetail
                                section = SettingsSection.SubAgentEditor
                            }) {
                                Icon(
                                    FeatherIcons.Edit2,
                                    contentDescription = stringResource(R.string.subagent_edit),
                                    tint = MaterialTheme.colorScheme.onBackground,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        SettingsSection.Log -> {
                            IconButton(onClick = { viewModel.refreshLogs() }) {
                                Icon(FeatherIcons.RefreshCw, contentDescription = stringResource(R.string.settings_refresh_logs))
                            }
                        }
                        SettingsSection.TokenStats -> {
                            IconButton(onClick = { showResetTokenStats = true }) {
                                Icon(
                                    FeatherIcons.Trash2,
                                    contentDescription = stringResource(R.string.settings_token_stats_reset),
                                    tint = MaterialTheme.colorScheme.onBackground,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                        SettingsSection.Prompts -> {
                            IconButton(onClick = { showPromptsAnnouncement = true }) {
                                Icon(
                                    FeatherIcons.Info,
                                    contentDescription = stringResource(R.string.prompts_help),
                                    tint = MaterialTheme.colorScheme.onBackground,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            IconButton(onClick = { showPromptsAddSheet = true }) {
                                Icon(
                                    FeatherIcons.Plus,
                                    contentDescription = stringResource(R.string.prompts_add_prompt),
                                    tint = MaterialTheme.colorScheme.onBackground,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                        SettingsSection.PromptDetail -> selectedPrompt?.let { fragment ->
                            IconButton(onClick = {
                                promptEditTarget = PromptEditTarget(fragment.number)
                                promptEditorReturn = SettingsSection.PromptDetail
                                section = SettingsSection.PromptEditor
                            }) {
                                Icon(
                                    FeatherIcons.Edit2,
                                    contentDescription = stringResource(R.string.subagent_edit),
                                    tint = MaterialTheme.colorScheme.onBackground,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        SettingsSection.Storage -> {
                            IconButton(onClick = { storageViewModel?.refresh() }) {
                                Icon(
                                    FeatherIcons.RefreshCw,
                                    contentDescription = stringResource(R.string.storage_refresh),
                                    tint = MaterialTheme.colorScheme.onBackground,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                        else -> {}
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (current) {
                // On large screens the menu is already persistent in the left column; when no section is selected, the right column shows a placeholder hint
                SettingsSection.Menu -> if (expanded) SettingsDetailPlaceholder() else menuBody()
                SettingsSection.General -> GeneralSettingsSection(
                    autoRemoveStaleModels = autoRemoveStaleModels,
                    onToggleAutoRemoveStaleModels = { viewModel.setAutoRemoveStaleModels(it) },
                    startupSessionMode = startupSessionMode,
                    onSelectStartupSessionMode = { viewModel.setStartupSessionMode(it) },
                    firstByteTimeoutSec = firstByteTimeoutSec,
                    onSetFirstByteTimeoutSec = { viewModel.setFirstByteTimeoutSec(it) },
                    streamIdleTimeoutSec = streamIdleTimeoutSec,
                    onSetStreamIdleTimeoutSec = { viewModel.setStreamIdleTimeoutSec(it) },
                    maxNetworkRetries = maxNetworkRetries,
                    onSetMaxNetworkRetries = { viewModel.setMaxNetworkRetries(it) },
                    enterToSend = enterToSend,
                    onToggleEnterToSend = { viewModel.setEnterToSend(it) },
                    compactionThresholdPercent = compactionThresholdPercent,
                    onSetCompactionThresholdPercent = { viewModel.setCompactionThresholdPercent(it) },
                    sendFileMaxSizeMb = sendFileMaxSizeMb,
                    onSetSendFileMaxSizeMb = { viewModel.setSendFileMaxSizeMb(it) },
                    deleteExternalWorkspaceSessions = deleteExternalWorkspaceSessions,
                    onToggleDeleteExternalWorkspaceSessions = { viewModel.setDeleteExternalWorkspaceSessions(it) },
                    todoDisplayPosition = todoDisplayPosition,
                    onSelectTodoDisplayPosition = { viewModel.setTodoDisplayPosition(it) }
                )
                SettingsSection.Providers -> ProvidersSection(
                    providers = providers,
                    onEdit = {
                        editingProvider = it
                        section = SettingsSection.ProviderEditor
                    },
                    onDelete = { viewModel.deleteProvider(it.id) },
                    onReorder = { fromIndex, toIndex ->
                        viewModel.reorderProviders(fromIndex, toIndex)
                    }
                )
                SettingsSection.DefaultModels -> DefaultModelsSection(
                    providers = providers,
                    visionProviderId = visionProviderId,
                    visionModel = visionModel,
                    compactionProviderId = compactionProviderId,
                    compactionModel = compactionModel,
                    titleProviderId = titleProviderId,
                    titleModel = titleModel,
                    imageGenProviderId = imageGenProviderId,
                    imageGenModel = imageGenModel,
                    reviewerProviderId = reviewerProviderId,
                    reviewerModel = reviewerModel,
                    modelMetadata = modelMetadata,
                    onLoadMetadata = { viewModel.loadAllModelMetadata() },
                    onSelectVisionModel = { pid, m -> viewModel.setVisionModel(pid, m) },
                    onClearVisionModel = { viewModel.clearVisionModel() },
                    onSelectCompactionModel = { pid, m -> viewModel.setCompactionModel(pid, m) },
                    onClearCompactionModel = { viewModel.clearCompactionModel() },
                    onSelectTitleModel = { pid, m -> viewModel.setTitleModel(pid, m) },
                    onClearTitleModel = { viewModel.clearTitleModel() },
                    onSelectImageGenModel = { pid, m -> viewModel.setImageGenModel(pid, m) },
                    onClearImageGenModel = { viewModel.clearImageGenModel() },
                    onSelectReviewerModel = { pid, m -> viewModel.setReviewerModel(pid, m) },
                    onClearReviewerModel = { viewModel.clearReviewerModel() },
                    contextWindowEnabled = contextWindowOverride.enabled,
                    onOpenContextWindow = { section = SettingsSection.ContextWindow }
                )
                SettingsSection.Mcp -> McpSection(
                    entries = mcpEntries,
                    statuses = mcpStatuses,
                    reloading = mcpReloading,
                    onReload = { viewModel.reloadMcp() },
                    onToggle = { name, enabled, scope -> viewModel.setMcpServerEnabled(name, enabled, scope) },
                    onEdit = {
                        editingMcp = it
                        showMcpDialog = true
                    },
                    onDelete = { name, scope -> viewModel.deleteMcpServer(name, scope) }
                )
                SettingsSection.Skills -> SkillsSection(
                    projectName = currentProjectName,
                    entries = skills,
                    onDelete = { skillToDelete = it },
                    onOpenDetail = {
                        selectedSkill = it
                        section = SettingsSection.SkillDetail
                    }
                )
                SettingsSection.Prompts -> {
                    val promptsViewModel: com.aicode.feature.settings.presentation.PromptsViewModel =
                        androidx.hilt.navigation.compose.hiltViewModel()
                    val promptsState by promptsViewModel.state.collectAsStateWithLifecycle()
                    LaunchedEffect(Unit) { promptsViewModel.refresh() }
                    PromptsSection(
                        state = promptsState,
                        onOpenFragment = { fragment ->
                            selectedPrompt = fragment
                            section = SettingsSection.PromptDetail
                        },
                        onDeleteFragment = promptsViewModel::deleteFragment,
                        onReorder = promptsViewModel::reorderFragments,
                        onToggleBuiltinDisabled = promptsViewModel::setBuiltinDisabled
                    )
                    if (showPromptsAddSheet) {
                        PromptsAddSheet(
                            onDismiss = { showPromptsAddSheet = false },
                            onAddFragment = {
                                showPromptsAddSheet = false
                                promptEditTarget = PromptEditTarget(null)
                                promptEditorReturn = SettingsSection.Prompts
                                section = SettingsSection.PromptEditor
                            },
                            onHelp = {
                                showPromptsAddSheet = false
                                showPromptsAnnouncement = true
                            }
                        )
                    }
                }
                SettingsSection.PromptDetail -> {
                    val promptsViewModel: com.aicode.feature.settings.presentation.PromptsViewModel =
                        androidx.hilt.navigation.compose.hiltViewModel()
                    val promptsState by promptsViewModel.state.collectAsStateWithLifecycle()
                    selectedPrompt?.let { sel ->
                        promptsState.fragments.firstOrNull { it.number == sel.number }?.let { fragment ->
                            PromptDetailSection(fragment = fragment)
                        }
                    }
                }
                SettingsSection.PromptEditor -> Unit
                SettingsSection.SkillDetail -> selectedSkill?.let { entry ->
                    SkillDetailSection(
                        entry = entry,
                        cache = skillMarkdownCache,
                        onToggle = { enabled ->
                            viewModel.setSkillEnabled(entry.name, enabled, entry.scope)
                            // Synchronously update the detail page snapshot so the switch responds immediately
                            selectedSkill = selectedSkill?.copy(disabled = !enabled)
                        }
                    )
                }
                SettingsSection.SubAgents -> SubAgentsSection(
                    projectName = currentProjectName,
                    entries = subAgents,
                    onDelete = { subAgentToDelete = it },
                    onOpenDetail = {
                        selectedSubAgent = it
                        section = SettingsSection.SubAgentDetail
                    }
                )
                SettingsSection.SubAgentDetail -> selectedSubAgent?.let { entry ->
                    SubAgentDetailSection(
                        entry = entry,
                        cache = subAgentMarkdownCache,
                        onToggle = { enabled ->
                            viewModel.setSubAgentEnabled(entry.name, enabled, entry.scope)
                            // Synchronously update the detail page snapshot so the switch responds immediately
                            selectedSubAgent = selectedSubAgent?.copy(disabled = !enabled)
                        }
                    )
                }
                SettingsSection.ExecutionChannel -> ExecutionChannelSection(
                    executionMode = executionMode,
                    profiles = containerProfiles,
                    activeProfileId = activeProfileId,
                    defaultContainerId = defaultContainerId,
                    rootProbeState = rootProbeState,
                    onSwitchChannel = viewModel::switchExecutionChannel,
                    onProbeRoot = viewModel::probeDeviceRoot
                )
                SettingsSection.Container -> ContainerSection(
                    profiles = containerProfiles,
                    activeProfileId = activeProfileId,
                    defaultContainerId = defaultContainerId,
                    osMap = containerOsMap,
                    showAddSheetExternal = showContainerAddSheet,
                    onDismissAddSheet = { showContainerAddSheet = false },
                    onSelect = { viewModel.setActiveContainerProfile(it) },
                    onSetDefaultContainer = { viewModel.setDefaultContainerId(it) },
                    onSaveCustom = { viewModel.saveCustomContainerProfile(it) },
                    onEditCustom = { viewModel.editCustomContainerProfile(it) },
                    onDeleteProfile = { viewModel.deleteContainerProfile(it) },
                    onSwitchConfirmed = onStopAllAndCloseTerminal,
                    onResetProfile = { profile ->
                        // Reset is as destructive as switching containers: the entire rootfs is deleted, so AI sessions and terminal tabs must all be stopped,
                        // otherwise they will keep reading and writing the directory being deleted.
                        onStopAllAndCloseTerminal()
                        viewModel.resetContainer(profile)
                    },
                    resetState = containerReset,
                    onRestoreBuiltin = { viewModel.restoreBuiltinAlpine() },
                    remoteConnections = remoteConnections
                )
                SettingsSection.ContainerDownloads -> ContainerImageDownloadSection(
                    catalog = imageCatalog,
                    state = imageDownload,
                    downloadedImages = downloadedImages,
                    sourceUnavailableIds = sourceUnavailableIds,
                    selectedSourceName = viewModel.sourceDisplayName(selectedImageSource, languageTag),
                    onDownload = { entry -> viewModel.startContainerImageDownload(entry, selectedImageSource) },
                    onCancel = { viewModel.cancelContainerImageDownload() },
                    onImport = { entryId, fileUri -> viewModel.importDownloadedImage(entryId, fileUri) },
                    onDelete = { entryId -> viewModel.deleteDownloadedImage(entryId) }
                )
                SettingsSection.Proxy -> ProxySection(
                    config = proxyConfig,
                    testState = proxyTestState,
                    onTestProxy = viewModel::testProxy,
                    onSetEnabled = viewModel::setProxyEnabled,
                    onSetType = viewModel::setProxyType,
                    onSetHost = viewModel::setProxyHost,
                    onSetPort = viewModel::setProxyPort,
                    onSetUsername = viewModel::setProxyUsername,
                    onSetPassword = viewModel::setProxyPassword,
                    onSetNoProxy = viewModel::setProxyNoProxy
                )
                SettingsSection.Log -> LogSection(
                    current = logLevel,
                    onSelect = { viewModel.setLogLevel(it) },
                    state = logViewerState,
                    onSelectFile = { viewModel.selectLogFile(it) },
                    onClearFilter = { viewModel.refreshLogs(filterServerName = null) },
                    onRefresh = { viewModel.refreshLogs(silent = true) }
                )
                SettingsSection.Permissions -> PermissionsSection(
                    commandAuthLevel = commandAuthLevel,
                    highRiskRules = highRiskRules,
                    onSelectAuthLevel = { viewModel.setCommandAuthLevel(it) },
                    onToggleHighRiskRule = { rule, enabled -> viewModel.updateHighRiskRule(rule.copy(enabled = enabled)) },
                    onSaveHighRiskRule = { rule, isNew ->
                        if (isNew) viewModel.addHighRiskRule(rule) else viewModel.updateHighRiskRule(rule)
                    },
                    onDeleteHighRiskRule = { viewModel.removeHighRiskRule(it.id) },
                    onResetHighRiskRules = { viewModel.resetHighRiskRules() }
                )
                SettingsSection.ContextWindow -> {
                    val ctxOverride by viewModel.contextWindowOverride.collectAsStateWithLifecycle()
                    ContextWindowSection(
                        state = ctxOverride,
                        onToggleEnabled = { viewModel.setContextWindowOverrideEnabled(it) },
                        onSave = { total, input, output ->
                            viewModel.saveContextWindowOverride(total, input, output)
                        }
                    )
                }
                SettingsSection.AppPermissions -> {
                    val shizukuViewModel: ShizukuViewModel =
                        androidx.hilt.navigation.compose.hiltViewModel()
                    val shizukuState by shizukuViewModel.state.collectAsStateWithLifecycle()
                    AppPermissionsSection(
                        shizukuState = shizukuState,
                        onRequestShizukuPermission = { shizukuViewModel.requestPermission() },
                        onOpenShizuku = { shizukuViewModel.openShizukuApp() },
                        onRefreshShizuku = { shizukuViewModel.refresh() }
                    )
                }
                SettingsSection.BackgroundRun -> BackgroundRunSection(
                    keepaliveEnabled = keepaliveEnabled,
                    onToggleKeepalive = { viewModel.setKeepaliveEnabled(it) },
                    screenOnEnabled = screenOnEnabled,
                    onToggleScreenOn = { viewModel.setScreenOnEnabled(it) },
                    agentSoundEnabled = agentSoundEnabled,
                    onToggleAgentSound = { viewModel.setAgentSoundEnabled(it) }
                )
                SettingsSection.Backup -> {
                    val backupViewModel: com.aicode.feature.backup.presentation.BackupViewModel =
                        androidx.hilt.navigation.compose.hiltViewModel()
                    BackupSection(viewModel = backupViewModel)
                }
                SettingsSection.TokenStats -> TokenStatsSection(
                    state = tokenStats,
                    onSelectPeriod = { viewModel.setTokenStatsPeriod(it) },
                    onSelectPage = { viewModel.setTokenStatsPage(it) },
                    onSelectProviderPage = { viewModel.setProviderStatsPage(it) },
                    onSelectModelPage = { viewModel.setModelStatsPage(it) },
                    onSelectFilterProvider = { viewModel.setFilterProviderId(it) },
                    onSelectFilterModel = { viewModel.setFilterModel(it) },
                    onClearFilters = { viewModel.clearTokenStatsFilters() }
                )
                SettingsSection.Storage -> storageViewModel?.let { StorageSectionHost(viewModel = it) }
                SettingsSection.PerfPanel -> {
                    val perfViewModel: com.aicode.feature.settings.presentation.PerfPanelViewModel =
                        androidx.hilt.navigation.compose.hiltViewModel()
                    PerfPanelSection(viewModel = perfViewModel)
                }
                SettingsSection.ProviderEditor -> {} // Already handled in the early return above
                SettingsSection.SkillEditor -> {} // Already handled in the early return above
                SettingsSection.SubAgentEditor -> {} // Already handled in the early return above
                SettingsSection.RemoteServers -> {} // Already handled in the early return above
                SettingsSection.About -> AboutSection(
                    updateCheckEnabled = updateCheckEnabled,
                    updateCheckChannel = updateCheckChannel,
                    onToggleUpdateCheck = { viewModel.setUpdateCheckEnabled(it) },
                    onSelectChannel = { viewModel.setUpdateCheckChannel(it) },
                    onCheckUpdate = { viewModel.checkUpdate(manual = true) }
                )
            }
        }
    }
        } // End of else branch (storageViewModel scope)
        } // End of detail area when
        } // End of AnimatedContent
        } // End of right column
    } // End of Row

    if (showMcpDialog) {
        McpServerEditDialog(
            initial = editingMcp?.server,
            initialScope = editingMcp?.scope,
            tools = viewModel.getMcpServerTools(editingMcp?.server?.name),
            onRefreshTools = { editingMcp?.let { viewModel.reloadMcpServer(it.server.name) } },
            onOpenLogs = editingMcp?.let { existing ->
                {
                    showMcpDialog = false
                    logReturnSection = SettingsSection.Mcp
                    viewModel.refreshLogs(filterServerName = existing.server.name)
                    section = SettingsSection.Log
                }
            },
            onDismiss = { showMcpDialog = false },
            onSave = { config, scope ->
                viewModel.upsertMcpServer(editingMcp?.server?.name, editingMcp?.scope, config, scope)
                showMcpDialog = false
            }
        )
    }

    if (showAddProviderSheet) {
        ProviderPresetSheet(
            onDismiss = { showAddProviderSheet = false },
            onSelectCustom = {
                showAddProviderSheet = false
                editingProvider = null
                providerPresetPrefill = null
                section = SettingsSection.ProviderEditor
            },
            onSelectOfficial = { preset ->
                showAddProviderSheet = false
                editingProvider = null
                providerPresetPrefill = preset
                section = SettingsSection.ProviderEditor
            }
        )
    }

    if (showSkillAddSheet) {
        SkillAddSheet(
            scope = skillImportScope,
            onScopeChange = { skillImportScope = it },
            onManual = {
                showSkillAddSheet = false
                editingSkill = null
                skillEditorReturn = SettingsSection.Skills
                section = SettingsSection.SkillEditor
            },
            onPickFile = {
                showSkillAddSheet = false
                skillFileLauncher.launch(arrayOf("text/*", "application/octet-stream"))
            },
            onPickZip = {
                showSkillAddSheet = false
                skillZipLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
            },
            onDismiss = { showSkillAddSheet = false }
        )
    }

    if (showThemeSheet) {
        ThemeSelectionSheet(
            selected = themeMode,
            selectedPresetId = themePresetId,
            dynamicColorEnabled = dynamicColorEnabled,
            onSelected = { viewModel.setThemeMode(it) },
            onPresetSelected = { viewModel.setThemePreset(it) },
            onDynamicColorChanged = { viewModel.setDynamicColorEnabled(it) },
            onDismiss = { showThemeSheet = false }
        )
    }

    if (showTerminalSettingsSheet) {
        TerminalSettingsSheet(
            settings = terminalSettings,
            onDismiss = { showTerminalSettingsSheet = false },
            onSelectTheme = { viewModel.setTerminalTheme(it) },
            onChangeFontSize = { viewModel.setTerminalFontSize(it) },
            onChangeCursorStyle = { viewModel.setTerminalCursorStyle(it) },
            onChangeFontPath = { viewModel.setTerminalFontPath(it) }
        )
    }

    if (showBackgroundSheet) {
        BackgroundImageSheet(
            imagePath = backgroundImagePath,
            alpha = backgroundAlpha,
            onPickImage = { viewModel.setBackgroundImage(it) },
            onAlphaChange = { viewModel.setBackgroundAlpha(it) },
            onRemove = { viewModel.clearBackgroundImage() },
            onDismiss = { showBackgroundSheet = false }
        )
    }

    if (showLanguageSheet) {
        LanguageSelectionSheet(
            currentTag = languageTag,
            onSelect = { viewModel.setLanguage(it) },
            onDismiss = { showLanguageSheet = false }
        )
    }

    if (showImageSourceSheet) {
        SourceSelectionSheet(
            options = imageSourceOptions.map { it to viewModel.sourceDisplayName(it, languageTag) },
            selected = selectedImageSource,
            onSelected = { viewModel.setImageSource(it) },
            onDismiss = { showImageSourceSheet = false }
        )
    }

    if (showResetTokenStats) {
        AlertDialog(
            onDismissRequest = { showResetTokenStats = false },
            title = { Text(stringResource(R.string.settings_token_stats_reset_title)) },
            text = { Text(stringResource(R.string.settings_token_stats_reset_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.resetTokenStats()
                    showResetTokenStats = false
                }) { Text(stringResource(R.string.settings_token_stats_reset)) }
            },
            dismissButton = {
                TextButton(onClick = { showResetTokenStats = false }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    skillToDelete?.let { target ->
        val deleting = skillDeleting == target.name
        // Keep the dialog open while deletion is in progress (showing a spinner), and only close it when complete, to avoid it "suddenly disappearing" without feedback.
        var deleteStarted by remember(target) { mutableStateOf(false) }
        LaunchedEffect(deleteStarted, deleting) {
            if (deleteStarted && !deleting) skillToDelete = null
        }
        AlertDialog(
            onDismissRequest = { if (!deleting) skillToDelete = null },
            title = { Text(stringResource(R.string.skills_delete_confirm_title)) },
            text = { Text(stringResource(R.string.skills_delete_confirm_message, target.name)) },
            confirmButton = {
                TextButton(
                    enabled = !deleting,
                    onClick = {
                        deleteStarted = true
                        viewModel.deleteSkill(target.name, target.scope)
                    }
                ) {
                    if (deleting) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text(stringResource(R.string.common_delete))
                    }
                }
            },
            dismissButton = {
                TextButton(enabled = !deleting, onClick = { skillToDelete = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    SkillImportResultDialog(state = skillImportState, onDismiss = { viewModel.clearSkillImportState() })

    subAgentToDelete?.let { target ->
        val deleting = subAgentDeleting == target.name
        var deleteStarted by remember(target) { mutableStateOf(false) }
        LaunchedEffect(deleteStarted, deleting) {
            if (deleteStarted && !deleting) subAgentToDelete = null
        }
        AlertDialog(
            onDismissRequest = { if (!deleting) subAgentToDelete = null },
            title = { Text(stringResource(R.string.subagents_delete_confirm_title)) },
            text = { Text(stringResource(R.string.subagents_delete_confirm_message, target.name)) },
            confirmButton = {
                TextButton(
                    enabled = !deleting,
                    onClick = {
                        deleteStarted = true
                        viewModel.deleteSubAgent(target.name, target.scope)
                    }
                ) {
                    if (deleting) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text(stringResource(R.string.common_delete))
                    }
                }
            },
            dismissButton = {
                TextButton(enabled = !deleting, onClick = { subAgentToDelete = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    // "Container & Images" usage announcement: automatically shown on first entry (or after content is updated), and the Info button in the top-right corner can be used to view it again at any time.
    if (showContainerAnnouncement) {
        val dismiss = {
            showContainerAnnouncement = false
            viewModel.markContainerAnnouncementShown()
        }
        Dialog(onDismissRequest = dismiss) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.72f)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .padding(20.dp)
                ) {
                    Text(
                        text = stringResource(R.string.container_announcement_title),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (containerAnnouncementText.isNotBlank()) {
                        // Inside mikepenz Markdown is a Column (not a LazyColumn), which itself is not scrollable,
                        // so the outer layer must provide a scrolling container, otherwise content exceeding the dialog height is directly clipped.
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .verticalScroll(rememberScrollState())
                                .padding(top = 8.dp, bottom = Spacing.lg)
                        ) {
                            MarkdownContent(
                                text = containerAnnouncementText,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.fillMaxWidth(),
                                loading = {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 24.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        CircularProgressIndicator()
                                    }
                                }
                            )
                        }
                    }
                    Button(
                        onClick = dismiss,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .padding(top = 4.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(stringResource(R.string.container_announcement_got_it))
                    }
                }
            }
        }
    }

    // Prompts usage announcement: automatically shown on first entry (or after content is updated), and the Info button in the top-right corner can be used to view it again at any time.
    if (showPromptsAnnouncement) {
        val dismiss = {
            showPromptsAnnouncement = false
            viewModel.markPromptsAnnouncementShown()
        }
        Dialog(onDismissRequest = dismiss) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.72f)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .padding(20.dp)
                ) {
                    Text(
                        text = stringResource(R.string.prompts_help),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (promptsAnnouncementText.isNotBlank()) {
                        // Inside mikepenz Markdown is a Column (not a LazyColumn), which itself is not scrollable,
                        // so the outer layer must provide a scrolling container, otherwise content exceeding the dialog height is directly clipped.
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .verticalScroll(rememberScrollState())
                                .padding(top = 8.dp, bottom = Spacing.lg)
                        ) {
                            MarkdownContent(
                                text = promptsAnnouncementText,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.fillMaxWidth(),
                                loading = {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 24.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        CircularProgressIndicator()
                                    }
                                }
                            )
                        }
                    }
                    Button(
                        onClick = dismiss,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .padding(top = 4.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(stringResource(R.string.common_got_it))
                    }
                }
            }
        }
    }
    } // End of BoxWithConstraints
}

/** Placeholder hint shown in the right column on large screens when no section is selected. */
@Composable
private fun SettingsDetailPlaceholder() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = stringResource(R.string.settings_select_section_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.semanticColors.subtleText
        )
    }
}

/** Settings home page: one clickable secondary menu entry per section. */
@Composable
internal fun SettingsMenu(
    scrollState: ScrollState,
    themeMode: AppThemeMode,
    themePresetId: String?,
    dynamicColorEnabled: Boolean,
    terminalSettings: TerminalSettings,
    currentLanguageDisplayName: String,
    backgroundImagePath: String?,
    backgroundAlpha: Float,
    onOpenThemeSheet: () -> Unit,
    onOpenTerminalSettingsSheet: () -> Unit,
    onOpenBackgroundSheet: () -> Unit,
    onOpenLanguageSheet: () -> Unit,
    onOpenManual: () -> Unit,
    onRerunOnboarding: () -> Unit,
    onOpen: (SettingsSection) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = Spacing.lg)
            .padding(bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        // ── General Settings ──
        SettingsGroupHeader(text = stringResource(R.string.settings_category_general))
        SettingsGroup {
            SettingsRow(
                icon = FeatherIcons.Sliders,
                title = stringResource(SettingsSection.General.titleRes),
                onClick = { onOpen(SettingsSection.General) }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Moon,
                title = stringResource(R.string.settings_theme_title),
                onClick = onOpenThemeSheet,
                trailing = {
                    val colorLabel = if (dynamicColorEnabled && isDynamicColorSupported) {
                        stringResource(R.string.theme_dynamic_color)
                    } else {
                        stringResource(AppThemePreset.findById(themePresetId).nameRes)
                    }
                    Text(
                        text = "${stringResource(themeMode.labelRes)} · $colorLabel",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.semanticColors.subtleText
                    )
                }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Terminal,
                title = stringResource(R.string.terminal_settings_title),
                onClick = onOpenTerminalSettingsSheet,
                trailing = {
                    Text(
                        text = stringResource(terminalSettings.theme.nameRes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.semanticColors.subtleText
                    )
                }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Image,
                title = stringResource(R.string.settings_background_image),
                onClick = onOpenBackgroundSheet,
                trailing = {
                    Text(
                        text = if (backgroundImagePath != null) "${BackgroundSettingsRepository.alphaToSlider(backgroundAlpha).toInt()}%"
                        else stringResource(R.string.settings_background_image_none),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.semanticColors.subtleText
                    )
                }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Globe,
                title = stringResource(R.string.settings_language),
                onClick = onOpenLanguageSheet,
                trailing = {
                    Text(
                        text = currentLanguageDisplayName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.semanticColors.subtleText
                    )
                }
            )
        }

        // ── AI Configuration ──
        SettingsGroupHeader(text = stringResource(R.string.settings_category_ai))
        SettingsGroup {
            SettingsRow(
                icon = FeatherIcons.Cloud,
                title = stringResource(SettingsSection.Providers.titleRes),
                onClick = { onOpen(SettingsSection.Providers) },
                modifier = Modifier.onboardingTarget(OnboardingStep.CONFIG_PROVIDER)
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Cpu,
                title = stringResource(SettingsSection.DefaultModels.titleRes),
                onClick = { onOpen(SettingsSection.DefaultModels) }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Box,
                title = stringResource(SettingsSection.Mcp.titleRes),
                onClick = { onOpen(SettingsSection.Mcp) }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Book,
                title = stringResource(SettingsSection.Skills.titleRes),
                onClick = { onOpen(SettingsSection.Skills) }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Users,
                title = stringResource(SettingsSection.SubAgents.titleRes),
                onClick = { onOpen(SettingsSection.SubAgents) }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.MessageSquare,
                title = stringResource(SettingsSection.Prompts.titleRes),
                onClick = { onOpen(SettingsSection.Prompts) }
            )
        }

        // ── Runtime Environment ──
        SettingsGroupHeader(text = stringResource(R.string.settings_category_environment))
        SettingsGroup {
            SettingsRow(
                icon = FeatherIcons.Zap,
                title = stringResource(SettingsSection.ExecutionChannel.titleRes),
                onClick = { onOpen(SettingsSection.ExecutionChannel) }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.HardDrive,
                title = stringResource(SettingsSection.Container.titleRes),
                onClick = { onOpen(SettingsSection.Container) }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Globe,
                title = stringResource(SettingsSection.Proxy.titleRes),
                onClick = { onOpen(SettingsSection.Proxy) }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Server,
                title = stringResource(SettingsSection.RemoteServers.titleRes),
                onClick = { onOpen(SettingsSection.RemoteServers) }
            )
        }

        // ── Permissions & Background ──
        SettingsGroupHeader(text = stringResource(R.string.settings_category_permissions))
        SettingsGroup {
            SettingsRow(
                icon = FeatherIcons.Lock,
                title = stringResource(SettingsSection.Permissions.titleRes),
                onClick = { onOpen(SettingsSection.Permissions) }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Shield,
                title = stringResource(SettingsSection.AppPermissions.titleRes),
                onClick = { onOpen(SettingsSection.AppPermissions) }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.RefreshCw,
                title = stringResource(SettingsSection.BackgroundRun.titleRes),
                onClick = { onOpen(SettingsSection.BackgroundRun) }
            )
        }

        // ── Data & Diagnostics ──
        SettingsGroupHeader(text = stringResource(R.string.settings_category_data))
        SettingsGroup {
            SettingsRow(
                icon = FeatherIcons.BarChart2,
                title = stringResource(SettingsSection.TokenStats.titleRes),
                onClick = { onOpen(SettingsSection.TokenStats) }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.PieChart,
                title = stringResource(SettingsSection.Storage.titleRes),
                onClick = { onOpen(SettingsSection.Storage) }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Activity,
                title = stringResource(SettingsSection.PerfPanel.titleRes),
                onClick = { onOpen(SettingsSection.PerfPanel) }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Save,
                title = stringResource(SettingsSection.Backup.titleRes),
                onClick = { onOpen(SettingsSection.Backup) }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.FileText,
                title = stringResource(SettingsSection.Log.titleRes),
                onClick = { onOpen(SettingsSection.Log) }
            )
        }

        // ── Help & About ──
        SettingsGroupHeader(text = stringResource(R.string.settings_category_help))
        SettingsGroup {
            SettingsRow(
                icon = FeatherIcons.BookOpen,
                title = stringResource(R.string.settings_user_guide),
                onClick = onOpenManual
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Zap,
                title = stringResource(R.string.settings_rerun_onboarding),
                onClick = onRerunOnboarding
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Info,
                title = stringResource(SettingsSection.About.titleRes),
                onClick = { onOpen(SettingsSection.About) }
            )
        }
    }

}

/** Import result dialog: shows a spinner while importing; on completion shows the success count and the reason each item was skipped (on overall failure, reports the error directly). */
@Composable
private fun SkillImportResultDialog(state: SkillImportState, onDismiss: () -> Unit) {
    when (state) {
        SkillImportState.Idle -> Unit
        SkillImportState.Running -> AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.skills_import_running)) },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            },
            confirmButton = {}
        )
        is SkillImportState.Done -> {
            val report = state.report
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(stringResource(R.string.skills_import_result_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        val fatal = report.fatal
                        if (fatal != null) {
                            Text(stringResource(fatal.messageRes()))
                        } else {
                            Text(
                                stringResource(
                                    if (report.failures.isEmpty()) R.string.skills_import_success
                                    else R.string.skills_import_partial,
                                    report.imported.size,
                                    report.failures.size
                                )
                            )
                            report.failures.forEach { failure ->
                                Text(
                                    text = stringResource(
                                        R.string.skills_import_failure_line,
                                        failure.name,
                                        stringResource(failure.error.messageRes())
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_got_it)) }
                }
            )
        }
    }
}

private fun SkillImportError.messageRes(): Int = when (this) {
    SkillImportError.INVALID_NAME -> R.string.skills_import_error_invalid_name
    SkillImportError.NAME_CONFLICT -> R.string.skills_import_error_name_conflict
    SkillImportError.EMPTY_CONTENT -> R.string.skills_import_error_empty_content
    SkillImportError.NO_SKILL_FOUND -> R.string.skills_import_error_no_skill
    SkillImportError.INVALID_ARCHIVE -> R.string.skills_import_error_invalid_archive
    SkillImportError.UNSUPPORTED_FILE -> R.string.skills_import_error_unsupported_file
    SkillImportError.IO_FAILED -> R.string.skills_import_error_io
}
