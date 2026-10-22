package com.aicode.feature.settings.data.remote

import android.content.Context
import com.aicode.R
import com.aicode.feature.settings.data.repository.UpdateChannel
import com.aicode.feature.settings.presentation.component.compareVersions
import com.aicode.feature.settings.presentation.component.parseVersionTag
import com.google.gson.JsonParser
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Changelog for a single version. */
data class VersionUpdate(
    val tag: String,
    val changelog: String
)

/** Fetched update info: latest version tag + changelog from the current version up to the latest. */
data class UpdateInfo(
    val latestTag: String,
    val changelog: String,
    val updates: List<VersionUpdate>
)

/** Update check result. */
sealed interface UpdateCheckResult {
    data object UpToDate : UpdateCheckResult
    data class NewVersion(val info: UpdateInfo) : UpdateCheckResult
    data class Error(val message: String) : UpdateCheckResult
}

/**
 * Fetches version info from GitHub Releases and builds a changelog.
 *
 * The stable channel filters out prereleases (RC), the latest channel includes prereleases; only releases strictly higher than the current version are counted,
 * and the changelog is concatenated from highest to lowest version (version tag + body, with light cleanup of markdown headings/bold).
 * Results are not persisted; the caller displays them as needed.
 */
@Singleton
class UpdateCheckService @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    suspend fun checkForUpdate(currentVersion: String, channel: UpdateChannel): UpdateCheckResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val req = okhttp3.Request.Builder()
                    .url("$GITHUB_RELEASES_API?per_page=50")
                    .header("Accept", "application/vnd.github+json")
                    .build()
                SHARED_CLIENT.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        return@use UpdateCheckResult.Error("HTTP ${resp.code}")
                    }
                    val body = resp.body?.string().orEmpty()
                    val array = runCatching { JsonParser.parseString(body).asJsonArray }
                        .getOrNull()
                        ?: return@use UpdateCheckResult.Error(context.getString(R.string.about_parse_version_failed))

                    val releases = array.mapNotNull { el ->
                        val obj = el.asJsonObject
                        val tag = obj.get("tag_name")?.asString ?: return@mapNotNull null
                        val version = parseVersionTag(tag) ?: return@mapNotNull null
                        ReleaseInfo(
                            version = version,
                            rawTag = tag,
                            notes = obj.get("body")?.asString.orEmpty(),
                            prerelease = obj.get("prerelease")?.asBoolean ?: false
                        )
                    }

                    val updates = releases
                        .filter { if (channel == UpdateChannel.STABLE) !it.prerelease else true }
                        .filter { compareVersions(it.version, currentVersion) > 0 }
                        .sortedWith { a, b -> compareVersions(b.version, a.version) }

                    if (updates.isEmpty()) {
                        UpdateCheckResult.UpToDate
                    } else {
                        val versionUpdates = updates.map { toVersionUpdate(it) }
                        UpdateCheckResult.NewVersion(
                            UpdateInfo(
                                latestTag = versionUpdates.first().tag,
                                changelog = versionUpdates.joinToString("\n\n") { "${it.tag}\n${it.changelog}" },
                                updates = versionUpdates
                            )
                        )
                    }
                }
            }.getOrElse { UpdateCheckResult.Error(it.message ?: context.getString(R.string.about_network_error)) }
        }

    private fun toVersionUpdate(release: ReleaseInfo): VersionUpdate {
        val notes = release.notes.trim()
        return VersionUpdate(
            tag = release.rawTag,
            changelog = if (notes.isEmpty()) {
                context.getString(R.string.about_no_changelog)
            } else {
                cleanMarkdown(notes)
            }
        )
    }

    /** Lightly cleans markdown: strips leading heading markers and bold markers, and removes GitHub auto-generated title/link lines, keeping plain text for direct reading. */
    private fun cleanMarkdown(text: String): String = text
        .lines()
        .map { it.trimEnd().replace("**", "") }
        .map { it.replace(Regex("^#{1,6}\\s+"), "") }
        .filterNot { line ->
            val t = line.trim()
            t.startsWith("What's Changed") ||
                t.startsWith("What's New") ||
                t.startsWith("Full Changelog:")
        }
        .joinToString("\n")

    private data class ReleaseInfo(
        val version: String,
        val rawTag: String,
        val notes: String,
        val prerelease: Boolean
    )

    private companion object {
        const val GITHUB_RELEASES_API = "https://api.github.com/repos/mrai-driod/mrai-aicode/releases"
        val SHARED_CLIENT by lazy {
            okhttp3.OkHttpClient.Builder()
                .proxyAuthenticator(com.aicode.core.net.AppProxy.okHttpAuthenticator)
                .build()
        }
    }
}
