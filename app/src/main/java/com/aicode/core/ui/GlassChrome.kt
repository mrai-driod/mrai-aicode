package com.aicode.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.styropyr0.prismal.PrismalBackdrop
import com.styropyr0.prismal.PrismalGlassSurface
import com.styropyr0.prismal.shapes.PrismalRoundedRectangle
import com.styropyr0.prismal.sources.PrismalGlassLayer
import com.styropyr0.prismal.sources.prismalGlassLayer
import com.styropyr0.prismal.sources.rememberPrismalGlassLayer

/**
 * Liquid Glass scaffolding (based on PrismalAGSL).
 *
 * Three rules of use; please keep them when making changes:
 *
 * 1. **Only one backdrop**: only one layer of [rememberChatGlassLayer] may be created in a single screen, and it should be attached with
 *    [Modifier.glassBackdrop] to the "content behind the glass" (on the chat page that is the message list).
 *    Multiple full-screen backdrop captures significantly increase GPU and memory overhead -- this is exactly what the requirements specifically call out to avoid.
 *    All glass elements (title bar / input area / to-do overlay) share this one layer, rather than sampling individually.
 *
 * 2. **Only local surfaces**: glass appears only on the few floating layers mentioned above; the page body remains ordinary content.
 *
 * 3. **Do not judge the API level yourself**: PrismalAGSL provides its own three-tier degradation
 *    (API 25-30 frosted → 31-32 RenderEffect blur → 33+ full AGSL refraction/chromatic aberration),
 *    we do not call RuntimeShader in business code, nor write Build.VERSION branches,
 *    otherwise the compatibility range of Android 8.0 (minSdk 26) would be broken.
 *    Refraction/chromatic aberration parameters are ignored internally by the library on lower versions; only the highlight overlay (self-drawn gradient) always takes effect.
 */

/** The screen's sole background sampling layer; after creation it must be attached to the background content with [Modifier.glassBackdrop]. */
@Composable
fun rememberChatGlassLayer(): PrismalGlassLayer = rememberPrismalGlassLayer()

/** Registers a subtree as a background source for the glass (each screen should attach this in only one place). */
fun Modifier.glassBackdrop(layer: PrismalGlassLayer): Modifier = this.prismalGlassLayer(layer)

/** Glass luminance state: [luminance] is handed to the glass for adaptive tuning, [contentColor] serves as the foreground color for text on the glass. */
@androidx.compose.runtime.Stable
class GlassLuminanceState(
    val luminance: Float,
    val contentColor: Color
)

/**
 * Glass luminance and foreground color (theme-derived, zero sampling).
 *
 * History: originally used PrismalAGSL's rememberPrismalAdaptiveLuminance -- it performs, every 600~1500ms, a
 * toImageBitmap + readPixels GPU→CPU readback of the background layer (sampling more densely while scrolling),
 * and each readback forces a render pipeline flush, which was a source of periodic small stutters during scrolling/streaming.
 * The chat page background is inherently a stable dark/light under dark/light themes, and the theme-derived value is almost identical to the measured luminance,
 * so it was changed to a zero-cost static value; PrismalGlassSurface's internal adaptiveTuning still works based on this value.
 * [source] / [enabled] are retained only to keep signature compatibility and no longer participate in sampling.
 */
@Composable
fun rememberGlassLuminance(
    source: PrismalGlassLayer,
    enabled: Boolean = true
): GlassLuminanceState {
    val surface = MaterialTheme.colorScheme.surface
    val contentColor = MaterialTheme.colorScheme.onSurface
    return remember(surface, contentColor) {
        val dark = surface.luminance() <= 0.5f
        GlassLuminanceState(
            luminance = if (dark) 0.12f else 0.92f,
            contentColor = contentColor
        )
    }
}

/**
 * Unified liquid glass container.
 *
 * The sense of light comes from three layers, all indispensable:
 * 1. **AGSL refraction band** ([refractionHeight] / [refractionAmount] / [chromaticAberration] /
 *    [depthEffect]): edge arc bulge + RGB chromatic aberration; when content slides to the glass edge it is genuinely "bent",
 *    this is the core of the "liquid" feel -- previously no refraction parameters were passed, so the glass was just a frosted panel;
 * 2. **vibrancy** ([saturation] / [contrast]): raises the saturation and micro-contrast of the sampled background,
 *    letting the colors behind "show through" instead of smearing into gray;
 * 3. **Self-drawn highlight** ([specularHighlight]): diagonal gradient sheen + top specular highlight band +
 *    rim light outline that is bright on top and dark below, simulating polished-edge reflection; effective on all APIs, does not depend on AGSL.
 *
 * - [cornerRadius]: large rounded corners, the basic form of liquid glass;
 * - [tintAlpha]: extremely low, only a very light surface tint, to avoid becoming a "large semi-transparent white block";
 * - [adaptiveLuminance] + [luminance]: make blur and color adapt to the background luminance, while preserving a lower bound on contrast.
 */
