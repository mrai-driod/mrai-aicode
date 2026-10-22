package com.aicode.core.ui

import android.os.Build
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Bottom padding that follows the soft keyboard frame by frame (used to hold the bottom bar / action bar above the keyboard).
 * Takes the larger of ime and navigationBars: when the keyboard pops up it floats above the keyboard, and when it collapses it rests on the navigation bar, with no duplicated padding.
 *
 * Why go to this much trouble: this app locks `targetSdk = 28` (PRoot needs to execute from a writable directory, see build.gradle.kts).
 * Under targetSdk < 30, `windowSoftInputMode=adjustResize` takes the legacy compatibility scaling path, which **swallows**
 * the platform IME inset frame-by-frame animation -- `WindowInsets.ime` only arrives in one step after the keyboard animation ends. The visible result is "the keyboard pops up first,
 * then the bottom bar follows a beat later".
 *
 * Solution: on API 30+, Activity.onCreate switches the window to `ADJUST_NOTHING` (making the legacy scaling path give way), and instead
 * we use insets to push the bottom bar up; at the same time this function attaches a [WindowInsetsAnimationCompat] callback, and in onProgress
 * it reads the keyboard's **current frame** position to drive the padding, thereby moving in frame-locked sync with the keyboard.
 *
 * **Important**: softInputMode is set once, uniformly, by the Activity; this function's onDispose no longer restores it --
 * when multiple pages (Terminal / Chat) use this function at the same time, the two composables coexist during the NavHost transition animation,
 * and the old page disposing would clear the callback the new page has just set and switch softInputMode back to adjustResize,
 * triggering a window relayout that causes a white screen.
 *
 * API < 30 has no IME inset animation API, so the manifest's legacy adjustResize behavior is kept (window scaling; functionality is normal,
 * just without a smooth animation).
 */
@Composable
fun rememberImeBottomInset(): Dp {
    val view = LocalView.current
    val density = LocalDensity.current

    // Resting / final state: when the keyboard animation ends, on rotation, and for the first frame, the Compose insets give the exact value.
    val restingPx = maxOf(
        WindowInsets.ime.getBottom(density),
        WindowInsets.navigationBars.getBottom(density)
    )

    // While the animation is in progress, onProgress provides the frame-by-frame values.
    var progressPx by remember { mutableIntStateOf(restingPx) }
    var animating by remember { mutableStateOf(false) }

    if (Build.VERSION.SDK_INT >= 30) {
        DisposableEffect(view) {
            val callback = object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                override fun onPrepare(animation: WindowInsetsAnimationCompat) {
                    if (animation.typeMask and WindowInsetsCompat.Type.ime() != 0) animating = true
                }

                override fun onProgress(
                    insets: WindowInsetsCompat,
                    runningAnimations: MutableList<WindowInsetsAnimationCompat>
                ): WindowInsetsCompat {
                    val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
                    val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
                    progressPx = maxOf(ime, nav)
                    return insets
                }

                override fun onEnd(animation: WindowInsetsAnimationCompat) {
                    if (animation.typeMask and WindowInsetsCompat.Type.ime() != 0) animating = false
                }
            }
            ViewCompat.setWindowInsetsAnimationCallback(view, callback)

            onDispose { }
        }
    }

    // Uncontrolled keyboard-showing paths (e.g. the terminal triggering the soft keyboard via InputMethodManager.showSoftInput) cannot get frame-by-frame
    // onProgress callbacks; restingPx jumps in one step to the final value once WindowInsets.ime settles, appearing as the bottom bar "teleporting".
    // On API 30+, a tween is added to this resting value so the bottom bar slides smoothly; on controlled paths (Compose TextField) that have frame-by-frame
    // callbacks, animating=true and progressPx is used directly, without stacking a second animation.
    // API < 30 gets no extra adaptation: it follows the manifest's adjustResize system default behavior, and old devices stay as they were.
    val animatedRestingPx by animateIntAsState(
        targetValue = restingPx,
        animationSpec = tween(durationMillis = 220),
        label = "ime-resting"
    )
    val px = when {
        animating -> progressPx
        Build.VERSION.SDK_INT >= 30 -> animatedRestingPx
        else -> restingPx
    }
    return with(density) { px.toDp() }
}