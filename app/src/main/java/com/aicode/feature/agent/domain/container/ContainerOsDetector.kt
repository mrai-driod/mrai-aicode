package com.aicode.feature.agent.domain.container

import android.content.Context
import com.aicode.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Cache of container OS detection results (profile id -> os id, such as `alpine`/`centos`).
 *
 * Detection is performed and written by [LinuxContainerEngine] after a container first runs (reading the ID field of /etc/os-release);
 * [ContainerInstaller] clears the corresponding cache entry when a rootfs is overwritten (reset/reinstall/delete/image switch), so the next run detects again.
 * Cache files live in the app cache directory `cacheDir/container_os/`; after a system cache clear the OS is re-detected automatically, with no stale data left behind.
 */
@Singleton
class ContainerOsDetector @Inject constructor(
    @ApplicationContext context: Context
) {
    private val cacheDir = File(context.cacheDir, "container_os")

    /** Recognized OS types: profile id -> os id. The UI uses this to show the OS icon in the container list. */
    private val _osMap = MutableStateFlow(loadAll())
    val osMap: StateFlow<Map<String, String>> = _osMap.asStateFlow()

    private fun fileFor(profileId: String): File = File(cacheDir, profileId)

    /** Reads the cached OS type for a profile, or null if none. */
    fun cachedOs(profileId: String): String? = _osMap.value[profileId]

    /** Writes a detection result (callers should be on an IO context; a write failure only logs a warning and does not affect use). */
    fun cacheOs(profileId: String, osId: String) {
        runCatching {
            cacheDir.mkdirs()
            fileFor(profileId).writeText(osId)
        }.onFailure { FileLogger.w(TAG, "Failed to write container OS cache: $profileId -> $osId", it) }
        _osMap.value = _osMap.value + (profileId to osId)
    }

    /** Clears a profile's cache (called after its rootfs is overwritten, so the next run detects again). */
    fun clear(profileId: String) {
        runCatching { fileFor(profileId).delete() }
        _osMap.value = _osMap.value - profileId
    }

    private fun loadAll(): Map<String, String> {
        val files = runCatching { cacheDir.listFiles()?.toList() ?: emptyList() }
            .getOrDefault(emptyList())
        return files.filter { it.isFile }
            .mapNotNull { file ->
                val osId = runCatching { file.readText().trim().ifBlank { null } }.getOrNull()
                if (osId != null) file.name to osId else null
            }
            .toMap()
    }

    private companion object {
        const val TAG = "ContainerOsDetector"
    }
}
