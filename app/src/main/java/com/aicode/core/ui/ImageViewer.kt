package com.aicode.core.ui

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image as ComposeImage
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.aicode.R
import com.aicode.core.theme.Spacing
import compose.icons.FeatherIcons
import compose.icons.feathericons.Image
import compose.icons.feathericons.X
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Image source.
 *
 * Deliberately made a value type rather than "each call site bringing its own suspend loading lambda": a value type has stable equals, so it can be used directly
 * as the key for [produceState]; the lambda style would keep holding the pending attachment's base64 string, up to 5MB, in hand --
 * even after the attachment has been sent and the in-memory copy has been cleared, the viewer would still be keeping it alive.
 */
sealed interface ImageSource {
    /** Absolute path of a local file on the host, which can be decoded directly. */
    data class LocalFile(val path: String) : ImageSource

    /** Container / remote path from the AI's perspective; it must first be brought to a host file through the file access abstraction. */
    data class ContainerPath(val path: String) : ImageSource

    /** Base64 image data held in memory. */
    data class Base64(val data: String) : ImageSource
}

/**
 * A single viewing request.
 *
 * [sources] are tried in order; if the previous one cannot be obtained, it falls back to the next -- in remote SSH mode, the `localPath` recorded for a sent attachment
 * is a temporary file from `createTempFile` + `deleteOnExit`, which is gone after the process restarts or the cache is cleared,
 * so it must be able to fall back to the container path and fetch it again.
 */
@Immutable
data class ImageViewerRequest(
    val sources: List<ImageSource>,
    /** Used for the accessibility description and failure prompt, usually the file name; if left empty, falls back to "Image preview". */
    val title: String = ""
)

/** Handle for opening the full-screen viewer: the caller only calls show and does not care about loading or gestures. */
@Stable
fun interface ImageViewer {
    fun show(request: ImageViewerRequest)
}

/**
 * The default is an empty implementation: tapping an image in a composition tree without [ImageViewerHost] attached silently does nothing and does not crash.
 *
 * The same pattern as `LocalMarkdownImageTransformer` -- using a CompositionLocal rather than adding callback parameters all the way down,
 * otherwise intermediate layers such as `AgentMessageItem` / `ChatInputBar` would all have to change their signatures just to forward a lambda.
 */
val LocalImageViewer = staticCompositionLocalOf<ImageViewer> { ImageViewer { } }

@Stable
class ImageViewerState : ImageViewer {
    var request by mutableStateOf<ImageViewerRequest?>(null)
        private set

    override fun show(request: ImageViewerRequest) {
        this.request = request
    }

    fun dismiss() {
        request = null
    }
}

@Composable
fun rememberImageViewerState(): ImageViewerState = remember { ImageViewerState() }

private sealed interface ViewerLoad {
    object Loading : ViewerLoad
    object Failed : ViewerLoad
    data class Ready(val bitmap: ImageBitmap) : ViewerLoad
}

/**
 * Host for the full-screen image viewer. It can be attached anywhere in the composition tree -- [Dialog] is an independent window and does not occupy parent layout size.
 *
 * Using Dialog rather than an in-composition overlay Box, for three hard reasons:
 * 1. `MainActivity` draws the global background watermark **after** `AppNavigation()`, so an overlay would have that watermark layered over the large image;
 * 2. In the tablet dual-pane layout the chat panel occupies only half the screen, so an overlay could only cover the chat column, not the whole screen;
 * 3. Dialog closes with the back key by itself, and its window has focus, so its priority is naturally higher than the existing "close sidebar / close right pane" BackHandlers,
 *    with no need to work out their relative order.
 *
 * @param load Decodes an [ImageSource] into a bitmap; the implementer switches to an IO thread itself, and returning null means this source cannot be obtained.
 */
