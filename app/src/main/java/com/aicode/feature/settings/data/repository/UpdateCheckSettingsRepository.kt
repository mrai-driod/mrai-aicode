package com.aicode.feature.settings.data.repository

import android.content.Context
import com.aicode.feature.settings.data.remote.UpdateCheckResult
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONObject

/** Update channel: Stable (official releases only) / Latest (including preview releases). */
enum class UpdateChannel {
    STABLE, LATEST
}

/**
 * "Automatic update check" preference storage: the toggle, update channel, and last check date are all stored in SharedPreferences
 * (a key-value file, not a database). The last check date is not written to the database, but is also not placed in cacheDir -- the cache directory may be
 * cleared by the system, which would break the "once a day" behavior, so persisting to SharedPreferences is more reliable.
 */
@Singleton
class UpdateCheckSettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val prefs = context.getSharedPreferences("update_check_prefs", Context.MODE_PRIVATE)

    /** Automatic update check toggle, enabled by default. */
    var autoCheckEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) {
            prefs.edit().putBoolean(KEY_ENABLED, value).apply()
        }

    /** Update channel, defaults to Stable. */
    var channel: UpdateChannel
        get() = prefs.getString(KEY_CHANNEL, null)
            ?.let { runCatching { UpdateChannel.valueOf(it) }.getOrNull() }
            ?: UpdateChannel.STABLE
        set(value) {
            prefs.edit().putString(KEY_CHANNEL, value.name).apply()
        }

    /** Whether an update check has already been done today (judged by the recorded date). */
    fun hasCheckedToday(): Boolean = prefs.getString(KEY_LAST_CHECKED, null) == today()

    /** Records that an update check has been done today. */
    fun markCheckedToday() {
        prefs.edit().putString(KEY_LAST_CHECKED, today()).apply()
    }

    /**
     * Writes version and update information to `~/.aicode/update-info.json` (host filesDir/aicode/),
     * for the AI inside the container to read (current version, update channel, last check time, latest version, and per-version changelogs).
     * Write failures are silent and do not affect the check flow.
     */
    fun writeUpdateInfo(currentVersion: String, channel: UpdateChannel, result: UpdateCheckResult) {
        runCatching {
            val dir = File(context.filesDir, "aicode").apply { mkdirs() }
            val obj = JSONObject()
            obj.put("currentVersion", currentVersion)
            obj.put("channel", channel.name.lowercase())
            obj.put("lastCheckedAt", java.time.LocalDateTime.now().toString())
            when (result) {
                is UpdateCheckResult.UpToDate -> obj.put("hasUpdate", false)
                is UpdateCheckResult.NewVersion -> {
                    obj.put("hasUpdate", true)
                    obj.put("latestVersion", result.info.latestTag)
                    val updates = org.json.JSONArray()
                    result.info.updates.forEach { u ->
                        updates.put(
                            JSONObject().apply {
                                put("tag", u.tag)
                                put("changelog", u.changelog)
                            }
                        )
                    }
                    obj.put("updates", updates)
                }
                is UpdateCheckResult.Error -> {
                    obj.put("hasUpdate", false)
                    obj.put("error", result.message)
                }
            }
            File(dir, "update-info.json").writeText(obj.toString())
        }
    }

    private fun today(): String = java.time.LocalDate.now().toString()

    private companion object {
        const val KEY_ENABLED = "auto_check_enabled"
        const val KEY_CHANNEL = "update_channel"
        const val KEY_LAST_CHECKED = "last_checked_date"
    }
}
