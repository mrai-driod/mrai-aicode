package com.aicode.feature.git.domain

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.CommandEngine
import com.aicode.feature.git.domain.model.GitBranch
import com.aicode.feature.git.domain.model.GitCommit
import com.aicode.feature.git.domain.model.GitFileChange
import com.aicode.feature.git.domain.model.GitGraph
import com.aicode.feature.git.domain.model.GitGraphRef
import com.aicode.feature.git.domain.model.GraphCommit
import com.aicode.feature.git.domain.model.GitStash
import com.aicode.feature.git.domain.model.GitStatus
import com.aicode.feature.git.domain.model.GitTag
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "GitRepository"

/**
 * Runs git commands inside the container and parses their output.
 *
 * Commands are dispatched via [GitCommandEngine] -- it ensures git always runs in a complete environment that has a git binary
 * (the local container, or a remote Linux host; the device ROOT channel automatically falls back to the local container, because Android does not ship git).
 * It does not go through the agent toolchain / permission engine -- the Git page is a user-initiated action. All output is parsed into pure domain models.
 *
 * Each command argument is escaped with [shellQuote] and the arguments are joined into a single `git ...` string handed to `/bin/sh -c`, so format strings containing
 * `|`, `%(...)`, and paths with spaces are all passed safely.
 *
 * Every command carries `-c core.quotepath=false`: by default git converts non-ASCII bytes in paths into octal escapes
 * (e.g. a three-byte UTF-8 character becomes `\344\270\255`-style sequences); disabling it makes git emit raw UTF-8, so non-ASCII directory/file names are no longer garbled.
 */
