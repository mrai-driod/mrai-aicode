package com.aicode.feature.agent.domain.permission

/**
 * Safe static analyzer for shell commands — the security core of the authorization system.
 *
 * Responsibility: split a command line into "top-level segments" and tokenize it, while also judging whether
 * the command **can be statically analyzed**. Auto-allow only happens when the command is "analyzable and every
 * segment hits an ALLOW rule"; any construct that cannot be statically analyzed downgrades the command to
 * "must show a dialog, cannot be remembered", preventing authorization bypasses.
 *
 * Safety conventions (must be maintained):
 * - Quote-aware: `&& || | ;` etc. inside single/double quotes are not treated as separators and are not split by mistake.
 * - Triggers for "not statically analyzable" ([Analysis.analyzable] = false):
 *     · Command substitution `$(...)`, backticks `` `...` `` (still effective inside double quotes, not inside single quotes);
 *     · Subshell/grouping parentheses `(` `)`, process substitution `<(` `>(` (all hit by a bare `(`);
 *     · Output redirection to an **absolute path** (`> /...`, `>> /...`, including quoted absolute paths) — can bypass the cwd and write outside the workspace;
 *     · Unclosed quotes.
 *   Relative redirects (landing in cwd = inside the workspace) are treated as safe. Input redirection `<` does not trigger (read risk is lower).
 * - Matching is by the **literal first token**: `/usr/bin/node` is not hit by a `node` rule (rather ask too often than allow by mistake).
 * - Environment assignments at the start of a segment (`FOO=bar cmd`) are skipped when taking the program name / matching, so `FOO=bar git ...` is still handled as `git`.
 */
object ShellCommandParser {

    /**
     * @param segments The token list of each top-level segment (quotes removed, split on whitespace; redirection operators are not included as tokens).
     * @param analyzable Whether the command can be statically analyzed; false means it is never auto-allowed/remembered and can only be allowed for a single use.
     */
    data class Analysis(
        val segments: List<List<String>>,
        val analyzable: Boolean
    )

    private const val NONE = 0
    private const val SINGLE = 1
    private const val DOUBLE = 2

    /** An environment assignment at the start of a segment, of the form `NAME=value`. */
    private val ENV_ASSIGN = Regex("^[A-Za-z_][A-Za-z0-9_]*=.*$")

    fun analyze(command: String): Analysis {
        val segments = mutableListOf<List<String>>()
        var current = mutableListOf<String>()
        val token = StringBuilder()
        var tokenStarted = false
        var analyzable = true
        var quote = NONE
        // True when the previous operator was an output redirect (`>`/`>>`): if the next token is an absolute path, the command is judged not statically analyzable.
        var expectRedirectTarget = false
        var i = 0
        val n = command.length

        fun flushToken() {
            if (tokenStarted) {
                val t = token.toString()
                if (expectRedirectTarget) {
                    if (t.startsWith("/")) analyzable = false
                    expectRedirectTarget = false
                }
                current.add(t)
                token.setLength(0)
                tokenStarted = false
            }
        }
        fun flushSegment() {
            flushToken()
            if (current.isNotEmpty()) {
                segments.add(current)
                current = mutableListOf()
            }
        }

        while (i < n) {
            val c = command[i]
            when (quote) {
                SINGLE -> {
                    if (c == '\'') quote = NONE else { token.append(c); tokenStarted = true }
                    i++
                }
                DOUBLE -> {
                    when {
                        c == '"' -> { quote = NONE; tokenStarted = true; i++ }
                        // Command substitution/backticks inside double quotes still execute -> not statically analyzable.
                        c == '`' -> { analyzable = false; token.append(c); tokenStarted = true; i++ }
                        c == '$' && i + 1 < n && command[i + 1] == '(' -> {
                            analyzable = false; token.append(c); tokenStarted = true; i++
                        }
                        else -> { token.append(c); tokenStarted = true; i++ }
                    }
                }
                else -> { // Outside quotes
                    when {
                        c == '\'' -> { quote = SINGLE; tokenStarted = true; i++ }
                        c == '"' -> { quote = DOUBLE; tokenStarted = true; i++ }
                        c == '\\' -> {
                            // Line continuation `\<newline>` at end of line: skip as a whole; other escapes take the next character literally.
                            if (i + 1 < n) {
                                val nx = command[i + 1]
                                if (nx != '\n') { token.append(nx); tokenStarted = true }
                                i += 2
                            } else i++
                        }
                        // Backticks / grouping parentheses / process substitution `<( >(` (all containing a bare `(`) -> not statically analyzable.
                        c == '`' || c == '(' || c == ')' -> {
                            analyzable = false; token.append(c); tokenStarted = true; i++
                        }
                        // Top-level segment separator.
                        c == '\n' || c == ';' -> { flushSegment(); i++ }
                        c == '&' -> { flushSegment(); i += if (i + 1 < n && command[i + 1] == '&') 2 else 1 }
                        c == '|' -> { flushSegment(); i += if (i + 1 < n && command[i + 1] == '|') 2 else 1 }
                        // Redirection: the operator itself is not a token; output redirects are flagged, pending a check of whether the target token is an absolute path.
                        c == '>' -> { flushToken(); i += if (i + 1 < n && command[i + 1] == '>') 2 else 1; expectRedirectTarget = true }
                        c == '<' -> { flushToken(); i++ }
                        c.isWhitespace() -> { flushToken(); i++ }
                        else -> { token.append(c); tokenStarted = true; i++ }
                    }
                }
            }
        }
        if (quote != NONE) analyzable = false // Unclosed quote: not trustworthy
        flushSegment()
        return Analysis(segments, analyzable)
    }

