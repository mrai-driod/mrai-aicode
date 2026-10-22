package com.aicode.feature.agent.domain.container

import com.aicode.feature.agent.domain.device.DeviceRootEngine
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Delegation layer for [CommandEngine]: holds the local container, remote SSH, and device ROOT implementations
 * and forwards each method call to the matching implementation according to [ExecutionModeHolder.currentMode].
 *
 * This way the Hilt injection timing no longer affects the final behaviour: no matter when [CommandEngine] is
 * first injected, the current mode is only read when a command is actually executed.
 *
 * Besides the global mode, callers can also explicitly specify an execution environment ([engineFor]): the global
 * mode is the "preference", and the AI can explicitly choose another channel when appropriate (for example, when
 * it needs the full Linux toolchain inside the container).
 */
@Singleton
class DelegatingCommandEngine @Inject constructor(
    private val modeHolder: ExecutionModeHolder,
    private val localEngine: LinuxContainerEngine,
    private val remoteEngine: RemoteSshEngine,
    private val deviceRootEngine: DeviceRootEngine
) : CommandEngine {

    /** Explicit execution environment. [AUTO] follows the global mode preference. */
    enum class TargetEnv { AUTO, CONTAINER, DEVICE, REMOTE }

    private fun delegate(): CommandEngine = when (modeHolder.currentMode()) {
        ExecutionMode.REMOTE_SSH -> remoteEngine
        ExecutionMode.DEVICE_ROOT -> deviceRootEngine
        ExecutionMode.LOCAL_PROOT -> localEngine
    }

    /**
     * Gets the engine for the specified execution environment. [TargetEnv.AUTO] is equivalent to the current
     * global mode (the preference); other values bypass the global mode and go straight to the corresponding
     * engine, so the AI can explicitly switch channels as the situation requires.
     */
    fun engineFor(env: TargetEnv): CommandEngine = when (env) {
        TargetEnv.AUTO -> delegate()
        TargetEnv.CONTAINER -> localEngine
        TargetEnv.DEVICE -> deviceRootEngine
        TargetEnv.REMOTE -> remoteEngine
    }

    override val initProgress: StateFlow<ContainerInitState>
        get() = delegate().initProgress

    override fun runCommandStream(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): Flow<CommandEvent> = delegate().runCommandStream(command, projectPath, timeoutMs)

    override suspend fun runCommandSync(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): String = delegate().runCommandSync(command, projectPath, timeoutMs)

    override suspend fun runCommandSyncWithExit(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): CommandResult = delegate().runCommandSyncWithExit(command, projectPath, timeoutMs)

    override suspend fun runCommandSyncUnbounded(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): CommandResult = delegate().runCommandSyncUnbounded(command, projectPath, timeoutMs)

    override suspend fun runCommandSyncIfReady(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): CommandResult? = delegate().runCommandSyncIfReady(command, projectPath, timeoutMs)

    override fun isContainerInstalled(): Boolean = delegate().isContainerInstalled()

    override fun isProvisioned(): Boolean = delegate().isProvisioned()

    override fun defaultShell(): String = delegate().defaultShell()

    override fun notReadyHint(): String? = delegate().notReadyHint()

    override suspend fun ensureInstalled() = delegate().ensureInstalled()
}
