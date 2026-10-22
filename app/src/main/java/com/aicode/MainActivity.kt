package com.aicode

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.aicode.core.theme.AIEditorTheme
import com.aicode.core.theme.AppThemePreset
import com.aicode.core.ui.BetaWatermark
import com.aicode.core.ui.ImageSource
import com.aicode.core.ui.ImageViewerHost
import com.aicode.core.ui.ImageViewerRequest
import com.aicode.core.ui.LocalImageViewer
import com.aicode.core.ui.PAGE_MOTION_MS
import com.aicode.core.ui.VerticalSplitHandle
import com.aicode.core.ui.drawerWidth
import com.aicode.core.ui.isExpandedWidth
import com.aicode.core.ui.pageEnter
import com.aicode.core.ui.pageExit
import com.aicode.core.ui.rememberImageViewerState
import com.aicode.core.ui.rememberViewerDecodeSpec
import com.aicode.feature.agent.presentation.AIAgentViewModel
import com.aicode.feature.agent.presentation.AgentAttachment
import com.aicode.feature.agent.presentation.component.AIChatPanel
import com.aicode.feature.agent.presentation.component.ChatDrawerContent
import com.aicode.feature.agent.presentation.component.chatImageLoader
import com.aicode.feature.agent.presentation.component.openSentAttachment
import com.aicode.feature.editor.presentation.CodeEditorScreen
import com.aicode.feature.browser.presentation.BrowserScreen
import com.aicode.feature.git.presentation.GitViewModel
import com.aicode.feature.credentials.presentation.component.CredentialScreen
import com.aicode.feature.git.presentation.component.GitScreen
import com.aicode.feature.onboarding.data.OnboardingStatus
import com.aicode.feature.onboarding.domain.OnboardingCoordinator
import com.aicode.feature.onboarding.domain.OnboardingStateHolder
import com.aicode.feature.onboarding.domain.OnboardingStep
import com.aicode.feature.onboarding.presentation.LocalOnboardingTargetRegistry
import com.aicode.feature.onboarding.presentation.OnboardingOverlay
import com.aicode.feature.onboarding.presentation.OnboardingTargetRegistry
import com.aicode.feature.settings.data.repository.KeepaliveSettingsRepository
import com.aicode.feature.settings.data.repository.AppThemeMode
import com.aicode.feature.settings.data.repository.BackgroundSettingsRepository
import com.aicode.feature.settings.data.repository.ScreenOnSettingsRepository
import com.aicode.feature.settings.data.repository.ThemeSettingsRepository
import com.aicode.feature.settings.presentation.SettingsViewModel
import com.aicode.feature.settings.presentation.FetchState
import com.aicode.feature.settings.presentation.UpdateCheckUiState
import com.aicode.feature.settings.presentation.component.githubReleaseUrl
import com.aicode.feature.settings.presentation.component.SettingsScreen
import com.aicode.feature.settings.presentation.component.UpdateCheckDialog
import com.aicode.feature.settings.presentation.component.decodeBackgroundBitmap
import com.aicode.feature.settings.presentation.component.openUrl
import com.aicode.feature.settings.presentation.component.settingsPageBackground
import com.aicode.feature.terminal.domain.TerminalKeepaliveService
import com.aicode.feature.terminal.presentation.TerminalViewModel
import com.aicode.feature.terminal.presentation.component.TerminalScreen
import com.aicode.feature.workspace.presentation.WorkspaceViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import com.aicode.R

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /** Used on cold start to restore the persistent keepalive notification in the foreground (startup in App.onCreate may be blocked by background FGS restrictions). */
    @Inject
    lateinit var keepaliveSettings: KeepaliveSettingsRepository

    @Inject
    lateinit var themeSettings: ThemeSettingsRepository

    @Inject
    lateinit var backgroundSettings: BackgroundSettingsRepository

    /** Keep-screen-on toggle: only constrains the screen while the App is in the foreground; automatically released by the system when backgrounded. */
    @Inject
    lateinit var screenOnSettings: ScreenOnSettingsRepository

    /** Language preference: read synchronously in attachBaseContext to apply the locale, recreate on change. */
    @Inject
    lateinit var languageSettings: com.aicode.feature.settings.data.repository.LanguageSettingsRepository

    /** When the App returns to the foreground, in remote mode trigger a reconnect if SSH is disconnected. */
    @Inject
    lateinit var remoteSshConnection: com.aicode.feature.agent.domain.container.RemoteSshConnection

    @Inject
    lateinit var executionModeHolder: com.aicode.feature.settings.data.repository.ExecutionModeHolder

    /** Unified dialog bridge for missing git credentials across all three surfaces (UI/AI Bash/interactive terminal): listens for the helper's file IPC requests after AIEditorApp starts. */
    @Inject
    lateinit var credentialRequestBridge: com.aicode.feature.credentials.data.CredentialRequestBridge

    /** Persistence for first-launch onboarding progress. */
    @Inject
    lateinit var onboardingRepository: com.aicode.feature.onboarding.data.OnboardingRepository

    @Inject
    lateinit var browserManager: com.aicode.feature.agent.domain.tool.browser.BrowserManager

    /** Performance sampler: after being manually enabled in the performance panel, attributes by route module for long-term usage analysis. */
    @Inject
    lateinit var perfMonitor: com.aicode.core.perf.PerfMonitor

    override fun attachBaseContext(newBase: android.content.Context) {
        // Synchronously apply the user-selected language before Activity creation, ensuring it also takes effect on cold start.
        // Hilt is not yet injected, so read synchronously directly from SharedPreferences.
        val tag = newBase.getSharedPreferences("language_prefs_sync", android.content.Context.MODE_PRIVATE)
            .getString("language_tag", null)
        val context = if (tag.isNullOrBlank()) {
            newBase
        } else {
            val config = android.content.res.Configuration(newBase.resources.configuration)
            config.setLocale(java.util.Locale.forLanguageTag(tag))
            newBase.createConfigurationContext(config)
        }
        super.attachBaseContext(context)
    }

    @Suppress("DEPRECATION") // Globally update application resources locale, createConfigurationContext cannot substitute for this
    override fun onCreate(savedInstanceState: Bundle?) {
        // Restore from the cold-start splash theme to the app's main theme
        setTheme(R.style.Theme_AICode)
        // Draw under the system status bar/navigation bar so the app background blends with the system bars (eliminating disjoint color blocks).
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Offscreen WebView hidden host: mount after the first frame to avoid layout traversals during onCreate compounding first-screen jank.
        // The WebView is only truly created when the browser page is first opened; hiddenHost merely provides it an onAttachedToWindow environment, so a 200ms delay has no impact.
        lifecycleScope.launch {
            kotlinx.coroutines.delay(200)
            val hiddenHost = android.widget.FrameLayout(this@MainActivity).apply {
                layoutParams = android.view.ViewGroup.LayoutParams(1, 1)
                alpha = 0.001f
                translationX = -10000f
            }
            findViewById<android.view.ViewGroup>(android.R.id.content)?.addView(hiddenHost)
            browserManager.attachHiddenHost(hiddenHost)
        }
        // Listen for language preference changes, update the Application/Activity locale, then recreate.
        lifecycleScope.launch {
            languageSettings.languageFlow.drop(1).distinctUntilChanged().collect { tag ->
                // Synchronously update the Application context Configuration, ensuring non-Activity sources
                // Resources (such as context.getString in a ViewModel) also get the correct locale.
                val app = applicationContext
                if (tag.isNullOrBlank()) {
                    // Follow system: reset to the system default Configuration
                    val sysConfig = android.content.res.Configuration(resources.configuration)
                    sysConfig.setLocale(java.util.Locale.getDefault())
                    app.resources.updateConfiguration(sysConfig, app.resources.displayMetrics)
                } else {
                    val config = android.content.res.Configuration(app.resources.configuration)
                    config.setLocale(java.util.Locale.forLanguageTag(tag))
                    app.resources.updateConfiguration(config, app.resources.displayMetrics)
                }
                recreate()
            }
        }
        // Keep screen on: the window flag only constrains the screen while the Activity is visible; the system automatically releases it in the background, no manual release needed.
        lifecycleScope.launch {
            screenOnSettings.enabledFlow.collect { enabled ->
                if (enabled) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }
        // API 30+: globally switch to ADJUST_NOTHING, with rememberImeBottomInset() taking over keyboard insets.
        // Must be set uniformly at the Activity level, not saved/restored separately in each composable --
        // During NavHost transition animations old and new pages coexist; the old page's dispose restoring softInputMode would trigger a window relayout causing a white screen.
        if (Build.VERSION.SDK_INT >= 30) {
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        }
        setContent {
            // -- Delayed first-frame loading --
            // On cold start, JIT must compile huge Composables such as AIChatPanel (1916 lines) and ChatInputBar,
            // compilation allocates ~39MB -> triggers a GC storm -> main thread blocked for 150-950ms (measured: all 11 frames janked).
            // First render a lightweight Surface in the same color as the splash (zero JIT overhead), letting JIT compile the hot path on a background thread before switching to heavy content.
            var contentReady by remember { androidx.compose.runtime.mutableStateOf(false) }
            LaunchedEffect(Unit) {
                // Wait for the first frame to finish rendering (splash already visible), then give JIT ~200ms on a background thread to compile hot-path Composables.
                // Measured: cold-start JIT compilation of AIChatPanel+ChatInputBar allocates ~39MB and takes ~500ms;
                // 200ms is enough for JIT to compile most hot methods, so switching to heavy content no longer triggers a main-thread GC storm.
                androidx.compose.runtime.withFrameNanos { }
                kotlinx.coroutines.delay(200)
                contentReady = true
            }

            if (!contentReady) {
                // Lightweight first frame: same color as Theme.AICode.Splash windowBackground, for a visually seamless transition.
                androidx.compose.material3.Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = androidx.compose.ui.graphics.Color(0xFFF9FAFA)
                ) {}
            } else {
            val themeMode by themeSettings.themeModeFlow.collectAsStateWithLifecycle(initialValue = AppThemeMode.AUTO)
            val themePresetId by themeSettings.themePresetIdFlow.collectAsStateWithLifecycle(initialValue = null)
            val dynamicColor by themeSettings.dynamicColorFlow.collectAsStateWithLifecycle(initialValue = false)
            val systemDarkTheme = isSystemInDarkTheme()
            val darkTheme = when (themeMode) {
                AppThemeMode.AUTO -> systemDarkTheme
                AppThemeMode.DARK -> true
                AppThemeMode.LIGHT -> false
            }
            val view = LocalView.current
            SideEffect {
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = !darkTheme
                controller.isAppearanceLightNavigationBars = !darkTheme
            }

            LaunchedEffect(darkTheme) {
                browserManager.setAppDarkTheme(darkTheme)
            }

            AIEditorTheme(
                darkTheme = darkTheme,
                preset = AppThemePreset.findById(themePresetId),
                dynamicColor = dynamicColor
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // Global custom background image: overlaid on top of all page content (watermark effect), with adjustable opacity.
                    // Does not intercept touch events; dialogs (separate Dialog windows) are unaffected by layering.
                    val bgPath by backgroundSettings.imagePathFlow.collectAsStateWithLifecycle(initialValue = null)
                    val bgAlpha by backgroundSettings.alphaFlow.collectAsStateWithLifecycle(initialValue = BackgroundSettingsRepository.DEFAULT_ALPHA)
                    Box(modifier = Modifier.fillMaxSize()) {
                        AppNavigation(
                            onboardingRepository = onboardingRepository,
                            perfMonitor = perfMonitor
                        )
                        // Global credential dialog: covers all pages, so missing command-line git credentials can prompt on any page.
                        com.aicode.feature.credentials.presentation.component.GlobalCredentialDialogHost(
                            bridge = credentialRequestBridge
                        )
                        if (bgPath != null && bgAlpha > 0f) {
                            val screen = LocalView.current
                            val bitmap by produceState<ImageBitmap?>(initialValue = null, bgPath) {
                                value = kotlinx.coroutines.withContext(Dispatchers.IO) {
                                    decodeBackgroundBitmap(
                                        bgPath!!,
                                        screen.width.coerceAtLeast(1),
                                        screen.height.coerceAtLeast(1)
                                    )
                                }
                            }
                            bitmap?.let {
                                Image(
                                    bitmap = it,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .alpha(bgAlpha)
                                )
                            }
                        }
                        // Beta build global watermark, placed on the top layer, does not intercept touches.
                        BetaWatermark()
                    }
                }
            }
            } // else (contentReady)
        }

        // At this point we are in the foreground, so starting a foreground service is always allowed: if the user previously enabled persistent keepalive, restore the notification.
        lifecycleScope.launch {
            if (keepaliveSettings.isEnabled()) {
                TerminalKeepaliveService.enablePersistent(this@MainActivity)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // When returning to the foreground in remote mode, if SSH is disconnected trigger a reconnect immediately without waiting for supervisor polling
        if (executionModeHolder.currentMode() == com.aicode.feature.settings.data.repository.ExecutionMode.REMOTE_SSH) {
            lifecycleScope.launch {
                runCatching { remoteSshConnection.tryReconnectIfDisconnected() }
            }
        }
    }

    override fun onDestroy() {
        browserManager.detachHiddenHost()
        super.onDestroy()
    }

}