@Composable
fun ImageViewerHost(
    state: ImageViewerState,
    load: suspend (ImageSource) -> ImageBitmap?
) {
    val request = state.request ?: return
    val loadState by produceState<ViewerLoad>(ViewerLoad.Loading, request, load) {
        value = request.sources.firstNotNullOfOrNull { load(it) }
            ?.let { ViewerLoad.Ready(it) }
            ?: ViewerLoad.Failed
    }

    Dialog(
        onDismissRequest = state::dismiss,
        properties = DialogProperties(
            // If not set, the dialog window is WRAP_CONTENT and the content cannot fill the screen
            usePlatformDefaultWidth = false,
            // If not set, the content is squeezed into the safe area by the system bar insets, the black background breaks at the status bar, revealing the chat screen behind
            decorFitsSystemWindows = false
        )
    ) {
        // Under a light theme the system bar icons are dark and are invisible pressed against the black background. The dialog has its own window, so its icon color is adjusted separately.
        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            dialogWindow?.let { window ->
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = false
                    isAppearanceLightNavigationBars = false
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                // The media viewer backdrop deliberately does not follow the theme: even in light mode it should be near-black, and making it a semantic color would instead be wrong
                .background(Color.Black.copy(alpha = BACKDROP_ALPHA))
        ) {
            when (val loaded = loadState) {
                ViewerLoad.Loading -> CircularProgressIndicator(
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center)
                )

                ViewerLoad.Failed -> LoadFailedPlaceholder(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .clickable(onClick = state::dismiss)
                        .padding(Spacing.lg)
                )

                is ViewerLoad.Ready -> ZoomableImage(
                    bitmap = loaded.bitmap,
                    contentDescription = request.title.ifBlank { stringResource(R.string.common_image_preview) },
                    onTap = state::dismiss,
                    modifier = Modifier.fillMaxSize()
                )
            }

            IconButton(
                onClick = state::dismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    // After decorFitsSystemWindows = false, it must make way for the system bars itself
                    .systemBarsPadding()
                    .padding(Spacing.sm)
            ) {
                Icon(
                    imageVector = FeatherIcons.X,
                    contentDescription = stringResource(R.string.common_close),
                    tint = Color.White
                )
            }
        }
    }
}

@Composable
private fun LoadFailedPlaceholder(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        Icon(
            imageVector = FeatherIcons.Image,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.6f),
            modifier = Modifier.size(40.dp)
        )
        Text(
            text = stringResource(R.string.common_image_load_failed),
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.8f)
        )
    }
}

/** Backdrop opacity. Compose's Dialog itself adds another dim layer to the window, so this does not need to be full. */
private const val BACKDROP_ALPHA = 0.92f

private const val MIN_SCALE = 1f

/**
 * Lower bound for the zoom limit. An extra-long image fitted into the screen with Fit is already very small in itself, and only 5x makes the details impossible to see clearly,
 * so the actual limit instead takes the value of "can be magnified to 1:1 decoded pixels"; see maxScale in [ZoomableImage].
 */
private const val MAX_SCALE_FLOOR = 5f

private const val DOUBLE_TAP_SCALE = 2.5f
private const val ZOOM_ANIM_MS = 220

/** Floating-point comparison tolerance: determines "whether it is still at the original size". */
private const val SCALE_EPS = 0.01f

/**
 * The actual drawn size after the image is placed into the container with [ContentScale.Fit].
 *
 * The pan clamping bounds must be computed from this size rather than the container size, otherwise the image can be dragged into the black bars on both sides.
 */
fun fitInside(image: IntSize, container: IntSize): Size {
    if (image.width <= 0 || image.height <= 0 || container.width <= 0 || container.height <= 0) {
        return Size.Zero
    }
    val ratio = minOf(
        container.width.toFloat() / image.width,
        container.height.toFloat() / image.height
    )
    return Size(image.width * ratio, image.height * ratio)
}

/** Maximum displacement on a single axis: the part exceeding the container after zooming is split in half. If it does not exceed it, this is 0, pinning the image dead center and preventing drift. */
fun maxPan(contentPx: Float, containerPx: Float, scale: Float): Float =
    ((contentPx * scale - containerPx) / 2f).coerceAtLeast(0f)

/** Clamps the displacement back into the visible range. */
fun clampPan(offset: Offset, content: Size, container: IntSize, scale: Float): Offset {
    val limitX = maxPan(content.width, container.width.toFloat(), scale)
    val limitY = maxPan(content.height, container.height.toFloat(), scale)
    return Offset(offset.x.coerceIn(-limitX, limitX), offset.y.coerceIn(-limitY, limitY))
}

/**
 * The new displacement after zooming anchored at [centroid] (coordinates inside the container): the pixel under the finger stays still while pinching.
 *
 * [graphicsLayer]'s transformOrigin defaults to the center, so everything takes the container center [containerCenter] as the origin.
 * Let v = centroid − containerCenter; the screen position of a content point p is `p·scale + offset`;
 * To make the content point at v land at v + pan after the gesture, solving gives
 * `newOffset = v + pan − (v − oldOffset)·(newScale / oldScale)`.
 *
 * Two degenerate cases can be used for verification: a pure translation (newScale == oldScale) gives `oldOffset + pan`;
 * a pure zoom around the center (v = 0, pan = 0) gives `oldOffset · k`.
 */
