package com.aicode.feature.settings.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aicode.core.datastore.preferencesCorruptionHandler
import com.aicode.feature.agent.domain.container.ContainerProfile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.containerDataStore by preferencesDataStore(
    name = "container_prefs",
    corruptionHandler = preferencesCorruptionHandler
)

/** Downloaded/installed image records from the Download Images page, retained across restarts. */
@Serializable
data class DownloadedImageRecord(
    val entryId: String,
    val fileUri: String,
    val installed: Boolean = false
)

/**
 * Persists the currently selected container profile and the user's custom profile list.
 *
 * DataStore usage matches [ThemeSettingsRepository] (constructor injection is enough, no DI module needed).
 * When [ACTIVE_PROFILE_ID_KEY] is absent, returns [ContainerProfile.BUILTIN_ID], same as before the change — the built-in Alpine by default.
 */
@Singleton
class ContainerSettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private companion object {
        val ACTIVE_PROFILE_ID_KEY = stringPreferencesKey("active_profile_id")
        val CUSTOM_PROFILES_KEY = stringPreferencesKey("custom_profiles_json")
        /** In remote-workspace mode, the container that runs local services such as MCP stdio; defaults to the built-in Alpine when unset. */
        val DEFAULT_CONTAINER_ID_KEY = stringPreferencesKey("default_container_profile_id")
        /** Whether the built-in Alpine default has been written on first launch; once set, clearing the list no longer auto-refills it. */
        val INITIALIZED_KEY = booleanPreferencesKey("initialized")
        /** Hash of the usage-notes announcement content already shown on the "Containers & Images" page; shows again when unset or when it differs from the current content hash. */
        val ANNOUNCEMENT_SHOWN_HASH_KEY = stringPreferencesKey("container_announcement_shown_hash")
        /** Downloaded/installed records from the Download Images page (JSON list). */
        val DOWNLOADED_IMAGES_KEY = stringPreferencesKey("downloaded_images_json")
        val downloadedImageSerializer = ListSerializer(DownloadedImageRecord.serializer())
        val profileSerializer = ListSerializer(ContainerProfile.serializer())
        val json = Json { ignoreUnknownKeys = true }
    }

    /** Currently selected profile id; defaults to the built-in Alpine when unset. */
    val activeProfileIdFlow: Flow<String> = context.containerDataStore.data.map { prefs ->
        prefs[ACTIVE_PROFILE_ID_KEY]?.takeIf { it.isNotBlank() } ?: ContainerProfile.BUILTIN_ID
    }

    /** Default container id in remote mode; defaults to the built-in Alpine when unset. */
    val defaultContainerIdFlow: Flow<String> = context.containerDataStore.data.map { prefs ->
        prefs[DEFAULT_CONTAINER_ID_KEY]?.takeIf { it.isNotBlank() } ?: ContainerProfile.BUILTIN_ID
    }

    /** User custom profile list (excluding built-in). Falls back to an empty list on parse failure. */
    val customProfilesFlow: Flow<List<ContainerProfile>> = context.containerDataStore.data.map { prefs ->
        prefs[CUSTOM_PROFILES_KEY]?.let { raw ->
            runCatching { json.decodeFromString(profileSerializer, raw) }.getOrNull()
        } ?: emptyList()
    }

    suspend fun setActiveProfile(id: String) {
        context.containerDataStore.edit { it[ACTIVE_PROFILE_ID_KEY] = id }
    }

    suspend fun setDefaultContainerId(id: String) {
        context.containerDataStore.edit { it[DEFAULT_CONTAINER_ID_KEY] = id }
    }

    /**
     * First-launch (or old-version upgrade) fallback: adds the built-in Alpine to the list (if missing) so the list always has a built-in entry.
     * After the initialized flag is set it no longer auto-refills — if the user later clears the list, they restore it manually from the empty state.
     * Also adds it on existing devices that already have custom images configured (old-version lists always contained the built-in entry; an upgrade must not make it disappear).
     */
    suspend fun ensureBuiltinDefault() {
        context.containerDataStore.edit { prefs ->
            if (prefs[INITIALIZED_KEY] == true) return@edit
            prefs[INITIALIZED_KEY] = true
            val current = prefs[CUSTOM_PROFILES_KEY]?.let { raw ->
                runCatching { json.decodeFromString(profileSerializer, raw) }.getOrNull()
            } ?: emptyList()
            if (current.none { it.id == ContainerProfile.BUILTIN_ID }) {
                prefs[CUSTOM_PROFILES_KEY] = json.encodeToString(
                    profileSerializer, listOf(ContainerProfile.BUILTIN_ALPINE) + current
                )
            }
        }
    }

    /** Hash of the announcement content already shown; null means it has never been shown. */
    val announcementShownHashFlow: Flow<String?> = context.containerDataStore.data.map { prefs ->
        prefs[ANNOUNCEMENT_SHOWN_HASH_KEY]
    }

    suspend fun markAnnouncementShown(hash: String) {
        context.containerDataStore.edit { it[ANNOUNCEMENT_SHOWN_HASH_KEY] = hash }
    }

    /** Downloaded image record list (deduplicated by entryId, newest wins). */
    val downloadedImagesFlow: Flow<List<DownloadedImageRecord>> = context.containerDataStore.data.map { prefs ->
        prefs[DOWNLOADED_IMAGES_KEY]?.let { raw ->
            runCatching { json.decodeFromString(downloadedImageSerializer, raw) }.getOrNull()
        } ?: emptyList()
    }

    suspend fun upsertDownloadedImage(record: DownloadedImageRecord) {
        context.containerDataStore.edit { prefs ->
            val current = prefs[DOWNLOADED_IMAGES_KEY]?.let { raw ->
                runCatching { json.decodeFromString(downloadedImageSerializer, raw) }.getOrNull()
            } ?: emptyList()
            val merged = current.filterNot { it.entryId == record.entryId } + record
            prefs[DOWNLOADED_IMAGES_KEY] = json.encodeToString(downloadedImageSerializer, merged)
        }
    }

    suspend fun removeDownloadedImage(entryId: String) {
        context.containerDataStore.edit { prefs ->
            val current = prefs[DOWNLOADED_IMAGES_KEY]?.let { raw ->
                runCatching { json.decodeFromString(downloadedImageSerializer, raw) }.getOrNull()
            } ?: emptyList()
            prefs[DOWNLOADED_IMAGES_KEY] =
                json.encodeToString(downloadedImageSerializer, current.filterNot { it.entryId == entryId })
        }
    }

    /**
     * Adds or overwrites a custom profile with the same id.
     * createdAt rule: if the passed value is non-zero, use it; if it is 0 and an existing record exists (edit scenario where form reconstruction lost the time), keep the original time;
     * otherwise treat it as new and write the current time — so saving an edit does not change its sort position.
     */
    suspend fun upsertCustomProfile(profile: ContainerProfile) {
        context.containerDataStore.edit { prefs ->
            val current = prefs[CUSTOM_PROFILES_KEY]?.let { raw ->
                runCatching { json.decodeFromString(profileSerializer, raw) }.getOrNull()
            } ?: emptyList()
            val existing = current.firstOrNull { it.id == profile.id }
            val final = when {
                profile.createdAt != 0L -> profile
                existing != null && existing.createdAt != 0L -> profile.copy(createdAt = existing.createdAt)
                else -> profile.copy(createdAt = System.currentTimeMillis())
            }
            val merged = (current.filterNot { it.id == final.id } + final)
            prefs[CUSTOM_PROFILES_KEY] = json.encodeToString(profileSerializer, merged)
        }
    }

    suspend fun deleteCustomProfile(id: String) {
        context.containerDataStore.edit { prefs ->
            val current = prefs[CUSTOM_PROFILES_KEY]?.let { raw ->
                runCatching { json.decodeFromString(profileSerializer, raw) }.getOrNull()
            } ?: emptyList()
            prefs[CUSTOM_PROFILES_KEY] =
                json.encodeToString(profileSerializer, current.filterNot { it.id == id })
        }
    }
}
