package com.aicode.feature.agent.presentation.component

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.Image as ComposeImage
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.aicode.R
import com.aicode.core.theme.Brand
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.core.ui.LocalImageViewer
import com.aicode.core.ui.THUMBNAIL_MAX_EDGE
import com.aicode.core.ui.decodeSampledBitmap
import com.aicode.feature.agent.presentation.AgentAttachment
import com.aicode.feature.workspace.domain.FileAccessProvider
import compose.icons.FeatherIcons
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.FileText
import compose.icons.feathericons.Image
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Attachment thumbnail side length: the small image on the left when there is one file per row; just big enough to tell a screenshot from a photo. */
private val AttachmentThumbSize = 44.dp

/**
 * List of sent / generated attachments: **one file per row**.
 *
 * Each row is "left thumbnail (a real thumbnail for images, a type icon otherwise) + file name + size · container path",
 * and the whole row is tappable. No more horizontally scrolling strip of square cards: in a 76dp cell a file name is bound to be truncated and multiple files need sideways swiping,
 * whereas one per row makes it clear at a glance which files were sent.
 *
 * Tap semantics: images always go through the built-in full-screen viewer (implicitly relying on an ancestor in the composition tree providing [LocalImageViewer], which the chat area always does);
 * non-images use the [onClick] given by the caller - tool cards pass "open in system app", while assistant bubbles pass none and are therefore not tappable.
 */
@Composable
internal fun MessageAttachmentList(
    attachments: List<AgentAttachment>,
    onClick: ((AgentAttachment) -> Unit)? = null
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.xs),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        attachments.forEach { attachment ->
            MessageAttachmentRow(attachment = attachment, onClick = onClick)
        }
    }
}