/** Default width ratio for the large-screen right pane, and the upper/lower limits for drag-split -- both sides keep at least 30% width. */
private const val DEFAULT_PANE_SPLIT = 0.5f
private const val MIN_PANE_SPLIT = 0.3f
private const val MAX_PANE_SPLIT = 0.7f

/**
 * Root navigation container.
 *
 * [ModalNavigationDrawer] is placed **outside** [NavHost], so the Drawer's lifecycle is independent of page switching.
 *
 * Page transitions uniformly go through [pageEnter] / [pageExit].
 *
 * **Note**: the terminal page hosts a native TerminalView; historically enabling transitions caused 'white screen when tapping the sidebar immediately after exiting the terminal' --
 * at that time old and new pages coexisted during the transition, and the old page's dispose switched softInputMode back to adjustResize, triggering a window relayout,
 * which combined with the Drawer open animation interrupted the rendering pipeline. That path is now guarded by onCreate uniformly setting softInputMode and
 * an empty onDispose in `rememberImeBottomInset`; if the white screen recurs, first downgrade the terminal route's transition to None to rule it out.
 *
 * ViewModels are hoisted to this level so Drawer content and AIChatPanel share the same instance.
 */
/** Navigation route -> performance panel module name. The editor route carries query parameters, so use prefix matching. */
private fun perfModuleOf(route: String?): String = when {
    route == null -> "other"
    route == "chat" -> "chat"
    route.startsWith("editor") -> "editor"
    route == "terminal" -> "terminal"
    route == "browser" -> "browser"
    route == "git" || route == "credentials" -> "git"
    route == "settings" -> "settings"
    else -> "other"
}

