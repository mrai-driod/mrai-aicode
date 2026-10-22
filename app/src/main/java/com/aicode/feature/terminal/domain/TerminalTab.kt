package com.aicode.feature.terminal.domain

import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView

/**
 * Running state of a terminal tab. Finished tabs stay in the list instead of being removed, so the user/AI can review their output.
 */
sealed interface RunState {
    data object Running : RunState
    data class Finished(val exitCode: Int) : RunState
}

/** Number of terminal output lines carried by a background task completion notification. */
const val TAIL_LINES = 50

/** Take the last n lines of a terminal transcript; null input returns null. */
fun String?.takeTailLines(n: Int): String? =
    this?.lines()?.takeLast(n)?.joinToString("\n")

/**
 * Event emitted when a background command ends, for the ViewModel to subscribe to and notify the AI.
 */
data class TabFinishedEvent(
    val tabId: String,
    val title: String,
    val command: String?,
    val exitCode: Int,
    /** ID of the session that started this background command; the callback uses it to route back to the original session, rather than the session the user is currently in. */
    val sourceSessionId: String?,
    /** Snapshot of the last [TAIL_LINES] lines of the terminal transcript at finish time. The event may be cached until the session is idle before being merged and sent, during which the tab may already have been closed, so it is captured in advance at emit time. */
    val tailOutput: String? = null
)

/**
 * A terminal tab: session + rendering view + metadata.
 *
 * [view] is filled in by Compose after creating [TerminalView]; when switching tabs, the same session is reused and the view is reattached.
 * The viewProvider of [client] always reads [view], so no matter how the view is rebuilt, output can be flushed to the currently attached view.
 *
 * Local and remote terminal modes share this type — the only difference is the [com.termux.terminal.SessionBackend] behind [session]
 * (the local SubprocessBackend forks a process, the remote one is an SSH shell stream).
 */
class TerminalTab(
    val id: String,
    title: String,
    val session: TerminalSession,
    val isBackground: Boolean,
    val command: String?,
    val notifyOnExit: Boolean = false,
    /** ID of the session that started this background command; null for interactive tabs. The callback uses it to route back to the original session. */
    val sourceSessionId: String? = null,
    val client: com.termux.terminal.TerminalSessionClient? = null,
    runState: RunState
) {
    var title: String = title
        internal set

    @Volatile
    var view: TerminalView? = null

    var runState: RunState = runState
        internal set

    /**
     * Whether a completion event has already been sent to the AI. Both fallback monitoring and onFinished may trigger completion,
     * so this flag ensures the same command only calls back once, avoiding duplicate notifications when a process exits late.
     */
    @Volatile
    var finishedNotified: Boolean = false
}

/**
 * Summary of a terminal tab, used when the AI's terminal tool lists tabs (does not carry runtime objects such as session/view).
 */
data class TabInfo(
    val id: String,
    val title: String,
    val isBackground: Boolean,
    val running: Boolean,
    val command: String?
)
