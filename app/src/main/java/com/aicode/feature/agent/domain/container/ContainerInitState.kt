package com.aicode.feature.agent.domain.container

/**
 * Live progress state for container initialization. Exposed by [LinuxContainerEngine.initProgress] and shared
 * by every initialization entry point, such as the terminal page and the AI page (the engine is a @Singleton).
 * StateFlow conflates automatically, so high-frequency updates keep only the latest value.
 */
sealed interface ContainerInitState {
    /** Initialization has not started yet. */
    data object Idle : ContainerInitState

    /** Cleaning up data left behind by the previous container (deleting the old rootfs after a reset/image switch); [processed] is the number of entries cleaned so far. */
    data class CleaningOldRootfs(val processed: Int) : ContainerInitState

    /** Extracting the Alpine rootfs; [processed] is the number of tar entries processed so far. */
    data class ExtractingRootfs(val processed: Int) : ContainerInitState

    /** Installing the base packages (python3/git/pip/node/npm/rg); [line] is a live output line from apk (may be null). */
    data class InstallingPackages(val line: String?) : ContainerInitState

    /** The container is ready (rootfs extraction and base package installation are both complete; proot ships with the APK and needs no deployment). */
    data object Ready : ContainerInitState

    /** Initialization failed; [reason] describes why. */
    data class Failed(val reason: String) : ContainerInitState
}
