package com.aicode.feature.settings.presentation.component

import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import com.aicode.core.ui.AdaptiveModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import com.aicode.core.ui.SwipeToDeleteRow
import com.aicode.feature.agent.domain.container.ContainerImageEntry
import com.aicode.feature.settings.data.repository.DownloadedImageRecord
import com.aicode.feature.settings.presentation.ContainerImageDownloadUiState
import compose.icons.FeatherIcons
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.HardDrive
import java.io.File
import kotlinx.coroutines.launch

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / 1024.0 / 1024 / 1024)
    bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024)
    bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

/** Distribution logo (icon style consistent with the Containers & Images page); returns null when unrecognised (the caller falls back to a generic icon). */
@Composable
private fun distroLogo(distro: String): Painter? = when (distro) {
    "alpine" -> painterResource(R.drawable.logo_alpine)
    "ubuntu" -> painterResource(R.drawable.logo_ubuntu)
    "debian" -> painterResource(R.drawable.logo_debian)
    "centos" -> painterResource(R.drawable.logo_centos)
    else -> null
}

/**
 * Download image page: lists downloadable images from the built-in catalogue (assets/container-images.json).
 * The row style matches "Containers & Images": distribution icon on the left + name/description + right arrow; tapping a row opens the detail sheet;
 * downloaded rows can be swiped left to delete (file + record). The download source is switched in a bottom sheet from the top-right corner of the page;
 * downloads proceed asynchronously, and after the sheet is closed the row continues to show progress; "Import" adds the image to the container list and can be imported repeatedly.
 */
@Composable
internal fun ContainerImageDownloadSection(
    catalog: List<ContainerImageEntry>,
    state: ContainerImageDownloadUiState,
    downloadedImages: Map<String, DownloadedImageRecord>,
    sourceUnavailableIds: Set<String>,
    selectedSourceName: String,
    onDownload: (ContainerImageEntry) -> Unit,
    onCancel: () -> Unit,
    onImport: (String, String) -> Unit,
    onDelete: (String) -> Unit
) {
    val context = LocalContext.current
    var detailEntry by remember { mutableStateOf<ContainerImageEntry?>(null) }
    var deleteEntry by remember { mutableStateOf<ContainerImageEntry?>(null) }
    // Only show images available from the current source; the list is filtered automatically after switching sources
    val visibleCatalog = catalog.filter { it.id !in sourceUnavailableIds }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg)
            .padding(bottom = Spacing.xl)
    ) {
        SettingsGroupHeader(text = stringResource(R.string.container_download_image))
        if (visibleCatalog.isEmpty()) {
            SettingsGroup {
                Text(
                    text = stringResource(R.string.container_download_list_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(Spacing.lg)
                )
            }
        } else {
            SettingsGroup {
                visibleCatalog.forEachIndexed { index, entry ->
                    if (index > 0) SettingsDivider()
                    val record = downloadedImages[entry.id]
                    val fileExists = record?.let { File(Uri.parse(it.fileUri).path ?: "").exists() } == true
                    DownloadImageRow(
                        entry = entry,
                        state = state,
                        isDownloaded = record != null && fileExists,
                        onOpenDetail = { detailEntry = entry },
                        onDelete = { deleteEntry = entry }
                    )
                }
            }
        }
    }

    detailEntry?.let { entry ->
        ImageDetailSheet(
            entry = entry,
            state = state,
            record = downloadedImages[entry.id],
            sourceUnavailable = entry.id in sourceUnavailableIds,
            sourceName = selectedSourceName,
            onDownload = { onDownload(entry) },
            onCancel = onCancel,
            onImport = { fileUri ->
                onImport(entry.id, fileUri)
                Toast.makeText(
                    context,
                    context.getString(R.string.container_download_imported_toast),
                    Toast.LENGTH_SHORT
                ).show()
            },
            onDismiss = { detailEntry = null }
        )
    }

    deleteEntry?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleteEntry = null },
            title = { Text(stringResource(R.string.container_download_delete_title)) },
            text = { Text(stringResource(R.string.container_download_delete_confirm, "${entry.name} ${entry.version}")) },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(entry.id)
                    deleteEntry = null
                }) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteEntry = null }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }
}

