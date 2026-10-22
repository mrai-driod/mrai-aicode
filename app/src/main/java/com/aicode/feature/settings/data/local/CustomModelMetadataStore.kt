package com.aicode.feature.settings.data.local

import android.content.Context
import com.aicode.core.util.FileLogger
import com.aicode.feature.settings.domain.model.ModelMetadata
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local cache store for custom model metadata (kept in the app cache directory, which the system may clear at any time).
 * The key is the composite "provider ID:model name" key; it takes priority over fetched/built-in metadata.
 */
@Singleton
class CustomModelMetadataStore @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()

    suspend fun all(): Map<String, ModelMetadata> = mutex.withLock {
        withContext(Dispatchers.IO) { read() }
    }

    suspend fun get(providerId: String, model: String): ModelMetadata? =
        all()[key(providerId, model)]

    suspend fun put(providerId: String, model: String, metadata: ModelMetadata) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val map = read().toMutableMap()
            map[key(providerId, model)] = metadata
            write(map)
        }
    }

    suspend fun remove(providerId: String, model: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val map = read().toMutableMap()
            if (map.remove(key(providerId, model)) != null) {
                write(map)
            }
        }
    }

    private fun key(providerId: String, model: String): String = "$providerId:$model"

    private fun read(): Map<String, ModelMetadata> {
        val f = file()
        if (!f.isFile) return emptyMap()
        return runCatching {
            json.decodeFromString<Map<String, ModelMetadata>>(f.readText(Charsets.UTF_8))
        }.getOrElse {
            FileLogger.w(TAG, "Failed to read custom model metadata", it)
            emptyMap()
        }
    }

    private fun write(map: Map<String, ModelMetadata>) {
        runCatching {
            file().writeText(json.encodeToString(map), Charsets.UTF_8)
        }.onFailure {
            FileLogger.w(TAG, "Failed to write custom model metadata", it)
        }
    }

    private fun file(): File = File(context.cacheDir, FILE_NAME)

    private companion object {
        const val TAG = "CustomModelMetadataStore"
        const val FILE_NAME = "custom-model-metadata.json"
    }
}