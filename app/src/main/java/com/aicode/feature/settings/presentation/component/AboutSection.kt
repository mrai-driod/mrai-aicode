package com.aicode.feature.settings.presentation.component

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.aicode.core.ui.AdaptiveModalBottomSheet
import androidx.compose.material3.Surface
import com.aicode.core.ui.AppSwitch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import com.aicode.feature.settings.data.repository.UpdateChannel
import compose.icons.FeatherIcons
import compose.icons.feathericons.Book
import compose.icons.feathericons.Check
import compose.icons.feathericons.Github
import compose.icons.feathericons.Globe
import compose.icons.feathericons.RefreshCw
import compose.icons.feathericons.Send
import compose.icons.feathericons.Tag

/**
 * About page: app info at the top, version number (tap to manually check for updates), auto-check-for-updates switch, update channel,
 * GitHub repository, and open-source license. Update-check logic and dialogs are hosted by the global [SettingsViewModel];
 * this page only triggers them and displays the settings items.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AboutSection(
    updateCheckEnabled: Boolean,
    updateCheckChannel: UpdateChannel,
    onToggleUpdateCheck: (Boolean) -> Unit,
    onSelectChannel: (UpdateChannel) -> Unit,
    onCheckUpdate: () -> Unit
) {
    val context = LocalContext.current
    var showChannelSheet by remember { mutableStateOf(false) }

    // Reads versionName/versionCode via PackageManager (the project does not enable BuildConfig)
    val appInfo = remember {
        runCatching {
            val pm = context.packageManager
            val info = pm.getPackageInfo(context.packageName, 0)
            val code = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
            AppVersion(name = info.versionName ?: "unknown", code = code)
        }.getOrDefault(AppVersion("unknown", 0L))
    }

    // App icon: loads the adaptive icon via PackageManager.loadIcon to avoid the mipmap-anydpi XML
    // not being supported in Compose painterResource (Only VectorDrawables and rasterized types).
    val appIcon = remember { loadAppIconBitmap(context) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg)
            .padding(bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        AboutHeaderCard(appName = stringResource(R.string.app_name), appIcon = appIcon)

        SettingsGroup {
            // Version: tap to manually check for updates
            SettingsRow(
                icon = FeatherIcons.Tag,
                title = stringResource(R.string.about_version),
                onClick = onCheckUpdate,
                trailing = {
                    Text(
                        text = "v${appInfo.name}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.semanticColors.subtleText
                    )
                }
            )
            SettingsDivider()
            // Auto check for updates: switch
            SettingsRow(
                icon = FeatherIcons.RefreshCw,
                title = stringResource(R.string.about_auto_check_update),
                subtitle = stringResource(R.string.about_auto_check_update_desc),
                trailing = {
                    AppSwitch(
                        checked = updateCheckEnabled,
                        onCheckedChange = onToggleUpdateCheck
                    )
                }
            )
            SettingsDivider()
            // Update channel
            SettingsRow(
                icon = FeatherIcons.Globe,
                title = stringResource(R.string.about_update_channel),
                onClick = { showChannelSheet = true },
                trailing = {
                    Text(
                        text = stringResource(
                            if (updateCheckChannel == UpdateChannel.STABLE) {
                                R.string.update_channel_stable
                            } else {
                                R.string.update_channel_latest
                            }
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.semanticColors.subtleText
                    )
                }
            )
            SettingsDivider()
            // GitHub repository (this repo, not upstream)
            SettingsRow(
                icon = FeatherIcons.Github,
                title = stringResource(R.string.about_github_repo),
                onClick = { openUrl(context, GITHUB_REPO_URL) }
            )
            SettingsDivider()
            // Telegram group: prefers launching the Telegram app, falls back to the web version if not installed
            SettingsRow(
                icon = FeatherIcons.Send,
                title = stringResource(R.string.about_telegram_group),
                onClick = { openTelegram(context, TELEGRAM_GROUP_URL) }
            )
            SettingsDivider()
            // License
            SettingsRow(
                icon = FeatherIcons.Book,
                title = stringResource(R.string.about_license),
                onClick = { openUrl(context, LICENSE_URL) }
            )
        }
    }

    if (showChannelSheet) {
        UpdateChannelSheet(
            current = updateCheckChannel,
            onSelect = {
                onSelectChannel(it)
                showChannelSheet = false
            },
            onDismiss = { showChannelSheet = false }
        )
    }
}

/** Top info card: app icon on the left, app name at top right, a one-line description below it. */
@Composable
private fun AboutHeaderCard(appName: String, appIcon: androidx.compose.ui.graphics.ImageBitmap?) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.semanticColors.cardSurface,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(Spacing.lg),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (appIcon != null) {
                Image(
                    bitmap = appIcon,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp)
                )
            } else {
                // Placeholder for the edge case where the icon fails to load, to keep the layout in place
                Spacer(Modifier.size(48.dp))
            }
            Spacer(Modifier.width(Spacing.lg))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = appName,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(R.string.about_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** Update channel bottom sheet: Stable / Latest single choice, styled to match the language switcher. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UpdateChannelSheet(
    current: UpdateChannel,
    onSelect: (UpdateChannel) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()
    AdaptiveModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = Spacing.xl)
        ) {
            Text(
                text = stringResource(R.string.about_update_channel),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(horizontal = Spacing.lg)
                    .padding(bottom = Spacing.md)
            )

            listOf(
                UpdateChannel.STABLE to stringResource(R.string.update_channel_stable),
                UpdateChannel.LATEST to stringResource(R.string.update_channel_latest)
            ).forEach { (channel, label) ->
                val selected = channel == current
                Surface(
                    onClick = {
                        onDismiss()
                        onSelect(channel)
                    },
                    color = Color.Transparent
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                            )
                            Text(
                                text = stringResource(
                                    if (channel == UpdateChannel.STABLE) {
                                        R.string.update_channel_stable_desc
                                    } else {
                                        R.string.update_channel_latest_desc
                                    }
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (selected) {
                            Icon(
                                imageVector = FeatherIcons.Check,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }
}

/** GitHub tags look like v1.7.0 / 1.7.0 / v1.7.0-rc1; extracts the bare version number. */
internal fun parseVersionTag(tag: String): String? {
    val raw = tag.trim().removePrefix("v")
    val seg = raw.substringBefore(' ')
    return seg.ifBlank { null }
}

/**
 * Semantic version comparison: determines whether the latest version [latest] is strictly greater than the current version [current].
 * If latest is not greater than current (i.e. the latest version is already installed or the current build is a dev/test build), returns true to indicate it is up to date.
 */
internal fun isUpToDate(latest: String, current: String): Boolean {
    val cmp = compareVersions(latest, current)
    return cmp <= 0
}

/**
 * Semantic version comparator (SemVer compatible):
 * Returns >0 if v1 > v2, <0 if v1 < v2, 0 if equal.
 * Rules:
 * 1. Compare major.minor.patch first (e.g. 1.7.0 > 1.6.9)
 * 2. When major/minor/patch are equal, release > pre-release (1.7.0 > 1.7.0-rc1 > 1.7.0-dev)
 * 3. When both are pre-releases, compare the number for the same identifier (rc2 > rc1, dev.10 > dev.9); different identifiers compare lexicographically
 */
internal fun compareVersions(v1: String, v2: String): Int {
    if (v1 == v2) return 0

    val (base1, pre1) = splitVersion(v1)
    val (base2, pre2) = splitVersion(v2)

    val parts1 = base1.split('.').mapNotNull { it.toIntOrNull() }
    val parts2 = base2.split('.').mapNotNull { it.toIntOrNull() }

    val maxLen = maxOf(parts1.size, parts2.size)
    for (i in 0 until maxLen) {
        val p1 = parts1.getOrElse(i) { 0 }
        val p2 = parts2.getOrElse(i) { 0 }
        if (p1 != p2) return p1.compareTo(p2)
    }

    // When the main version numbers are the same:
    // release (pre is empty) > pre-release (pre is not empty)
    if (pre1.isEmpty() && pre2.isNotEmpty()) return 1
    if (pre1.isNotEmpty() && pre2.isEmpty()) return -1

    return comparePreRelease(pre1, pre2)
}

private val PRE_RELEASE_TOKEN = Regex("^(\\D*?)(\\d+)$")

/**
 * Pre-release segment comparison: "same identifier + number" compares numerically (rc10 > rc9), avoiding lexicographic ordering that would place rc10 before rc9;
 * falls back to lexicographic order when identifiers differ or there is no trailing number.
 */
private fun comparePreRelease(a: String, b: String): Int {
    val ma = PRE_RELEASE_TOKEN.matchEntire(a)
    val mb = PRE_RELEASE_TOKEN.matchEntire(b)
    if (ma != null && mb != null && ma.groupValues[1] == mb.groupValues[1]) {
        return ma.groupValues[2].toInt().compareTo(mb.groupValues[2].toInt())
    }
    return a.compareTo(b)
}

internal fun splitVersion(v: String): Pair<String, String> {
    val clean = v.substringBefore('+') // Removes the build hash, e.g. +g04bc2fa
    val base = clean.substringBefore('-')
    val pre = if (clean.contains('-')) clean.substringAfter('-') else ""
    return base to pre
}

private data class AppVersion(val name: String, val code: Long)

/**
 * Loads the app icon via PackageManager.loadIcon and converts it to an ImageBitmap, handling adaptive icons
 * (adaptive icon XML). Fixes painterResource(R.mipmap.ic_launcher) throwing IllegalArgumentException on v26+ devices
 * because it resolves to mipmap-anydpi-v26/ic_launcher.xml.
 * Returns null on failure (the caller reserves a placeholder).
 */
private fun loadAppIconBitmap(context: Context): ImageBitmap? {
    return runCatching {
        val pm = context.packageManager
        val drawable: Drawable = pm.getApplicationInfo(context.packageName, 0).loadIcon(pm)
        val sizePx = android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_DIP,
            ICON_PX_DP.toFloat(),
            context.resources.displayMetrics
        ).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, sizePx, sizePx)
        Canvas(bitmap).also { drawable.draw(it) }
        bitmap.asImageBitmap()
    }.getOrNull()
}

private const val GITHUB_REPO_URL = "https://github.com/mrai-driod/mrai-aicode"
private const val LICENSE_URL = "https://github.com/mrai-driod/mrai-aicode/blob/main/LICENSE"
private const val TELEGRAM_GROUP_URL = "https://t.me/maoleikernel"
private const val ICON_PX_DP = 48

/**
 * Opens the Telegram group: prefers the tg:// scheme to launch the Telegram app (directly to the group by domain),
 * and falls back to the t.me web version in the system browser when the app is not installed (ActivityNotFoundException).
 */
private fun openTelegram(context: Context, webUrl: String) {
    val domain = webUrl.removePrefix("https://t.me/")
    val appLaunched = runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("tg://resolve?domain=$domain")))
    }.isSuccess
    if (!appLaunched) {
        openUrl(context, webUrl)
    }
}
