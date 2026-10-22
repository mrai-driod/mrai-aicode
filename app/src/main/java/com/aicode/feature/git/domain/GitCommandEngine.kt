package com.aicode.feature.git.domain

import com.aicode.feature.agent.domain.container.CommandEngine
import com.aicode.feature.agent.domain.container.CommandEvent
import com.aicode.feature.agent.domain.container.CommandResult
import com.aicode.feature.agent.domain.container.ContainerInitState
import com.aicode.feature.agent.domain.container.LinuxContainerEngine
import com.aicode.feature.agent.domain.device.DeviceRootEngine
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Command engine dedicated to the Git module: on the "device ROOT" channel it redirects commands to the local PRoot container; on all other channels it forwards them unchanged.
 *
 * Background: a rooted Android device's root environment has **no git binary** (neither the system nor Magisk ships one), while the Debian/Ubuntu
 * container image has it preinstalled. Previously the Git page, like other tools, went through the [CommandEngine] delegation layer; after switching to the device channel,
 * every `git` invocation became `/system/bin/sh: git: inaccessible or not found`, surfacing as "failed to initialize repository".
 *
 * Only the "device" channel falls back, because:
 * - REMOTE_SSH: the workspace itself lives on a remote Linux host, so git must run remotely to see the repository; falling back to local would be wrong;
 * - LOCAL_PROOT: it is already the container, so behaviour is unchanged.
 *
 * If the container is not installed yet either, [LinuxContainerEngine.notReadyHint] returns guidance text that the Git page shows to the user.
 */
@Singleton
class GitCommandEngine @Inject constructor(
    private val modeHolder: ExecutionModeHolder,
    private val localEngine: LinuxContainerEngine,
    private val remoteEngine: com.aicode.feature.agent.domain.container.RemoteSshEngine
) : CommandEngine {

    /** Device channel -> container; all other channels forward as-is (the user gives up running git on-device). */
    private fun delegate(): CommandEngine = when (modeHolder.currentMode()) {
        ExecutionMode.REMOTE_SSH -> remoteEngine
        ExecutionMode.DEVICE_ROOT -> localEngine
        ExecutionMode.LOCAL_PROOT -> localEngine
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

    override suspend fun ensureInstalled() {
        // On the device channel this initializes the container (root needs no install), so the Git page can also bring the container up.
        localEngine.ensureInstalled()
    }
}
