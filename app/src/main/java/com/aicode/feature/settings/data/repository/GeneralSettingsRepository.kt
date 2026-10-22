package com.aicode.feature.settings.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.generalDataStore by preferencesDataStore(name = "general_prefs")

/**
 * Display position of the "Task Todo" area on the chat page.
 *
 * Todos are maintained by the AI via TodoTool, and in the UI they appear as a collapsible lightweight bar: by default it only shows
 * "x/y completed" and the currently in-progress task; tap to expand the full list. This enum decides where on the chat page it is attached.
 */
enum class TodoDisplayPosition {
    /** Below the title bar: directly under the floating title bar at the top, occupying one row of height before entering the message flow. */
    BELOW_TITLE_BAR,

    /** Above the message bar: directly above the input area at the bottom (the pre-rework existing behaviour, kept as the default). */
    ABOVE_INPUT_BAR,

    /** Off: the todo area is not rendered on the chat page (it can still be maintained by the AI within the session, just not shown). */
    OFF
}

/** Which session to enter when the app starts (including when switching workspaces). */
enum class StartupSessionMode {
    /** New session: reuse an empty session in the current workspace that has not sent any messages yet, otherwise create a new one. */
    NEW_SESSION,

    /** Open recent session: go directly to the most recently updated session; create a new one only when this workspace has no session yet. */
    RECENT_SESSION
}

/**
 * User preferences in "Preferences".
 *
 * Currently eight items: whether to automatically remove local models that no longer exist remotely after a successful model fetch (enabled by default),
 * whether to enter a new session or the recent session at startup (defaults to a new session), the first-byte / chunk-gap timeouts (seconds),
 * the maximum number of retries for network requests (default 6), enter-to-send, the auto-compaction threshold,
 * the sendFile per-file size limit (default 100MB),
 * and whether removing an external local workspace also deletes its chat history (disabled by default).
 * DataStore usage is consistent with [KeepaliveSettingsRepository].
 */
@Singleton
class GeneralSettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private companion object {
        val AUTO_REMOVE_STALE_MODELS_KEY = booleanPreferencesKey("auto_remove_stale_models")
        val STARTUP_SESSION_MODE_KEY = stringPreferencesKey("startup_session_mode")
        val FIRST_BYTE_TIMEOUT_SEC_KEY = intPreferencesKey("first_byte_timeout_sec")
        val STREAM_IDLE_TIMEOUT_SEC_KEY = intPreferencesKey("stream_idle_timeout_sec")
        val MAX_NETWORK_RETRIES_KEY = intPreferencesKey("max_network_retries")
        val ENTER_TO_SEND_KEY = booleanPreferencesKey("enter_to_send")
        val COMPACTION_THRESHOLD_PERCENT_KEY = intPreferencesKey("compaction_threshold_percent")
        val SENDFILE_MAX_SIZE_MB_KEY = intPreferencesKey("sendfile_max_size_mb")
        val DELETE_EXTERNAL_WORKSPACE_SESSIONS_KEY = booleanPreferencesKey("delete_external_workspace_sessions")
        val TODO_DISPLAY_POSITION_KEY = stringPreferencesKey("todo_display_position")
        /** Hash of the shown content of the usage-notes announcement on the "Prompts" page; shown again when there is no value or it does not match the current content hash. */
        val PROMPTS_ANNOUNCEMENT_SHOWN_HASH_KEY = stringPreferencesKey("prompts_announcement_shown_hash")

        /** First-byte timeout defaults to 5 minutes, matching the original hard-coded value. */
        const val DEFAULT_FIRST_BYTE_TIMEOUT_SEC = 300

        /** Network retry count defaults to 6, matching the original hard-coded value. */
        const val DEFAULT_MAX_NETWORK_RETRIES = 6

        /** Auto-compaction threshold defaults to 90%, matching the original hard-coded value. */
        const val DEFAULT_COMPACTION_THRESHOLD_PERCENT = 90

