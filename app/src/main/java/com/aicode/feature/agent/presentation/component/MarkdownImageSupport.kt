package com.aicode.feature.agent.presentation.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aicode.R
import com.aicode.core.ui.LocalImageViewer
import com.aicode.core.ui.MARKDOWN_MAX_EDGE
import com.aicode.core.ui.decodeSampledBitmap
import com.aicode.core.util.FileLogger
import com.aicode.feature.workspace.domain.FileAccessProvider
import com.mikepenz.markdown.model.ImageData
import com.mikepenz.markdown.model.ImageTransformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * CompositionLocal for the Markdown image transformer.
 * The default empty implementation (returning null) does not render image nodes, matching the behaviour before this feature was introduced.
 */
internal val LocalMarkdownImageTransformer = staticCompositionLocalOf<ImageTransformer> { NoOpImageTransformer }

private object NoOpImageTransformer : ImageTransformer {
    @Composable
    override fun transform(link: String): ImageData? = null
}

/**
 * Markdown image renderer that loads local images only.
 *
 * Supports local paths from the AI container's point of view (`~/workspace/...`, container absolute paths, relative paths) and `file://`,
 * materialised to a host file via [FileAccessProvider.copyToLocal] (remote mode downloads to a temp file automatically);
 * network schemes (http/https/data/content, etc.) are never rendered.
 *
 * In body text, sampled per [MARKDOWN_MAX_EDGE] and scaled proportionally with a height cap (GIFs show only the first frame); tapping opens the full-screen viewer
 * to see the original image, where a clearer copy is re-decoded to the screen size.
 */
internal class MarkdownImageTransformer(
    private val fileAccess: FileAccessProvider
) : ImageTransformer {

    @Composable
    override fun transform(link: String): ImageData? {
        if (!isLocalPath(link)) return null
        val painter by produceState<Painter?>(initialValue = null, key1 = link) {
            value = withContext(Dispatchers.IO) { decode(link) }
        }
        val p = painter ?: return null
        val viewer = LocalImageViewer.current
        val clickLabel = stringResource(R.string.common_image_preview)
        // Remember the modifier: ImageData is a data class, and giving it a new Modifier instance every frame would make its
        // equals never hold, pointlessly triggering downstream recomposition.
        val modifier = remember(link, viewer, clickLabel) {
            Modifier
                .fillMaxWidth()
                .heightIn(max = MAX_HEIGHT_DP)
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClickLabel = clickLabel) { viewer.show(markdownViewerRequest(link)) }
        }
        return ImageData(painter = p, modifier = modifier, contentScale = ContentScale.Fit)
    }

    private fun decode(url: String): Painter? {
        return try {
            val file = fileAccess.copyToLocal(url)
            if (!file.isFile || file.length() <= 0L) return null
            decodeSampledBitmap(file.absolutePath, MARKDOWN_MAX_EDGE)?.let { BitmapPainter(it) }
        } catch (e: Exception) {
            FileLogger.w(TAG, "Failed to decode local markdown image: $url", e)
            null
        }
    }

    private fun isLocalPath(url: String): Boolean {
        val u = url.trim()
        if (u.isEmpty()) return false
        if (u.startsWith("file://")) return true
        // Any other scheme with :// (http/https/data/content/ftp...) is treated as non-local
        return !u.contains("://")
    }

    private companion object {
        const val TAG = "MarkdownImage"
        /** Maximum display height (dp); over-tall images are scaled proportionally. */
        val MAX_HEIGHT_DP = 320.dp
    }
}
