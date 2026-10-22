// Shizuku UserService interface: runs in the shell (uid 2000) process and executes shell commands on behalf of the app.
// Returns a Bundle: output (String) command output, exitCode (int) exit code (negative on timeout or error).
package com.aicode.feature.agent.domain.shizuku;

import android.os.Bundle;

interface IShizukuShellService {
    // Shizuku reserved destroy entry point: the transaction number is fixed (the aidl declares 16777114, but the server actually invokes it as 16777115),
    // triggered when unbindUserService(remove=true) is called; the implementation should terminate the process. Do not change.
    void destroy() = 16777114;

    Bundle exec(String command, int timeoutMs) = 1;
}
