package com.aicode.feature.settings.presentation

import androidx.lifecycle.ViewModel
import com.aicode.feature.agent.domain.shizuku.ShizukuManager
import com.aicode.feature.agent.domain.shizuku.ShizukuState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/** Settings page state and action entry points for the Shizuku execution backend. */
@HiltViewModel
class ShizukuViewModel @Inject constructor(
    private val shizukuManager: ShizukuManager
) : ViewModel() {

    val state: StateFlow<ShizukuState> = shizukuManager.state

    /** Re-probes the state (e.g. after returning from the Shizuku app). */
    fun refresh() = shizukuManager.refreshState()

    fun requestPermission() = shizukuManager.requestPermission()

    fun openShizukuApp() = shizukuManager.openShizukuApp()
}
