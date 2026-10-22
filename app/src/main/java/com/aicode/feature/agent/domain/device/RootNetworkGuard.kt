package com.aicode.feature.agent.domain.device

import android.content.Context
import com.aicode.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ROOT "fake foreground": as root, makes the system keep treating this app as a foreground process, so it stops
 * suspending its network. Only takes effect on devices that have obtained ROOT; silently skipped without ROOT.
 *
 * ## Why this is necessary
 *
 * After moving to the background, SSE silently hangs, and testing shows there are **two independent layers** of
 * restriction; removing only one is not enough:
 *
 * 1. **cgroup v2 freezer**: threads are suspended wholesale, stuck on a socket `read()` - they neither advance
 *    nor throw (so there is no error in the logs); the moment they thaw, the backlog of RSTs bursts out at once as
 *    `SocketException: Software caused connection abort`, and two independent connections die in the same
 *    millisecond, because that is a process-level event, not a peer timeout.
 *
 *    Note that cgroup freezing is **hierarchically inherited**: when freeze=1 is set at UID level, every process
 *    under it freezes, and the process-level file showing 0 changes nothing - looking only at the process level
 *    leads to the wrong conclusion that it was "not frozen". Also, while frozen, `/proc/<pid>/status` still shows
 *    `S (sleeping)` (`TASK_FROZEN` is just `TASK_INTERRUPTIBLE` plus a flag), so the process state cannot be used
 *    to judge freezing.
 *
 * 2. **ROM-private background network control**: ColorOS (OPPO/OnePlus) has a native `hans` daemon that writes
 *    background UIDs into netd's eBPF map (`map_oplus-netd_app_drop_wlan_socket_uid_limit_map`, etc.) and drops
 *    packets directly at the socket layer. At that point iptables contains **not a single rule**, so checking the
 *    firewall finds nothing; it manifests as `UnknownHostException` (DNS packets are dropped), which is a different
 *    matter from freezing.
 *
 * Neither is related to foreground services, `PARTIAL_WAKE_LOCK`, `WifiLock`, or `netpolicy` allowlists: those
 * protect CPU/priority/background data quota, and can do nothing about "threads not executing" or "sockets dropping packets".
 *
 * ## Countermeasure: maintain continuously rather than set once
 *
 * The system keeps reverting the state, so a one-time setting does not survive more than a few seconds. This starts
 * a resident root daemon loop to maintain it (a marker file guarantees a single instance, and it exits by itself
 * once this app's process is gone):
 *
 * - High frequency (every 2s): unfreeze the UID-level and all process-level `cgroup.freeze`;
 * - Low frequency (every 10s): `standby bucket = active`, AppOps allow for background running;
 * - Every check: `stop hans` (only when that service exists, i.e. ColorOS);
 * - One-time: disable the global `app_standby_enabled`, add the `netpolicy` background-data allowlist entry.
 *
 * Deliberately **does not request a Doze allowlist entry**: after the screen turns off there is no need to keep
 * working anyway; this targets only the network being suspended.
 *
 * The daemon's stdout/stderr must be redirected to `/dev/null`: the caller merges stderr into stdout and reads to
 * EOF, and a background process holding the pipe would make `waitFor()` block for the full timeout.
 */
@Singleton
class RootNetworkGuard @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val deviceRootEngine: DeviceRootEngine
) {
    private val mutex = Mutex()

    @Volatile
    private var lastAppliedAtMs = 0L

    /**
     * Applies the background network exemption. Must be called on an IO thread (internally goes through an `su` child process).
     *
     * Throttled by [APPLY_INTERVAL_MS] within a session: once these settings take effect they stay in effect, so
     * there is no need to fork an su for every message. Failures do not throw; the upper layer continues with the
     * regular keep-alive path.
     *
     * @return whether it is in effect (already, or as of this call); returns false without ROOT.
     */
    suspend fun applyIfRooted(): Boolean {
        // The ROOT probe itself is cached; silently skip when ROOT has not been obtained
        if (!deviceRootEngine.isProvisioned()) return false

        return mutex.withLock {
            val now = System.currentTimeMillis()
            if (now - lastAppliedAtMs < APPLY_INTERVAL_MS) return@withLock true

            val pkg = context.packageName
            // One script, one su: first the one-time settings, then write the daemon script and launch it in the background.
            // $pkg is injected by Kotlin; \$xxx is a device shell variable and must be escaped.
            val script = buildString {
                append("uid=\$(stat -c %u /data/data/$pkg 2>/dev/null); ")
                // ---- One-time: turn off App Standby, which defers background network, and add the background data allowlist entry ----
                append("settings put global app_standby_enabled 0; ")
                append("[ -n \"\$uid\" ] && cmd netpolicy add restrict-background-whitelist \$uid; ")
                // ---- Unfreeze once immediately, without waiting for the daemon's first tick ----
                append("if [ -n \"\$uid\" ]; then for f in /sys/fs/cgroup/apps/uid_\$uid/cgroup.freeze ")
                append("/sys/fs/cgroup/uid_\$uid/cgroup.freeze ")
                append("/sys/fs/cgroup/apps/uid_\$uid/pid_*/cgroup.freeze ")
                append("/sys/fs/cgroup/uid_\$uid/pid_*/cgroup.freeze; ")
                append("do [ -f \"\$f\" ] && echo 0 > \"\$f\" 2>/dev/null; done; fi; ")
                append("am set-standby-bucket $pkg active; ")
                append("cmd appops set $pkg RUN_IN_BACKGROUND allow; ")
                append("cmd appops set $pkg RUN_ANY_IN_BACKGROUND allow; ")
                // ---- Stop the ROM-private background network control daemon (ColorOS's hans) ----
                append("if [ \"\$(getprop init.svc.hans)\" = running ]; then stop hans; fi; ")
                // ---- Write the daemon script: the heredoc quoting prevents the shell from expanding it early ----
                append("cat > $GUARD_PATH <<'AICODE_GUARD_EOF'\n")
                append("#!/system/bin/sh\n")
                append("u=\$(stat -c %u /data/data/$pkg 2>/dev/null)\n")
                append("op=\$(cat $GUARD_MARKER 2>/dev/null)\n")
                append("if [ -n \"\$op\" ] && [ -d /proc/\$op ]; then exit 0; fi\n")
                append("echo \$\$ > $GUARD_MARKER 2>/dev/null\n")
                append("i=0\n")
                append("while [ \$i -lt $GUARD_MAX_TICKS ]; do\n")
                append("  pidof $pkg >/dev/null 2>&1 || break\n")
                // High frequency: unfreeze (pure echo, negligible cost)
                append("  for f in /sys/fs/cgroup/apps/uid_\$u/cgroup.freeze /sys/fs/cgroup/uid_\$u/cgroup.freeze ")
                append("/sys/fs/cgroup/apps/uid_\$u/pid_*/cgroup.freeze /sys/fs/cgroup/uid_\$u/pid_*/cgroup.freeze; do\n")
                append("    [ -f \"\$f\" ] && echo 0 > \"\$f\" 2>/dev/null\n")
                append("  done\n")
                // Every iteration: keep the ROM-private control daemon down (it drops background UIDs' packets at the socket layer)
                append("  if [ \"\$(getprop init.svc.hans)\" = running ]; then stop hans; fi\n")
                // Low frequency: am/cmd are Java commands with a large fork cost, so run them less often
                append("  if [ \$((i % $STATE_EVERY_TICKS)) -eq 0 ]; then\n")
                append("    am set-standby-bucket $pkg active >/dev/null 2>&1\n")
                append("    cmd appops set $pkg RUN_IN_BACKGROUND allow >/dev/null 2>&1\n")
                append("    cmd appops set $pkg RUN_ANY_IN_BACKGROUND allow >/dev/null 2>&1\n")
                append("  fi\n")
                append("  sleep $GUARD_TICK_SECONDS\n")
                append("  i=\$((i+1))\n")
                append("done\n")
                append("rm -f $GUARD_MARKER 2>/dev/null\n")
                append("AICODE_GUARD_EOF\n")
                append("nohup sh $GUARD_PATH >/dev/null 2>&1 &\n")
                // ---- Read back the key state, so the logs show at a glance whether it really took effect ----
                append("echo \"freeze=\$(cat /sys/fs/cgroup/apps/uid_\$uid/cgroup.freeze 2>/dev/null) ")
                append("hans=\$(getprop init.svc.hans) bucket=\$(am get-standby-bucket $pkg)\"")
            }

            val result = runCatching {
                deviceRootEngine.runCommandSyncWithExit(script, null, TIMEOUT_MS)
            }.getOrElse {
                FileLogger.w(TAG, "ROOT network exemption execution failed: ${it.message}")
                return@withLock false
            }

            if (result.exitCode == null) {
                FileLogger.w(TAG, "ROOT network exemption did not complete: ${result.output.take(200)}")
                return@withLock false
            }
            lastAppliedAtMs = now
            val status = result.output.lineSequence()
                .firstOrNull { it.contains("freeze=") }
                ?.trim()
            FileLogger.i(
                TAG,
                "ROOT fake foreground deployed (exit=$result.exitCode) $status"
            )
            true
        }
    }

    private companion object {
        const val TAG = "RootNetworkGuard"

        /** Timeout for a single script: `cmd netpolicy` occasionally stalls, so allow plenty without holding up task startup. */
        const val TIMEOUT_MS = 15_000L

        /**
         * Throttle interval: only decides how often the daemon is re-checked and re-launched if needed; the daemon itself keeps maintaining state in between.
         * It must not be too long: the daemon may be cleaned up by the system, and too long an interval would leave a window with nobody maintaining state;
         * the script itself is idempotent (the marker guarantees a single instance) and takes under 1 second, so checking once a minute is cheap enough.
         */
        const val APPLY_INTERVAL_MS = 60 * 1000L

        /** Path where the daemon script is written. */
        const val GUARD_PATH = "/data/local/tmp/.aicode_guard.sh"

        /** Daemon single-instance marker: stores the pid; once the process is gone it is treated as invalid. */
        const val GUARD_MARKER = "/data/local/tmp/.aicode_guard"

        /** Interval between daemon loop iterations (seconds): the system re-freezes on a seconds scale, so 2 seconds is enough to keep it down. */
        const val GUARD_TICK_SECONDS = 2

        /** `am`/`cmd` are Java commands with a large fork cost, so they run only every N ticks. */
        const val STATE_EVERY_TICKS = 5

        /** Maximum number of ticks the daemon lives: 2s x 1800 = 60 minutes, on the scale of one long task. */
        const val GUARD_MAX_TICKS = 1800
    }
}
