package com.aicode.feature.editor.presentation.component

import android.net.Uri
import android.view.ContextThemeWrapper
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import com.aicode.R
import com.aicode.core.util.FileLogger
import com.aicode.feature.editor.presentation.MarkdownPreviewViewModel

/**
 * Markdown preview page: renders full Markdown (including embedded HTML) with WebView, reproducing the GitHub look and feel.
 *
 * Local file links in the preview page navigate in place like a browser (Markdown targets are rendered as a new preview page by [MarkdownPreviewViewModel]);
 * the back button first goes back through WebView history, and exits preview when there is no more history; external links do not navigate.
 *
 * This is an independent path from the chat area's Compose rendering, and the two do not affect each other.
 */
@Composable
internal fun MarkdownPreviewWebView(
    text: String,
    path: String,
    onExitPreview: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MarkdownPreviewViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val bgColor = if (dark) BG_DARK else BG_LIGHT
    val exitPreview by rememberUpdatedState(onExitPreview)

    val webView = remember {
        val themedContext = ContextThemeWrapper(context, webViewThemeRes(dark))
        WebView(themedContext).apply {
            settings.javaScriptEnabled = true
            // The page only fetches resources via assetLoader and does not expose the local file system
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.domStorageEnabled = false
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            setBackgroundColor(bgColor)
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest
                ): WebResourceResponse? {
                    val response = viewModel.assetLoader.shouldInterceptRequest(request.url)
                    FileLogger.v(TAG, "Preview resource request: ${request.url} -> ${if (response == null) "go to network" else "intercepted"}")
                    return response
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest
                ): Boolean {
                    val url = request.url
                    // In-page anchor (#xxx): only the fragment changes and the path matches the current page, allow it and let WebView scroll
                    if (url.fragment != null && url.path == view.url?.let { Uri.parse(it).path }) return false
                    // Allow file links pointing to the workspace, letting WebView navigate in place (Markdown is rendered as a preview page by assetLoader)
                    if (viewModel.containerPathForUrl(url) != null) return false
                    // Block everything else (external links etc.)
                    return true
                }
            }
        }
    }

    // WebView's isLightTheme is determined by the context theme; update it in sync when dark mode switches, to avoid scrollbar and other control colors mismatching.
    LaunchedEffect(dark) {
        (webView.context as? ContextThemeWrapper)?.setTheme(webViewThemeRes(dark))
        webView.setBackgroundColor(bgColor)
    }

    DisposableEffect(webView) {
        onDispose { webView.destroy() }
    }

    LaunchedEffect(text, path, dark) {
        val html = viewModel.buildHtml(text, dark)
        webView.loadDataWithBaseURL(viewModel.baseUrl(path), html, "text/html", "utf-8", null)
    }

    BackHandler {
        if (webView.canGoBack()) webView.goBack() else exitPreview()
    }

    AndroidView(factory = { webView }, modifier = modifier)
}

private fun webViewThemeRes(dark: Boolean): Int =
    if (dark) R.style.Theme_AICode_WebView_Dark else R.style.Theme_AICode_WebView

private const val BG_DARK = 0xFF0D1117.toInt()
private const val BG_LIGHT = 0xFFFFFFFF.toInt()
private const val TAG = "MarkdownPreview"
