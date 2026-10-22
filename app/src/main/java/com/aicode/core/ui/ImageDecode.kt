package com.aicode.core.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.aicode.core.util.FileLogger

/**
 * Globally unified bitmap sampled decoding.
 *
 * The project does not include Coil/Glide (dependencies are deliberately kept lean for F-Droid reproducible builds), so all images are decoded by hand-written
 * [BitmapFactory] decoding. Sampling and OOM fallback logic are consolidated here, to avoid each call site writing its own copy, each with its own bug.
 */
private const val TAG = "ImageDecode"

/** Thumbnail tier: the chat attachment preview card is only 76dp, so sampling to 180 already leaves headroom. */
const val THUMBNAIL_MAX_EDGE = 180

/** Markdown body inline image tier: display height limit 320dp. */
const val MARKDOWN_MAX_EDGE = 1024

/** Total pixel limit for the full-screen viewer: peak is about 16MB under ARGB_8888. */
private const val VIEWER_MAX_PIXELS = 4_000_000L

/** Number of downsampling retries after a decode OOM. */
private const val OOM_RETRIES = 3

/** Upper limit for inSampleSize, preventing the fallback loop from running out of control on malformed dimensions. */
private const val MAX_SAMPLE = 32

/**
 * Computes [BitmapFactory.Options.inSampleSize] by the **maximum edge**.
 *
 * The check uses `||` rather than `&&`: `&&` actually constrains the short edge, so long screenshots such as 4000×300 would escape sampling entirely and
 * be decoded at full size (about 4.8MB) -- and AI-produced long web page screenshots and terminal long-output screenshots are exactly this shape.
 */
fun calculateInSampleSize(width: Int, height: Int, maxEdge: Int): Int {
    if (width <= 0 || height <= 0 || maxEdge <= 0) return 1
    var sample = 1
    while (sample < MAX_SAMPLE && (width / (sample * 2) >= maxEdge || height / (sample * 2) >= maxEdge)) {
        sample *= 2
    }
    return sample
}

/**
 * On top of the maximum-edge constraint, also guards by total pixels: under ARGB_8888, `maxPixels × 4` bytes is the peak decode memory,
 * and images with extreme aspect ratios (maximum edge not exceeded but total pixels huge) are stopped by this layer.
 */
fun calculateInSampleSize(width: Int, height: Int, maxEdge: Int, maxPixels: Long): Int {
    var sample = calculateInSampleSize(width, height, maxEdge)
    if (width <= 0 || height <= 0 || maxPixels <= 0) return sample
    while (sample < MAX_SAMPLE && (width / sample).toLong() * (height / sample) > maxPixels) {
        sample *= 2
    }
    return sample
}

/** Sample-decodes a local file. Returns null if the file does not exist, is not an image, or decoding fails. */
fun decodeSampledBitmap(path: String, maxEdge: Int, maxPixels: Long = Long.MAX_VALUE): ImageBitmap? {
    val bounds = decodeBounds(path) { BitmapFactory.decodeFile(path, it) } ?: return null
    return decodeWithOomRetry(path, bounds, maxEdge, maxPixels) { BitmapFactory.decodeFile(path, it) }
}

/** Sample-decodes image bytes in memory. */
fun decodeSampledBitmap(bytes: ByteArray, maxEdge: Int, maxPixels: Long = Long.MAX_VALUE): ImageBitmap? {
    val what = "byte[${bytes.size}]"
    val decode: (BitmapFactory.Options) -> Bitmap? = { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, it) }
    val bounds = decodeBounds(what, decode) ?: return null
    return decodeWithOomRetry(what, bounds, maxEdge, maxPixels, decode)
}

/** Only reads the image width and height, without actually allocating pixel memory. */
private fun decodeBounds(what: String, decode: (BitmapFactory.Options) -> Bitmap?): Pair<Int, Int>? {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    try {
        decode(options)
    } catch (e: Exception) {
        FileLogger.w(TAG, "Failed to read image bounds: $what", e)
        return null
    }
    return if (options.outWidth > 0 && options.outHeight > 0) options.outWidth to options.outHeight else null
}

/**
 * Decodes at the sampled tier, retrying one tier lower on OOM.
 *
 * [OutOfMemoryError] is an `Error`, not an `Exception`, so neither `runCatching {}` nor `catch (e: Exception)`
 * can catch it -- if it is not caught explicitly, a single oversized image can crash the App.
 */
private fun decodeWithOomRetry(
    what: String,
    bounds: Pair<Int, Int>,
    maxEdge: Int,
    maxPixels: Long,
    decode: (BitmapFactory.Options) -> Bitmap?
): ImageBitmap? {
    var sample = calculateInSampleSize(bounds.first, bounds.second, maxEdge, maxPixels)
    repeat(OOM_RETRIES) {
        try {
            return decode(BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
        } catch (e: OutOfMemoryError) {
            FileLogger.w(TAG, "Decode OOM, inSampleSize $sample -> ${sample * 2}: $what")
            sample *= 2
        } catch (e: Exception) {
            FileLogger.w(TAG, "Decode failed: $what", e)
            return null
        }
    }
    FileLogger.w(TAG, "Decode OOM repeatedly, giving up: $what")
    return null
}

/** Decode tier: maximum edge (pixels) + total pixel limit. */
data class ImageDecodeSpec(val maxEdge: Int, val maxPixels: Long)

/**
 * Decode tier for the full-screen viewer, following the current window rather than being hardcoded.
 *
 * The viewer allows zooming in to see details, so it takes 1.5x the window's long edge, clamped to 1024..2048. Hardcoding 2048 on a 1080p phone
 * is pure waste (the screen is only 2.6MP), while on a 720p low-end device it is a real OOM risk.
 */
@Composable
fun rememberViewerDecodeSpec(): ImageDecodeSpec {
    val config = LocalConfiguration.current
    val density = LocalDensity.current
    return remember(config.screenWidthDp, config.screenHeightDp, density.density) {
        val longEdgeDp = maxOf(config.screenWidthDp, config.screenHeightDp)
        val longEdgePx = with(density) { longEdgeDp.dp.toPx() }.toInt()
        ImageDecodeSpec(
            maxEdge = (longEdgePx * 3 / 2).coerceIn(1024, 2048),
            maxPixels = VIEWER_MAX_PIXELS
        )
    }
}