@Singleton
class GitRepository @Inject constructor(
    private val engine: GitCommandEngine,
    private val workspaceRepository: WorkspaceRepository
) {
    private companion object {
        /** Number of commits loaded per page for the topology graph. The first batch and each "load more" fetch this many; any more require scrolling to the bottom to load further. */
        const val GRAPH_PAGE_SIZE = 100

        /** Maximum byte size for one side of a diff: beyond this, contents are not fetched and the result degrades directly to "file too large". */
        const val MAX_DIFF_FILE_BYTES = 2L * 1024 * 1024
    }
    /**
     * Runs a single `git` subcommand and returns the combined stdout+stderr text. For read-only commands only (status/branches/log/remote, etc.):
     * these rely on output parsing and are fault-tolerant -- a non-zero git exit code does not mislead the UI (parsing simply yields empty). Credentials are auto-injected from the credential file via `credential.helper=store`
     * (see [com.aicode.feature.credentials.data.repository.FileCredentialRepository]),
     * so no per-command `http.extraHeader` is added here.
     * Each argument is single-quote escaped with [shellQuote], so values with spaces/special characters (commit messages, paths with spaces, etc.) are passed safely.
     */
    private suspend fun git(
        vararg args: String
    ): String = gitRaw(args)

    /**
     * Runs a single `git` **write** subcommand and judges success by exit code: non-zero (a real failure) throws [GitCommandFailureException]
     * carrying the git output text, so the caller [com.aicode.feature.git.presentation.GitViewModel.runAction] can truthfully show
     * "failure + reason" instead of a false success. Typical cases: committing without a configured identity, unauthorized push, merge conflict. An empty exit code (timeout/exception)
     * is likewise thrown as a failure, avoiding a silent false success.
     */
    private suspend fun gitChecked(
        vararg args: String
    ): String = gitCheckedWithTimeout(CommandEngine.DEFAULT_TIMEOUT_MS, *args)

    private suspend fun gitCheckedWithTimeout(
        timeoutMs: Long,
        vararg args: String
    ): String {
        // Run unbounded: diff contents / file contents can far exceed the AI toolchain's 40k-character cap,
        // and a truncation placeholder mixed into the diff stream would be rendered by the UI as fake diff lines.
        val result = engine.runCommandSyncUnbounded(buildGitCommand(args), workspaceRepository.currentPath(), timeoutMs)
        if (result.outputTruncated) throw GitOutputTooLargeException()
        if (result.exitCode == 0) return result.output
        throw GitCommandFailureException(result.output.ifBlank { "git exit code ${result.exitCode}" })
    }

    /** Builds the command and runs it (without checking the exit code); shared by [git] and [gitChecked]. */
    private suspend fun gitRaw(args: Array<out String>): String {
        val result = engine.runCommandSyncUnbounded(buildGitCommand(args), workspaceRepository.currentPath())
        if (result.outputTruncated) throw GitOutputTooLargeException()
        return result.output
    }

    /** Builds the single command string handed to `/bin/sh -c`, escaping each argument with [shellQuote]. */
    private fun buildGitCommand(args: Array<out String>): String = buildString {
        append("git -c core.quotepath=false")
        args.forEach { append(' '); append(shellQuote(it)) }
    }

    /** Whether the current workspace is inside a git work tree. Returns false rather than throwing on errors such as SSH not connected, to avoid crashing the UI. */
    suspend fun isRepo(): Boolean {
        return runCatching { git("rev-parse", "--is-inside-work-tree").trim() == "true" }
            .getOrElse { false }
    }

    /** Whether the container is ready to run git commands; when not ready, returns guidance text (for the Git page to prompt the user to initialize it on the Terminal page before refreshing). */
    fun notReadyHint(): String? = engine.notReadyHint()

    /**
     * Initializes a git repository in the current workspace.
     *
     * The default branch prefers the modern standard `main` (Git 2.28+ supports `git init -b main`);
     * if the environment's Git is too old to support `-b`, it degrades to `git init` first, then pointing the unborn HEAD at `refs/heads/main`.
     */
    suspend fun initRepo(): String {
        return runCatching { gitChecked("init", "-b", "main") }
            .getOrElse {
                val out = gitChecked("init")
                runCatching { git("symbolic-ref", "HEAD", "refs/heads/main") }
                out
            }
    }

    /** Clones a remote repository into the current workspace root (`git clone <url> .`). Judged by exit code; failure throws [GitCommandFailureException]. */
    suspend fun cloneRepo(url: String): String =
        gitCheckedWithTimeout(600_000L, "clone", url.trim(), ".")

    /** Whether at least one remote is configured (`git remote` output is non-empty). Used to gate pull/push. */
    suspend fun hasRemote(): Boolean = git("remote").trim().isNotEmpty()

    /** Aggregated view of `git status`. */
    suspend fun status(): GitStatus {
        val raw = git("status", "--porcelain=v1", "-b")
        val lines = raw.split('\n').map { it.removeSuffix("\r") }

        var branch = "(unknown)"
        var upstream: String? = null
        var isDetached = false
        var ahead = 0
        var behind = 0
        val staged = mutableListOf<GitFileChange>()
        val unstaged = mutableListOf<GitFileChange>()
        val untracked = mutableListOf<String>()
        val conflicted = mutableListOf<GitFileChange>()

        for (line in lines) {
            if (line.isBlank()) continue
            if (line.startsWith("## ")) {
                val header = line.removePrefix("## ")
                isDetached = header.startsWith("HEAD (no branch)")
                val tracking = header.substringBefore(" [")
                if (isDetached) {
                    branch = "HEAD"
                } else {
                    branch = tracking.substringBefore("...").ifBlank { tracking }
                    upstream = tracking.substringAfter("...", "").ifBlank { null }
                }
                val bracket = header.substringAfter(" [", "")
                if (bracket.isNotBlank()) {
                    bracket.removeSuffix("]").split(",").forEach { tok ->
                        val t = tok.trim()
                        val n = t.filter { it.isDigit() }.toIntOrNull() ?: 0
                        if (t.startsWith("ahead")) ahead = n
                        else if (t.startsWith("behind")) behind = n
                    }
                }
                continue
            }
            if (line.length < 3) continue
            val x = line[0]
            val y = line[1]
            val rawPath = line.substring(3)
            // A rename looks like "old -> new"; show the new path.
            val path = unquoteGitPath(rawPath.substringAfter(" -> ").trim())

            if (x == '?' && y == '?') {
                untracked.add(path)
                continue
            }
            // Unmerged conflicts: UU, AA, DD, AU, UD, UA, DU, etc.
            val isConflict = x == 'U' || y == 'U' || (x == 'A' && y == 'A') || (x == 'D' && y == 'D')
            if (isConflict) {
                conflicted.add(GitFileChange(path, "$x$y", staged = false))
                continue
            }
            if (x != ' ' && x != '?') {
                staged.add(GitFileChange(path, x.toString(), staged = true))
            }
            if (y != ' ' && y != '?') {
                unstaged.add(GitFileChange(path, y.toString(), staged = false))
            }
        }
        val isMerging = hasMergeHead() || conflicted.isNotEmpty()
        return GitStatus(branch, ahead, behind, staged, unstaged, untracked, conflicted, isMerging, upstream, isDetached)
    }

    /** Local + remote branch list, with the current branch highlighted. */
    suspend fun branches(): List<GitBranch> = loadAllRefs().branches

    /**
     * Fetches only the refs-by-commit map for local branches (`for-each-ref refs/heads`, sub-second even for large repos),
     * so the lightweight first-screen snapshot graph can mark the current branch position. Remote branch and tag labels are deferred to the full load on the BRANCHES tab.
     */
    suspend fun localRefsOnly(): Map<String, List<GitGraphRef>> = withContext(Dispatchers.Default) {
        val raw = runCatching {
            git("for-each-ref", "--format=%(refname:short)\u001f%(objectname)\u001f%(HEAD)\u001f%(refname)", "refs/heads")
        }.getOrDefault("")
        if (raw.isBlank() || raw.startsWith("fatal:")) return@withContext emptyMap()
        val result = mutableMapOf<String, MutableList<GitGraphRef>>()
        for (line in raw.split('\n')) {
            val l = line.removeSuffix("\r").trim()
            if (l.isBlank()) continue
            val parts = l.split('\u001f')
            if (parts.size < 4) continue
            val isHead = parts[2].trim() == "*"
            result.getOrPut(parts[1]) { mutableListOf() }
                .add(GitGraphRef(parts[0], isBranch = true, isCurrent = isHead, isRemote = false))
        }
        result
    }

    /** The most recent [limit] commits. */
    suspend fun log(limit: Int = 50): List<GitCommit> {
        val raw = git("log", "--pretty=format:%H%x1f%h%x1f%an%x1f%ar%x1f%s", "-n", limit.toString())
        if (raw.isBlank() || raw.startsWith("fatal:")) return emptyList()
        return raw.split('\n').mapNotNull { line ->
            val parts = line.removeSuffix("\r").split('\u001f')
            if (parts.size < 5) null
            else GitCommit(parts[0], parts[1], parts[2], parts[3], parts[4])
        }
    }

    /**
     * Topology graph view: commits (with parent hashes) + refs (branches/tags) + lane layout.
     *
     * `git log --pretty=format:...%P` appends the parent hash list after the regular fields (space-separated; multiple entries mean a merge commit);
     * `git for-each-ref` fetches all branches/tags, the commit hash each points at, and whether it is HEAD, in one call. After commits and refs are parsed,
     * the pure-Kotlin lane assignment algorithm runs on [Dispatchers.Default] (see [GitGraphBuilder]) to produce a [GitGraph].
     * On failure (not a repo / no commits) returns [GitGraph.EMPTY] without throwing -- the UI shows an empty state for an empty graph.
     *
     * The first batch loads [GRAPH_PAGE_SIZE] entries; [hasMore] is decided by whether this batch returned a full page, and the UI uses it when scrolled to the bottom
     * to trigger [graphAppend] for the next page. When there are fewer commits than a page, hasMore=false and the UI no longer offers load-more.
     *
     * [refs] is the refs-by-commit map passed in from outside (from [loadAllRefs]), avoiding a duplicate full ref fetch inside graph.
     * When empty, the graph labels no refs (lightweight first-screen snapshot case: only local branches are fetched; remote/tag labels are deferred to the BRANCHES tab).
     */
    suspend fun graph(
        limit: Int = GRAPH_PAGE_SIZE,
        refs: Map<String, List<GitGraphRef>> = emptyMap()
    ): GitGraph =
        graphAppend(emptyList(), refs, limit)

    /**
     * Loads the next batch of commits page by page and recomputes the lane layout for the whole graph.
     *
     * Fetches the next batch from the already-loaded count via `git log --skip=<existingCommits.size> -n <limit>`, merges it with [existingCommits]
     * into a complete list, then recomputes the layout with [GitGraphBuilder] as a whole -- lane assignment depends on the global parent-child order, and a parent may point across batches
     * at an already-loaded commit, so computing only the new batch would make column numbers clash with the old layout and break lines; the whole graph must be recomputed. Recomputation cost is O(loaded count),
     * and pure in-memory Kotlin computation on mobile takes milliseconds, which is acceptable.
     *
     * Returns a complete [GitGraph] containing all loaded commits (old + new); the ViewModel replaces state.graph wholesale; the UI's LazyColumn
     * only incrementally recomposes new rows via its `item(key=...)` mechanism, without flicker. [hasMore] = whether the new batch returned a full page (otherwise the end of history).
     */
    suspend fun graphAppend(
        existingCommits: List<GraphCommit>,
        refs: Map<String, List<GitGraphRef>> = emptyMap(),
        limit: Int = GRAPH_PAGE_SIZE
    ): GitGraph = withContext(Dispatchers.Default) {
        val skip = existingCommits.size
        val logRaw = runCatching { git("log", "--pretty=format:%H%x1f%h%x1f%an%x1f%ar%x1f%s%x1f%P%x1f%b", "--skip", skip.toString(), "-n", limit.toString()) }
            .getOrDefault("")
        if (logRaw.isBlank() || logRaw.startsWith("fatal:")) {
            return@withContext if (existingCommits.isEmpty()) GitGraph.EMPTY
            else GitGraphBuilder.buildGraph(existingCommits, refs, hasMore = false)
        }

        val newCommits = GitGraphBuilder.parseGraphCommits(logRaw)
        if (newCommits.isEmpty() && existingCommits.isEmpty()) return@withContext GitGraph.EMPTY

        // Merge with dedup: in theory the new batch cannot overlap the loaded commits (--skip skips the loaded count), but defensively dedup by hash while preserving order.
        val existingHashes = existingCommits.mapTo(HashSet()) { it.hash }
        val merged = existingCommits + newCommits.filter { it.hash !in existingHashes }
        GitGraphBuilder.buildGraph(merged, refs, hasMore = newCommits.size >= limit)
    }

    /**
     * Fetches all branches/tags and the commit hash each points at in one call, parsed into three views: the [AllRefs.branches] list, the [AllRefs.tags] list,
     * and the [AllRefs.refsByCommit] map. `branches()`, `listTags()`, and the graph's ref labels share this single call,
     * eliminating the previous three separate `for-each-ref`/`tag` commands (8-20s each on large repos).
     *
     * `%(refname:short)` is the short name, `%(objectname)` the full hash pointed at, and `%(HEAD)` marks the current branch (outputs `*` or empty).
     * Local branches (refs/heads) have isRemote=false, remote branches (refs/remotes) isRemote=true, and tags (refs/tags) isBranch=false.
     * Tags are reversed by refname (pure string comparison, without reading object contents), avoiding the 8s cost of `--sort=-creatordate` traversing every tag object.
     */
    data class AllRefs(
        val branches: List<GitBranch>,
        val tags: List<GitTag>,
        val refsByCommit: Map<String, List<GitGraphRef>>
    )

    suspend fun loadAllRefs(): AllRefs = withContext(Dispatchers.Default) {
        val raw = runCatching {
            git(
                "for-each-ref",
                "--format=%(refname:short)\u001f%(objectname)\u001f%(HEAD)\u001f%(refname)\u001f%(upstream:short)\u001f%(upstream:track)",
                "refs/heads", "refs/remotes", "refs/tags"
            )
        }.getOrDefault("")
        if (raw.isBlank() || raw.startsWith("fatal:")) return@withContext AllRefs(emptyList(), emptyList(), emptyMap())
        val branches = mutableListOf<GitBranch>()
        val tags = mutableListOf<GitTag>()
        val refsByCommit = mutableMapOf<String, MutableList<GitGraphRef>>()
        for (line in raw.split('\n')) {
            val l = line.removeSuffix("\r").trim()
            if (l.isBlank()) continue
            val parts = l.split('\u001f')
            if (parts.size < 4) continue
            val name = parts[0]
            val hash = parts[1]
            val isHead = parts[2].trim() == "*"
            val refname = parts[3]
            val upstream = parts.getOrNull(4)?.ifBlank { null }
            val track = parts.getOrNull(5).orEmpty()
            val ahead = Regex("ahead (\\d+)").find(track)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val behind = Regex("behind (\\d+)").find(track)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val isRemote = refname.startsWith("refs/remotes")
            val isBranch = refname.startsWith("refs/heads") || isRemote
            refsByCommit.getOrPut(hash) { mutableListOf() }
                .add(GitGraphRef(name, isBranch, isHead && !isRemote, isRemote))
            if (isBranch) {
                branches.add(GitBranch(name, current = isHead && !isRemote, remote = isRemote, upstream = upstream, ahead = ahead, behind = behind))
            } else {
                // Tag: objectname uses the short hash (first 7 chars), consistent with the original listTags behaviour.
                tags.add(GitTag(name, hash.take(7)))
            }
        }
        // for-each-ref sorts by refname by default (local branches, remote branches, and tags are each ordered); tags are then reversed to match the original behaviour.
        tags.reverse()
        AllRefs(branches, tags, refsByCommit)
    }

    /**
     * The list of files changed by a commit. Uses `diff-tree --root` to support root commits with no parent; `--no-renames` degrades a rename
     * to "delete old + add new", so taking the first character of the status code reuses [com.aicode.feature.git.domain.model.GitFileChange].
     * An empty list means the commit changed no files (e.g. an empty commit).
     */
    suspend fun commitFiles(hash: String): List<GitFileChange> {
        val raw = git("diff-tree", "--no-commit-id", "-r", "--root", "--name-status", "--no-renames", hash)
        return withContext(Dispatchers.Default) {
            raw.lineSequence().mapNotNull { line ->
                val l = line.removeSuffix("\r").trim()
                val tab = l.indexOf('\t')
                if (l.isBlank() || tab < 0) null
                else {
                    val status = l.substring(0, tab).trim()
                    val path = unquoteGitPath(l.substring(tab + 1).trim())
                    GitFileChange(path, status, staged = false)
                }
            }.toList()
        }
    }

    suspend fun stage(path: String) = gitChecked("add", "--", path)
    suspend fun unstage(path: String) = gitChecked("reset", "HEAD", "--", path)
    suspend fun stageAll() = gitChecked("add", "-A")
    suspend fun unstageAll() = gitChecked("reset")
    suspend fun commit(message: String, amend: Boolean = false) =
        if (amend) gitChecked("commit", "--amend", "-m", message)
        else gitChecked("commit", "-m", message)

    /**
     * Gets the diff text used by AI to generate a commit message.
     * Prefers the staged diff (`git diff --cached`); if the staging area is empty, falls back to the worktree diff (`git diff`).
     */
    suspend fun diffForCommitMessage(): String {
        val staged = gitRaw(arrayOf("diff", "--cached", "--no-color", "--no-ext-diff"))
        if (staged.isNotBlank() && !staged.startsWith("fatal:")) return staged
        val worktree = gitRaw(arrayOf("diff", "--no-color", "--no-ext-diff"))
        return if (!worktree.startsWith("fatal:")) worktree else ""
    }

    /**
     * Reverts the unstaged changes of a single file: restores the worktree to the index contents (`git checkout -- <path>`),
     * so a deleted file reappears. Uses `checkout` rather than `restore` -- the latter needs git 2.23+,
     * and in remote SSH mode the server's git may be older.
     */
    suspend fun revertWorktree(path: String): String = gitChecked("checkout", "--", path)

    /**
     * Reverts all changes (staged + unstaged) of a single file back to the last commit: first `reset` returns the index to HEAD,
     * then the worktree is restored from the index. When the file does not exist in HEAD (a newly added file), reset has already returned it to untracked,
     * so checkout is skipped -- otherwise git would fail with `pathspec did not match`.
     * When the repo has no commits yet there is no HEAD to compare against, so only `rm --cached` can return it to untracked.
     */
    suspend fun revertToHead(path: String): String {
        if (!hasHead()) return gitChecked("rm", "-q", "--cached", "--", path)
        gitChecked("reset", "-q", "HEAD", "--", path)
        return if (existsInHead(path)) gitChecked("checkout", "--", path) else ""
    }

    /**
     * Reverts all unstaged changes. The pathspec `:/` denotes the repo root, so when the worktree is a subdirectory of the repo it still covers changes across the whole repo
     * (`--  .` only applies to the current directory). Staged contents are unaffected.
     */
    suspend fun revertAllWorktree(): String = gitChecked("checkout", "--", ":/")

    /**
     * Deletes an untracked file or directory (`git clean -f -d`). `-d` covers new directories that status collapses into one line;
     * without `-x`, files ignored by .gitignore are left untouched. These files were never in version control, so git cannot restore them after deletion.
     */
    suspend fun deleteUntracked(path: String): String = gitChecked("clean", "-f", "-d", "--", path)

    /**
     * Lists the untracked files under an untracked directory. `git status` collapses a new directory into a single `dir/` line by default,
     * without listing the files inside individually; the UI calls this on demand when expanding such a directory, avoiding `-uall` listing thousands of entries in a large directory at once.
     */
    suspend fun untrackedFilesIn(dir: String): List<String> {
        val raw = git("ls-files", "--others", "--exclude-standard", "--", dir)
        if (raw.isBlank() || raw.startsWith("fatal:")) return emptyList()
        return raw.split('\n').mapNotNull { it.removeSuffix("\r").trim().ifBlank { null } }
            .map { unquoteGitPath(it) }
    }

    /** Whether the repo already has commits (HEAD resolves). In an empty repo, `reset HEAD` / `ls-tree HEAD` both fail. */
    private suspend fun hasHead(): Boolean =
        runCatching { git("rev-parse", "--verify", "HEAD").trim() }
            .getOrDefault("")
            .let { it.isNotBlank() && !it.startsWith("fatal") }

    /** Whether the given path exists in the HEAD commit. */
    private suspend fun existsInHead(path: String): Boolean =
        runCatching { git("ls-tree", "HEAD", "--", path).trim() }
            .getOrDefault("")
            .let { it.isNotBlank() && !it.startsWith("fatal") }

    /**
     * Pull: runs `git pull` directly; credentials are auto-injected by the container `credential.helper` chain -- a `store` hit passes instantly with existing credentials,
     * and on a miss a custom helper triggers an app dialog via file IPC to fill them in, after which git resumes automatically (see [CredentialRequestBridge]).
     * So host credentials are no longer pre-checked here: all three surfaces (UI/terminal/AI) share the same helper fallback, a single source of logic. A missing remote
     * or a real failure is thrown by [gitChecked] as a [GitCommandFailureException] based on the exit code, for the caller to toast.
     */
    suspend fun pull(): String = gitChecked("pull")

    /**
     * Push: with an upstream, runs `git push`; when the current branch has no upstream, automatically runs `git push --set-upstream <remote> <branch>` for the first push to create the association,
     * mimicking the "first push creates the upstream automatically" experience of Win/Mac git clients, so the user does not hit the raw `fatal: has no upstream branch` error.
     * The remote is the first entry of `git remote` (the first by default with multiple remotes; no remote is already blocked upstream by the hasRemote gate);
     * the branch comes from `git rev-parse --abbrev-ref HEAD`. Credentials are still injected by the container credential.helper chain as a fallback.
     */
    suspend fun push(): String {
        val hasUpstream = runCatching { git("rev-parse", "--abbrev-ref", "@{upstream}").trim() }
            .getOrDefault("")
            .takeIf { it.isNotBlank() && it != "HEAD" && !it.startsWith("fatal") } != null
        if (hasUpstream) return gitChecked("push")
        val remote = git("remote").split('\n').firstOrNull { it.removeSuffix("\r").isNotBlank() }?.removeSuffix("\r")?.trim()
            ?: throw GitCommandFailureException("No remote repository configured")
        val branch = git("rev-parse", "--abbrev-ref", "HEAD").removeSuffix("\r").trim()
            .takeIf { it.isNotBlank() && it != "HEAD" }
            ?: throw GitCommandFailureException("Cannot determine the current branch (detached HEAD)")
        return gitChecked("push", "--set-upstream", remote, branch)
    }

    /**
     * Whether this is the first push of a master branch (i.e. the current branch is master and no upstream tracking relation exists yet).
     * Used by the UI to ask the user in a dialog whether to rename it to the modern, common main branch.
     */
    suspend fun isFirstPushOfMaster(): Boolean {
        val hasUpstream = runCatching { git("rev-parse", "--abbrev-ref", "@{upstream}").trim() }
            .getOrDefault("")
            .takeIf { it.isNotBlank() && it != "HEAD" && !it.startsWith("fatal") } != null
        if (hasUpstream) return false
        val branch = git("rev-parse", "--abbrev-ref", "HEAD").removeSuffix("\r").trim()
        return branch == "master"
    }

    /** Renames the current master branch to main and performs the first push. */
    suspend fun renameMasterToMainAndPush(): String {
        renameBranch("master", "main")
        return push()
    }

    /** Local tag list, ordered by creation time descending (newest first). */
    suspend fun listTags(): List<GitTag> = loadAllRefs().tags

    /**
     * Creates a new branch. name is the new branch name; startPoint is the base branch name (null/empty -> from the current HEAD);
     * checkout=true creates and switches (`git checkout -b`); otherwise it only creates without switching (`git branch`).
     * A missing start point or an invalid branch name is thrown by [gitChecked] as a [GitCommandFailureException] based on the exit code, for the caller to toast.
     */
    suspend fun createBranch(name: String, startPoint: String?, checkout: Boolean): String {
        return if (checkout) {
            if (startPoint.isNullOrBlank()) gitChecked("checkout", "-b", name)
            else gitChecked("checkout", "-b", name, startPoint)
        } else {
            if (startPoint.isNullOrBlank()) gitChecked("branch", name)
            else gitChecked("branch", name, startPoint)
        }
    }

    /**
     * Safely deletes a local branch (`git branch -d`): only deletes branches already merged into their upstream; when unmerged, git errors
     * and [gitChecked] throws a [GitCommandFailureException] based on the exit code, for the caller to toast. The current branch cannot be deleted (git itself blocks it).
     */
    suspend fun deleteBranch(name: String): String = gitChecked("branch", "-d", name)

    /**
     * Renames a local branch (`git branch -m <old> <new>`). The current branch can also be renamed: the single-argument form `git branch -m <new>`
     * renames the current branch; here the two-argument form is used uniformly, with the caller guaranteeing oldName is non-empty. An invalid or already-existing name is thrown by [gitChecked]
     * as a [GitCommandFailureException] based on the exit code, for the caller to toast.
     */
    suspend fun renameBranch(oldName: String, newName: String): String =
        gitChecked("branch", "-m", oldName, newName)

    /**
     * Creates a lightweight tag (`git tag <name>`), pointing at the current HEAD or a specified commit.
     */
    suspend fun createTag(name: String, commitHash: String? = null): String =
        if (commitHash.isNullOrBlank()) gitChecked("tag", name)
        else gitChecked("tag", name, commitHash)

    /**
     * Resets the current branch to the specified commit.
     * [mode] can be "soft" or "mixed".
     */
    suspend fun resetToCommit(commitHash: String, mode: String = "mixed"): String =
        gitChecked("reset", "--$mode", commitHash)

    /**
     * Appends an ignore rule to the workspace's .gitignore file.
     * Creates and appends automatically whether or not .gitignore already exists; runs via the engine to support both local and remote modes.
     */
    suspend fun addToGitignore(pattern: String): String {
        val normalized = pattern.trim()
        if (normalized.isBlank()) return ""
        // First check whether .gitignore already has this rule
        val checkCmd = "grep -F -x -- ${shellQuote(normalized)} .gitignore"
        val checkResult = engine.runCommandSyncUnbounded(checkCmd, workspaceRepository.currentPath())
        if (checkResult.exitCode == 0) return normalized // Already exists; no need to append again

        // Otherwise append safely via printf (creates the file automatically if it does not exist)
        val appendCmd = "printf '%s\\n' ${shellQuote(normalized)} >> .gitignore"
        val appendResult = engine.runCommandSyncUnbounded(appendCmd, workspaceRepository.currentPath())
        if (appendResult.exitCode != 0) {
            throw GitCommandFailureException(appendResult.output.ifBlank { "Failed to write .gitignore" })
        }
        return normalized
    }

    /**
     * Deletes a local tag (`git tag -d <name>`). A missing tag is thrown by [gitChecked] as a [GitCommandFailureException] based on the exit code, for the caller to toast.
     */
    suspend fun deleteTag(name: String): String = gitChecked("tag", "-d", name)

    /**
     * Deletes a remote branch (`git push <remote> --delete <branch>`). ref looks like `origin/feature`; the remote and branch name are split out;
     * with no remote prefix it falls back to `origin`. This mutates the remote; failure is thrown by [gitChecked] as a [GitCommandFailureException].
     */
    suspend fun deleteRemoteBranch(ref: String): String {
        val remote = ref.substringBefore('/', "origin")
        val branch = ref.substringAfter('/', ref)
        return gitChecked("push", remote, "--delete", branch)
    }

    /** Whether an unfinished merge is in progress (MERGE_HEAD exists). */
    private suspend fun hasMergeHead(): Boolean =
        runCatching { git("rev-parse", "-q", "--verify", "MERGE_HEAD").trim() }
            .getOrDefault("")
            .let { it.isNotBlank() && !it.startsWith("fatal") }

    /**
     * Switches to the specified branch or tag. branch can be a local branch name, a remote branch name, or a tag name.
     * A remote branch creates a local tracking branch with `git checkout -b <local> <remote>`, stripping the remote prefix (e.g. origin/).
     */
    suspend fun checkout(branch: String, isRemote: Boolean): String {
        return if (isRemote) {
            val localName = branch.substringAfter('/', branch)
            gitChecked("checkout", "-b", localName, "--track", branch)
        } else {
            gitChecked("checkout", branch)
        }
    }

    /**
     * Merges the specified branch into the current branch.
     * [branch] is the target branch name.
     * [noFf] when true, forces a merge commit even when a fast-forward is possible (`--no-ff`).
     * A conflict or a dirty worktree is thrown by [gitChecked] as a [GitCommandFailureException].
     */
    suspend fun mergeBranch(branch: String, noFf: Boolean = false): String {
        return if (noFf) gitChecked("merge", "--no-ff", branch)
        else gitChecked("merge", branch)
    }

    /**
     * Aborts the merge currently in progress (`git merge --abort`).
     */
    suspend fun abortMerge(): String = gitChecked("merge", "--abort")

    /**
     * Lists all stash entries.
     */
    suspend fun stashList(): List<GitStash> = withContext(Dispatchers.Default) {
        val raw = runCatching { git("stash", "list", "--pretty=format:%gd%x1f%s%x1f%ar") }
            .getOrDefault("")
        if (raw.isBlank() || raw.startsWith("fatal:")) return@withContext emptyList()
        raw.split('\n').mapNotNull { line ->
            val l = line.removeSuffix("\r").trim()
            if (l.isBlank()) return@mapNotNull null
            val parts = l.split('\u001f')
            if (parts.size < 3) null
            else GitStash(index = parts[0], message = parts[1], date = parts[2])
        }
    }

    /**
     * Stashes worktree changes.
     * [message] is an optional note; [includeUntracked] when true also includes untracked files (`-u`).
     */
    suspend fun stashSave(message: String? = null, includeUntracked: Boolean = false): String {
        val args = mutableListOf("stash", "push")
        if (includeUntracked) args.add("-u")
        if (!message.isNullOrBlank()) {
            args.add("-m")
            args.add(message.trim())
        }
        return gitChecked(*args.toTypedArray())
    }

    /**
     * Pops the specified stash (applies and deletes it), defaulting to the most recent one, `stash@{0}`.
     */
    suspend fun stashPop(index: String = "stash@{0}"): String =
        gitChecked("stash", "pop", index)

    /**
     * Applies the specified stash (keeping the entry), defaulting to the most recent one, `stash@{0}`.
     */
    suspend fun stashApply(index: String = "stash@{0}"): String =
        gitChecked("stash", "apply", index)

    /**
     * Drops the specified stash, defaulting to `stash@{0}`.
     */
    suspend fun stashDrop(index: String = "stash@{0}"): String =
        gitChecked("stash", "drop", index)

    /**
     * Clears all stash entries.
     */
    suspend fun stashClear(): String = gitChecked("stash", "clear")

    /**
     * Writes the commit identity, **preferring project scope**: when the current workspace (~/workspace/.git/config) already has a project-level identity it writes local,
     * otherwise it writes global (the container's `GIT_CONFIG_GLOBAL=/root/.aicode/.gitconfig`, a persistent mount that survives rootfs upgrades) as the default.
     * This way the UI and the terminal's `git config user.name` read the same value -- project scope first, global as fallback, matching git's own resolution order.
     * Empty values skip the corresponding item without touching existing config. Called explicitly by the UI when the user saves their identity.
     */
    suspend fun setUserIdentity(name: String, email: String) {
        if (name.isNotBlank()) gitChecked("config", if (hasLocalConfig("user.name")) "--local" else "--global", "user.name", name)
        if (email.isNotBlank()) gitChecked("config", if (hasLocalConfig("user.email")) "--local" else "--global", "user.email", email)
    }

    /** Whether the current workspace has a project-level (`--local`) config value. Returns false when not a git repo or when there is no value. */
    private suspend fun hasLocalConfig(key: String): Boolean =
        runCatching { git("config", "--local", "--get", key).trim() }.getOrDefault("").isNotBlank()

    /** Reads the user.name currently in effect for git (in git resolution order: local->global->system); used for UI display and for the commit button's empty check. Returns an empty string on failure. */
    suspend fun getUserName(): String =
        runCatching { git("config", "--get", "user.name").trim() }.getOrDefault("").removeSuffix("\r")

    /** Reads the user.email currently in effect for git (local->global->system); used for UI display and as the edit field's initial value. Returns an empty string on failure. */
    suspend fun getUserEmail(): String =
        runCatching { git("config", "--get", "user.email").trim() }.getOrDefault("").removeSuffix("\r")

    /**
     * Writes the repository URL remote.origin.url, **local scope only**: remote.origin.url is a single repository's remote address,
     * unlike an "identity default" such as user.name/email -- it must never be written to global. Once in global, every later `git clone`
     * of a new repository would resolve both the global value and the local value written by clone, making fetch use the stale global value
     * and push show multiple entries pointing at different repositories (reproduced in practice), so a cloned repo would fetch from the wrong repository.
     *
     * So this always writes `--local` (the current workspace must be a git repo); when not a repo it throws a [GitCommandFailureException] to inform the user,
     * instead of falling back to global. It also clears any remote.origin.url left in global (written by mistake in an earlier version), eliminating the pollution in one pass.
     */
    suspend fun setRepoUrl(url: String) {
        if (url.isNotBlank()) {
            gitChecked("config", "--local", "remote.origin.url", url)
        } else {
            // An empty value deletes the local remote.origin.url; git config --unset returns non-zero for a missing key but does not affect other config
            runCatching { gitChecked("config", "--local", "--unset", "remote.origin.url") }
        }
        // Clear any remote.origin.url left in global (written by mistake historically), to avoid polluting later commands such as git clone.
        runCatching { git("config", "--global", "--unset", "remote.origin.url") }
    }

    /** Reads the remote.origin.url currently in effect for git (local->global->system); used for UI display and as the edit field's initial value. Returns an empty string on failure. */
    suspend fun getRepoUrl(): String =
        runCatching { git("config", "--get", "remote.origin.url").trim() }.getOrDefault("").removeSuffix("\r")

    /**
     * Reads the full contents of a file at a given ref (commit/branch/tag) (`git show <ref>:<path>`).
     * Used for commit file diffs: compares `<hash>^:<path>` (before the change) against `<hash>:<path>` (after the change).
     * When the file does not exist at that ref (e.g. the first commit that adds the file), git outputs `fatal:`; detecting that here returns an empty string,
     * and the caller treats an empty string as "added/deleted", presenting the whole file as fully added or fully deleted.
     */
    suspend fun showFileContent(ref: String, path: String): String {
        if (refBlobSize("$ref:$path") > MAX_DIFF_FILE_BYTES) throw GitOutputTooLargeException()
        val out = git("show", "$ref:$path")
        // git show outputs fatal to stderr for a missing path, and runCommandSync merges stdout+stderr.
        // A fatal prefix is treated as "this version has no such file", returning an empty string so the diff handles it as fully added/deleted.
        return if (out.startsWith("fatal:") || out.startsWith("error:")) "" else out
    }

    /** The byte size of a file at the given ref (`<ref>:<path>` / `:<path>`); returns 0 when it cannot be obtained (missing / invalid ref). */
    private suspend fun refBlobSize(spec: String): Long =
        runCatching { git("cat-file", "-s", spec).trim().toLong() }.getOrDefault(0L)

    /**
     * Reads the current contents of a worktree file. Used for worktree-change diffs: compared against `HEAD:<path>` to show unstaged changes.
     * Returns an empty string when the file does not exist or reading fails.
     *
     * In local mode the workspace is in the app's private directory, so reading directly with [java.io.File] is fastest; in remote SSH mode
     * [WorkspaceRepository.currentPath] is a remote absolute path that a local File cannot read (it would silently become an empty string,
     * making the diff look like a whole-file deletion), so it falls back to fetching the contents with `cat` on the execution backend.
     */
    suspend fun worktreeFileContent(path: String): String {
        val local = withContext(Dispatchers.IO) {
            runCatching {
                java.io.File(workspaceRepository.currentPath(), path)
                    .takeIf { it.isFile }
                    ?.also { if (it.length() > MAX_DIFF_FILE_BYTES) throw GitOutputTooLargeException() }
                    ?.readText()
            }.getOrElse { e ->
                if (e is GitOutputTooLargeException) throw e
                null
            }
        }
        if (local != null) return local
        val result = engine.runCommandSyncUnbounded(
            "cat -- ${shellQuote(path)}",
            workspaceRepository.currentPath()
        )
        if (result.outputTruncated) throw GitOutputTooLargeException()
        return if (result.exitCode == 0) result.output else ""
    }

    /** Reads the current contents of a file in the staging area (index). Returns an empty string when the file is not staged yet. */
    suspend fun indexFileContent(path: String): String {
        if (refBlobSize(":$path") > MAX_DIFF_FILE_BYTES) throw GitOutputTooLargeException()
        val out = git("show", ":$path")
        return if (out.startsWith("fatal:") || out.startsWith("error:")) "" else out
    }

    /**
     * Single-quote escapes a single shell argument. When it contains characters outside the "safe set" (spaces, `|`, `$`, backticks, `*`, etc.),
     * the whole value is wrapped in single quotes, with an embedded single quote closed-escaped-reopened as `'\''`. Format strings (with `|`, `%(...)`), paths with spaces,
     * and commit messages are all passed safely this way. Note `|` is the shell pipe character and must **not** be in the safe set.
     */
    private fun shellQuote(arg: String): String {
        if (arg.isEmpty()) return "''"
        if (arg.all { it.isLetterOrDigit() || it in "_.@/:=+,%-" }) return arg
        return "'" + arg.replace("'", "'\\''") + "'"
    }
}

