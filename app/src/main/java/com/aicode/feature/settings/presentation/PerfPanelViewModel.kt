package com.aicode.feature.settings.presentation

import androidx.lifecycle.ViewModel
import com.aicode.core.perf.PerfFilesInfo
import com.aicode.core.perf.PerfMonitor
import com.aicode.core.perf.PerfSample
import com.aicode.core.perf.PerfSystem
import com.aicode.core.perf.PerfTotals
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import javax.inject.Inject

/**
 * Performance panel: directly forwards [PerfMonitor]'s state flow, and delegates all actions too.
 * Aggregation (per-module mean/peak) is deliberately computed on the fly in the UI layer — the sample flow changes only once per second, so the cost is negligible,
 * and in return, changing the aggregation rules does not require touching the ViewModel.
 */
@HiltViewModel
class PerfPanelViewModel @Inject constructor(
    private val perf: PerfMonitor
) : ViewModel() {

    val enabled: StateFlow<Boolean> = perf.enabled
    val module: StateFlow<String> = perf.module
    val samples: StateFlow<List<PerfSample>> = perf.samples
    val totals: StateFlow<PerfTotals> = perf.totals
    val system: StateFlow<PerfSystem> = perf.system
    val filesInfo: StateFlow<PerfFilesInfo> = perf.filesInfo

    fun setEnabled(on: Boolean) = perf.setEnabled(on)

    suspend fun exportRaw(): File = perf.exportRawFile()

    suspend fun exportCsv(): File = perf.exportSummaryCsv()

    fun clearHistory() = perf.clearHistory()
}
