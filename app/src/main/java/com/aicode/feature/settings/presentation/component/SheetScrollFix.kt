package com.aicode.feature.settings.presentation.component

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection

/**
 * Backward-compatible alias: the implementation has moved to [com.aicode.core.ui.rememberSheetFlingFix],
 * and is automatically integrated in [com.aicode.core.ui.AdaptiveModalBottomSheet].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun rememberSheetFlingFix(sheetState: SheetState): NestedScrollConnection =
    com.aicode.core.ui.rememberSheetFlingFix(sheetState)
