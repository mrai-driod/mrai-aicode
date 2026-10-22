package com.aicode.feature.terminal.domain

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.aicode.feature.settings.data.repository.KeepaliveSettingsRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Keepalive fallback Worker: periodically checks whether the resident keepalive service is alive, and restarts it if killed.
 *
 * START_STICKY relies on the system recreating it when the process is reclaimed, which is neither timely nor guaranteed; WorkManager is scheduled uniformly by the system and is more reliable.
 * It is only scheduled when the user enables the background keepalive switch (see [com.aicode.AIEditorApp]); when disabled the task is cancelled,
 * so the switch is still rechecked here to avoid an erroneous restart if the user disabled it after scheduling.
 */
@HiltWorker
class KeepaliveWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParameters: WorkerParameters,
    private val keepaliveSettings: KeepaliveSettingsRepository
) : CoroutineWorker(context, workerParameters) {

    override suspend fun doWork(): Result {
        if (!keepaliveSettings.isEnabled()) return Result.success()
        if (!TerminalKeepaliveService.isRunning(applicationContext)) {
            TerminalKeepaliveService.enablePersistent(applicationContext)
        }
        return Result.success()
    }

    companion object {
        private const val UNIQUE_NAME = "keepalive-check"
        /** Minimum period allowed by WorkManager. */
        private const val PERIOD_MINUTES = 15L

        /** Schedules the periodic fallback task (idempotent). Called when the keepalive switch is enabled. */
        fun schedule(context: Context) {
            val request =
                PeriodicWorkRequestBuilder<KeepaliveWorker>(PERIOD_MINUTES, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }

        /** Cancels the periodic fallback task. Called when the keepalive switch is disabled. */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
        }
    }
}