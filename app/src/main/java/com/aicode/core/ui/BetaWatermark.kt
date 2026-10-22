package com.aicode.core.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.sp
import kotlin.math.hypot

/**
 * Full-screen diagonally tiled "Beta" watermark.
 *
 * Shown only when the applicationId ends with `.beta` (see the beta buildType), so users can tell a test build from a release build at a glance.
 * Has no touch gestures; simply overlay it on the topmost layer of the root layout, and it does not intercept interaction.
 */
@Composable
fun BetaWatermark(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val isBeta = remember(context) { context.applicationContext.packageName.endsWith(".beta") }
    if (!isBeta) return

    val textSizePx = with(LocalDensity.current) { 44.sp.toPx() }
    val color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f).toArgb()

    Canvas(modifier = modifier.fillMaxSize()) {
        if (size.minDimension <= 0f) return@Canvas
        val paint = Paint().apply {
            isAntiAlias = true
            this.textSize = textSizePx
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            this.color = color
        }
        val stepX = textSizePx * 4.5f
        val stepY = textSizePx * 3.2f
        // After the canvas is rotated around the center, the four corners move outside the visible area, so the tiling range is expanded along the diagonal with extra margin to ensure full-screen coverage.
        val reach = hypot(size.width, size.height) / 2f + textSizePx * 2f
        val canvas = drawContext.canvas.nativeCanvas
        rotate(degrees = -30f) {
            var y = center.y - reach
            while (y <= center.y + reach) {
                var x = center.x - reach
                while (x <= center.x + reach) {
                    canvas.drawText("Beta", x, y, paint)
                    x += stepX
                }
                y += stepY
            }
        }
    }
}