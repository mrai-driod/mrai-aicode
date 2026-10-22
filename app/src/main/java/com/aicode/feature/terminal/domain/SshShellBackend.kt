package com.aicode.feature.terminal.domain

import com.aicode.core.util.FileLogger
import com.termux.terminal.SessionBackend
import net.schmizz.sshj.connection.channel.direct.Session
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Executors

private const val TAG = "SshShellBackend"

/**
 * [SessionBackend] backed by an sshj interactive shell channel ([Session.Shell]).
 *
 * Used by [RemoteTerminalSessionManager] so the remote SSH shell drives the Termux
 * [com.termux.terminal.TerminalEmulator] exactly like a local pty would: shell output →
 * emulator, user input → shell stdin, resize → PTY window size change.
 *
 * The [Session.Shell] must already be started (with a PTY allocated) before being wrapped
 * here. [waitForExit] blocks on [Session.Shell.join]; remote shells have no meaningful
 * exit status, so 0 is returned (the emulator appends its own "[Process completed]" notice
 * via [com.termux.terminal.TerminalSession] once the reader thread hits EOF).
 */
class SshShellBackend(
    private val shell: Session.Shell
) : SessionBackend {

    // changeWindowDimensions performs network I/O; resize is triggered by TerminalView on the main thread, so it must be moved to a background thread
    private val resizeExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "SshShellResize") }
    @Volatile private var closed = false

    override fun getInputStream(): InputStream = shell.inputStream

    override fun getOutputStream(): OutputStream = shell.outputStream

    override fun resize(columns: Int, rows: Int) {
        if (closed || resizeExecutor.isShutdown) return
        resizeExecutor.execute {
            runCatching { shell.changeWindowDimensions(columns, rows, 0, 0) }
                .onFailure { FileLogger.w(TAG, "PTY resize failed ${columns}x${rows}", it) }
        }
    }

    override fun waitForExit(): Int {
        return runCatching {
            shell.join()
            0
        }.getOrElse {
            FileLogger.w(TAG, "shell.join exception", it)
            0
        }
    }

    override fun close() {
        closed = true
        resizeExecutor.shutdownNow()
        // shell.close() performs network I/O and cannot be executed synchronously on the main thread
        Thread({
            runCatching { shell.close() }
                .onFailure { FileLogger.w(TAG, "shell.close exception", it) }
        }, "SshShellClose").start()
    }
}
