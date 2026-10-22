package com.aicode.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Window width classes, with breakpoints following the Material 3 window size class: 600dp / 840dp.
 *
 * - [COMPACT]  Phone portrait, narrow window after split-screen
 * - [MEDIUM]   Most tablets in portrait, phones in landscape, foldables unfolded held in portrait
 * - [EXPANDED] Tablets in landscape, desktop window mode -- dual-pane and a persistent sidebar are only used from this class upwards
 */
enum class WindowWidthClass { COMPACT, MEDIUM, EXPANDED }

/** Breakpoint determination. A pure function, making it easy for unit tests to cover boundary values. */
fun windowWidthClassOf(widthDp: Int): WindowWidthClass = when {
    widthDp < 600 -> WindowWidthClass.COMPACT
    widthDp < 840 -> WindowWidthClass.MEDIUM
    else -> WindowWidthClass.EXPANDED
}

/**
 * Current window width class.
 *
 * Uses `Configuration.screenWidthDp` rather than the physical screen size: under split-screen / freeform windows it gives the width of **this window**,
 * so when a tablet is dragged into a 1/3 narrow window it automatically falls back to the single-column layout of [WindowWidthClass.COMPACT].
 */
@Composable
fun currentWindowWidthClass(): WindowWidthClass {
    val widthDp = LocalConfiguration.current.screenWidthDp
    return remember(widthDp) { windowWidthClassOf(widthDp) }
}

/** Whether this is a large screen (tablet landscape and above), the switch condition for the dual-pane layout. */
@Composable
fun isExpandedWidth(): Boolean = currentWindowWidthClass() == WindowWidthClass.EXPANDED

/** Whether this is a compact width (phone portrait, narrow split-screen, etc., < 600dp). */
@Composable
fun isCompactWidth(): Boolean = currentWindowWidthClass() == WindowWidthClass.COMPACT

object ContentWidth {
    /** Maximum width of the body text column: any wider and a line of text is too long, with a high cost for the eyes scanning back and forth. */
    val readable = 800.dp

    /** Sidebar width: narrow windows keep the original size; large screens leave a little more horizontal space for the file tree. */
    val drawerCompact = 300.dp
    val drawerWide = 340.dp
}

/**
 * Upper limit for the body text column width: on large screens it narrows to [ContentWidth.readable] and is centered; other classes impose no limit.
 *
 * When [Dp.Unspecified] is returned, `Modifier.widthIn(max = …)` is equivalent to adding no constraint, so call sites need no branching.
 */
@Composable
fun readableContentMaxWidth(): Dp =
    if (isExpandedWidth()) ContentWidth.readable else Dp.Unspecified

/** Sidebar width: switches with the window class. */
@Composable
fun drawerWidth(): Dp =
    if (currentWindowWidthClass() == WindowWidthClass.COMPACT) {
        ContentWidth.drawerCompact
    } else {
        ContentWidth.drawerWide
    }
