package com.aicode.feature.editor.presentation

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.webkit.WebResourceResponse
import androidx.lifecycle.ViewModel
import androidx.webkit.WebViewAssetLoader
import com.aicode.core.util.FileLogger
import com.aicode.feature.workspace.domain.FileAccessProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.net.URLDecoder
import java.nio.file.Paths
import javax.inject.Inject

/**
 * Backend for the Markdown preview page (WebView rendering): assembles the rendering HTML and provides image resources for it.
 *
 * Preview page resources are loaded via [WebViewAssetLoader] through two routes:
 * - `/assets/` is provided by the built-in [WebViewAssetLoader.AssetsPathHandler] with the JS/CSS needed for rendering;
 * - `/local/` is read by [LocalAssetPathHandler] from [FileAccessProvider] (direct read in local mode, SFTP download in remote mode),
 *   so that relative-path images in documents such as READMEs can be displayed in both execution modes.
 *
 * The path after `/local/` is the path "relative to the workspace root" (assembled by [baseUrl] based on the directory of the previewed file);
 * do not put `~` into the URL -- in some WebView versions `~` is treated as a character requiring special handling, causing relative resource resolution to fail.
 */
@HiltViewModel
class MarkdownPreviewViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val fileAccess: FileAccessProvider
) : ViewModel() {

    companion object {
        /** The default virtual host of [WebViewAssetLoader]. */
        const val ASSET_HOST = "appassets.androidplatform.net"

        private const val ASSET_BASE = "https://$ASSET_HOST/assets/markdown/"
        private const val LOCAL_PREFIX = "/local/"
        private const val TAG = "MarkdownPreview"

        /** The workspace root path inside the container, consistent with [com.aicode.feature.workspace.domain.WorkspacePathMapper.CONTAINER_ROOT]. */
        private const val WS_ROOT = "~/workspace"

        /** Page background color in dark mode, consistent with the github-markdown-css dark theme. */
        private const val BG_DARK = "#0d1117"
        private const val BG_LIGHT = "#ffffff"
    }

    private val template: String by lazy {
        context.assets.open("markdown/preview.html").bufferedReader().use { it.readText() }
    }

    /** The light/dark mode used by the most recent render, reused by [LocalAssetPathHandler] when rendering a navigated-to Markdown file. */
    @Volatile
    private var currentDark: Boolean = false

    val assetLoader: WebViewAssetLoader = WebViewAssetLoader.Builder()
        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
        .addPathHandler(LOCAL_PREFIX, LocalAssetPathHandler())
        .build()

    /** Injects the raw markdown into the template to generate preview HTML; [dark] decides which of the light/dark GitHub style sets to apply. */
    suspend fun buildHtml(text: String, dark: Boolean): String = withContext(Dispatchers.IO) {
        buildHtmlInternal(text, dark)
    }

    private fun buildHtmlInternal(text: String, dark: Boolean): String {
        currentDark = dark
        val b64 = Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return template
            .replace("__ASSET_BASE__", ASSET_BASE)
            .replace("__MD_CSS__", if (dark) "github-markdown-dark.css" else "github-markdown-light.css")
            .replace("__HL_CSS__", if (dark) "highlight-github-dark.min.css" else "highlight-github.min.css")
            .replace("__BG__", if (dark) BG_DARK else BG_LIGHT)
            .replace("__MD_B64__", b64)
    }

    /**
     * Preview page baseUrl: based on the directory of the previewed file, relative image paths in markdown (such as `docs/a.png`)
     * resolve to `/local/<directory relative to workspace root>/docs/a.png`, which [containerPathOf] restores to a container path for reading.
     */
    fun baseUrl(path: String): String {
        val dir = relativeDirOf(path)
        val encoded = Uri.encode(dir, "/")
        return if (encoded.isEmpty()) {
            "https://$ASSET_HOST$LOCAL_PREFIX"
        } else {
            "https://$ASSET_HOST$LOCAL_PREFIX$encoded/"
        }
    }

    /**
     * Restores the relative path after `/local/` (relative to the workspace root) to a container path; returns null when nothing can be parsed.
     * The input is the suffix path after WebViewAssetLoader strips the registered prefix, without `/local/`.
     * Normalization keeps `..` within the root, preventing requests from escaping the workspace.
     */
    fun containerPathOf(relativePath: String): String? {
        val decoded = runCatching { URLDecoder.decode(relativePath, "UTF-8") }.getOrNull() ?: return null
        if (decoded.isEmpty()) return null
        val normalized = runCatching { Paths.get("/$decoded").normalize().toString() }.getOrNull() ?: return null
        val trimmed = normalized.trimStart('/')
        if (trimmed.isEmpty()) return null
        return "$WS_ROOT/$trimmed"
    }

    /** Returns the container path when a URL in the preview page points to a workspace file; returns null for non-local URLs. */
    fun containerPathForUrl(url: Uri): String? {
        if (url.host != ASSET_HOST) return null
        val path = url.path ?: return null
        if (!path.startsWith(LOCAL_PREFIX)) return null
        return containerPathOf(path.removePrefix(LOCAL_PREFIX))
    }

    /** The path of the previewed file's directory relative to the workspace root (without leading/trailing slashes); falls back to the workspace root when not inside the workspace. */
    private fun relativeDirOf(path: String): String {
        val parent = fileAccess.parentPath(path)?.trimEnd('/') ?: return ""
        return when {
            parent == WS_ROOT -> ""
            parent.startsWith("$WS_ROOT/") -> parent.removePrefix("$WS_ROOT/")
            else -> ""
        }
    }

    private inner class LocalAssetPathHandler : WebViewAssetLoader.PathHandler {
        override fun handle(path: String): WebResourceResponse? {
            val containerPath = containerPathOf(path)
            FileLogger.v(TAG, "Preview local resource: $path -> ${containerPath ?: "null"}")
            if (containerPath == null) {
                FileLogger.w(TAG, "Preview resource path could not be resolved: $path")
                return notFound()
            }
            val bytes = runCatching { fileAccess.readBytes(containerPath) }
                .onFailure { FileLogger.w(TAG, "Failed to read preview resource: $containerPath", it) }
                .getOrNull()
            if (bytes == null) return notFound()
            // When a local Markdown link is clicked in the preview page, render the target file directly as a preview page (browser-style navigation)
            if (isMarkdown(containerPath)) {
                val html = buildHtmlInternal(bytes.toString(Charsets.UTF_8), currentDark)
                return WebResourceResponse(
                    "text/html", "utf-8", 200, "OK", emptyMap(),
                    ByteArrayInputStream(html.toByteArray(Charsets.UTF_8))
                )
            }
            return WebResourceResponse(mimeOf(containerPath), null, ByteArrayInputStream(bytes))
        }
    }

    private fun isMarkdown(path: String): Boolean =
        path.substringAfterLast('.', "").lowercase() in setOf("md", "markdown")

    /** Already matched `/local/` but content is unavailable: return 404 instead of null, to avoid WebView treating it as not intercepted and falling back to network access. */
    private fun notFound(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    private fun mimeOf(path: String): String = when (val ext = path.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "bmp" -> "image/bmp"
        "svg" -> "image/svg+xml"
        "ico" -> "image/x-icon"
        // Text types (including extensionless LICENSE / README etc.) are returned as plain text, so they can be displayed directly in the preview page instead of being treated as a download
        "txt", "text", "log", "json", "jsonc", "yml", "yaml", "toml", "ini", "conf", "cfg", "properties",
        "xml", "html", "htm", "css", "js", "mjs", "ts", "tsx", "jsx", "kt", "kts", "java", "py", "rb",
        "go", "rs", "c", "h", "cpp", "hpp", "cs", "php", "swift", "sh", "bash", "zsh", "sql", "gradle",
        "gitignore", "env", "csv" -> "text/plain"
        else -> if (ext.isEmpty()) "text/plain" else "application/octet-stream"
    }
}
