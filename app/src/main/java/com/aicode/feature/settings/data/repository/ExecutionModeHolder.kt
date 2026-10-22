package com.aicode.feature.settings.data.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Synchronous cache of the execution mode: at app startup it reads the first-frame mode from [ExecutionModeRepository] and caches it in memory,
 * so each delegating layer (`DelegatingCommandEngine`/`DelegatingFileAccess`/`DelegatingTerminalSessionProvider`)
 * can read it synchronously on every call to decide whether to forward to the local or the remote implementation.
 *
 * After the mode is switched (on the settings page), [setMode] updates the cache and the switch takes effect immediately, with no restart needed.
 */
@Singleton
class ExecutionModeHolder @Inject constructor() {
    private val _mode = MutableStateFlow(ExecutionMode.LOCAL_PROOT)
    val mode: StateFlow<ExecutionMode> = _mode.asStateFlow()

    fun setMode(mode: ExecutionMode) {
        _mode.value = mode
    }

    fun currentMode(): ExecutionMode = _mode.value
}