@Composable
fun AppNavigation(
    onboardingRepository: com.aicode.feature.onboarding.data.OnboardingRepository,
    perfMonitor: com.aicode.core.perf.PerfMonitor
) {
    val navController = rememberNavController()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // Used to determine the current route: Drawer gestures are only allowed on the chat page.
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentBackStackEntry?.destination?.route

    // Record the current route globally; crash reports use it to locate the crashing page.
    LaunchedEffect(currentRoute) {
        AIEditorApp.currentRoute = currentRoute
        // Performance sampling is attributed to a module by route, for module-level usage analysis in the performance panel.
        perfMonitor.setModule(perfModuleOf(currentRoute))
    }

    // Activity-level ViewModels -- Drawer and AIChatPanel share the same instance.
    val agentViewModel: AIAgentViewModel = hiltViewModel()
    val settingsViewModel: SettingsViewModel = hiltViewModel()
    val workspaceViewModel: WorkspaceViewModel = hiltViewModel()

    // -- First-launch onboarding --
    val onboardingStateHolder = remember { OnboardingStateHolder() }
    val onboardingUiState by onboardingStateHolder.state.collectAsStateWithLifecycle()
    val onboardingRegistry = remember { OnboardingTargetRegistry() }
    val onboardingScope = rememberCoroutineScope()
    val onboardingCoordinator = remember {
        OnboardingCoordinator(
            stateHolder = onboardingStateHolder,
            onPersist = { status ->
                onboardingRepository.markStatus(status)
            },
            scope = onboardingScope
        )
    }
    // Startup check: if IN_PROGRESS, activate onboarding.
    LaunchedEffect(Unit) {
        val status = onboardingRepository.status()
        onboardingCoordinator.initialize(status ?: OnboardingStatus.IN_PROGRESS)
    }

    // Auto update check: asynchronously check when entering the home page (when the toggle is on and at most once per day, silent on failure)
    LaunchedEffect(Unit) {
        settingsViewModel.checkUpdate(manual = false)
    }

    // When the sidebar is open, the system back key closes the sidebar first.
    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }

    // Data needed by the sidebar.
    val currentWorkspace by workspaceViewModel.current.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(currentWorkspace) {
        // In remote mode when the connection is not ready currentWorkspace is null; do not trigger setWorkspace, to avoid an empty path igniting session loading
        val path = currentWorkspace?.path ?: return@LaunchedEffect
        agentViewModel.setWorkspace(path)
    }

    val sessions by agentViewModel.sessions.collectAsStateWithLifecycle()
    val currentSessionId by agentViewModel.currentSessionId.collectAsStateWithLifecycle()
    val agentStates by agentViewModel.agentStates.collectAsStateWithLifecycle()
    val awaitingPermissionSessionIds by agentViewModel.awaitingPermissionSessionIds.collectAsStateWithLifecycle()
    val subSessionsByParent by agentViewModel.subSessionsByParent.collectAsStateWithLifecycle()
    val expandedPaths by agentViewModel.expandedPaths.collectAsStateWithLifecycle()
    val expandingPath by agentViewModel.expandingPath.collectAsStateWithLifecycle()
    val fileOpPaths by agentViewModel.fileOpPaths.collectAsStateWithLifecycle()
    val browseState by agentViewModel.browseState.collectAsStateWithLifecycle()
    val browseClipboard by agentViewModel.browseClipboard.collectAsStateWithLifecycle()
    val pasteConflict by agentViewModel.pasteConflict.collectAsStateWithLifecycle()
    val chatSearchQuery by agentViewModel.chatSearchQuery.collectAsStateWithLifecycle()
    val chatSearchState by agentViewModel.chatSearchState.collectAsStateWithLifecycle()

    // -- Export session: SAF save file --
    var pendingExportSessionId by remember { mutableStateOf<String?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val sessionExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val sessionId = pendingExportSessionId
        if (uri != null && sessionId != null) {
            scope.launch {
                val os = withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri) }
                if (os != null) {
                    agentViewModel.exportSession(sessionId, os) { success ->
                        Toast.makeText(
                            context,
                            context.getString(if (success) R.string.chat_export_session_done else R.string.chat_export_session_failed),
                            if (success) Toast.LENGTH_SHORT else Toast.LENGTH_LONG
                        ).show()
                    }
                } else {
                    Toast.makeText(context, context.getString(R.string.chat_export_session_failed), Toast.LENGTH_LONG).show()
                }
                pendingExportSessionId = null
            }
        } else {
            pendingExportSessionId = null
        }
    }

    // -- Large-screen (tablet landscape and above) layout --
    val expanded = isExpandedWidth()
    // The persistent sidebar is only open on the chat page: terminal / editor / settings are all full-screen pages, and being squeezed by the sidebar makes them harder to use.
    // The persistent sidebar only depends on window width: it only expands on the chat page and slides back on other pages (see sidebarWidth below).
    val permanentDrawer = expanded

    // Refresh the file list once when the sidebar appears: local mode covers missed inotify events, remote mode has no inotify and relies entirely on this.
    // Cannot directly watch drawerState.isOpen: the large-screen persistent sidebar does not go through ModalNavigationDrawer, so drawerState is always Closed,
    // which would leave the file tree in large-screen remote mode dependent only on the manual refresh button. Instead use 'sidebar visible': on narrow windows check whether the drawer is open,
    // on large screens check whether we are on the chat page (the sidebar only expands on the chat page).
    val sidebarVisible = if (permanentDrawer) currentRoute == "chat" else drawerState.isOpen
    LaunchedEffect(sidebarVisible) {
        if (sidebarVisible) agentViewModel.refreshBrowse()
    }

    // Right-pane workbench: on large screens editor / terminal / Git sit side-by-side with chat; on narrow windows they still use full-screen routes.
    var paneKind by rememberSaveable { mutableStateOf(WorkbenchPaneKind.NONE) }
    var paneEditorPath by rememberSaveable { mutableStateOf("") }
    var paneEditorLine by rememberSaveable { mutableIntStateOf(0) }
    var paneSplit by rememberSaveable { mutableFloatStateOf(DEFAULT_PANE_SPLIT) }

    // When the right pane is open, the back key closes it first. Limited to the chat page: the right pane is not rendered on other pages, so back events must not be swallowed there.
    BackHandler(
        enabled = expanded && currentRoute == "chat" &&
            paneKind != WorkbenchPaneKind.NONE && !drawerState.isOpen
    ) {
        paneKind = WorkbenchPaneKind.NONE
    }

    // Open file: on large screens go to the right pane, on narrow windows jump to the full-screen editor page.
    val openFile: (String, Int, Boolean) -> Unit = { filePath, line, fromDrawer ->
        if (expanded) {
            paneEditorPath = filePath
            paneEditorLine = line
            paneKind = WorkbenchPaneKind.EDITOR
            // The persistent sidebar does not need to close; in cases still using a modal drawer (e.g. tablet portrait), close it after selecting a file.
            if (!permanentDrawer) scope.launch { drawerState.close() }
        } else {
            scope.launch { drawerState.close() }
            navController.navigate(
                "editor?path=${android.net.Uri.encode(filePath)}&line=$line&drawer=$fromDrawer"
            )
        }
    }

    // Tapping a file in the file tree: images go to the built-in full-screen viewer, APKs go to the system installer, everything else still goes to the editor.
    val drawerImageViewerState = rememberImageViewerState()
    val drawerDecodeSpec = rememberViewerDecodeSpec()
    val drawerImageLoad = remember(agentViewModel.fileAccess, drawerDecodeSpec) {
        chatImageLoader(agentViewModel.fileAccess, drawerDecodeSpec.maxEdge, drawerDecodeSpec.maxPixels)
    }
    val openTreeEntry: (String) -> Unit = { path ->
        val name = path.trimEnd('/').substringAfterLast('/')
        val ext = name.substringAfterLast('.', "").lowercase()
        when {
            ext in PREVIEWABLE_IMAGE_EXTS -> drawerImageViewerState.show(
                ImageViewerRequest(listOf(ImageSource.ContainerPath(path)), name)
            )
            ext in INSTALLABLE_APK_EXTS -> scope.launch {
                openSentAttachment(
                    context,
                    AgentAttachment(
                        fileName = name,
                        containerPath = path,
                        localPath = "",
                        mimeType = APK_MIME_TYPE,
                        sizeBytes = 0L,
                        isImage = false
                    ),
                    agentViewModel.fileAccess
                )
            }
            else -> openFile(path, 0, true)
        }
    }

    // Terminal / Git: on large screens toggle within the right pane, on narrow windows jump to the full-screen page.
    val openWorkbench: (WorkbenchPaneKind) -> Unit = { target ->
        if (expanded) {
            paneKind = if (paneKind == target) WorkbenchPaneKind.NONE else target
        } else {
            val route = when (target) {
                WorkbenchPaneKind.TERMINAL -> "terminal"
                WorkbenchPaneKind.GIT -> "git"
                WorkbenchPaneKind.BROWSER -> "browser"
                else -> "chat"
            }
            navController.navigate(route)
        }
    }

    // Sidebar content: the modal drawer and the large-screen persistent bar share the same content, avoiding duplicating dozens of lines of parameters in two places.
    val drawerBody: @Composable () -> Unit = {
        ChatDrawerContent(
            sessions = sessions,
            currentSessionId = currentSessionId,
            agentStates = agentStates,
            awaitingPermissionSessionIds = awaitingPermissionSessionIds,
            subSessionsByParent = subSessionsByParent,
            browseState = browseState,
            expandedPaths = expandedPaths,
            expandingPath = expandingPath,
            fileOpPaths = fileOpPaths,
            clipboard = browseClipboard,
            pasteConflict = pasteConflict,
            onToggleExpand = { agentViewModel.toggleExpand(it) },
            onOpenFile = openTreeEntry,
            onRefreshBrowse = { agentViewModel.refreshBrowse() },
            onCreateFile = { parent, name ->
                agentViewModel.createBrowseFile(parent, name) { ok ->
                    if (!ok) toastFileOpFailed(context, R.string.file_browser_create_failed)
                }
            },
            onCreateFolder = { parent, name ->
                agentViewModel.createBrowseFolder(parent, name) { ok ->
                    if (!ok) toastFileOpFailed(context, R.string.file_browser_create_failed)
                }
            },
            onRenameEntry = { path, newName ->
                agentViewModel.renameBrowseEntry(path, newName) { ok ->
                    if (!ok) toastFileOpFailed(context, R.string.file_browser_rename_failed)
                }
            },
            onDeleteEntry = { path ->
                agentViewModel.deleteBrowseEntry(path) { ok ->
                    if (!ok) toastFileOpFailed(context, R.string.file_browser_delete_failed)
                }
            },
            onCopyEntry = { path, name ->
                agentViewModel.copyBrowseEntry(path, name)
            },
            onCutEntry = { path, name ->
                agentViewModel.cutBrowseEntry(path, name)
            },
            onPasteEntry = { targetDir, onResult ->
                agentViewModel.pasteBrowseEntry(targetDir, onResult)
            },
            onClearClipboard = { agentViewModel.clearBrowseClipboard() },
            onPasteOverwrite = { agentViewModel.pasteBrowseEntryOverwrite() },
            onCancelPasteOverwrite = { agentViewModel.clearPasteConflict() },
            onSelect = {
                agentViewModel.selectSession(it.id)
                if (!permanentDrawer) scope.launch { drawerState.close() }
            },
            onDelete = { agentViewModel.deleteSession(it.id) },
            onDeleteSessions = { agentViewModel.deleteSessions(it) },
            onRename = { session, title -> agentViewModel.renameSession(session.id, title) },
            onTogglePin = { agentViewModel.togglePinSession(it.id) },
            onExport = { session ->
                pendingExportSessionId = session.id
                val safeTitle = session.title.replace(Regex("[^\\w\\u4e00-\\u9fa5\\-]"), "_")
                sessionExportLauncher.launch("aicode-session-$safeTitle-${System.currentTimeMillis()}.tar.gz")
            },
            onNavigateToBrowser = {
                openWorkbench(WorkbenchPaneKind.BROWSER)
                if (!permanentDrawer) scope.launch { drawerState.close() }
            },
            onNavigateToSettings = {
                navController.navigate("settings")
                if (!permanentDrawer) scope.launch { drawerState.snapTo(DrawerValue.Closed) }
            },
            searchQuery = chatSearchQuery,
            searchState = chatSearchState,
            onSearchQueryChange = { agentViewModel.updateChatSearchQuery(it) },
            onClearSearch = { agentViewModel.clearChatSearch() },
            onOpenSearchHit = { hit ->
                agentViewModel.openChatSearchHit(hit)
                if (!permanentDrawer) scope.launch { drawerState.close() }
            }
        )
    }

    // Page body (NavHost): on large screens placed on the right side of a Row, on narrow windows placed inside the modal drawer container.
    val navBody: @Composable () -> Unit = {
        NavHost(
            navController = navController,
            startDestination = "chat",
            // During transitions the exiting page moves left out of its own area; on large screens without clipping it would draw over the persistent session sidebar on the left.
            modifier = Modifier.fillMaxSize().clipToBounds(),
            enterTransition = { pageEnter(forward = true) },
            exitTransition = { pageExit(forward = true) },
            popEnterTransition = { pageEnter(forward = false) },
            popExitTransition = { pageExit(forward = false) }
        ) {
            composable("chat") {
                // Override LocalUriHandler inside the chat area: Markdown link clicks are dispatched through this handler by default.
                // File-path links (no scheme or file://) are intercepted to jump to the editor; the rest (http/https, etc.) still go to the system browser.
                val defaultUriHandler = androidx.compose.ui.platform.LocalUriHandler.current
                val fileUriHandler = remember(defaultUriHandler, expanded) {
                    FileAwareUriHandler(defaultUriHandler) { filePath, line ->
                        openFile(filePath, line, false)
                    }
                }
                val paneOpen = expanded && paneKind != WorkbenchPaneKind.NONE
                // When dragging the handle, displacement pixels must be converted to a split ratio, so record the container's measured width.
                var rowWidthPx by remember { mutableIntStateOf(0) }
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .onSizeChanged { rowWidthPx = it.width }
                ) {
                    Box(modifier = Modifier.weight(if (paneOpen) paneSplit else 1f)) {
                        CompositionLocalProvider(
                            androidx.compose.ui.platform.LocalUriHandler provides fileUriHandler
                        ) {
                            AIChatPanel(
                                viewModel = agentViewModel,
                                settingsViewModel = settingsViewModel,
                                workspaceViewModel = workspaceViewModel,
                                onOpenDrawer = { scope.launch { drawerState.open() } },
                                showMenuButton = !permanentDrawer,
                                onNavigateToTerminal = { openWorkbench(WorkbenchPaneKind.TERMINAL) },
                                onNavigateToGit = { openWorkbench(WorkbenchPaneKind.GIT) },
                                onNavigateToBrowser = { openWorkbench(WorkbenchPaneKind.BROWSER) },
                                terminalActive = paneOpen && paneKind == WorkbenchPaneKind.TERMINAL,
                                gitActive = paneOpen && paneKind == WorkbenchPaneKind.GIT,
                                browserActive = paneOpen && paneKind == WorkbenchPaneKind.BROWSER,
                                onboardingStep = if (onboardingUiState.active) onboardingUiState.step else null,
                                onSelectModelInOnboarding = {
                                    if (onboardingUiState.active && onboardingUiState.step == OnboardingStep.SIMULATE_CHOOSE_MODEL) {
                                        onboardingCoordinator.nextStep()
                                    }
                                },
                                onDismissModelSheetInOnboarding = {
                                    if (onboardingUiState.active && onboardingUiState.step == OnboardingStep.SIMULATE_CHOOSE_MODEL) {
                                        onboardingCoordinator.goToStep(OnboardingStep.OPEN_MODEL_PICKER)
                                    }
                                }
                            )
                        }
                    }
                    if (paneOpen) {
                        VerticalSplitHandle(
                            onDragDelta = { dx ->
                                if (rowWidthPx > 0) {
                                    paneSplit = (paneSplit + dx / rowWidthPx)
                                        .coerceIn(MIN_PANE_SPLIT, MAX_PANE_SPLIT)
                                }
                            }
                        )
                        WorkbenchPaneContent(
                            kind = paneKind,
                            editorPath = paneEditorPath,
                            editorLine = paneEditorLine,
                            onClose = { paneKind = WorkbenchPaneKind.NONE },
                            onOpenFile = { path -> openFile(path, 0, false) },
                            modifier = Modifier.weight(1f - paneSplit)
                        )
                    }
                }
            }
            composable("settings") {
                // Reuse the Activity-level settingsViewModel (already created and initialized at the top of MainActivity),
                // avoiding creating another NavBackStackEntry-level instance when entering the settings page and re-running init and 9 flow subscriptions,
                // which is the main cause of the hitch when tapping Settings in the sidebar.
                SettingsScreen(
                    viewModel = settingsViewModel,
                    onNavigateBack = {
                        navController.popBackStack()
                        // After returning to the chat page on large screens the sidebar is already persistent, so do not pop up the modal drawer again.
                        if (!expanded) scope.launch { drawerState.open() }
                    },
                    onStopAllAndCloseTerminal = { agentViewModel.stopAllAndCloseTerminal() },
                    onRerunOnboarding = {
                        // From the settings page 'rerun onboarding': clear state back to in_progress, activate onboarding and return to the chat page.
                        scope.launch {
                            onboardingRepository.resetToInProgress()
                            onboardingCoordinator.resetAndStart()
                            navController.navigate("chat") {
                                popUpTo("chat") { inclusive = false }
                            }
                        }
                    },
                    onboardingStep = onboardingUiState.step.takeIf { onboardingUiState.active },
                    onOnboardingModelAdded = {
                        if (onboardingUiState.active && onboardingUiState.step == OnboardingStep.SIMULATE_FETCH_DIALOG) {
                            navController.popBackStack()
                            onboardingCoordinator.nextStep()
                        }
                    },
                    onOnboardingDismissFetchDialog = {
                        if (onboardingUiState.active && onboardingUiState.step == OnboardingStep.SIMULATE_FETCH_DIALOG) {
                            onboardingCoordinator.goToStep(OnboardingStep.PROVIDER_FETCH_MODELS)
                        }
                    }
                )
            }
            composable("terminal") { entry ->
                val terminalViewModel: TerminalViewModel = hiltViewModel()
                TerminalScreen(
                    viewModel = terminalViewModel,
                    onNavigateBack = {
                        // Clicks may still be received during exit; must not pop the chat home page that has already become the current page.
                        if (navController.currentBackStackEntry === entry) {
                            navController.popBackStack()
                        }
                    }
                )
            }
            composable("browser") {
                val browserViewModel: com.aicode.feature.browser.presentation.BrowserViewModel = hiltViewModel()
                BrowserScreen(
                    browserManager = browserViewModel.browserManager,
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            composable("git") {
                val gitViewModel: GitViewModel = hiltViewModel()
                GitScreen(
                    viewModel = gitViewModel,
                    onNavigateToCredentials = { navController.navigate("credentials") },
                    onNavigateBack = { navController.popBackStack() },
                    onOpenFile = { path -> openFile(path, 0, false) }
                )
            }
            composable("credentials") {
                val credentialViewModel: com.aicode.feature.credentials.presentation.CredentialViewModel = hiltViewModel()
                CredentialScreen(
                    viewModel = credentialViewModel,
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            composable(
                route = "editor?path={path}&line={line}&drawer={drawer}",
                arguments = listOf(
                    navArgument("path") {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                    navArgument("line") {
                        type = NavType.IntType
                        defaultValue = 0
                    },
                    navArgument("drawer") {
                        type = NavType.BoolType
                        defaultValue = false
                    }
                )
            ) { entry ->
                // When entered from the sidebar file page, going back should reopen the drawer (otherwise after viewing one file you would have to reopen the drawer to get back to the directory);
                // when entered from a chat-area link, going back should return to chat without opening the drawer.
                val openDrawerOnBack = entry.arguments?.getBoolean("drawer") ?: false
                CodeEditorScreen(
                    path = entry.arguments?.getString("path").orEmpty(),
                    initialLine = entry.arguments?.getInt("line") ?: 0,
                    onBack = {
                        navController.popBackStack()
                        if (openDrawerOnBack && !expanded) scope.launch { drawerState.open() }
                    }
                )
            }
        }
    }

    CompositionLocalProvider(
        LocalOnboardingTargetRegistry provides onboardingRegistry,
        LocalImageViewer provides drawerImageViewerState
    ) {
    Box(modifier = Modifier.fillMaxSize()) {
    // Images opened from the file tree use this viewer; the chat panel has its own, and the two layers do not affect each other.
    ImageViewerHost(state = drawerImageViewerState, load = drawerImageLoad)
    if (expanded) {
        // Large screens do not wrap in ModalNavigationDrawer: the sidebar is persistent on the left, and the drawer's scrim / gestures / anchors are entirely unused here.
        // The sidebar only expands on the chat page: the settings page itself is already two columns ('menu + detail'), and adding a session sidebar outside would make three columns.
        // Width is animated with a tween rather than added/removed directly, otherwise the right area's width would jump abruptly and flicker noticeably when switching pages;
        // the duration uses the same value as the page transition, so both animations finish together.
        val sidebarWidth by animateDpAsState(
            targetValue = if (currentRoute == "chat") drawerWidth() else 0.dp,
            animationSpec = tween(durationMillis = PAGE_MOTION_MS),
            label = "sidebar-width"
        )
        // After the sidebar is fully collapsed the whole subtree leaves composition, so use SaveableStateHolder to hold its internal state,
        // otherwise every return from the settings page would jump the tab back to 'Sessions' and lose the file tree scroll position.
        val sidebarStateHolder = rememberSaveableStateHolder()
        Row(modifier = Modifier.fillMaxSize()) {
            if (sidebarWidth > 0.dp) {
                Box(
                    modifier = Modifier
                        .width(sidebarWidth)
                        .fillMaxHeight()
                        .clipToBounds()
                ) {
                    // Content is rendered at full width and then clipped: the sidebar's internal layout is not reflowed during collapse.
                    Box(
                        modifier = Modifier
                            .width(drawerWidth())
                            .fillMaxHeight()
                    ) {
                        sidebarStateHolder.SaveableStateProvider("chat-sidebar") {
                            drawerBody()
                        }
                    }
                }
                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            Box(modifier = Modifier.weight(1f)) {
                navBody()
            }
        }
    } else {
        ModalNavigationDrawer(
            drawerState = drawerState,
            // Only enable swipe-out gestures on the chat page; disabled on other pages (but can always be closed when already open).
            gesturesEnabled = currentRoute == "chat" || drawerState.isOpen,
            drawerContent = {
                ModalDrawerSheet(
                    drawerShape = RectangleShape,
                    drawerContainerColor = settingsPageBackground(),
                    drawerTonalElevation = 0.dp,
                    modifier = Modifier.width(drawerWidth())
                ) {
                    drawerBody()
                }
            }
        ) {
            navBody()
        }
    }

    // Update check dialog (global host: shared by auto detection and manual check on the About page, covering all pages)
    val updateCheckState by settingsViewModel.updateCheckState.collectAsStateWithLifecycle()
    if (updateCheckState != UpdateCheckUiState.Idle) {
        val version = remember {
            runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
            }.getOrDefault("unknown")
        }
        UpdateCheckDialog(
            state = updateCheckState,
            currentVersion = version,
            onDismiss = { settingsViewModel.dismissUpdateCheck() },
            onOpenRelease = { tag ->
                openUrl(context, githubReleaseUrl(tag))
                settingsViewModel.dismissUpdateCheck()
            }
        )
    }

    // First-launch onboarding overlay (covers all routes, resident on the top layer throughout, smooth transitions on page switches with no flicker).
    if (onboardingUiState.active) {
        OnboardingOverlay(
            stateHolder = onboardingStateHolder,
            coordinator = onboardingCoordinator,
            registry = onboardingRegistry,
            onNextStep = {
                val step = onboardingStateHolder.state.value.step
                when (step) {
                    OnboardingStep.OPEN_SIDEBAR -> {
                        scope.launch { drawerState.open() }
                        onboardingCoordinator.nextStep()
                    }
                    OnboardingStep.ENTER_SETTINGS -> {
                        navController.navigate("settings")
                        scope.launch { drawerState.snapTo(DrawerValue.Closed) }
                        onboardingCoordinator.nextStep()
                    }
                    OnboardingStep.CONFIG_PROVIDER -> {
                        onboardingCoordinator.nextStep()
                    }
                    OnboardingStep.PROVIDER_ADD -> {
                        onboardingCoordinator.nextStep()
                    }
                    OnboardingStep.PROVIDER_CONFIG_INFO -> {
                        onboardingCoordinator.nextStep()
                    }
                    OnboardingStep.PROVIDER_FETCH_MODELS -> {
                        onboardingCoordinator.nextStep()
                    }
                    OnboardingStep.SIMULATE_FETCH_DIALOG -> {
                        val current = settingsViewModel.providers.value.firstOrNull()
                        if (current != null && current.models.isEmpty()) {
                            settingsViewModel.saveProvider(current.copy(models = listOf("deepseek-v4-flash")))
                        }
                        navController.popBackStack()
                        onboardingCoordinator.nextStep()
                    }
                    OnboardingStep.OPEN_MODEL_PICKER -> {
                        onboardingCoordinator.nextStep()
                    }
                    OnboardingStep.SIMULATE_CHOOSE_MODEL -> {
                        val current = settingsViewModel.providers.value.firstOrNull()
                        if (current != null && current.models.isNotEmpty()) {
                            agentViewModel.setSessionProviderModel(current.id, current.effectiveModel.ifEmpty { current.models.first() })
                        }
                        onboardingCoordinator.nextStep()
                    }
                    OnboardingStep.SEND_MESSAGE -> {
                        onboardingCoordinator.complete()
                    }
                }
            },
            onTargetClick = {
                val step = onboardingStateHolder.state.value.step
                when (step) {
                    OnboardingStep.OPEN_SIDEBAR -> {
                        scope.launch { drawerState.open() }
                        onboardingCoordinator.nextStep()
                    }
                    OnboardingStep.ENTER_SETTINGS -> {
                        navController.navigate("settings")
                        scope.launch { drawerState.snapTo(DrawerValue.Closed) }
                        onboardingCoordinator.nextStep()
                    }
                    OnboardingStep.CONFIG_PROVIDER -> {
                        onboardingCoordinator.nextStep()
                    }
                    OnboardingStep.PROVIDER_ADD -> {
                        onboardingCoordinator.nextStep()
                    }
                    OnboardingStep.PROVIDER_CONFIG_INFO -> {
                        onboardingCoordinator.nextStep()
                    }
                    OnboardingStep.PROVIDER_FETCH_MODELS -> {
                        onboardingCoordinator.nextStep()
                    }
                    OnboardingStep.SIMULATE_FETCH_DIALOG -> {
                        val current = settingsViewModel.providers.value.firstOrNull()
                        if (current != null && current.models.isEmpty()) {
                            settingsViewModel.saveProvider(current.copy(models = listOf("deepseek-v4-flash")))
                        }
                        navController.popBackStack()
                        onboardingCoordinator.nextStep()
                    }
                    OnboardingStep.OPEN_MODEL_PICKER -> {
                        onboardingCoordinator.nextStep()
                    }
                    OnboardingStep.SIMULATE_CHOOSE_MODEL -> {
                        val current = settingsViewModel.providers.value.firstOrNull()
                        if (current != null && current.models.isNotEmpty()) {
                            agentViewModel.setSessionProviderModel(current.id, current.effectiveModel.ifEmpty { current.models.first() })
                        }
                        onboardingCoordinator.nextStep()
                    }
                    OnboardingStep.SEND_MESSAGE -> {
                        onboardingCoordinator.complete()
                    }
                }
            }
        )
    }
    }
    }
}

/** Failure toast for sidebar file page write operations (shared by create / rename / delete). */
private fun toastFileOpFailed(context: android.content.Context, messageRes: Int) {
    Toast.makeText(context, context.getString(messageRes), Toast.LENGTH_SHORT).show()
}

/**
 * Chat-area Markdown link handling: intercepts 'container file path' links and hands them to [onOpenFile] to open the editor,
 * all other URLs (http/https/mailto, etc.) are delegated to the system default [delegate] (browser).
 *
 * Convention: the AI outputs clickable paths as `[display text](path)`; the path may carry a `:line` suffix (e.g. `~/workspace/a.kt:42`).
 */
private class FileAwareUriHandler(
    private val delegate: androidx.compose.ui.platform.UriHandler,
    private val onOpenFile: (path: String, line: Int) -> Unit
) : androidx.compose.ui.platform.UriHandler {
    override fun openUri(uri: String) {
        val filePath = asChatFilePath(uri)
        if (filePath == null) {
            delegate.openUri(uri)
            return
        }
        val (path, line) = splitPathAndLine(filePath)
        onOpenFile(path, line)
    }
}

/** Hierarchical schemes with `//`: `http://`, `https://`, `file://`, `content://`, etc. */
private val HIERARCHICAL_SCHEME = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*)://")

/**
 * Opaque URL schemes without `//` can only be allowlisted: scheme syntax allows `.`, so under the workspace root
 * a file link `notes.md:12` and `mailto:x` are syntactically indistinguishable; judging by 'has a colon means URL' would cause
 * such links to be handed to the browser and become unopenable.
 */
private val OPAQUE_URL_SCHEME = Regex(
    "^(mailto|tel|sms|smsto|geo|data|market|intent|about|javascript):",
    RegexOption.IGNORE_CASE
)

private val TRAILING_LINE = Regex("^(.*):(\\d+)$")

/** Image formats that preview in the built-in viewer when tapped in the file tree (consistent with sendFile's image detection). */
private val PREVIEWABLE_IMAGE_EXTS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")

/** Package formats that invoke the system installer when tapped in the file tree. */
private val INSTALLABLE_APK_EXTS = setOf("apk", "apks", "xapk")

private const val APK_MIME_TYPE = "application/vnd.android.package-archive"

/** Chat-area link target -> normalized file path; returns null when judged to be a URL (non-`file` scheme). */
internal fun asChatFilePath(uri: String): String? {
    val hierarchical = HIERARCHICAL_SCHEME.find(uri)?.groupValues?.get(1)?.lowercase()
    return when {
        hierarchical == "file" -> android.net.Uri.decode(uri.removePrefix("file://"))
        hierarchical != null -> null // http/https/content, etc. handed to the system
        OPAQUE_URL_SCHEME.containsMatchIn(uri) -> null // mailto/tel, etc.
        else -> uri // Container absolute path or workspace-relative path, including plain filenames like `notes.md:12`
    }
}

/** Split out the optional `:line` suffix; line is 0 when absent. */
internal fun splitPathAndLine(path: String): Pair<String, Int> {
    val cleaned = path.substringBefore('#').trim()
    val m = TRAILING_LINE.find(cleaned) ?: return cleaned to 0
    return m.groupValues[1] to (m.groupValues[2].toIntOrNull() ?: 0)
}
