package com.aicode.core.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.ui.unit.IntOffset

/**
 * Full-page transition specification (Material "shared axis X"): two pages move horizontally in the **same direction**, pushing left together when moving forward,
 * returning right together when going back, combined with staggered fade-out/fade-in. The top-level route (NavHost) and the internal sections of the settings page share the same specification,
 * so the two places do not each write their own set of parameters with inconsistent animation rhythm.
 *
 * Why a small same-direction displacement rather than the iOS style of "new page covers the old page / old page is pulled away to reveal the layer below":
 * In `AnimatedContent`, **entering content is always placed on the topmost layer** (with the same zIndex, the target is placed last), while
 * navigation-compose's NavHost does not expose `targetContentZIndex`, so when returning there is no way to place the popped page on top.
 * Same-direction translation does not depend on z-order, so in any direction the "page that should be revealed is covered" situation never occurs.
 *
 * The fade-out is faster than the fade-in and the fade-in is delayed slightly, so the two pages are almost never visible at the same time, avoiding color mixing and blurring of the two content layers during overlap.
 */
const val PAGE_MOTION_MS = 260

private const val PAGE_FADE_IN_MS = 200
private const val PAGE_FADE_IN_DELAY_MS = 60
private const val PAGE_FADE_OUT_MS = 90

/** The proportion of the container width taken by the horizontal displacement. A full-screen displacement on a large screen feels like "flinging" the page; one quarter is enough to express direction. */
private const val PAGE_SLIDE_FRACTION = 0.25f

private val PageSlideSpec = tween<IntOffset>(
    durationMillis = PAGE_MOTION_MS,
    easing = FastOutSlowInEasing
)

/**
 * Entering page transition: when [forward] is true (entering the next level) it slides in from the right; when false (returning to the previous level) it slides in from the left.
 */
fun pageEnter(forward: Boolean): EnterTransition =
    slideInHorizontally(PageSlideSpec) { width ->
        val offset = (width * PAGE_SLIDE_FRACTION).toInt()
        if (forward) offset else -offset
    } + fadeIn(tween(PAGE_FADE_IN_MS, delayMillis = PAGE_FADE_IN_DELAY_MS))

/** Exiting page transition: in the same direction as [pageEnter]; when moving forward it exits to the left, when returning it exits to the right. */
fun pageExit(forward: Boolean): ExitTransition =
    slideOutHorizontally(PageSlideSpec) { width ->
        val offset = (width * PAGE_SLIDE_FRACTION).toInt()
        if (forward) -offset else offset
    } + fadeOut(tween(PAGE_FADE_OUT_MS))