@Composable
private fun MessageAttachmentRow(
    attachment: AgentAttachment,
    onClick: ((AgentAttachment) -> Unit)? = null
) {
    val viewer = LocalImageViewer.current
    val handler: (() -> Unit)? = if (attachment.isImage) {
        { viewer.show(attachment.toViewerRequest()) }
    } else if (onClick != null) {
        { onClick(attachment) }
    } else {
        null
    }
    val fallbackName = stringResource(R.string.common_file)
    val name = attachment.fileName.ifBlank { fallbackName }
    // For screen readers and long-press hints: images are "Preview", others are "Open"; the two land in different places.
    val actionLabel = if (attachment.isImage) {
        stringResource(R.string.chat_attachment_preview, name)
    } else {
        stringResource(R.string.chat_attachment_open, name)
    }
    // Secondary info: size + container path. The path is key information for "a file the AI sent" (it can be found directly in the terminal/editor),
    // and when the whole row does not fit it is truncated with an ellipsis, never wrapped.
    val meta = remember(attachment.sizeBytes, attachment.containerPath) {
        listOf(formatBytes(attachment.sizeBytes), attachment.containerPath)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
    }
    Surface(
        shape = RoundedCornerShape(Radius.md),
        color = chatMutedSurfaceColor(),
        modifier = Modifier
            .fillMaxWidth()
            // Clip the rounded corners before attaching clickable: the ripple is then clipped inside the corners instead of spilling into a right-angled square
            .clip(RoundedCornerShape(Radius.md))
            .then(
                if (handler != null) {
                    Modifier.clickable(onClickLabel = actionLabel, onClick = handler)
                } else {
                    Modifier
                }
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.sm, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            AttachmentThumb(attachment = attachment)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = meta,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            // Only show an indicator arrow when there is an action: non-tappable rows should not look tappable (such as non-image attachments in assistant bubbles)
            if (handler != null) {
                Icon(
                    FeatherIcons.ChevronRight,
                    contentDescription = null,
                    tint = Brand.IconGray,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun AttachmentThumb(attachment: AgentAttachment) {
    Surface(
        shape = RoundedCornerShape(Radius.sm),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.size(AttachmentThumbSize)
    ) {
        if (attachment.isImage) {
            AttachmentImageThumb(attachment = attachment)
        } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    FeatherIcons.FileText,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun AttachmentImageThumb(attachment: AgentAttachment) {
    // Disk reads + decoding go on the IO thread: previously decodeFile ran synchronously inside remember, janking the main thread when scrolling to a message with images.
    // Sampling and OOM fallback both go through decodeSampledBitmap.
    val bitmap by produceState<ImageBitmap?>(null, attachment.localPath) {
        value = withContext(Dispatchers.IO) {
            decodeSampledBitmap(attachment.localPath, THUMBNAIL_MAX_EDGE)
        }
    }
    val loaded = bitmap
    if (loaded != null) {
        ComposeImage(
            bitmap = loaded,
            contentDescription = attachment.fileName.ifBlank { stringResource(R.string.common_image_preview) },
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
    } else {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                FeatherIcons.Image,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/**
 * Opens a sent non-image attachment (with a system app). The default is an empty implementation, following the same pattern as `LocalImageViewer` -
 * cards are rendered in intermediate layers such as `ToolMessageBody`, so a CompositionLocal avoids threading callback parameters all the way through and
 * spares intermediate layers from changing their signatures just to forward a lambda.
 */
fun interface AttachmentOpener {
    fun open(attachment: AgentAttachment)
}

internal val LocalAttachmentOpener = staticCompositionLocalOf<AttachmentOpener> { AttachmentOpener { } }

/**
 * Opens an attachment file with the corresponding system app (a FileProvider-authorised URI).
 *
 * The [AgentAttachment.localPath] recorded on a card is not guaranteed to still be valid: in remote SSH mode it is a temporary `copyToLocal` copy
 * (which disappears after a process restart or a system cache clear), and files from external local-directory workspaces or custom mount sources fall outside the directory tree declared by the FileProvider.
 * So when it is stale, fall back to [AgentAttachment.containerPath] and re-fetch through [fileAccess]; if the re-fetched path still cannot be authorised,
 * copy it into cacheDir (already covered by cache-path) for sharing. A genuinely missing file and a final inability to share are reported separately, no longer conflated into one message.
 */
internal suspend fun openSentAttachment(
    context: Context,
    attachment: AgentAttachment,
    fileAccess: FileAccessProvider
) {
    val local = withContext(Dispatchers.IO) { resolveLocalFile(attachment, fileAccess) }
    if (local == null) {
        Toast.makeText(context, context.getString(R.string.chat_open_file_missing), Toast.LENGTH_SHORT).show()
        return
    }
    // APK install package: the system installer requires the "allow installing unknown apps" permission; when it is not granted, guide the user to the Settings page to enable it,
    // otherwise a tap only pops up a "no permission to install" refusal.
    if (isApk(attachment)) {
        if (!context.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(context, context.getString(R.string.chat_open_apk_permission), Toast.LENGTH_LONG).show()
            try {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${context.packageName}")
                    )
                )
            } catch (e: Exception) {
                Toast.makeText(context, context.getString(R.string.chat_open_file_no_app), Toast.LENGTH_SHORT).show()
            }
            return
        }
    }
    val shareable = withContext(Dispatchers.IO) {
        if (isShareable(context, local)) local else copyToShareCache(context, local)
    }
    val uri = shareable?.let {
        runCatching { FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", it) }.getOrNull()
    }
    if (uri == null) {
        Toast.makeText(context, context.getString(R.string.chat_open_file_unshareable), Toast.LENGTH_SHORT).show()
        return
    }
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, attachment.mimeType.ifBlank { "*/*" })
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(context, context.getString(R.string.chat_open_file_no_app), Toast.LENGTH_SHORT).show()
    }
}

/** When the host path recorded on a card is stale (remote temp copy cleared, file deleted or moved), fall back to the container path and re-fetch. */
private suspend fun resolveLocalFile(attachment: AgentAttachment, fileAccess: FileAccessProvider): File? {
    attachment.localPath.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.isFile }?.let { return it }
    val containerPath = attachment.containerPath
    if (containerPath.isBlank()) return null
    return runCatching { fileAccess.copyToLocal(containerPath) }.getOrNull()?.takeIf { it.isFile }
}

/** A path inside the directory tree declared by the FileProvider can be authorised directly; otherwise it throws, so this is judged by whether a URI can be obtained. */
private fun isShareable(context: Context, file: File): Boolean =
    runCatching { FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file) }.isSuccess

/**
 * Copies a file that cannot be authorised directly (external local-directory workspace, custom mount source, SD card, etc.) into cacheDir and then shares it.
 * The target directory is separated by a hash of the source path: this both avoids same-named files overwriting each other and keeps the hash out of the shared file name.
 */
private fun copyToShareCache(context: Context, source: File): File? = runCatching {
    val dir = File(File(context.cacheDir, SHARE_CACHE_DIR), source.absolutePath.hashCode().toString())
    dir.mkdirs()
    val target = File(dir, source.name.ifBlank { "file" })
    source.copyTo(target, overwrite = true)
    target
}.getOrNull()

private const val SHARE_CACHE_DIR = "sent_files"

private fun isApk(attachment: AgentAttachment): Boolean {
    if (attachment.mimeType.equals("application/vnd.android.package-archive", ignoreCase = true)) return true
    val name = attachment.fileName.lowercase()
    return name.endsWith(".apk") || name.endsWith(".apks") || name.endsWith(".xapk")
}