/**
 * porcelain v1 / ls-files / diff-tree quote paths containing quotes, backslashes, spaces, or control characters as a whole and apply
 * C-style escaping (e.g. `"a\"b.txt"`, `"a\tb.txt"`, octal `\NNN`); this reverses that parsing to restore the real path.
 * An ordinary unquoted path is returned unchanged.
 *
 * Octal escapes are **per byte**: one three-byte UTF-8 character becomes three `\NNN` segments, and decoding each segment with `toChar()` alone would yield
 * three Latin-1 characters (i.e. mojibake), so consecutive `\NNN` segments are first collected into a byte sequence and then decoded as UTF-8 as a whole.
 */
internal fun unquoteGitPath(raw: String): String {
    if (!raw.startsWith("\"")) return raw
    val inner = raw.removeSurrounding("\"")
    val sb = StringBuilder(inner.length)
    var i = 0
    while (i < inner.length) {
        val c = inner[i]
        if (c != '\\' || i + 1 >= inner.length) {
            sb.append(c)
            i += 1
            continue
        }
        if (inner[i + 1] in '0'..'7') {
            val bytes = mutableListOf<Byte>()
            while (i + 1 < inner.length && inner[i] == '\\' && inner[i + 1] in '0'..'7') {
                val octal = inner.substring(i + 1, minOf(i + 4, inner.length)).takeWhile { it in '0'..'7' }
                if (octal.length != 3) break
                bytes.add(octal.toInt(8).toByte())
                i += 4
            }
            if (bytes.isEmpty()) {
                sb.append(c)
                i += 1
            } else {
                sb.append(String(bytes.toByteArray(), Charsets.UTF_8))
            }
            continue
        }
        val decoded = when (inner[i + 1]) {
            'a' -> '\u0007'
            'b' -> '\b'
            'f' -> '\u000C'
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            'v' -> '\u000B'
            '"' -> '"'
            '\\' -> '\\'
            else -> null
        }
        if (decoded == null) {
            sb.append(c)
            i += 1
        } else {
            sb.append(decoded)
            i += 2
        }
    }
    return sb.toString()
}
