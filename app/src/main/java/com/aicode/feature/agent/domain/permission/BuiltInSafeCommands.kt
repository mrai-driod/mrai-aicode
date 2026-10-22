package com.aicode.feature.agent.domain.permission

/**
 * Built-in safe allowlist: common command prefixes that are genuinely "read-only, no side effects, no network,
 * no child-process spawning"; they are auto-allowed without a dialog.
 *
 * This allowlist is built into the code and immutable — deliberately never persisted to DataStore, and never
 * into `~/workspace` either, in line with the authorization layer's safety principle that "rules are never
 * persisted into the container": the AI inside the container cannot read or write this table to grant itself
 * more privileges.
 *
 * Design principles (must be maintained):
 * - Only include commands that are "read-only no matter what arguments are passed". Any command that can write
 *   files, change state, access the network, or spawn child processes is never included.
 * - Spawning/execution commands are never included: `node`/`python`/`ruby`/`sh -c`/`xargs`/`find -exec`/`env CMD`/`command CMD`/
 *   `source`/`.`/`eval`/`sed -i`/`awk system()` — otherwise things like `env rm -rf /` would be wrongly allowed by a prefix rule.
 * - Writable commands are not included: `find` (can -exec/-delete), `sed`/`awk` (can -i/system), `tee`, `cp`/`mv`/`rm`/`ln`/
 *   `mkdir`/`rmdir`/`chmod`/`chown`/`touch`/`dd`.
 * - Network commands are not included: `curl`/`wget`/`ssh`/`scp`/`rsync`.
 * - Leaking/spawnable commands are not included: `env`/`printenv` (leak environment variables, and `env CMD` spawns).
 * - For git, only subcommands that are "read-only for any arguments" are included; pull/clone/push/commit/add/reset/checkout/branch/tag/merge/rebase are not.
 *
 * Matching uses the token-prefix semantics of [ShellCommandParser.matches]: `git status` matches `git status -s`, but not `git push`.
 *
 * Safety fallback: the user can add a DENY rule in the management UI to override this allowlist (during
 * evaluation, DENY takes precedence over every ALLOW, including built-in ones).
 * Commands that cannot be statically analyzed (including command substitution/grouping/absolute-path redirects)
 * do not go through this allowlist and still trigger a dialog.
 */
object BuiltInSafeCommands {

    /** Git subcommand prefixes that are "read-only for any arguments"; write-operation subcommands are not listed here and still require dialog authorization. */
    private val GIT_READ_ONLY_PREFIXES: List<String> = listOf(
        "git status", "git log", "git diff", "git show", "git blame",
        "git rev-parse", "git ls-files", "git describe", "git for-each-ref",
        "git cat-file", "git ls-tree", "git config --get",
        "git stash list", "git remote -v"
    )

    /** Safe command prefixes that are auto-allowed (matched by token prefix). */
    val SAFE_PREFIXES: List<String> = listOf(
        // —— Directory/file metadata inspection (read-only) ——
        "ls", "pwd", "tree", "stat", "file", "du", "df",
        "basename", "dirname", "realpath", "readlink", "lsof",
        // —— File content viewing (read-only; relative redirects writing workspace files are treated as acceptable by ShellCommandParser's established policy) ——
        "cat", "head", "tail", "less", "more", "wc", "nl", "tac", "paste", "column",
        // —— Search (read-only) ——
        "grep", "egrep", "fgrep", "rg", "ack", "zgrep",
        // —— Text processing (read-only, output to stdout; excludes sed/awk, both of which can modify files via -i/system) ——
        "sort", "uniq", "cut", "tr", "fold", "fmt", "expand", "unexpand", "rev", "comm", "diff", "cmp",
        // —— Info/lookup/system monitoring (read-only; excludes env/printenv — they leak environment variables and can spawn execution) ——
        "echo", "printf", "date", "whoami", "id", "uname", "hostname", "uptime", "nproc", "cal",
        "which", "type", "whereis", "locate", "getconf",
        "ps", "top", "free", "htop", "vmstat", "lscpu", "lsblk", "arch", "clear", "history", "true", "false"
    ) + GIT_READ_ONLY_PREFIXES

    /** Whether this segment hits any built-in safe prefix. */
    fun isSafe(segmentTokens: List<String>): Boolean =
        SAFE_PREFIXES.any { ShellCommandParser.matches(it, segmentTokens) }
}
