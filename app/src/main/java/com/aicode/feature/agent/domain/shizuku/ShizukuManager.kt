package com.aicode.feature.agent.domain.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import com.aicode.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import javax.inject.Inject
import javax.inject.Singleton

/** Shizuku availability state, for display on the settings page and for checks before tool execution. */
enum class ShizukuState {
    /** Shizuku (or Sui) is not installed. */
    NOT_INSTALLED,

    /** Installed but the service is not running; the user needs to start the service in Shizuku first. */
    NOT_RUNNING,

    /** The service is running, but this app has not yet been granted permission. */
    PERMISSION_DENIED,

    /** Ready; commands can be executed. */
    READY
}

/** The result of one Shizuku command execution. A negative [exitCode] means a timeout or a startup exception. */
data class ShizukuCommandResult(val output: String, val exitCode: Int)

/**
 * Shizuku backend: executes commands with the identity of adb shell (uid 2000).
 *
 * Executes commands via a UserService ([ShizukuShellService]) rather than the deprecated `Shizuku#newProcess`:
 * the app binds a service running in the shell process and calls its AIDL interface to execute on its behalf; only the command and the result are passed between processes.
 *
 * The state is driven by the survival/death of the Shizuku binder and by permission results; when [state] changes, the settings page and tools react accordingly.
 */
@Singleton
class ShizukuManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private companion object {
        const val TAG = "ShizukuManager"

        /** The package name of the official Shizuku app (Sui also uses it as its entry point). */
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

        const val PERMISSION_REQUEST_CODE = 1001

        /** Service identity tag: when not set the class name is used, which is unstable after R8 obfuscation, so it is explicitly fixed. */
        const val SERVICE_TAG = "shizuku_shell"

        /** Upper limit for waiting to bind the UserService (milliseconds). The Shizuku timeout for starting the service itself is 30 seconds. */
        const val BIND_TIMEOUT_MS = 30_000L

        /** Upper limit for the command timeout (milliseconds), aligned with [com.aicode.feature.agent.domain.container.CommandEngine.MAX_TIMEOUT_MS]. */
        const val MAX_TIMEOUT_MS = 1_800_000L

        const val DOWNLOAD_URL = "https://shizuku.rikka.app/download/"
    }

    private val _state = MutableStateFlow(ShizukuState.NOT_INSTALLED)
    val state: StateFlow<ShizukuState> = _state.asStateFlow()

    private val bindMutex = Mutex()

    @Volatile
    private var shellService: IShizukuShellService? = null

    @Volatile
    private var pendingBind: CompletableDeferred<IShizukuShellService>? = null

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener { refreshState() }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        shellService = null
        pendingBind?.completeExceptionally(IllegalStateException("Shizuku service disconnected"))
        pendingBind = null
        refreshState()
    }

    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { _, _ ->
        refreshState()
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val bound = IShizukuShellService.Stub.asInterface(service)
            shellService = bound
            pendingBind?.complete(bound)
            pendingBind = null
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            shellService = null
            pendingBind?.completeExceptionally(IllegalStateException("Shizuku UserService disconnected"))
            pendingBind = null
            refreshState()
        }
    }

    private val userServiceArgs: Shizuku.UserServiceArgs by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(context.packageName, ShizukuShellService::class.java.name)
        )
            .daemon(false)
            .tag(SERVICE_TAG)
            .processNameSuffix("shizuku_shell")
            .debuggable(isDebuggable)
            .version(appVersionCode)
    }

    /** The project does not enable BuildConfig; the version code is read from PackageManager (the UserService is rebuilt automatically after an upgrade). */
    @Suppress("DEPRECATION")
    private val appVersionCode: Int by lazy {
        runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode.toInt()
            } else {
                info.versionCode
            }
        }.getOrDefault(1)
    }

    private val isDebuggable: Boolean by lazy {
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    init {
        runCatching {
            // Sticky: calls back immediately when the binder is already ready, with no need to probe the first-frame state separately.
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(permissionResultListener)
        }.onFailure { FileLogger.w(TAG, "Failed to register Shizuku listener: ${it.message}") }
        refreshState()
    }

    /** Recomputes and publishes the current state. */
    fun refreshState() {
        _state.value = computeState()
    }

    private fun computeState(): ShizukuState {
        if (!isShizukuInstalled()) return ShizukuState.NOT_INSTALLED
        if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) return ShizukuState.NOT_RUNNING
        // pre-v11 has no UserService, so it is treated as unavailable.
        if (runCatching { Shizuku.isPreV11() }.getOrDefault(false)) return ShizukuState.NOT_RUNNING
        val granted = runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        return if (granted) ShizukuState.READY else ShizukuState.PERMISSION_DENIED
    }

    private fun isShizukuInstalled(): Boolean = runCatching {
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    }.getOrDefault(false)

    /** Requests Shizuku permission. Must be called on the main thread (an internal Shizuku requirement). */
    fun requestPermission() {
        if (computeState() != ShizukuState.PERMISSION_DENIED) return
        runCatching { Shizuku.requestPermission(PERMISSION_REQUEST_CODE) }
            .onFailure { FileLogger.w(TAG, "Failed to request Shizuku permission: ${it.message}") }
    }

    /** Opens the Shizuku app; if it is not installed, jumps to the official download page. */
    fun openShizukuApp() {
        val launch = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
        runCatching {
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launch)
            } else {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(DOWNLOAD_URL))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }.onFailure { FileLogger.w(TAG, "Failed to open Shizuku: ${it.message}") }
    }

    /** Executes a shell command. Throws an exception when not ready or when binding fails, for the caller to convert into a tool error. */
    suspend fun runCommand(command: String, timeoutMs: Long): ShizukuCommandResult {
        val service = ensureBound()
        val timeout = timeoutMs.coerceIn(1_000L, MAX_TIMEOUT_MS).toInt()
        return withContext(Dispatchers.IO) {
            val bundle = service.exec(command, timeout)
            ShizukuCommandResult(
                output = bundle.getString(ShizukuShellService.KEY_OUTPUT).orEmpty(),
                exitCode = bundle.getInt(ShizukuShellService.KEY_EXIT_CODE, ShizukuShellService.EXIT_FAILURE)
            )
        }
    }

    /** Idempotently binds the UserService and returns a usable AIDL proxy. */
    private suspend fun ensureBound(): IShizukuShellService {
        shellService?.let { return it }
        return bindMutex.withLock {
            shellService?.let { return@withLock it }
            if (computeState() != ShizukuState.READY) {
                throw IllegalStateException("Shizuku not ready (${_state.value})")
            }
            val deferred = CompletableDeferred<IShizukuShellService>()
            pendingBind = deferred
            withContext(Dispatchers.Main) {
                Shizuku.bindUserService(userServiceArgs, serviceConnection)
            }
            withTimeoutOrNull(BIND_TIMEOUT_MS) { deferred.await() }
                ?: throw IllegalStateException("Timed out binding the Shizuku service")
        }
    }
}