        /** sendFile per-file size limit defaults to 100MB, matching the original hard-coded value. */
        const val DEFAULT_SENDFILE_MAX_SIZE_MB = 100
    }

    /** Flow for the switch that automatically aligns the local list after fetching models; falls back to true (enabled by default) when not set. */
    val autoRemoveStaleModelsFlow: Flow<Boolean> =
        context.generalDataStore.data.map { it[AUTO_REMOVE_STALE_MODELS_KEY] ?: true }

    suspend fun setAutoRemoveStaleModels(enabled: Boolean) {
        context.generalDataStore.edit { it[AUTO_REMOVE_STALE_MODELS_KEY] = enabled }
    }

    /** Startup session preference flow; falls back to [StartupSessionMode.NEW_SESSION] when not set or the value is unrecognised. */
    val startupSessionModeFlow: Flow<StartupSessionMode> = context.generalDataStore.data.map { prefs ->
        when (prefs[STARTUP_SESSION_MODE_KEY]) {
            StartupSessionMode.RECENT_SESSION.name -> StartupSessionMode.RECENT_SESSION
            else -> StartupSessionMode.NEW_SESSION
        }
    }

    suspend fun setStartupSessionMode(mode: StartupSessionMode) {
        context.generalDataStore.edit { it[STARTUP_SESSION_MODE_KEY] = mode.name }
    }

    /** Read the preference once before switching to a workspace, to avoid opening an extra collection flow on the session initialisation path. */
    suspend fun startupSessionMode(): StartupSessionMode = startupSessionModeFlow.first()

    /** Backup snapshot: the current auto-align switch. */
    suspend fun autoRemoveStaleModelsSnapshot(): Boolean = autoRemoveStaleModelsFlow.first()

    /** Backup snapshot: the current startup session preference name. */
    suspend fun startupSessionModeSnapshot(): String = startupSessionModeFlow.first().name

    /** Restore the auto-align switch from a backup. */
    suspend fun restoreAutoRemoveStaleModels(enabled: Boolean) = setAutoRemoveStaleModels(enabled)

    /** Restore the startup session preference from a backup; falls back to a new session when an old backup has no such field (null) or the value is unrecognised. */
    suspend fun restoreStartupSessionMode(mode: String?) {
        setStartupSessionMode(
            StartupSessionMode.entries.firstOrNull { it.name == mode } ?: StartupSessionMode.NEW_SESSION
        )
    }

    /** First-byte timeout (seconds); 0 means no limit, falls back to 300 seconds when not set. */
    val firstByteTimeoutSecFlow: Flow<Int> = context.generalDataStore.data.map {
        (it[FIRST_BYTE_TIMEOUT_SEC_KEY] ?: DEFAULT_FIRST_BYTE_TIMEOUT_SEC).coerceAtLeast(0)
    }

    /** Timeout between adjacent chunks of a streaming response (seconds); 0 (default) means no limit. */
    val streamIdleTimeoutSecFlow: Flow<Int> = context.generalDataStore.data.map {
        (it[STREAM_IDLE_TIMEOUT_SEC_KEY] ?: 0).coerceAtLeast(0)
    }

    suspend fun setFirstByteTimeoutSec(sec: Int) {
        context.generalDataStore.edit { it[FIRST_BYTE_TIMEOUT_SEC_KEY] = sec.coerceAtLeast(0) }
    }

    suspend fun setStreamIdleTimeoutSec(sec: Int) {
        context.generalDataStore.edit { it[STREAM_IDLE_TIMEOUT_SEC_KEY] = sec.coerceAtLeast(0) }
    }

    /** Read the first-byte timeout once before assembling the provider (milliseconds); 0 means no limit. */
    suspend fun firstByteTimeoutMs(): Long = firstByteTimeoutSecFlow.first() * 1000L

    /** Read the chunk-gap timeout once before assembling the provider (milliseconds); 0 means no limit. */
    suspend fun streamIdleTimeoutMs(): Long = streamIdleTimeoutSecFlow.first() * 1000L

    /** Backup snapshot: the two timeout settings (seconds). */
    suspend fun firstByteTimeoutSecSnapshot(): Int = firstByteTimeoutSecFlow.first()

    suspend fun streamIdleTimeoutSecSnapshot(): Int = streamIdleTimeoutSecFlow.first()

    suspend fun restoreFirstByteTimeoutSec(sec: Int) = setFirstByteTimeoutSec(sec)

    suspend fun restoreStreamIdleTimeoutSec(sec: Int) = setStreamIdleTimeoutSec(sec)

    /** Maximum number of retries for network requests (excluding the first request); 0 means no retries, falls back to 6 when not set. */
    val maxNetworkRetriesFlow: Flow<Int> = context.generalDataStore.data.map {
        (it[MAX_NETWORK_RETRIES_KEY] ?: DEFAULT_MAX_NETWORK_RETRIES).coerceAtLeast(0)
    }

    suspend fun setMaxNetworkRetries(count: Int) {
        context.generalDataStore.edit { it[MAX_NETWORK_RETRIES_KEY] = count.coerceAtLeast(0) }
    }

    /** Read the maximum retry count once before assembling the provider. */
    suspend fun maxNetworkRetries(): Int = maxNetworkRetriesFlow.first()

    /** Backup snapshot: the maximum retry count. */
    suspend fun maxNetworkRetriesSnapshot(): Int = maxNetworkRetriesFlow.first()

    suspend fun restoreMaxNetworkRetries(count: Int) = setMaxNetworkRetries(count)

    /** Whether the Enter key sends a message directly; disabled by default (Enter inserts a newline, sending is done via the send button). */
    val enterToSendFlow: Flow<Boolean> =
        context.generalDataStore.data.map { it[ENTER_TO_SEND_KEY] ?: false }

    suspend fun setEnterToSend(enabled: Boolean) {
        context.generalDataStore.edit { it[ENTER_TO_SEND_KEY] = enabled }
    }

    /** Auto-compaction trigger threshold (percentage of the context window); defaults to 90, clamped to 1..100. */
    val compactionThresholdPercentFlow: Flow<Int> = context.generalDataStore.data.map {
        (it[COMPACTION_THRESHOLD_PERCENT_KEY] ?: DEFAULT_COMPACTION_THRESHOLD_PERCENT).coerceIn(1, 100)
    }

    suspend fun setCompactionThresholdPercent(percent: Int) {
        context.generalDataStore.edit { it[COMPACTION_THRESHOLD_PERCENT_KEY] = percent.coerceIn(1, 100) }
    }

    /** Read the trigger threshold percentage once before compaction. */
    suspend fun compactionThresholdPercent(): Int = compactionThresholdPercentFlow.first()

    /** Backup snapshot: the enter-to-send switch and the compaction threshold. */
    suspend fun enterToSendSnapshot(): Boolean = enterToSendFlow.first()

    suspend fun compactionThresholdPercentSnapshot(): Int = compactionThresholdPercentFlow.first()

    suspend fun restoreEnterToSend(enabled: Boolean) = setEnterToSend(enabled)

    suspend fun restoreCompactionThresholdPercent(percent: Int) = setCompactionThresholdPercent(percent)

    /** sendFile per-file size limit (MB); defaults to 100, minimum 1, no upper limit. */
    val sendFileMaxSizeMbFlow: Flow<Int> = context.generalDataStore.data.map {
        (it[SENDFILE_MAX_SIZE_MB_KEY] ?: DEFAULT_SENDFILE_MAX_SIZE_MB).coerceAtLeast(1)
    }

    suspend fun setSendFileMaxSizeMb(mb: Int) {
        context.generalDataStore.edit { it[SENDFILE_MAX_SIZE_MB_KEY] = mb.coerceAtLeast(1) }
    }

    /** Read the per-file size limit once before executing sendFile (MB). */
    suspend fun sendFileMaxSizeMb(): Int = sendFileMaxSizeMbFlow.first()

    /** Backup snapshot: the sendFile per-file size limit. */
    suspend fun sendFileMaxSizeMbSnapshot(): Int = sendFileMaxSizeMbFlow.first()

    suspend fun restoreSendFileMaxSizeMb(mb: Int) = setSendFileMaxSizeMb(mb)

    /** Whether removing an external local workspace also deletes its chat history; disabled by default (only unlinks it, keeping the chat history). */
    val deleteExternalWorkspaceSessionsFlow: Flow<Boolean> =
        context.generalDataStore.data.map { it[DELETE_EXTERNAL_WORKSPACE_SESSIONS_KEY] ?: false }

    suspend fun setDeleteExternalWorkspaceSessions(enabled: Boolean) {
        context.generalDataStore.edit { it[DELETE_EXTERNAL_WORKSPACE_SESSIONS_KEY] = enabled }
    }

    /** Read this preference once before removing an external workspace. */
    suspend fun deleteExternalWorkspaceSessions(): Boolean = deleteExternalWorkspaceSessionsFlow.first()

    /**
     * Task todo display position; falls back to [TodoDisplayPosition.ABOVE_INPUT_BAR] when not set or the value is unrecognised,
     * i.e. the pre-rework existing position (above the input bar), so existing users see no behaviour change after upgrading.
     */
    val todoDisplayPositionFlow: Flow<TodoDisplayPosition> = context.generalDataStore.data.map { prefs ->
        TodoDisplayPosition.entries.firstOrNull { it.name == prefs[TODO_DISPLAY_POSITION_KEY] }
            ?: TodoDisplayPosition.ABOVE_INPUT_BAR
    }

    suspend fun setTodoDisplayPosition(position: TodoDisplayPosition) {
        context.generalDataStore.edit { it[TODO_DISPLAY_POSITION_KEY] = position.name }
    }

    /** Backup snapshot: the task todo display position name. */
    suspend fun todoDisplayPositionSnapshot(): String = todoDisplayPositionFlow.first().name

    /** Restore the task todo display position from a backup; falls back to above the input bar when an old backup has no such field (null) or the value is unrecognised. */
    suspend fun restoreTodoDisplayPosition(position: String?) {
        setTodoDisplayPosition(
            TodoDisplayPosition.entries.firstOrNull { it.name == position }
                ?: TodoDisplayPosition.ABOVE_INPUT_BAR
        )
    }

    /** "Prompts" page announcement shown-content hash; no value means it has never been shown. */
    val promptsAnnouncementShownHashFlow: Flow<String?> = context.generalDataStore.data.map { prefs ->
        prefs[PROMPTS_ANNOUNCEMENT_SHOWN_HASH_KEY]
    }

    suspend fun markPromptsAnnouncementShown(hash: String) {
        context.generalDataStore.edit { it[PROMPTS_ANNOUNCEMENT_SHOWN_HASH_KEY] = hash }
    }

    /** Backup snapshot: the external workspace chat history handling preference. */
    suspend fun deleteExternalWorkspaceSessionsSnapshot(): Boolean = deleteExternalWorkspaceSessionsFlow.first()

    suspend fun restoreDeleteExternalWorkspaceSessions(enabled: Boolean) = setDeleteExternalWorkspaceSessions(enabled)
}