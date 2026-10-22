package com.aicode.feature.agent.domain.shizuku

import android.os.Bundle
import com.aicode.feature.agent.domain.container.BoundedOutput
import java.util.concurrent.TimeUnit

/**
 * Shizuku UserService: instantiated by Shizuku in the shell (uid 2000) process, executing shell commands on behalf of the App.
 *
 * This process is not a legitimate Android application process; it can only use Java/system-level APIs and cannot use Context-related capabilities
 * (getContentResolver / registerReceiver, etc.), so the implementation uses only [ProcessBuilder].
 *
 * The class name and constructor are loaded by Shizuku via reflection and must not be obfuscated by R8 (see proguard-rules.pro).
 */
class ShizukuShellService : IShizukuShellService.Stub() {

    /** Destruction entry point reserved by Shizuku: triggered on unbindUserService(remove=true), ending this process. */
    override fun destroy() {
        System.exit(0)
    }

    override fun exec(command: String, timeoutMs: Int): Bundle {
        val result = Bundle()
        var process: Process? = null
        try {
            process = ProcessBuilder("sh", "-c", command)
                .redirectErrorStream(true)
                .start()
            // Reading the output and waiting for completion must happen in parallel: a full pipe blocks the child process, so calling waitFor before reading would deadlock.
            // Also capped: the Binder transaction buffer limit is 1MB, and returning oversized output directly would throw TransactionTooLargeException.
            val output = BoundedOutput()
            val reader = Thread {
                runCatching {
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            output.append(line)
                            output.append("\n")
                        }
                    }
                }
            }
            reader.start()
            val finished = process.waitFor(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            if (!finished) process.destroyForcibly()
            reader.join(READER_JOIN_TIMEOUT_MS)
            result.putString(KEY_OUTPUT, output.build())
            result.putInt(KEY_EXIT_CODE, if (finished) process.exitValue() else EXIT_TIMEOUT)
        } catch (e: Exception) {
            result.putString(KEY_OUTPUT, e.message ?: "Execution failed")
            result.putInt(KEY_EXIT_CODE, EXIT_FAILURE)
        } finally {
            process?.destroy()
        }
        return result
    }

    companion object {
        const val KEY_OUTPUT = "output"
        const val KEY_EXIT_CODE = "exitCode"

        /** Exit code when a command times out (the process is force-killed). */
        const val EXIT_TIMEOUT = -1000

        /** Exit code on a startup/read exception. */
        const val EXIT_FAILURE = -1

        private const val READER_JOIN_TIMEOUT_MS = 2_000L
    }
}
