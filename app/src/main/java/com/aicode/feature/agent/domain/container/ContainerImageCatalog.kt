package com.aicode.feature.agent.domain.container

import android.content.Context
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** A downloadable image entry: [path] is a relative path, and the {abi} placeholder is replaced per architecture by [ContainerImageCatalog.urlFor]. */
@Serializable
data class ContainerImageEntry(
    val id: String,
    val name: String,
    val version: String,
    val description: String = "",
    val sizeBytes: Long = 0,
    /** The distribution it belongs to (a key in the global sources table, such as alpine/ubuntu). */
    val distro: String = "",
    val path: String,
    /** Standard architecture key (arm64/x86_64) -> the distribution's actual directory name (such as aarch64/amd64). */
    val abiNames: Map<String, String> = emptyMap()
)

/** A download source: Chinese/English display names + a prefix per distribution (missing/empty means the source does not offer that distribution). */
@Serializable
data class ContainerImageSource(
    val name: Map<String, String> = emptyMap(),
    val distros: Map<String, String> = emptyMap()
)

/** The global image catalog: sources (source id -> definition) + the image list. */
@Serializable
data class ContainerImageCatalogData(
    val sources: Map<String, ContainerImageSource> = emptyMap(),
    val images: List<ContainerImageEntry> = emptyList()
)

/**
 * Loads the downloadable image catalog from the built-in assets/container-images.json.
 * Sources and images are stored separately: URL = the sources[source][distribution] prefix + the entry path ({abi} is replaced per device architecture).
 */
@Singleton
class ContainerImageCatalog @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private var data = ContainerImageCatalogData()

    fun load(): List<ContainerImageEntry> {
        val raw = runCatching {
            context.assets.open(ASSET_FILE).bufferedReader().use { it.readText() }
        }.getOrNull() ?: return emptyList()
        val parsed = runCatching {
            json.decodeFromString<ContainerImageCatalogData>(raw)
        }.getOrNull()
        data = parsed ?: ContainerImageCatalogData()
        return data.images
    }

    /** List of global source ids (in JSON order), for the source switcher in the top-right corner. */
    val sourceIds: List<String>
        get() = data.sources.keys.toList()

    /** Source display name (looked up by the zh/en language key, falling back to any available name if missing); returns null if the source does not exist. */
    fun sourceName(sourceId: String, lang: String): String? {
        val names = data.sources[sourceId]?.name ?: return null
        return names[lang] ?: names.values.firstOrNull()
    }

    /** The full download URL of [entry] from the given source for the current device architecture; returns null if the source/distribution/architecture is missing. */
    fun urlFor(entry: ContainerImageEntry, sourceId: String, abi: String): String? {
        val prefix = data.sources[sourceId]?.distros?.get(entry.distro)
            ?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return null
        val abiName = entry.abiNames[abi] ?: return null
        return "$prefix/${entry.path}".replace("{abi}", abiName)
    }

    companion object {
        private const val ASSET_FILE = "container-images.json"
        private val json = Json { ignoreUnknownKeys = true }

        /** Architecture determination consistent with [com.aicode.feature.agent.domain.container.ContainerInstaller]: x86 devices use x86_64, everything else uses arm64. */
        val CURRENT_ABI: String = if (Build.SUPPORTED_ABIS.any { it.contains("x86") }) "x86_64" else "arm64"
    }
}