    /** The effective tokens after skipping leading environment assignments (used to get the program name and for matching). */
    private fun effectiveTokens(tokens: List<String>): List<String> {
        var idx = 0
        while (idx < tokens.size && ENV_ASSIGN.matches(tokens[idx])) idx++
        return if (idx == 0) tokens else tokens.subList(idx, tokens.size)
    }

    /** The command prefix remembered by default for this segment = the program-name token; returns null for an empty segment. */
    fun programPrefix(tokens: List<String>): String? = effectiveTokens(tokens).firstOrNull()

    /**
     * Known "subcommand dispatcher" programs: their first non-flag argument is a subcommand (`git pull`/`npm install`/`cargo build`).
     * For these programs, "Always allow" should remember the subcommand along with the program, to avoid remembering
     * only `git` and thereby also allowing `git push`/`git reset --hard` etc. Non-dispatcher programs are still
     * remembered by program name as a whole (`cat`/`ls` etc.).
     */
    private val SUBCOMMAND_DISPATCHERS = setOf(
        "git", "gh",
        "npm", "npx", "yarn", "pnpm", "pnpx",
        "cargo", "rustup",
        "go",
        "pip", "pip3", "poetry", "uv", "conda",
        "gradle", "mvn", "sbt",
        "docker", "kubectl", "helm",
        "adb", "fastlane",
        "brew", "apt", "apt-get", "gem", "bundle",
        "az", "aws", "gcloud", "terraform", "ansible"
    )

    data class RmInfo(
        val isRm: Boolean,
        val isRecursive: Boolean,
        val isWildcard: Boolean,
        val targetPaths: List<String>
    )

    /** Parses whether this command segment is an rm deletion operation, and its argument info (whether recursive, whether it has wildcards, and the target paths). */
    fun parseRmInfo(tokens: List<String>): RmInfo {
        val eff = effectiveTokens(tokens)
        val program = eff.firstOrNull()
        if (program != "rm" && program?.endsWith("/rm") != true) {
            return RmInfo(isRm = false, isRecursive = false, isWildcard = false, targetPaths = emptyList())
        }
        var isRecursive = false
        var isWildcard = false
        val targetPaths = mutableListOf<String>()
        var hasDoubleDash = false
        for (i in 1 until eff.size) {
            val t = eff[i]
            if (!hasDoubleDash && t == "--") {
                hasDoubleDash = true
                continue
            }
            if (!hasDoubleDash && t.startsWith("-") && t != "-") {
                if (t == "--recursive" || (!t.startsWith("--") && (t.contains('r') || t.contains('R')))) {
                    isRecursive = true
                }
                continue
            }
            if (t.contains('*') || t.contains('?')) {
                isWildcard = true
            }
            targetPaths.add(t)
        }
        return RmInfo(isRm = true, isRecursive = isRecursive, isWildcard = isWildcard, targetPaths = targetPaths)
    }

    /**
     * The command prefix that "Always allow" can remember:
     * - rm commands get fine-grained handling: recursive/wildcard deletions cannot be remembered; an ordinary
     *   single-file deletion is remembered precisely as the full command prefix;
     * - Non-dispatcher programs: only the program name (`cat`/`ls`), consistent with [programPrefix];
     * - Subcommand dispatchers: program name + the first non-flag subcommand immediately following it
     *   (`git pull`/`git clone`/`npm install`); when a flag immediately follows the program name (e.g. `git --version`)
     *   it degrades to just the program name. Returns null for an empty segment.
     *
     * Matching still uses the token-prefix semantics of [matches]: `git pull` matches `git pull origin main`, but
     * not `git clone`, so different subcommands are each remembered independently and do not cross-allow each other.
     */
    fun rememberablePrefix(tokens: List<String>): String? {
        val eff = effectiveTokens(tokens)
        val program = eff.firstOrNull() ?: return null
        val rmInfo = parseRmInfo(tokens)
        if (rmInfo.isRm) {
            if (rmInfo.targetPaths.isEmpty() || rmInfo.isRecursive || rmInfo.isWildcard) {
                return null
            }
            return eff.joinToString(" ")
        }
        if (program !in SUBCOMMAND_DISPATCHERS) return program
        val subcommand = eff.getOrNull(1)?.takeIf { !it.startsWith("-") } ?: return program
        return "$program $subcommand"
    }

    /** Token-prefix matching: a match occurs when the token sequence of the rule [pattern] is a prefix of this segment's effective token sequence. */
    fun matches(pattern: String, segmentTokens: List<String>): Boolean {
        val patternTokens = pattern.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (patternTokens.isEmpty()) return false
        val eff = effectiveTokens(segmentTokens)
        if (eff.size < patternTokens.size) return false
        for (k in patternTokens.indices) if (eff[k] != patternTokens[k]) return false
        return true
    }
}
