package com.aicode

import android.app.ActivityManager
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.os.Process
import androidx.work.Configuration
import androidx.hilt.work.HiltWorkerFactory
import com.aicode.core.net.AppProxy
import com.aicode.core.util.AILogger
import com.aicode.core.util.FileLogger
import net.schmizz.sshj.common.SecurityUtils
import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.agent.domain.mcp.McpManager
import com.aicode.feature.settings.data.repository.KeepaliveSettingsRepository
import com.aicode.feature.settings.data.repository.LanguageSettingsRepository
import com.aicode.feature.settings.data.repository.LogSettingsRepository
import com.aicode.feature.settings.data.remote.ModelMetadataService
import com.aicode.feature.terminal.domain.KeepaliveWorker
import com.aicode.feature.terminal.domain.TerminalKeepaliveService
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class AIEditorApp : Application(), Configuration.Provider {

    companion object {
        const val TAG = "AIEditorApp"
        const val LANG_PREFS = "language_prefs_sync"
        const val LANG_KEY = "language_tag"
        /** Crash stack traces are passed via Intent extra; exceeding the Binder limit throws an exception, so truncation is required. */
        const val MAX_CRASH_STACK_CHARS = 50_000
        private const val CRASH_PREFS = "crash_state"
        private const val KEY_CRASH_UI_SHOWING = "crash_ui_showing"
        private const val KEY_LAST_CRASH_TIME = "last_crash_time"
        private const val KEY_CRASH_COUNT = "crash_count"
        /** Consecutive crashes within 10 seconds are considered dense crashes. */
        private const val CRASH_RESET_WINDOW_MS = 10_000L
        /** Reaching 3 dense crashes triggers a hard circuit-breaker exit to prevent infinite loops. */
        private const val MAX_CONSECUTIVE_CRASHES = 3

        /** Current navigation route (written by MainActivity), included in crash reports to identify the crashing page. */
        @Volatile
        var currentRoute: String? = null

        /** Current workspace mode (local PRoot / remote SSH), included in crash reports. */
        @Volatile
        var currentWorkspaceMode: String? = null

        /** Reset crash state (called when the main process is running healthily or the user restarts from the error page). */
        fun resetCrashState(context: android.content.Context) {
            context.getSharedPreferences(CRASH_PREFS, android.content.Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_CRASH_UI_SHOWING)
                .remove(KEY_CRASH_COUNT)
                .remove(KEY_LAST_CRASH_TIME)
                .apply()
        }

        /** Legacy alias kept for backward compatibility, retained for external callers. */
        fun resetCrashUiFlag(context: android.content.Context) {
            resetCrashState(context)
        }

        /** Compatibly retrieve the current process name. */
        fun getProcessNameCompat(context: android.content.Context): String {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return Application.getProcessName()
            }
            return runCatching {
                val cmdline = java.io.File("/proc/self/cmdline")
                if (cmdline.exists()) {
                    cmdline.readText().trim().trim { it <= ' ' || it == '\u0000' }
                } else null
            }.getOrNull()?.takeIf { it.isNotBlank() }
                ?: runCatching {
                    val activityThread = Class.forName("android.app.ActivityThread")
                    val method = activityThread.getMethod("currentProcessName")
                    method.invoke(null) as? String
                }.getOrNull()
                ?: context.packageName
        }

        /** Determine whether the current process is the subprocess dedicated to rendering the crash error page. */
        fun isCrashProcess(context: android.content.Context): Boolean =
            getProcessNameCompat(context).endsWith(":crash")
    }

    override fun attachBaseContext(base: android.content.Context) {
        // Earliest entry point: set up logging and crash persistence before any Hilt injection / business initialization.
        // Crashes during early startup (e.g. when the Hilt injection chain instantiates @Singleton utilities) that occur before FileLogger initialization
        // would leave no trace, so logging and the global crash handler are moved to the very front of attachBaseContext.
        FileLogger.init(base)
        AILogger.init(base)
        installCrashHandler()
        // Global proxy entry point: must be set before any Hilt injection / OkHttpClient construction,
        // so that all App-side HTTP paths (chat, MCP, update checks, etc.) go through the global/provider proxy as needed.
        // Use base rather than the local context defined below: during attachBaseContext, base is already a valid Context.
        AppProxy.applyGlobal(base)
        val tag = base.getSharedPreferences(LANG_PREFS, android.content.Context.MODE_PRIVATE)
            .getString(LANG_KEY, null)
        val context = if (tag.isNullOrBlank()) {
            base
        } else {
            val config = android.content.res.Configuration(base.resources.configuration)
            config.setLocale(java.util.Locale.forLanguageTag(tag))
            base.createConfigurationContext(config)
        }
        super.attachBaseContext(context)
    }

    /** Hilt field injection: available after the super call in [onCreate]. */
    @Inject
    lateinit var logSettings: LogSettingsRepository

    /** Persistence for the background keepalive toggle. */
    @Inject
    lateinit var keepaliveSettings: KeepaliveSettingsRepository

    /** WorkManager Worker factory: enables @HiltWorker (KeepaliveWorker) to inject dependencies. */
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    /** MCP lifecycle manager: connects to configured remote servers on startup. */
    @Inject
    lateinit var mcpManager: McpManager

    /** MCP config repository: watches for direct external edits to mcp.json on startup, refreshes the list within seconds and triggers reconnection. */
    @Inject
    lateinit var mcpConfigRepository: com.aicode.feature.agent.domain.mcp.McpConfigRepository

    /** Tool permission rules repository: watches for direct external edits to permissions.json on startup, refreshes rules within seconds. */
    @Inject
    lateinit var permissionRulesRepository: com.aicode.feature.agent.domain.permission.PermissionRulesRepository

    /** Skill config repository: watches for external changes to the skill directory and skills.json on startup, refreshes the skill list within seconds. */
    @Inject
    lateinit var skillConfigRepository: com.aicode.feature.agent.domain.skill.SkillConfigRepository

    /** One-time migrator for legacy Room git credentials: on startup writes legacy table data to the git-credentials file and then drops the table (source of truth has moved to the file). */
    @Inject
    lateinit var legacyCredentialMigrator: com.aicode.feature.credentials.data.LegacyCredentialMigrator

    /** Sensitive-field encryption migrator: on startup encrypts and rewrites historically plaintext API Key/password/private-key files (idempotent). */
    @Inject
    lateinit var secretEncryptionMigrator: com.aicode.feature.settings.data.SecretEncryptionMigrator

    @Inject
    lateinit var builtInProviderSeeder: com.aicode.feature.settings.data.repository.BuiltInProviderSeeder

    /** Git credential repository: on startup migrates legacy plaintext credential files to the encoded format. */
    @Inject
    lateinit var fileCredentialRepository: com.aicode.feature.credentials.data.repository.FileCredentialRepository

    /** Unified dialog bridge for missing git credentials across all three surfaces (UI/AI Bash/interactive terminal): listens for not-logged-in requests sent by the in-container credential helper via file IPC,
     *  exposes a StateFlow for the global dialog to fill in and feed back to git. Must be started on the main thread (FileObserver binds to the main Looper). */
    @Inject
    lateinit var credentialRequestBridge: com.aicode.feature.credentials.data.CredentialRequestBridge

    /** Execution mode repository (local PRoot / remote SSH). */
    @Inject
    lateinit var executionModeRepository: com.aicode.feature.settings.data.repository.ExecutionModeRepository

    /** App language preference repository: persists the user-selected language for synchronous reading in attachBaseContext. */
    @Inject
    lateinit var languageSettings: LanguageSettingsRepository

    /** Execution mode sync cache: reads the first frame from DataStore on startup and injects it into DI. */
    @Inject
    lateinit var executionModeHolder: com.aicode.feature.settings.data.repository.ExecutionModeHolder

    /** Remote SSH connection manager: in remote mode, establishes a connection from the config on startup. */
    @Inject
    lateinit var remoteSshConnection: com.aicode.feature.agent.domain.container.RemoteSshConnection

    /** Connection config resolver for the currently active profile: drives reconnection when connection/profile/mode changes. */
    @Inject
    lateinit var activeRemoteConnectionResolver: com.aicode.feature.agent.domain.container.ActiveRemoteConnectionResolver

    /** Workspace repository: reloads the workspace after a successful SSH reconnection. */
    @Inject
    lateinit var workspaceRepository: com.aicode.feature.workspace.data.repository.WorkspaceRepository

    /** Connection and sync repository: injected to create the @Singleton instance early in startup; internally follows the current workspace to automatically connect/disconnect mounts. */
    @Inject
    lateinit var remoteRepository: com.aicode.feature.workspace.domain.repository.RemoteRepository

    /** Model metadata service: on startup asynchronously fetches the model catalog from this repository (12h cache, silent on failure, falls back to built-in data). */
    @Inject
    lateinit var modelMetadataService: ModelMetadataService

    /** Provider-level proxy registry: builds the provider-to-proxy index on startup for AppProxy to dispatch by target host. */
    @Inject
    lateinit var providerProxyRegistry: com.aicode.feature.settings.data.repository.ProviderProxyRegistry

    /** Long-lived scope: continuously syncs the persisted log level to FileLogger. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        if (isCrashProcess(this)) {
            // The crash error page runs in a separate :crash process.
            // Strictly do not call super.onCreate() (to avoid triggering Hilt @Inject field injection and implicit business class loading),
            // and strictly do not start main-process business coroutines, database access, or resident services, so the error page has a clean, lightweight rendering environment.
            return
        }
        super.onCreate()
        logDeviceInfo()
        // Attach the provider-level proxy registry to AppProxy (applyGlobal was already done in attachBaseContext),
        // thereafter dispatch provider-specific proxies by target host; falls back to the global proxy when no provider config exists.
        AppProxy.registerProviderProxyRegistry(providerProxyRegistry)
        // Register the full BouncyCastle in a background IO coroutine to avoid loading many cryptography classes on the main thread and blocking the first screen;
        // remote SSH connections run in appScope, so completing registration before use is sufficient.
        appScope.launch(Dispatchers.IO) {
            registerBouncyCastle()
        }
        createNotificationChannels()
        // Start credential request listening: cred-req-* written by the in-container credential helper -> global dialog fill-in -> feed back to git to continue.
        credentialRequestBridge.start()
        // -- Non-critical background tasks: deferred until after the first screen --
        // On cold start, JIT compilation of huge Composables (AIChatPanel, etc.) allocates ~39MB, triggering a GC storm that blocks the main thread.
        // These file extraction/migration/network refresh tasks allocate many temporary objects on background threads; if they run at the same time as JIT they compound GC pressure.
        // Delaying by 500ms lets the JIT compilation window pass first, so first-screen rendering is not disturbed by GC. The tasks themselves are all idempotent, so a 500ms delay has no impact.
        appScope.launch {
            kotlinx.coroutines.delay(500)
            ContainerInstaller.extractDocs(this@AIEditorApp)
        }
        appScope.launch {
            kotlinx.coroutines.delay(500)
            ContainerInstaller.extractPrompts(this@AIEditorApp)
        }
        appScope.launch {
            kotlinx.coroutines.delay(500)
            ContainerInstaller.extractScripts(this@AIEditorApp)
        }
        appScope.launch {
            kotlinx.coroutines.delay(500)
            ContainerInstaller.extractAgents(this@AIEditorApp)
        }
        appScope.launch {
            kotlinx.coroutines.delay(500)
            legacyCredentialMigrator.migrateIfNeeded()
        }
        appScope.launch {
            kotlinx.coroutines.delay(500)
            secretEncryptionMigrator.migrateIfNeeded()
        }
        appScope.launch {
            kotlinx.coroutines.delay(500)
            fileCredentialRepository.migrateToEncoded()
        }
        appScope.launch {
            kotlinx.coroutines.delay(800)
            modelMetadataService.refreshFromNetworkIfStale()
        }
        appScope.launch {
            kotlinx.coroutines.delay(800)
            com.aicode.feature.settings.data.local.ProviderPresetLibrary.refreshFromNetworkIfStale(this@AIEditorApp)
        }
        // Mrai fork: seed the built-in free providers (Mrai AI + OpenCode Free)
        // so a fresh install can chat immediately, with no API key setup.
        appScope.launch {
            runCatching { builtInProviderSeeder.seedIfNeeded() }
                .onFailure { FileLogger.w(TAG, "Built-in provider seeding failed", it) }
        }
        // Load the persisted level on startup and apply changes from the settings page in real time (the sole sync point).
        appScope.launch {
            logSettings.levelFlow.collectLatest { FileLogger.setMinLevel(it) }
        }
        // On startup, synchronously read the first-frame execution mode from DataStore into ExecutionModeHolder for synchronous reading by DI @Provides.
        // Asynchronously read the first-frame mode and write it to ExecutionModeHolder. The delegation layer (DelegatingCommandEngine/DelegatingFileAccess)
        // only forwards per holder.currentMode() on each method call and does not depend on injection timing, so a holder write delayed by a few milliseconds is harmless --
        // the first command/file operation always happens after UI startup, by which time the holder is already ready.
        // SSH connection runs in the background; failure does not block the UI (on connection failure, the first command triggers an ensureInstalled retry).
        appScope.launch {
            val mode = executionModeRepository.executionModeFlow.first()
            executionModeHolder.setMode(mode)
        }
        // SSH connection supervisor runs resident (idles when config is empty): periodically probes liveness, auto-reconnects on disconnect, and reloads the workspace and syncs docs after a successful reconnection.
        appScope.launch {
            remoteSshConnection.startSupervisor(appScope) {
                runCatching { workspaceRepository.initialize() }
                    .onFailure { FileLogger.w(TAG, "Failed to reload workspace after SSH reconnection", it) }
                syncDocsToRemote()
                releaseBuiltinAssetsToRemote()
            }
        }
        // When the active profile's connection config changes (edit connection / edit profile / switch profile / switch mode), reconnect immediately so connection changes take effect at once.
        appScope.launch {
            combine(
                activeRemoteConnectionResolver.activeConfigFlow,
                executionModeHolder.mode
            ) { config, mode -> config to mode }.collect { (config, mode) ->
                if (mode != com.aicode.feature.settings.data.repository.ExecutionMode.REMOTE_SSH) return@collect
                if (config == null || config == remoteSshConnection.config) return@collect
                runCatching {
                    remoteSshConnection.connect(config)
                    // After a successful connection, sync built-in docs to remote ~/.aicode/docs/ for AI reference.
                    syncDocsToRemote()
                    releaseBuiltinAssetsToRemote()
                }.onFailure { FileLogger.e(TAG, "SSH connection failed, will retry on first command", it) }
            }
        }
        // The connection-and-sync 'follow current workspace' behavior is executed automatically by RemoteRepository internally listening to workspace changes (ready upon startup injection):
        // When the workspace is ready/switched, automatically disconnect mounts that are not in the current workspace, and connect mounts in the current workspace that have 'auto-connect on app startup' checked.
        // The sole reactor for the background keepalive persistent notification: listens to the toggle and starts/stops the persistent mode of TerminalKeepaliveService.
        // Covers both real-time toggling on the settings page and cold-start recovery. Only sends disable on an on-to-off transition,
        // to avoid starting a Service that was never started just to turn it off.
        appScope.launch {
            var last: Boolean? = null
            keepaliveSettings.enabledFlow.distinctUntilChanged().collect { enabled ->
                if (enabled) {
                    TerminalKeepaliveService.enablePersistent(this@AIEditorApp)
                    // WorkManager fallback: periodically checks service liveness and restarts it automatically after it is killed.
                    KeepaliveWorker.schedule(this@AIEditorApp)
                } else if (last == true) {
                    TerminalKeepaliveService.disablePersistent(this@AIEditorApp)
                    KeepaliveWorker.cancel(this@AIEditorApp)
                }
                last = enabled
            }
        }
        // Connect to configured MCP servers and register their tools into ToolRegistry (has its own internal scope; failure does not affect startup).
        mcpManager.start()
        // Permission rules are subscribed residently from App startup (AI evaluation needs the latest rules at any time); MCP config and skill list
        // are instead subscribed on demand by their respective consumers (McpManager / settings page), with no need to start them here.
        appScope.launch { permissionRulesRepository.startWatching() }
        // Continuously sync the workspace mode cache, included in crash reports
        appScope.launch {
            executionModeRepository.executionModeFlow.collect { mode ->
                currentWorkspaceMode = mode.name
            }
        }
        // 10 seconds after a healthy main-process startup, automatically clear the consecutive crash count and error-page showing flag to restore a healthy state
        appScope.launch {
            kotlinx.coroutines.delay(10_000)
            resetCrashState(this@AIEditorApp)
        }
        // Language switching is managed uniformly by MainActivity's attachBaseContext + recreate().
        // MainActivity inherits ComponentActivity (not AppCompatActivity),
        // so the automatic recreate from AppCompatDelegate.setApplicationLocales does not take effect,
        // and setting the locale in both places at the same time causes a race that leads to occasional language confusion.
    }

    /** Log device/version info: when a user reports 'the container will not start on a certain OS version', the log must already contain OS and ABI context. */
    private fun logDeviceInfo() {
        runCatching {
            val versionName = packageManager.getPackageInfo(packageName, 0).versionName
            FileLogger.i(
                TAG,
                "Device info: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}" +
                    "(API ${Build.VERSION.SDK_INT}), ABI=${Build.SUPPORTED_ABIS.joinToString(",")}, " +
                    "targetSdk=${applicationInfo.targetSdkVersion}, nativeLibraryDir=${applicationInfo.nativeLibraryDir}, " +
                    "version=$versionName"
            )
        }.onFailure { FileLogger.w(TAG, "Failed to log device info", it) }
    }

    /** HiltWorkerFactory: Workers annotated with @HiltWorker are created through this factory so they can inject Repositories. */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    /**
     * Reads all built-in docs under assets/docs (including category subdirectories such as guide/ advanced/), and syncs them via SSH exec to remote ~/.aicode/docs/.
     * In remote mode, when the AI consults the settings docs in ~/.aicode/docs/, these files need to exist on the remote server.
     * Called after a successful connection and after a successful reconnection, ensuring remote docs are updated with App upgrades. Failures are only logged and do not block the flow.
     */
    private suspend fun syncDocsToRemote() {
        runCatching {
            val docs = linkedMapOf<String, String>()
            collectAssetDocs("docs", "", docs)
            remoteSshConnection.uploadDocs(docs)
        }.onFailure { FileLogger.w(TAG, "Failed to sync built-in docs to remote", it) }
    }

    /**
     * Releases the built-in sub-agent definition (Explore) and panel example scripts to remote `~/.aicode/agents` and `~/.aicode/scripts`.
     * In remote mode, global agents/scripts are authoritative on the remote side; built-in defaults must exist remotely to work out of the box; only missing items are filled in, user modifications are not overwritten.
     */
    private suspend fun releaseBuiltinAssetsToRemote() {
        remoteSshConnection.uploadBuiltinFiles("agents", collectAssetBytes("agents"))
        remoteSshConnection.uploadBuiltinFiles("scripts", collectAssetBytes("aicode/scripts"), executable = true)
    }

    /** Collect flat files under an assets directory (name -> bytes). Directory entries are skipped. */
    private fun collectAssetBytes(assetDir: String): Map<String, ByteArray> {
        val out = linkedMapOf<String, ByteArray>()
        assets.list(assetDir)?.forEach { entry ->
            runCatching { assets.open("$assetDir/$entry").use { out[entry] = it.readBytes() } }
                .onFailure { FileLogger.w(TAG, "Failed to read built-in asset: $assetDir/$entry", it) }
        }
        return out
    }

    /** Recursively collect assets docs, with keys as paths relative to docs/ (e.g. guide/terminal.md). */
    private fun collectAssetDocs(assetDir: String, relativePrefix: String, out: MutableMap<String, String>) {
        val entries = assets.list(assetDir) ?: return
        for (entry in entries) {
            val assetPath = "$assetDir/$entry"
            val relativePath = if (relativePrefix.isEmpty()) entry else "$relativePrefix/$entry"
            try {
                out[relativePath] = assets.open(assetPath).bufferedReader().use { it.readText() }
            } catch (e: java.io.IOException) {
                // Directory entry: assets.open throws IOException for directories, handle recursively
                collectAssetDocs(assetPath, relativePath, out)
            }
        }
    }

    /** Register the full BouncyCastle to replace Android's bundled stripped-down version.
     *  sshj 0.38.0 uses X25519 for key exchange; Android's bundled BC provider does not include the X25519 algorithm,
     *  so the stripped version must be removed first and the full bcprov-jdk18on (a transitive dependency of sshj) registered, and sshj told to use it.
     *  Must be completed before any sshj call. */
    private fun registerBouncyCastle() {
        // First remove Android's bundled stripped BC, then register the full bcprov-jdk18on.
        // Use addProvider rather than insertProviderAt(..., 1): BC only needs to exist in the Provider list for sshj
        // to find via SecurityUtils.setSecurityProvider("BC") by name; it does not need the highest priority.
        // If raised to position 1, it would preempt the KeyStore lookup when OkHttp/Conscrypt initialize the default SSLContext,
        // BC registers the BKS type but has no matching default truststore, causing KeyStoreException: BKS not found
        // (manifesting as crashes on HTTPS requests such as update checks). Placing it at the end lets the system provider continue to handle TLS.
        java.security.Security.removeProvider("BC")
        java.security.Security.addProvider(org.bouncycastle.jce.provider.BouncyCastleProvider())
        SecurityUtils.setSecurityProvider("BC")
    }

    /**
     * Capture unhandled exceptions: after persisting to disk, launch the full-screen error page (showing crash details, with copy support),
     * then immediately kill the process so the system restarts and restores the error page.
     *
     * The process must be killed: after the main thread crashes its Looper is dead; without killing the process the error page window may appear
     * but onCreate never executes (white screen), and it is eventually killed by the system as an ANR. After killing, AMS detects
     * that the process died during Activity creation, automatically restarts the process and restores CrashActivity,
     * and with a healthy main thread in the new process, the error page can render normally.
     *
     * Recursion prevention: when the error page itself crashes the persisted flag is still present, so hand back directly to the system default handler to avoid infinite restarts.
     */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        val isCrash = isCrashProcess(this)
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val processName = getProcessNameCompat(this)
            FileLogger.e("CRASH", "Thread ${thread.name} uncaught exception (process=$processName)", throwable)
            // Synchronously flush buffered logs before the crash to avoid losing the last segment (including this error line) in memory.
            FileLogger.flushSync()

            // 1. If the :crash process itself throws: hand back to the system default handler and exit completely, never relaunch the error page
            if (isCrash) {
                FileLogger.e(TAG, "Crash error page process threw an exception, force-terminating process to avoid recursion")
                previous?.uncaughtException(thread, throwable)
                Process.killProcess(Process.myPid())
                return@setDefaultUncaughtExceptionHandler
            }

            // 2. Main process crash: check crash frequency and state to prevent an infinite relaunch loop
            val prefs = getSharedPreferences(CRASH_PREFS, MODE_PRIVATE)
            val isUiShowing = prefs.getBoolean(KEY_CRASH_UI_SHOWING, false)
            val lastCrashTime = prefs.getLong(KEY_LAST_CRASH_TIME, 0L)
            val now = System.currentTimeMillis()
            val recentCrashCount = if (now - lastCrashTime < CRASH_RESET_WINDOW_MS) {
                prefs.getInt(KEY_CRASH_COUNT, 0) + 1
            } else {
                1
            }
            prefs.edit()
                .putLong(KEY_LAST_CRASH_TIME, now)
                .putInt(KEY_CRASH_COUNT, recentCrashCount)
                .apply()

            // Circuit-breaker protection: the error page is showing and another crash occurs, or consecutive crashes reach the threshold in a short time
            if (isUiShowing || recentCrashCount >= MAX_CONSECUTIVE_CRASHES) {
                FileLogger.e(TAG, "Crash circuit breaker triggered (isUiShowing=$isUiShowing, count=$recentCrashCount), terminating self-restart and handing over to the system")
                previous?.uncaughtException(thread, throwable)
                Process.killProcess(Process.myPid())
                return@setDefaultUncaughtExceptionHandler
            }

            prefs.edit().putBoolean(KEY_CRASH_UI_SHOWING, true).apply()
            try {
                startActivity(
                    Intent(this, CrashActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        putExtra(CrashActivity.EXTRA_THREAD_NAME, thread.name)
                        putExtra(CrashActivity.EXTRA_STACK, stackTraceOf(throwable))
                        putExtra(CrashActivity.EXTRA_SCREEN, currentRoute)
                        putExtra(CrashActivity.EXTRA_WORKSPACE_MODE, currentWorkspaceMode)
                        putExtra(CrashActivity.EXTRA_MEMORY, memorySummary())
                    }
                )
            } catch (t: Throwable) {
                // Error page launch failed (e.g. system restriction): hand back to the default handler so the crash is not swallowed
                FileLogger.e(TAG, "Failed to launch crash error page", t)
                prefs.edit().remove(KEY_CRASH_UI_SHOWING).apply()
                previous?.uncaughtException(thread, throwable)
                return@setDefaultUncaughtExceptionHandler
            }
            Process.killProcess(Process.myPid())
        }
    }

    /**
     * Memory snapshot of the crashing process, included in the report. Must be captured in the main process -- the error page runs in the separate :crash process,
     * so reading there would only give the reporting process's heap, which is meaningless for locating OOM.
     */
    private fun memorySummary(): String = runCatching {
        val runtime = Runtime.getRuntime()
        val maxMb = runtime.maxMemory() / 1024 / 1024
        val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024
        val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val pssMb = runCatching {
            am.getProcessMemoryInfo(intArrayOf(Process.myPid())).firstOrNull()?.totalPss?.toLong()?.div(1024)
        }.getOrNull()
        buildString {
            append("heap ").append(usedMb).append('/').append(maxMb).append("MB")
            if (pssMb != null) append(", PSS ").append(pssMb).append("MB")
            append(", memoryClass ").append(am.memoryClass).append("MB")
            append(", lowRam ").append(am.isLowRamDevice)
        }
    }.getOrDefault("unavailable")

    private fun stackTraceOf(throwable: Throwable): String {
        val sw = java.io.StringWriter()
        throwable.printStackTrace(java.io.PrintWriter(sw))
        val text = sw.toString()
        return if (text.length > MAX_CRASH_STACK_CHARS) {
            text.take(MAX_CRASH_STACK_CHARS) + "\n...[truncated]"
        } else {
            text
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                "terminal_service",
                getString(R.string.notification_channel_terminal_service),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_terminal_service_desc)
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)

            // Agent completion notification: needs a popup + sound, distinct from the silent persistent notification of terminal_service.
            val agentChannel = NotificationChannel(
                "agent_complete",
                getString(R.string.notification_channel_agent_complete),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.notification_channel_agent_complete_desc)
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(agentChannel)
        }
    }
}
