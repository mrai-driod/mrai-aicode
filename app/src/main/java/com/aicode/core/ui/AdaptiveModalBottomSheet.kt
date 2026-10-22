package com.aicode.core.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetDefaults
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.aicode.core.theme.Spacing

/**
 * Fixes a known Material3 ModalBottomSheet bug (issuetracker 486562294):
 * When the content is close to full-screen height and has a scrollable area, a fast upward fling to the content boundary passes the unconsumed upward velocity to
 * the sheet's drag state, causing the sheet to repeatedly oscillate "drag down then bounce back".
 *
 * Attach to the outer layer of scrollable content: when the sheet is fully expanded, swallow the remaining upward fling velocity so the velocity no longer flows to
 * the sheet's AnchoredDraggableState; the content's own inertial scrolling is unaffected (only the remainder is intercepted after child scrolling has consumed its share).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun rememberSheetFlingFix(sheetState: SheetState): NestedScrollConnection =
    remember(sheetState) {
        object : NestedScrollConnection {
            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
                if ((sheetState.currentValue == SheetValue.Expanded || sheetState.targetValue == SheetValue.Expanded) && available.y < 0f) {
                    available
                } else {
                    Velocity.Zero
                }
        }
    }

/**
 * Adaptive modal bottom sheet/dialog:
 * - Compact screens (phone portrait, narrow split-screen, etc., < 600dp): presented as a standard bottom-anchored [ModalBottomSheet], retaining the pull-down gesture and drag handle,
 *   and with built-in [rememberSheetFlingFix] to eliminate the jerky oscillation when flinging quickly to the boundary;
 * - Wide screens/tablets (>= 600dp): presented as a standard centred modal [Dialog], removing the drag handle, limiting the maximum width and showing it centred.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdaptiveModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    shape: Shape = BottomSheetDefaults.ExpandedShape,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = contentColorFor(containerColor),
    tonalElevation: Dp = BottomSheetDefaults.Elevation,
    scrimColor: Color = BottomSheetDefaults.ScrimColor,
    dragHandle: @Composable (() -> Unit)? = { BottomSheetDefaults.DragHandle() },
    contentWindowInsets: @Composable () -> WindowInsets = { BottomSheetDefaults.windowInsets },
    properties: ModalBottomSheetProperties = ModalBottomSheetDefaults.properties,
    sheetGesturesEnabled: Boolean = true,
    dialogMaxWidth: Dp = 560.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    if (isCompactWidth()) {
        val flingFix = rememberSheetFlingFix(sheetState)
        ModalBottomSheet(
            onDismissRequest = onDismissRequest,
            modifier = modifier,
            sheetState = sheetState,
            shape = shape,
            containerColor = containerColor,
            contentColor = contentColor,
            tonalElevation = tonalElevation,
            scrimColor = scrimColor,
            dragHandle = dragHandle,
            contentWindowInsets = contentWindowInsets,
            properties = properties,
            sheetGesturesEnabled = sheetGesturesEnabled
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .nestedScroll(flingFix)
            ) {
                content()
            }
        }
    } else {
        Dialog(
            onDismissRequest = onDismissRequest,
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = modifier
                    .widthIn(min = 280.dp, max = dialogMaxWidth)
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = Spacing.xl)
                    .heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.85f).dp),
                shape = RoundedCornerShape(16.dp),
                color = containerColor,
                contentColor = contentColor,
                tonalElevation = tonalElevation
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Spacing.md)
                ) {
                    content()
                }
            }
        }
    }
}