@Composable
fun GlassSurface(
    /**
     * Background sampling layer; when null, degrades to a plain container of "semi-transparent surface + extremely light outline".
     * This way the caller does not need to write two layouts; the glass switch only needs to pass null / non-null.
     */
    backdrop: PrismalBackdrop?,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 22.dp,
    blurRadius: Dp = 12.dp,
    tintAlpha: Float = 0.10f,
    adaptiveLuminance: Boolean = true,
    luminance: () -> Float = { 0.5f },
    /** Refraction band thickness (width of the arc bulge inward from the edge); 0.dp disables refraction. */
    refractionHeight: Dp = 14.dp,
    /** Refraction bending strength (pixel-level displacement amount); larger values make the edge distortion more pronounced. */
    refractionAmount: Dp = 30.dp,
    /** RGB chromatic aberration strength [0,1], the rainbow color fringe at the refraction band edge; 0 disables it. */
    chromaticAberration: Float = 0.16f,
    /** Sampled background saturation boost (iOS vibrancy); 1f = no boost. */
    saturation: Float = 1.35f,
    /** Top specular highlight and rim light outline (self-drawn, effective on all APIs). */
    specularHighlight: Boolean = true,
    content: @Composable BoxScope.() -> Unit
) {
    if (backdrop == null) {
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(cornerRadius))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.98f))
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                    RoundedCornerShape(cornerRadius)
                ),
            content = content
        )
        return
    }
    val density = LocalDensity.current
    PrismalGlassSurface(
        backdrop = backdrop,
        modifier = modifier,
        shape = { PrismalRoundedRectangle(cornerRadius) },
        adaptiveLuminance = adaptiveLuminance,
        luminance = luminance,
        blurRadius = blurRadius,
        tint = MaterialTheme.colorScheme.surface,
        tintAlpha = tintAlpha,
        saturation = saturation,
        contrast = 1.04f,
        refractionHeightPx = with(density) { refractionHeight.toPx() },
        refractionAmountPx = with(density) { refractionAmount.toPx() },
        depthEffect = true,
        chromaticAberration = chromaticAberration
    ) {
        if (specularHighlight) {
            GlassSheenOverlay(cornerRadius = cornerRadius)
        }
        content()
        if (specularHighlight) {
            GlassRimOverlay(cornerRadius = cornerRadius)
        }
    }
}

/**
 * Glass volumetric light: diagonal sheen (slightly bright at the top-left, extremely light reflection at the bottom) + top specular highlight band.
 * Drawn beneath the content, it only provides the volumetric feel of "light passing through glass" and does not interfere with reading.
 *
 * Brushes are remembered: when GlassSurface recomposes it no longer allocates 3 new Brush objects per frame,
 * reducing GC pressure and GPU shader pipeline rebuilds during scrolling/streaming output.
 */
@Composable
private fun BoxScope.GlassSheenOverlay(cornerRadius: Dp) {
    val shape = RoundedCornerShape(cornerRadius)
    // Under a light theme the background itself is bright, so the highlight must be more restrained to avoid a wash of white
    val scale = if (MaterialTheme.colorScheme.surface.luminance() > 0.5f) 0.55f else 1f
    val sheenBrush = remember(scale) {
        Brush.linearGradient(
            0.0f to Color.White.copy(alpha = 0.10f * scale),
            0.22f to Color.White.copy(alpha = 0.03f * scale),
            0.5f to Color.Transparent,
            1.0f to Color.White.copy(alpha = 0.05f * scale),
            start = Offset.Zero,
            end = Offset.Infinite
        )
    }
    val specularBrush = remember(scale) {
        Brush.verticalGradient(
            0.0f to Color.White.copy(alpha = 0.22f * scale),
            1.0f to Color.Transparent
        )
    }
    Box(
        modifier = Modifier
            .matchParentSize()
            .clip(shape)
            .background(sheenBrush)
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(16.dp)
            .align(Alignment.TopCenter)
            .clip(shape)
            .background(specularBrush)
    )
}

/**
 * Rim light outline: bright top edge, weak side edges, a little reflected bounce at the bottom edge -- the edge reflection of polished-edge glass.
 * Drawn above the content (only 1dp, does not cover the content), keeping the glass outline clear even against complex backgrounds.
 */
@Composable
private fun BoxScope.GlassRimOverlay(cornerRadius: Dp) {
    val scale = if (MaterialTheme.colorScheme.surface.luminance() > 0.5f) 0.5f else 1f
    val rimBrush = remember(scale) {
        Brush.verticalGradient(
            0.0f to Color.White.copy(alpha = 0.55f * scale),
            0.12f to Color.White.copy(alpha = 0.20f * scale),
            0.5f to Color.White.copy(alpha = 0.07f * scale),
            1.0f to Color.White.copy(alpha = 0.30f * scale)
        )
    }
    Box(
        modifier = Modifier
            .matchParentSize()
            .border(
                width = 1.dp,
                brush = rimBrush,
                shape = RoundedCornerShape(cornerRadius)
            )
    )
}