fun zoomAround(
    centroid: Offset,
    containerCenter: Offset,
    oldScale: Float,
    newScale: Float,
    oldOffset: Offset,
    pan: Offset
): Offset {
    if (oldScale <= 0f) return oldOffset
    val v = centroid - containerCenter
    return v + pan - (v - oldOffset) * (newScale / oldScale)
}

/**
 * An image supporting pinch-to-zoom with two fingers + single-finger dragging after zooming + double-tapping to switch between the original size and magnified.
 *
 * Zoom and displacement all go through [graphicsLayer] and do not trigger relayout; the bounds are clamped by [clampPan] using the actual drawn size after Fit,
 * so on release there is no drift, and it cannot be dragged into the black bars.
 *
 * @param onTap Single-tap callback. Triggered only when not magnified -- when viewing details magnified, an accidental tap would close it far too easily.
 */
@Composable
fun ZoomableImage(
    bitmap: ImageBitmap,
    contentDescription: String?,
    onTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    var container by remember { mutableStateOf(IntSize.Zero) }
    var scale by remember { mutableFloatStateOf(MIN_SCALE) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var zoomAnim by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()

    val imageSize = remember(bitmap) { IntSize(bitmap.width, bitmap.height) }
    val content = remember(imageSize, container) { fitInside(imageSize, container) }
    val center = remember(container) { Offset(container.width / 2f, container.height / 2f) }
    // The limit takes "returning to 1:1 decoded pixels"; otherwise a long screenshot shrinks extremely small after Fit, and even 5x cannot make the text legible
    val maxScale = remember(imageSize, content) {
        if (content.width <= 0f) MAX_SCALE_FLOOR else maxOf(MAX_SCALE_FLOOR, imageSize.width / content.width)
    }

    // After rotating the screen or dragging the tablet split pane, the container size changes and the old displacement may go out of bounds. AndroidManifest's configChanges
    // include orientation, so the Activity is not recreated and scale/offset are all retained; therefore it must be clamped again here.
    // Deliberately not putting scale into the key: that would make every zoom go through a recomposition, wasting the benefit of graphicsLayer.
    LaunchedEffect(content, container) {
        offset = clampPan(offset, content, container, scale)
    }

    Box(
        modifier = modifier
            .clipToBounds()
            .onSizeChanged { container = it }
            // tap must be written before transform: in the same modifier chain, the later one is deeper, and the Main pass arrives first.
            // Let transform consume first; only after a pinch passes touchSlop will tap's waitForUpOrCancellation see
            // isConsumed and give up; if the order were reversed, a single pinch would close the viewer.
            //
            // The key must include imageSize / container: when pointerInput's key does not change, an already-running gesture coroutine is not restarted,
            // only the lambda reference is replaced, so the content / center / maxScale captured by value in the callback would keep
            // staying at the old size -- after rotating the screen it would zoom around the wrong center point and clamp displacement with the wrong bounds.
            .pointerInput(imageSize, container) {
                detectTapGestures(
                    onTap = { if (scale <= MIN_SCALE + SCALE_EPS) onTap() },
                    onDoubleTap = { tap ->
                        val from = scale
                        val to = if (from > MIN_SCALE + SCALE_EPS) {
                            MIN_SCALE
                        } else {
                            minOf(DOUBLE_TAP_SCALE, maxScale)
                        }
                        val fromOffset = offset
                        val toOffset = if (to <= MIN_SCALE + SCALE_EPS) {
                            Offset.Zero
                        } else {
                            clampPan(
                                zoomAround(tap, center, from, to, fromOffset, Offset.Zero),
                                content,
                                container,
                                to
                            )
                        }
                        zoomAnim?.cancel()
                        zoomAnim = scope.launch {
                            animate(0f, 1f, animationSpec = tween(ZOOM_ANIM_MS)) { fraction, _ ->
                                scale = from + (to - from) * fraction
                                offset = lerp(fromOffset, toOffset, fraction)
                            }
                        }
                    }
                )
            }
            .pointerInput(imageSize, container) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    zoomAnim?.cancel()
                    val from = scale
                    val to = (from * zoom).coerceIn(MIN_SCALE, maxScale)
                    val moved = zoomAround(centroid, center, from, to, offset, pan)
                    scale = to
                    offset = clampPan(moved, content, container, to)
                }
            }
    ) {
        ComposeImage(
            bitmap = bitmap,
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                }
        )
    }
}