/** List row: icon + name (with a badge when downloaded) / description (a progress bar is shown while downloading) + right arrow; all rows can be swiped left, and the delete button is disabled when not downloaded. */
@Composable
private fun DownloadImageRow(
    entry: ContainerImageEntry,
    state: ContainerImageDownloadUiState,
    isDownloaded: Boolean,
    onOpenDetail: () -> Unit,
    onDelete: () -> Unit
) {
    val light = MaterialTheme.colorScheme.background.luminance() > 0.5f

    val downloading = state as? ContainerImageDownloadUiState.Downloading

    SwipeToDeleteRow(
        onDelete = onDelete,
        onClick = onOpenDetail,
        deleteEnabled = isDownloaded
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Distribution icon on the left
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                val logo = distroLogo(entry.distro)
                if (logo != null) {
                    Icon(
                        painter = logo,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp)
                    )
                } else {
                    Icon(
                        imageVector = FeatherIcons.HardDrive,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Name + description (a progress bar is shown while downloading)
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${entry.name} ${entry.version}",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (isDownloaded) {
                        Box(
                            modifier = Modifier
                                .padding(start = 6.dp)
                                .background(MaterialTheme.semanticColors.success.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 1.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.container_download_done),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.semanticColors.success
                            )
                        }
                    }
                }
                downloading?.takeIf { it.entryId == entry.id }?.let { d ->
                    Spacer(modifier = Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = {
                            if (d.totalBytes > 0) {
                                (d.bytesRead.toFloat() / d.totalBytes).coerceIn(0f, 1f)
                            } else {
                                0f
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "${formatBytes(d.bytesRead)} / " +
                            (if (d.totalBytes > 0) formatBytes(d.totalBytes) else "?"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                } ?: run {
                    Text(
                        text = entry.description.ifBlank { if (isDownloaded) stringResource(R.string.container_download_done) else "" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }

            // Arrow on the right
            Icon(
                imageVector = FeatherIcons.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

/** Image detail bottom sheet: information + download/progress/import action area. Closing the sheet does not affect the background download. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImageDetailSheet(
    entry: ContainerImageEntry,
    state: ContainerImageDownloadUiState,
    record: DownloadedImageRecord?,
    sourceUnavailable: Boolean,
    sourceName: String,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onImport: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()
    val fileExists = record?.let { File(Uri.parse(it.fileUri).path ?: "").exists() } == true
    val isDownloaded = record != null && fileExists
    val downloading = state as? ContainerImageDownloadUiState.Downloading
    val isDownloading = downloading?.entryId == entry.id
    val error = (state as? ContainerImageDownloadUiState.Error)?.takeIf { it.entryId == entry.id }

    AdaptiveModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xl)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(10.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    val logo = distroLogo(entry.distro)
                    if (logo != null) {
                        Icon(
                            painter = logo,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp)
                        )
                    } else {
                        Icon(
                            imageVector = FeatherIcons.HardDrive,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "${entry.name} ${entry.version}",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (isDownloaded) {
                        Text(
                            text = stringResource(R.string.container_download_done),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.semanticColors.success
                        )
                    }
                }
            }

            if (entry.description.isNotBlank()) {
                Text(
                    text = entry.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.md)
                )
            }

            Column(modifier = Modifier.padding(top = Spacing.md), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp)) {
                if (entry.sizeBytes > 0) {
                    Text(
                        text = stringResource(R.string.container_download_size, formatBytes(entry.sizeBytes)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = stringResource(R.string.container_download_architecture, Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(R.string.container_download_current_source, sourceName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            when {
                isDownloading -> {
                    val d = downloading
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "${formatBytes(d.bytesRead)} / " +
                                    (if (d.totalBytes > 0) formatBytes(d.totalBytes) else "?"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = onCancel) {
                                Text(stringResource(R.string.container_download_cancel))
                            }
                        }
                        LinearProgressIndicator(
                            progress = {
                                if (d.totalBytes > 0) {
                                    (d.bytesRead.toFloat() / d.totalBytes).coerceIn(0f, 1f)
                                } else {
                                    0f
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                isDownloaded -> {
                    Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = onDownload,
                            modifier = Modifier.weight(1f).height(44.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(stringResource(R.string.container_download_redownload))
                        }
                        Button(
                            onClick = { onImport(record!!.fileUri) },
                            modifier = Modifier.weight(1f).height(44.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(stringResource(R.string.container_download_import))
                        }
                    }
                }
                error != null -> {
                    Column {
                        Text(
                            text = stringResource(R.string.container_download_failed, error.message),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        Button(
                            onClick = onDownload,
                            modifier = Modifier.fillMaxWidth().height(44.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(stringResource(R.string.container_download_redownload))
                        }
                    }
                }
                else -> {
                    if (sourceUnavailable) {
                        Text(
                            text = stringResource(R.string.container_download_source_unavailable),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                    Button(
                        onClick = onDownload,
                        enabled = !sourceUnavailable,
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(stringResource(R.string.container_download_start))
                    }
                }
            }
        }
    }
}
