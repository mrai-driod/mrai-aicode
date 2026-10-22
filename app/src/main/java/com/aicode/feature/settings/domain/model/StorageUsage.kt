package com.aicode.feature.settings.domain.model

import androidx.annotation.StringRes
import com.aicode.R

/**
 * Storage usage category. **Categories are mutually exclusive** and sum to the app's total usage; the enum order is the display order in the UI.
 *
 * Category boundaries are defined by physical directory: the directories holding logs and caches are not counted again in [OtherData],
 * and chat attachments inside workspaces count under [Workspaces] (mentioned only as a subtitle hint, not as a separate category).
 */
enum class StorageCategory(@param:StringRes val labelRes: Int) {
    Chat(R.string.storage_category_chat),
    Container(R.string.storage_category_container),
    ContainerImages(R.string.storage_category_container_images),
    Workspaces(R.string.storage_category_workspaces),
    AiConfig(R.string.storage_category_ai_config),
    Checkpoints(R.string.storage_category_checkpoints),
    Logs(R.string.storage_category_logs),
    Caches(R.string.storage_category_caches),
    OtherData(R.string.storage_category_other),
    Apk(R.string.storage_category_apk)
}

/**
 * A single detail entry under a category (container name, project name, session title, subdirectory name, etc.).
 *
 * @param key Stable identifier the UI uses to look up values per item (e.g. finding the cleanable size by `tool-output`); null for display-only items.
 * @param indent Whether to display indented as a drill-down item under the previous detail level.
 */
data class StorageDetail(
    val label: String,
    val bytes: Long,
    val note: String? = null,
    val key: String? = null,
    val indent: Boolean = false
)

data class StorageEntry(
    val category: StorageCategory,
    val bytes: Long,
    val details: List<StorageDetail> = emptyList()
)

/** [StorageDetail.key] values: decouples which detail items are cleanable from their directory names. */
object StorageDetailKey {
    const val TOOL_OUTPUT = "toolOutput"
    const val VISION_SESSIONS = "visionSessions"
}

/** Cleanable items: all temporary data that can be rebuilt automatically or only affects viewing history, containing no user content. */
enum class CleanupKind(
    @param:StringRes val labelRes: Int,
    @param:StringRes val descRes: Int
) {
    Caches(R.string.storage_clean_caches, R.string.storage_clean_caches_desc),
    Logs(R.string.storage_clean_logs, R.string.storage_clean_logs_desc),
    ToolOutput(R.string.storage_clean_tool_output, R.string.storage_clean_tool_output_desc),
    VisionSessions(R.string.storage_clean_vision, R.string.storage_clean_vision_desc)
}

/** Capacity information for the storage volume (the volume holding the app's private directory). */
data class DeviceSpace(
    val totalBytes: Long,
    val availableBytes: Long
)

/** Converts a byte count to readable text. Always uses the decimal point from [java.util.Locale.US] to avoid following the system locale and becoming a comma. */
internal fun formatStorageSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format(java.util.Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
    bytes >= 1024L -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}
