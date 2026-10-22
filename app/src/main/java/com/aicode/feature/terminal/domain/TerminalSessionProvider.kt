package com.aicode.feature.terminal.domain

import kotlinx.coroutines.flow.SharedFlow

/**
 * Terminal session backend abstraction: decouples "where the terminal session runs" from the hardcoded local Termux PTY.
 *
 * [TerminalSessionTool] (the AI's terminal tool) depends on this interface rather than a concrete implementation,
 * and the DI delegation layer forwards to the corresponding implementation based on the current execution mode:
 * - [TerminalSessionManager]: local Termux PTY (forks a proot process);
 * - `RemoteTerminalSessionManager`: remote SSH shell channel.
 */
interface TerminalSessionProvider {

    /** Event emitted when a background command ends, for the ViewModel to subscribe to and notify the AI. */
    val tabFinishedEvents: SharedFlow<TabFinishedEvent>

    /** Runs a command in the background (e.g. `npm run dev`), returns a unique tabId. */
    suspend fun startBackgroundCommand(
        command: String,
        title: String? = null,
        notify: Boolean = false,
        sourceSessionId: String? = null
    ): String

    /** Sends input to the tab by id and presses Enter to execute. Returns whether the tab was found and is still active. */
    fun sendInput(id: String, input: String, appendNewline: Boolean = true): Boolean

    /** Writes raw text to the tab by id, without automatically appending a newline. */
    fun writeToTab(id: String, text: String): Boolean

    /** Writes raw bytes to the tab by id (control characters, e.g. Ctrl-C=0x03). */
    fun writeBytesToTab(id: String, vararg bytes: Int): Boolean

    /** Reads terminal content by id (emulator screen buffer). Returns null if there is no such tab. */
    fun getTabOutput(id: String): String?

    /** Lists summaries of all tabs. */
    fun listTabs(): List<TabInfo>

    /** Closes and destroys a tab. Returns whether it succeeded. */
    fun closeTab(id: String): Boolean
}
