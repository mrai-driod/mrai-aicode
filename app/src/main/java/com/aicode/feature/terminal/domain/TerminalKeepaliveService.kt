package com.aicode.feature.terminal.domain

import android.app.ActivityManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.aicode.MainActivity
import com.aicode.core.util.FileLogger
import com.aicode.R
import com.aicode.feature.settings.data.repository.KeepaliveSettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class TerminalKeepaliveService : Service() {
    private val binder = LocalBinder()
    private var sessionCount = 0

    /** Persistent keepalive enabled by the user on the settings page: when true, the foreground notification is kept even with no background sessions. */
    private var persistent = false

    /**
     * Service-level WakeLock: holds PARTIAL_WAKE_LOCK while there are active sessions, ensuring the CPU does not sleep after moving to the background and
     * SSE/network I/O is not suspended by the system. Released when all sessions end or persistent keepalive is turned off.
     */
    private var wakeLock: PowerManager.WakeLock? = null

    /**
     * Service-level WifiLock: holds WIFI_MODE_FULL_HIGH_PERF while there are active sessions, ensuring the Wi-Fi radio does not
     * enter low-power mode after moving to the background. PARTIAL_WAKE_LOCK only keeps the CPU awake, but the system may still throttle Wi-Fi, causing
     * long-lived TCP I/O to be suspended -- this is the core reason SSE disconnects in the background.
     * Browser downloads are unaffected because DownloadManager runs in the system process and is not subject to app background restrictions.
     */
    private var wifiLock: WifiManager.WifiLock? = null

    @Inject
    lateinit var keepaliveSettings: KeepaliveSettingsRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    inner class LocalBinder : Binder() {
        fun getService(): TerminalKeepaliveService = this@TerminalKeepaliveService
    }

    override fun onCreate() {
        super.onCreate()
        FileLogger.i(TAG, "Service created")
        // After the process is reclaimed by the system and rebuilt via START_STICKY: persistent is in-memory state and will be lost.
        // Restore persistent foreground mode from the persisted switch, otherwise the service idles and then stopSelf makes keepalive useless.
        serviceScope.launch {
            if (keepaliveSettings.isEnabled()) {
                persistent = true
                ensureForeground()
                FileLogger.i(TAG, "Restored persistent keepalive after restart")
            }
        }
    }

    override fun onDestroy() {
        releaseServiceWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // The user swiping the app away from recents = explicitly not wanting background running: stop the foreground service and cancel the WorkManager periodic fallback,
        // otherwise after the process is killed the service would be relaunched by START_STICKY or KeepaliveWorker and pop a notification.
        // The switch itself stays unchanged; next time the app is opened it is restored automatically by MainActivity / AIEditorApp.
        KeepaliveWorker.cancel(this)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        FileLogger.i(TAG, "Task removed, keepalive stopped")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SESSION -> {
                sessionCount++
                ensureForeground()
                acquireServiceWakeLock()
                FileLogger.i(TAG, "Session started, count=$sessionCount")
            }
            ACTION_STOP_SESSION -> {
                sessionCount = (sessionCount - 1).coerceAtLeast(0)
                if (sessionCount == 0 && !persistent) {
                    releaseServiceWakeLock()
                    stopSelf(startId)
                    FileLogger.i(TAG, "All sessions ended, stopping service")
                } else {
                    ensureForeground()
                    if (sessionCount == 0) releaseServiceWakeLock()
                    FileLogger.i(TAG, "Session ended, count=$sessionCount, persistent=$persistent")
                }
            }
            ACTION_ENABLE_PERSISTENT -> {
                persistent = true
                ensureForeground()
                acquireServiceWakeLock()
                FileLogger.i(TAG, "Persistent keepalive enabled")
            }
            ACTION_DISABLE_PERSISTENT -> {
                persistent = false
                if (sessionCount == 0) {
                    releaseServiceWakeLock()
                    stopSelf(startId)
                    FileLogger.i(TAG, "Persistent keepalive disabled, no sessions, stopping service")
                } else {
                    ensureForeground()
                    FileLogger.i(TAG, "Persistent keepalive disabled, sessions still running count=$sessionCount")
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    /**
     * Enters the foreground and refreshes the notification; the text varies with the combination of persistent keepalive / background sessions.
     *
     * startForeground on Android 12+ may throw when starting a foreground service from the background
     * ForegroundServiceStartNotAllowedException; it is caught and only logged here -- the persistent notification simply fails to show this time,
     * and must not crash the process (the settings-page switch is still on, and it will be restored by [com.aicode.MainActivity] next time the app is in the foreground).
     */
    private fun ensureForeground() {
        val text = when {
            sessionCount > 0 && persistent ->
                getString(R.string.notification_keepalive_persistent_sessions, sessionCount)
            sessionCount > 0 ->
                getString(R.string.notification_keepalive_running_sessions, sessionCount)
            else -> getString(R.string.notification_keepalive_enabled)
        }
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)
            .build()

        runCatching { startForeground(NOTIFICATION_ID, notification) }
            .onFailure { FileLogger.e(TAG, "startForeground failed", it) }
    }

    /**
     * Acquires the service-level PARTIAL_WAKE_LOCK + WIFI_MODE_FULL_HIGH_PERF WifiLock:
     * - WakeLock keeps the CPU awake, preventing the system from suspending network I/O
     * - WifiLock keeps the Wi-Fi radio in high-performance mode, preventing TCP disconnects caused by Wi-Fi throttling in the background
     * Both are indispensable: with only the CPU awake but Wi-Fi asleep, SSE still disconnects.
     * Held only while there are active sessions; released when all sessions end.
     */
    private fun acquireServiceWakeLock() {
        if (wakeLock?.isHeld != true) {
            val pm = getSystemService(POWER_SERVICE) as? PowerManager
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AiCode:KeepaliveService").also {
                    runCatching { it.acquire(WAKELOCK_TIMEOUT_MS) }
                        .onFailure { FileLogger.e(TAG, "acquire service wakeLock failed", it) }
                }
            }
        }
        if (wifiLock?.isHeld != true) {
            val wm = getSystemService(WIFI_SERVICE) as? WifiManager
            if (wm != null) {
                @Suppress("DEPRECATION")
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "AiCode:StreamNetwork").also {
                    runCatching { it.acquire() }
                        .onFailure { FileLogger.e(TAG, "acquire wifiLock failed", it) }
                }
            }
        }
    }

    /** Releases the service-level WakeLock and WifiLock. */
    private fun releaseServiceWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.let { lock ->
            runCatching { lock.release() }
                .onFailure { FileLogger.e(TAG, "release service wakeLock failed", it) }
        }
        wakeLock = null
        wifiLock?.takeIf { it.isHeld }?.let { lock ->
            runCatching { lock.release() }
                .onFailure { FileLogger.e(TAG, "release wifiLock failed", it) }
        }
        wifiLock = null
    }

    companion object {
        private const val TAG = "TerminalKeepaliveService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "terminal_service"
        /** Service-level WakeLock timeout safeguard: AI streaming generation can last a long time, so allow a full 60 minutes. */
        private const val WAKELOCK_TIMEOUT_MS = 60 * 60 * 1000L
        const val ACTION_START_SESSION = "com.aicode.action.START_SESSION"
        const val ACTION_STOP_SESSION = "com.aicode.action.STOP_SESSION"
        const val ACTION_ENABLE_PERSISTENT = "com.aicode.action.ENABLE_PERSISTENT"
        const val ACTION_DISABLE_PERSISTENT = "com.aicode.action.DISABLE_PERSISTENT"

        /**
         * Registers an active session and brings up the foreground service (used for AI streaming generation / background terminals).
         * Uses startForegroundService rather than startService, ensuring it can also start correctly when restored from the background on Android 8+.
         */
        fun startSession(context: Context) {
            val intent = Intent(context, TerminalKeepaliveService::class.java).apply {
                action = ACTION_START_SESSION
            }
            ContextCompat.startForegroundService(context, intent)
        }

        /** Ends an active session. The service stops automatically when the session count reaches zero and there is no persistent keepalive. */
        fun stopSession(context: Context) {
            val intent = Intent(context, TerminalKeepaliveService::class.java).apply {
                action = ACTION_STOP_SESSION
            }
            runCatching { context.startService(intent) }
                .onFailure { FileLogger.e(TAG, "stopSession startService failed", it) }
        }

        /** Enables persistent keepalive (idempotent). Uses startForegroundService so it can be brought up from the background too. */
        fun enablePersistent(context: Context) {
            val intent = Intent(context, TerminalKeepaliveService::class.java).apply {
                action = ACTION_ENABLE_PERSISTENT
            }
            ContextCompat.startForegroundService(context, intent)
        }

        /** Disables persistent keepalive (idempotent). Only call when it was actually enabled, to avoid bringing up a Service just to turn it off. */
        fun disablePersistent(context: Context) {
            val intent = Intent(context, TerminalKeepaliveService::class.java).apply {
                action = ACTION_DISABLE_PERSISTENT
            }
            runCatching { context.startService(intent) }
                .onFailure { FileLogger.e(TAG, "disablePersistent startService failed", it) }
        }

        /** Checks whether the keepalive service is currently running. The WorkManager fallback probes this before bringing it up, to avoid repeated startService calls. */
        @Suppress("DEPRECATION")
        fun isRunning(context: Context): Boolean {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val serviceName = TerminalKeepaliveService::class.java.name
            return am.getRunningServices(Int.MAX_VALUE)
                .any { it.service.packageName == context.packageName && it.service.className == serviceName }
        }
    }
}
