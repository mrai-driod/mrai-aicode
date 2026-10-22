package com.aicode.feature.agent.presentation.component

import androidx.compose.ui.graphics.ImageBitmap
import com.aicode.core.ui.ImageSource
import com.aicode.core.ui.ImageViewerRequest
import com.aicode.core.ui.decodeSampledBitmap
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.presentation.AgentAttachment
import com.aicode.feature.workspace.domain.FileAccessProvider
import java.io.File
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "ChatImageLoader"

/**
 * Chat-area image loader: materialises an [ImageSource] into a bitmap for the full-screen viewer. This is the project's single decoding strategy.
 *
 * [ImageSource.ContainerPath] is materialised to a host file via [FileAccessProvider.copyToLocal] - in local PRoot mode
 * that is only a path mapping and produces no copy; in remote SSH mode the file is pulled back as base64 and written to a temp file.
 */
internal fun chatImageLoader(
    fileAccess: FileAccessProvider,
    maxEdge: Int,
    maxPixels: Long
): suspend (ImageSource) -> ImageBitmap? = { source ->
    withContext(Dispatchers.IO) {
        try {
            when (source) {
                is ImageSource.LocalFile ->
                    decodeExistingFile(File(source.path), maxEdge, maxPixels)

                is ImageSource.ContainerPath ->
                    decodeExistingFile(fileAccess.copyToLocal(source.path), maxEdge, maxPixels)

                is ImageSource.Base64 ->
                    decodeSampledBitmap(Base64.getDecoder().decode(source.data), maxEdge, maxPixels)
            }
        } catch (e: Exception) {
            FileLogger.w(TAG, "Image load failed: $source", e)
            null
        }
    }
}

private fun decodeExistingFile(file: File, maxEdge: Int, maxPixels: Long): ImageBitmap? {
    if (!file.isFile || file.length() <= 0L) return null
    return decodeSampledBitmap(file.absolutePath, maxEdge, maxPixels)
}

/**
 * View request for a pending image attachment.
 *
 * `localPath` is preferred, with base64 as fallback: `decodeByteArray` needs the full byte[] resident, on top of the base64 string already held in memory
 * (UTF-16, actually taking twice the space), so a 5MB image can transiently cost ten-odd MB extra; decoding from disk has no such overhead.
 */
internal fun PendingUploadAttachment.toViewerRequest(): ImageViewerRequest = ImageViewerRequest(
    sources = listOfNotNull(
        localPath.takeIf { it.isNotBlank() }?.let { ImageSource.LocalFile(it) },
        image?.base64Data?.takeIf { it.isNotBlank() }?.let { ImageSource.Base64(it) }
    ),
    title = fileName
)

/**
 * View request for a sent attachment.
 *
 * Carries `containerPath` as a fallback: in remote mode the `localPath` recorded by `sendFile` comes from `createTempFile` +
 * `deleteOnExit`, so after a process restart or cache clear the file is gone; it must then be possible to fall back to the container path and fetch it again.
 */
internal fun AgentAttachment.toViewerRequest(): ImageViewerRequest = ImageViewerRequest(
    sources = listOfNotNull(
        localPath.takeIf { it.isNotBlank() }?.let { ImageSource.LocalFile(it) },
        containerPath.takeIf { it.isNotBlank() }?.let { ImageSource.ContainerPath(it) }
    ),
    title = fileName
)

/**
 * View request for a Markdown-embedded image: the link is the container path from the AI's point of view.
 *
 * The library passes alt straight to Image as contentDescription, bypassing the transformer, so it is unavailable here;
 * instead, the file name from the last path segment is used as the title.
 */
internal fun markdownViewerRequest(link: String): ImageViewerRequest = ImageViewerRequest(
    sources = listOf(ImageSource.ContainerPath(link)),
    title = link.trimEnd('/').substringAfterLast('/')
)
