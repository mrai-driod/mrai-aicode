package com.aicode.feature.git.presentation

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.R
import com.aicode.core.util.FileLogger
import com.aicode.core.util.LineDiff
import com.aicode.feature.agent.domain.workflow.AgentWorkflow
import com.aicode.feature.git.domain.GitCommandFailureException
import com.aicode.feature.git.domain.GitOutputTooLargeException
import com.aicode.feature.git.domain.GitErrorMessage
import com.aicode.feature.git.domain.GitRepository
import com.aicode.feature.git.domain.model.GitBranch
import com.aicode.feature.git.domain.model.GitCommit
import com.aicode.feature.git.domain.model.GitFileChange
import com.aicode.feature.git.domain.model.GitGraph
import com.aicode.feature.git.domain.model.GitStash
import com.aicode.feature.git.domain.model.GitStatus
import com.aicode.feature.git.domain.model.GitTab
import com.aicode.feature.git.domain.model.GitTag
import com.aicode.feature.git.presentation.component.DiffData
import com.aicode.feature.git.presentation.component.DiffRow
import com.aicode.feature.git.presentation.component.highlightCode
import com.aicode.feature.git.presentation.component.inferSyntaxLanguage
import com.aicode.feature.settings.data.repository.AppThemeMode
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.settings.data.repository.ThemeSettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI state and operations for the Git page.
 *
 * On entering the page, [refresh] concurrently fetches status/graph/remote, then asynchronously fetches all branches/tags; each write operation
 * (stage/commit/pull/push) refreshes automatically when done and reports back via [GitUiState.toast]. [GitUiState.busy]
 * guards against concurrent write operations trampling each other.
 */
private fun GitGraph.toGitCommits() =
    commits.map { GitCommit(it.hash, it.shortHash, it.author, it.date, it.message) }

@HiltViewModel
class GitViewModel @Inject constructor(
    private val repository: GitRepository,
    @param:ApplicationContext private val context: Context,
    private val themeSettings: ThemeSettingsRepository,
    private val agentWorkflow: AgentWorkflow,
    private val executionModeHolder: ExecutionModeHolder
) : ViewModel() {

    private companion object {
        const val TAG = "GitViewModel"
        /** Diff line-count limit: beyond it, LCS is skipped to avoid O(n·m) memory pressure on mobile. Aligned with FileTools. */
        const val MAX_DIFF_LINES = 2000
        /** Single-line length limit: an overlong single line (such as minified JSON) would blow up Compose Constraints when rendered, so it degrades directly to a hint. */
        const val MAX_DIFF_LINE_LENGTH = 2000
        /** Source markers for the two sides of a diff; only stored in [DiffData.oldRef]/[DiffData.newRef] for troubleshooting, never shown to the user. */
        const val REF_WORKTREE = "worktree"
        const val REF_INDEX = "index"
        const val REF_EMPTY = "empty"
    }

    data class GitUiState(
        val loading: Boolean = true,
        val notARepo: Boolean = false,
        val status: GitStatus? = null,
        val branches: List<GitBranch> = emptyList(),
        val commits: List<GitCommit> = emptyList(),
        /** Topology graph view: commits (with parent hashes) + refs + lane layout, for LogTab to draw coloured branch lines. */
        val graph: GitGraph = GitGraph.EMPTY,
        val tags: List<GitTag> = emptyList(),
        val tab: GitTab = GitTab.STATUS,
        val busy: Boolean = false,
        val toast: String? = null,
        /** Whether a remote repository is configured; controls pull/push button availability. */
        val hasRemote: Boolean = false,
        /** Whether a global author identity user.name is configured (git config --global); controls commit button availability; a commit without an identity would be a failed commit. */
        val hasIdentity: Boolean = false,
        /** Hash of the commit currently viewed in the detail sheet; null means not open. */
        val commitDetailHash: String? = null,
        /** Lazily loaded commit file lists, cached by hash. */
        val commitFiles: Map<String, List<GitFileChange>> = emptyMap(),
        /** Hash of the commit whose file list is loading. */
        val loadingCommit: String? = null,
        /** Whether older commits are being paginated in (triggered by scrolling to the bottom). Does not set busy, to avoid blocking write operations. */
        val graphLoadingMore: Boolean = false,
        /** Whether all branches/tags have been loaded (fetched in the background on entering the page, without blocking the first frame; if not finished when switching to the BRANCHES tab, it keeps waiting). */
        val branchesLoaded: Boolean = false,
        /** Whether all branches/tags are loading. */
        val branchesLoading: Boolean = false,
        /** Stash list. */
        val stashes: List<GitStash> = emptyList(),
        /** Whether the stash list is loading. */
        val stashesLoading: Boolean = false,
        /** The branch currently being switched to. */
        val checkoutLoading: String? = null,
        /** Expanded untracked directories → the untracked file lists under them. git status folds a new directory into one line; its files are queried on demand when expanded. */
        val untrackedDirFiles: Map<String, List<String>> = emptyMap(),
        /** Path of the untracked directory currently being expanded. */
        val untrackedDirLoading: String? = null,
        /** Whether a commit message is being generated. */
        val generatingCommitMessage: Boolean = false,
        /** Whether to show the full-screen diff page; set to true on entry, and while diffData is null the page shows a loading state. */
        val diffVisible: Boolean = false,
        /** Path of the file whose diff is being viewed; while loading (diffData is null) the top bar uses it to show the file name. */
        val diffPath: String? = null,
        /** Diff view data; when non-null the diff page renders its content. */
        val diffData: DiffData? = null
    )

    private val _state = MutableStateFlow(GitUiState())
    val state: StateFlow<GitUiState> = _state.asStateFlow()

    /**
     * Whether this is the real-device execution mode (DEVICE_ROOT).
     *
     * The real-device channel sends commands directly to the device's root environment, and most ROMs ship no git binary,
     * so entering this page previously stalled directly on "Failed to initialise GIT". Real-device mode therefore no longer relies on Git for version control,
     * and instead uses "checkpoint snapshot rewind" (com.aicode.feature.agent.domain.checkpoint.CheckpointManager):
     * each conversation turn automatically snapshots the files the AI modified/created, and rewinding restores the code to that turn's original state,
     * the same approach as Cursor / Trae's checkpoint rewind.
     */
    val deviceRootMode: StateFlow<Boolean> = executionModeHolder.mode
        .map { it == ExecutionMode.DEVICE_ROOT }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = executionModeHolder.currentMode() == ExecutionMode.DEVICE_ROOT
        )

    init {
        // Decide whether to run git based on the current execution mode: the first-frame emission replaces the original init { refresh() },
        // and when the mode is toggled back and forth on the Settings page (while this page is still alive) it switches along immediately, leaving no stale data from the previous mode.
        viewModelScope.launch {
            executionModeHolder.mode.collect { mode ->
                if (mode == ExecutionMode.DEVICE_ROOT) {
                    _state.update { GitUiState(loading = false, notARepo = false) }
                } else {
                    refresh()
                }
            }
        }
    }

    /** Repository snapshot: the result of concurrently fetching status/graph/remote (+ optional identity). Does not include all branches/tags —
     * those are fetched asynchronously after [refresh] succeeds ([loadBranches]), to avoid blocking the first frame.
     * The graph's ref labels use local branches only ([repository.localRefsOnly], sub-second), and remote/tag labels are completed by [loadBranches]. */
    private data class RepoSnapshot(
        val status: GitStatus,
        val graph: GitGraph,
        val hasRemote: Boolean,
        val hasIdentity: Boolean = false,
        val untrackedDirFiles: Map<String, List<String>> = emptyMap()
    )

    /** Concurrently fetch the lightweight snapshot: status + graph (local refs) + remote + optional identity. */
    private suspend fun loadSnapshot(includeIdentity: Boolean): RepoSnapshot = coroutineScope {
        val s = async { repository.status() }
        val localRefs = async { repository.localRefsOnly() }
        val r = async { repository.hasRemote() }
        val id = async { if (includeIdentity) repository.getUserName().isNotBlank() else false }
        val g = async { repository.graph(refs = localRefs.await()) }
        val status = s.await()
        RepoSnapshot(status, g.await(), r.await(), id.await(), reloadExpandedUntrackedDirs(status))
    }

    /**
     * After a refresh, re-query expanded untracked directories to keep their expanded state. Only keeps directories still present in [GitStatus.untracked] —
     * once a directory is staged or deleted it should not keep a stale file list.
     */
    private suspend fun reloadExpandedUntrackedDirs(status: GitStatus): Map<String, List<String>> {
        val dirs = _state.value.untrackedDirFiles.keys.filter { it in status.untracked }
        if (dirs.isEmpty()) return emptyMap()
        return coroutineScope {
            dirs.map { dir -> dir to async { repository.untrackedFilesIn(dir) } }
                .associate { (dir, deferred) -> dir to deferred.await() }
        }
    }

    fun setTab(tab: GitTab) {
        _state.update { it.copy(tab = tab) }
        // Fallback: loading already starts asynchronously on entering the page; if it has not started/finished yet (e.g. re-entering after a load failure), trigger it once more when switching tabs.
        if (tab == GitTab.BRANCHES && !_state.value.branchesLoaded && !_state.value.branchesLoading) {
            loadBranches()
        }
    }

    /**
     * Load all branches/tags (triggered as soon as [refresh] succeeds on entering the page, no need to wait for switching to the BRANCHES tab).
     * A single [repository.loadAllRefs] fetch fills branches/tags and updates the graph labels with the full refs.
     * Does not set busy; read-only, does not block write operations. Skips if already loaded; after a write operation, [refreshBranchesIfLoaded] must be called manually to sync.
     */
    fun loadBranches() {
        if (_state.value.branchesLoading || _state.value.branchesLoaded) return
        _state.update { it.copy(branchesLoading = true) }
        viewModelScope.launch {
            try {
                val allRefs = repository.loadAllRefs()
                // Update the graph labels with the full refs (completing remote branches + tags).
                val graph = repository.graphAppend(_state.value.graph.commits, allRefs.refsByCommit)
                val commits = graph.toGitCommits()
                _state.update {
                    it.copy(branches = allRefs.branches, tags = allRefs.tags, graph = graph, commits = commits, branchesLoading = false, branchesLoaded = true)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                FileLogger.e(TAG, "Failed to load branch list", e)
                _state.update { it.copy(branchesLoading = false, toast = context.getString(R.string.git_toast_load_branches_failed, e.message)) }
            }
        }
    }

    /**
     * After a write operation, refresh the loaded branches/tags in sync (if already loaded). If never loaded, do not fetch proactively, preserving the lazy-loading semantics.
     */
    private fun refreshBranchesIfLoaded() {
        if (!_state.value.branchesLoaded) return
        viewModelScope.launch {
            try {
                val allRefs = repository.loadAllRefs()
                _state.update { it.copy(branches = allRefs.branches, tags = allRefs.tags) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                FileLogger.e(TAG, "Failed to refresh branch list", e)
            }
        }
    }

    fun refresh() {
        // Real-device mode: the device environment has no git, and this page now shows snapshot-rewind guidance instead, so no git commands are issued.
        if (executionModeHolder.currentMode() == ExecutionMode.DEVICE_ROOT) {
            _state.update { GitUiState(loading = false, notARepo = false) }
            return
        }
        if (_state.value.busy) return
        // When the container is not ready, do not run git commands; prompt the user to finish initialisation on the Terminal page instead, to avoid misleadingly showing "Not a Git repository".
        repository.notReadyHint()?.let { hint ->
            _state.update { it.copy(loading = false, toast = hint) }
            return
        }
        _state.update { it.copy(loading = true, toast = null) }
        viewModelScope.launch {
            try {
                if (!repository.isRepo()) {
                    _state.update { it.copy(loading = false, notARepo = true) }
                    return@launch
                }
                val snap = loadSnapshot(includeIdentity = true)
                val commits = snap.graph.toGitCommits()
                _state.update {
                    it.copy(loading = false, notARepo = false, status = snap.status, commits = commits, graph = snap.graph, hasRemote = snap.hasRemote, hasIdentity = snap.hasIdentity, untrackedDirFiles = snap.untrackedDirFiles, branchesLoaded = false, branchesLoading = false, branches = emptyList(), tags = emptyList())
                }
                // Page already open: fetch all branches/tags in the background, so the user does not have to wait again when switching to the BRANCHES tab.
                loadBranches()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                FileLogger.e(TAG, "Refresh failed", e)
                _state.update { it.copy(loading = false, toast = context.getString(R.string.git_toast_refresh_failed, e.message)) }
            }
        }
    }

    /** Run a write operation: set busy → run the command → refresh → report back. Write operations are mutually exclusive. */
    private fun runAction(
        @StringRes nameRes: Int,
        action: suspend () -> String,
        onComplete: (suspend () -> Unit)? = null
    ) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, toast = null) }
        viewModelScope.launch {
            val name = context.getString(nameRes)
            val msg = try {
                action()
                context.getString(R.string.git_toast_action_success, name)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                FileLogger.e(TAG, "${name} failed", e)
                val reason = (e as? GitCommandFailureException)?.output ?: e.message
                context.getString(R.string.git_toast_action_failed, name, GitErrorMessage.friendly(reason ?: ""))
            }
            // Refresh to reflect the new state; refresh even on failure, so the UI stays consistent with the repository.
            try {
                if (repository.isRepo()) {
                    val snap = loadSnapshot(includeIdentity = false)
                    val commits = snap.graph.toGitCommits()
                    _state.update { it.copy(busy = false, status = snap.status, commits = commits, graph = snap.graph, hasRemote = snap.hasRemote, untrackedDirFiles = snap.untrackedDirFiles, notARepo = false, toast = msg) }
                    refreshBranchesIfLoaded()
                    onComplete?.invoke()
                } else {
                    _state.update { it.copy(busy = false, notARepo = true, toast = msg) }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.update { it.copy(busy = false, toast = context.getString(R.string.git_toast_action_refresh_failed, msg)) }
            }
        }
    }

    fun stage(path: String) = runAction(R.string.git_stage, { repository.stage(path) })
    fun unstage(path: String) = runAction(R.string.git_unstage, { repository.unstage(path) })
    fun stageAll() = runAction(R.string.git_action_stage_all, { repository.stageAll() })
    fun unstageAll() = runAction(R.string.git_action_unstage_all, { repository.unstageAll() })

    /**
     * Revert a single file's changes. When [staged] is true, staged content is also restored to the last commit; otherwise only unstaged changes are reverted
     * (a deleted file is thereby recovered). The changes were never committed, so they cannot be recovered after reverting; the UI must therefore confirm first.
     */
    fun revertFile(path: String, staged: Boolean) =
        runAction(R.string.git_action_revert, {
            if (staged) repository.revertToHead(path) else repository.revertWorktree(path)
        })

    /** Revert all unstaged changes; staged content is left untouched. */
    fun revertAllUnstaged() = runAction(R.string.git_action_revert_all, { repository.revertAllWorktree() })

    /** Delete an untracked file or directory. These files were never committed, so git cannot restore them after deletion. */
    fun deleteUntracked(path: String) =
        runAction(R.string.git_action_delete_file, { repository.deleteUntracked(path) })

    /**
     * Expand/collapse a folded untracked directory (`git status` shows a new directory as a single `dir/` line). When expanding, query the untracked files under the directory on demand,
     * so they can be viewed as diffs or deleted individually. Does not set busy — a read-only query should not block write operations.
     */
    fun toggleUntrackedDir(dir: String) {
        if (dir in _state.value.untrackedDirFiles) {
            _state.update { it.copy(untrackedDirFiles = it.untrackedDirFiles - dir) }
            return
        }
        if (_state.value.untrackedDirLoading != null) return
        _state.update { it.copy(untrackedDirLoading = dir) }
        viewModelScope.launch {
            val files = try {
                repository.untrackedFilesIn(dir)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                FileLogger.e(TAG, "Failed to load untracked directory: $dir", e)
                emptyList()
            }
            _state.update {
                it.copy(untrackedDirFiles = it.untrackedDirFiles + (dir to files), untrackedDirLoading = null)
            }
        }
    }
    fun commit(message: String, amend: Boolean = false) =
        runAction(R.string.git_action_commit, { repository.commit(message, amend) })

    /**
     * Call the AI to generate a commit message from the staged diff.
     */
    fun generateCommitMessage(onResult: (String) -> Unit) {
        if (_state.value.generatingCommitMessage) return
        _state.update { it.copy(generatingCommitMessage = true) }
        viewModelScope.launch {
            try {
                val diff = repository.diffForCommitMessage()
                if (diff.isBlank()) {
                    _state.update { it.copy(generatingCommitMessage = false, toast = context.getString(R.string.git_toast_no_diff_for_commit)) }
                    return@launch
                }
                val message = agentWorkflow.generateCommitMessage(diff)
                _state.update { it.copy(generatingCommitMessage = false) }
                if (!message.isNullOrBlank()) {
                    onResult(message)
                } else {
                    _state.update { it.copy(toast = context.getString(R.string.git_toast_generate_commit_failed)) }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                FileLogger.e(TAG, "Exception generating commit message", e)
                _state.update { it.copy(generatingCommitMessage = false, toast = context.getString(R.string.git_toast_generate_commit_failed)) }
            }
        }
    }
    /** Run `git init` in the current workspace to initialise a repository; on success the end of runAction refreshes automatically (notARepo flips to false). */
    fun initRepo() = runAction(R.string.git_action_init, { repository.initRepo() })

    /** Clone a remote repository into the current workspace; on success the end of runAction refreshes automatically into the repository state. */
    fun cloneRepo(url: String) = runAction(R.string.git_action_clone, { repository.cloneRepo(url) })
    fun pull() {
        if (!_state.value.hasRemote) {
            _state.update { it.copy(toast = context.getString(R.string.git_toast_no_remote_pull)) }
            return
        }
        runAction(R.string.git_pull, { repository.pull() })
    }
    fun push(onPromptRenameMaster: () -> Unit = {}) {
        if (!_state.value.hasRemote) {
            _state.update { it.copy(toast = context.getString(R.string.git_toast_no_remote_push)) }
            return
        }
        viewModelScope.launch {
            if (repository.isFirstPushOfMaster()) {
                onPromptRenameMaster()
            } else {
                runAction(R.string.git_push, { repository.push() })
            }
        }
    }

    /** The user chose, on the first push of master, to rename it to main and push. */
    fun pushRenameMasterToMain() =
        runAction(R.string.git_push, { repository.renameMasterToMainAndPush() })

    /** The user chose, on the first push of master, to keep master and push directly. */
    fun pushDirectly() =
        runAction(R.string.git_push, { repository.push() })

    /**
     * Open the detail sheet for a commit. If the file list has not been loaded yet, load it lazily (without setting [GitUiState.busy],
     * because this is a read-only view and should not block write operations on status/branches).
     */
    fun openCommitDetail(hash: String) {
        val current = _state.value
        if (current.commitDetailHash == hash) return
        _state.update { it.copy(commitDetailHash = hash) }
        if (hash in current.commitFiles) return
        _state.update { it.copy(loadingCommit = hash) }
        viewModelScope.launch {
            val files = try {
                repository.commitFiles(hash)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                FileLogger.e(TAG, "Failed to load commit files: $hash", e)
                emptyList()
            }
            _state.update {
                it.copy(commitFiles = it.commitFiles + (hash to files), loadingCommit = null)
            }
        }
    }

    fun closeCommitDetail() = _state.update { it.copy(commitDetailHash = null) }

    fun consumeToast() = _state.update { it.copy(toast = null) }

    /**
     * Paginate in older commits. Called when the UI scrolls to the bottom: takes the loaded commits as an anchor, [repository.graphAppend] uses
     * `--skip` to fetch the next page and recomputes the whole lane layout, returning the complete graph to replace state.graph directly. Does not set busy, to avoid blocking write operations.
     * Guard: returns directly if already loading or there is no more. On failure it toasts a hint and keeps hasMore, allowing a retry.
     */
    fun loadMoreCommits() {
        val current = _state.value
        if (current.graphLoadingMore || !current.graph.hasMore) return
        _state.update { it.copy(graphLoadingMore = true) }
        viewModelScope.launch {
            try {
                val graph = repository.graphAppend(current.graph.commits, current.graph.refs)
                val commits = graph.toGitCommits()
                _state.update { it.copy(graph = graph, commits = commits, graphLoadingMore = false) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                FileLogger.e(TAG, "Failed to load more commits", e)
                _state.update { it.copy(graphLoadingMore = false, toast = context.getString(R.string.git_toast_load_more_failed, e.message)) }
            }
        }
    }

    /**
     * Switch to the given branch or tag. Refreshes the full state on success.
     */
    fun checkoutBranch(ref: String, isRemote: Boolean = false) {
        if (_state.value.busy || _state.value.checkoutLoading != null) return
        _state.update { it.copy(checkoutLoading = ref, toast = null) }
        viewModelScope.launch {
            val msg = try {
                repository.checkout(ref, isRemote)
                context.getString(R.string.git_toast_checkout_success, ref)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                FileLogger.e(TAG, "Failed to switch branch", e)
                val reason = (e as? GitCommandFailureException)?.output ?: e.message
                context.getString(R.string.git_toast_checkout_failed, GitErrorMessage.friendly(reason ?: ""))
            }
            try {
                if (repository.isRepo()) {
                    val snap = loadSnapshot(includeIdentity = false)
                    val commits = snap.graph.toGitCommits()
                    _state.update { it.copy(checkoutLoading = null, status = snap.status, commits = commits, graph = snap.graph, hasRemote = snap.hasRemote, notARepo = false, toast = msg) }
                    // After switching branches the branch list may change; refresh it if it was already loaded.
                    refreshBranchesIfLoaded()
                } else {
                    _state.update { it.copy(checkoutLoading = null, notARepo = true, toast = msg) }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.update { it.copy(checkoutLoading = null, toast = context.getString(R.string.git_toast_action_refresh_failed, msg)) }
            }
        }
    }

    /**
     * Create a new branch. name is the new branch name; startPoint is the base branch (null/empty → from the current HEAD);
     * checkout=true creates and switches to it. Reuses runAction: set busy → run the command → refresh → toast.
     */
    fun createBranch(name: String, startPoint: String?, checkout: Boolean) {
        if (name.isBlank()) return
        runAction(R.string.git_action_create_branch, { repository.createBranch(name, startPoint, checkout) })
    }

    /**
     * Safely delete a local branch (git branch -d). If unmerged or the current branch, git reports an error, surfaced via the runAction toast.
     */
    fun deleteBranch(name: String) {
        if (name.isBlank()) return
        runAction(R.string.git_action_delete_branch, { repository.deleteBranch(name) })
    }

    /**
     * Delete a remote branch (git push --delete). Changes the remote; failures are surfaced via the runAction toast.
     */
    fun deleteRemoteBranch(ref: String) {
        if (ref.isBlank()) return
        runAction(R.string.git_action_delete_remote_branch, { repository.deleteRemoteBranch(ref) })
    }

    /**
     * Rename a local branch (git branch -m). The current branch can also be renamed. Failures are surfaced via the runAction toast.
     */
    fun renameBranch(oldName: String, newName: String) {
        if (oldName.isBlank() || newName.isBlank()) return
        runAction(R.string.git_action_rename_branch, { repository.renameBranch(oldName, newName) })
    }

    /**
     * Create a lightweight tag (git tag <name>), pointing at the current HEAD or the given commit. Failures are surfaced via the runAction toast.
     */
    fun createTag(name: String, commitHash: String? = null) {
        if (name.isBlank()) return
        runAction(R.string.git_action_create_tag, { repository.createTag(name, commitHash) })
    }

    /**
     * Reset the current branch to the given commit.
     */
    fun resetToCommit(commitHash: String, mode: String = "mixed") {
        if (commitHash.isBlank()) return
        runAction(R.string.git_reset_to_commit, { repository.resetToCommit(commitHash, mode) })
    }

    /**
     * Add a rule to .gitignore.
     */
    fun addToGitignore(pattern: String) {
        if (pattern.isBlank()) return
        runAction(R.string.git_add_to_gitignore, { repository.addToGitignore(pattern) })
    }

    /**
     * Delete a local tag (git tag -d). Failures are surfaced via the runAction toast.
     */
    fun deleteTag(name: String) {
        if (name.isBlank()) return
        runAction(R.string.git_action_delete_tag, { repository.deleteTag(name) })
    }

    /**
     * Merge a branch into the current branch.
     */
    fun mergeBranch(branch: String, noFf: Boolean = false) {
        if (branch.isBlank()) return
        runAction(R.string.git_action_merge, { repository.mergeBranch(branch, noFf) })
    }

    /**
     * Abort the merge currently in progress.
     */
    fun abortMerge() {
        runAction(R.string.git_action_abort_merge, { repository.abortMerge() })
    }

    /**
     * Load the stash list.
     */
    fun loadStashes() {
        viewModelScope.launch {
            _state.update { it.copy(stashesLoading = true) }
            val list = runCatching { repository.stashList() }.getOrDefault(emptyList())
            _state.update { it.copy(stashes = list, stashesLoading = false) }
        }
    }

    /**
     * Stash working-tree changes.
     */
    fun stashSave(message: String? = null, includeUntracked: Boolean = false) {
        runAction(R.string.git_action_stash, { repository.stashSave(message, includeUntracked) }) {
            loadStashes()
        }
    }

    /**
     * Pop the given stash (apply and delete).
     */
    fun stashPop(index: String = "stash@{0}") {
        runAction(R.string.git_stash_pop, { repository.stashPop(index) }) {
            loadStashes()
        }
    }

    /**
     * Apply the given stash (keep the entry).
     */
    fun stashApply(index: String = "stash@{0}") {
        runAction(R.string.git_stash_apply, { repository.stashApply(index) }) {
            loadStashes()
        }
    }

    /**
     * Drop the given stash.
     */
    fun stashDrop(index: String) {
        runAction(R.string.git_stash_drop, { repository.stashDrop(index) }) {
            loadStashes()
        }
    }

    /**
     * Clear all stash entries.
     */
    fun stashClear() {
        runAction(R.string.git_stash_clear, { repository.stashClear() }) {
            loadStashes()
        }
    }

    /**
     * Leave the diff view and clear the diff state.
     */
    fun clearDiff() = _state.update { it.copy(diffVisible = false, diffPath = null, diffData = null) }

    /**
     * Load the diff of a file in a commit (before vs after the change).
 * hash is the commit hash, path is the file path. Computes the diff + highlighting on a background thread, then fills diffData on completion.
 */
    fun loadCommitFileDiff(hash: String, path: String) {
        openDiff(path)
        viewModelScope.launch {
            val data = runCatching { computeDiff(path, "${hash}^", hash, repository::showFileContent) }
                .getOrElse { e ->
                    FileLogger.e(TAG, "Failed to load commit file diff: $path", e)
                    null
                }
            finishDiff(data)
        }
    }

    /**
     * Load the unstaged diff of a file in the working tree (HEAD vs the current working-tree content).
 * path is the file path. Uses showFileContent("HEAD", path) to get the repository snapshot, and worktreeFileContent(path) to get the current working-tree content.
 */
    fun loadWorktreeDiff(path: String) {
        loadDiff(path, "HEAD", REF_WORKTREE) { ref, p ->
            if (ref == REF_WORKTREE) repository.worktreeFileContent(p)
            else repository.showFileContent(ref, p)
        }
    }

    /** Load the staged diff of a file (HEAD vs index). */
    fun loadStagedDiff(path: String) {
        loadDiff(path, "HEAD", REF_INDEX) { ref, p ->
            if (ref == REF_INDEX) repository.indexFileContent(p)
            else repository.showFileContent(ref, p)
        }
    }

    /**
     * Load the diff of an untracked file: the repository has no version of it, so the old side is always empty and the whole file is presented as newly added.
     */
    fun loadUntrackedDiff(path: String) {
        loadDiff(path, REF_EMPTY, REF_WORKTREE) { ref, p ->
            if (ref == REF_WORKTREE) repository.worktreeFileContent(p) else ""
        }
    }

    private fun loadDiff(
        path: String,
        oldRef: String,
        newRef: String,
        contentProvider: suspend (String, String) -> String
    ) {
        openDiff(path)
        viewModelScope.launch {
            val data = runCatching {
                computeDiff(path, oldRef, newRef, contentProvider)
            }.getOrElse { e ->
                FileLogger.e(TAG, "Failed to load diff: $path", e)
                null
            }
            finishDiff(data)
        }
    }

    /** Enter the full-screen diff page (loading inside the page); ignored if a diff page is already open. */
    private fun openDiff(path: String) {
        if (_state.value.diffVisible) return
        _state.update { it.copy(diffVisible = true, diffPath = path, diffData = null) }
    }

    /** Diff computation finished: on success fill in the data; on failure close the diff page and toast. */
    private fun finishDiff(data: DiffData?) {
        if (data == null) {
            _state.update { it.copy(diffVisible = false, diffData = null, toast = context.getString(R.string.git_toast_diff_failed)) }
        } else {
            _state.update { it.copy(diffData = data) }
        }
    }

    /**
     * Take the old/new file contents, compute a line-level diff with [LineDiff], apply syntax highlighting to each line, and assemble a [DiffData].
 * Binary files (containing NUL bytes) or oversized files (either side over [MAX_DIFF_LINES]) are degraded.
 * [contentProvider] is responsible for fetching file content by ref, shared by commit diffs and working-tree diffs.
 */
    private suspend fun computeDiff(
        path: String,
        oldRef: String,
        newRef: String,
        contentProvider: suspend (String, String) -> String
    ): DiffData = withContext(Dispatchers.Default) {
        val oldContent: String
        val newContent: String
        try {
            oldContent = contentProvider(oldRef, path)
            newContent = contentProvider(newRef, path)
        } catch (e: GitOutputTooLargeException) {
            FileLogger.i(TAG, "File too large, skipping diff content: $path")
            return@withContext DiffData(path, oldRef, newRef, emptyList(), 0, 0, isLarge = true)
        }

        // Binary detection: git show returns garbled output for binary files, so just check whether it contains NUL.
        if (oldContent.contains('\u0000') || newContent.contains('\u0000')) {
            return@withContext DiffData(path, oldRef, newRef, emptyList(), 0, 0, isBinary = true)
        }

        val oldLines = oldContent.split('\n')
        val newLines = newContent.split('\n')
        if (maxOf(oldLines.size, newLines.size) > MAX_DIFF_LINES ||
            oldLines.any { it.length > MAX_DIFF_LINE_LENGTH } ||
            newLines.any { it.length > MAX_DIFF_LINE_LENGTH }
        ) {
            return@withContext DiffData(path, oldRef, newRef, emptyList(), 0, 0, isLarge = true)
        }

        val diffLines = LineDiff.diff(oldContent, newContent)
        val language = inferSyntaxLanguage(path)

        // Run highlighting once over the whole old/new file, obtaining token ranges for the full text; at render time, slice the corresponding SpanStyle by line offset.
        // The syntax theme follows the current UI theme, to avoid token colours from the light theme (such as = and {}) being hard to read in dark mode.
        // Both sides are computed in parallel (highlighting is the main cost of a diff), and the whole thing already runs on the Default thread pool, never touching the main thread.
        val darkMode = currentDarkMode()
        val (oldHighlighted, newHighlighted) = coroutineScope {
            val old = async { highlightCode(oldContent, language, darkMode) }
            val new = async { highlightCode(newContent, language, darkMode) }
            old.await() to new.await()
        }

        // Build line numbers + highlighting slices. oldLineOffsets/newLineOffsets are the start offset of each line in the full text.
        val oldOffsets = lineOffsets(oldContent)
        val newOffsets = lineOffsets(newContent)

        var oldNum = 0
        var newNum = 0
        val rows = diffLines.map { line ->
            val (oldN, newN) = when (line.type) {
                LineDiff.LineType.CONTEXT -> { oldNum++; newNum++; oldNum to newNum }
                LineDiff.LineType.REMOVE -> { oldNum++; oldNum to null }
                LineDiff.LineType.ADD -> { newNum++; null to newNum }
            }
            // Slice the SpanStyle for this line from the highlighted full text. Line index = line number - 1 (0-based).
            val highlighted = when (line.type) {
                LineDiff.LineType.REMOVE -> sliceLineHighlight(oldHighlighted, oldOffsets, oldN?.let { it - 1 })
                LineDiff.LineType.ADD -> sliceLineHighlight(newHighlighted, newOffsets, newN?.let { it - 1 })
                LineDiff.LineType.CONTEXT -> sliceLineHighlight(oldHighlighted, oldOffsets, oldN?.let { it - 1 })
            }
            DiffRow(line.type, line.text, highlighted, oldN, newN)
        }

        val added = rows.count { it.type == LineDiff.LineType.ADD }
        val removed = rows.count { it.type == LineDiff.LineType.REMOVE }
        return@withContext DiffData(path, oldRef, newRef, rows, added, removed)
    }

    /**
     * The current effective dark mode: AUTO follows the system, kept consistent with the darkTheme decision for AIEditorTheme in MainActivity.
     */
    private suspend fun currentDarkMode(): Boolean {
        val themeMode = themeSettings.themeModeFlow.first()
        val systemDark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        return when (themeMode) {
            AppThemeMode.AUTO -> systemDark
            AppThemeMode.DARK -> true
            AppThemeMode.LIGHT -> false
        }
    }

    /**
     * Slice the SpanStyle of a line from a fully highlighted AnnotatedString, returning a new AnnotatedString containing only that line's text + styles.
 * Returns null when lineIndex is out of range or the highlighting is null (degrades to plain text).
 */
    private fun sliceLineHighlight(
        highlighted: androidx.compose.ui.text.AnnotatedString?,
        offsets: IntArray,
        lineIndex: Int?
    ): androidx.compose.ui.text.AnnotatedString? {
        if (highlighted == null || lineIndex == null || lineIndex < 0 || lineIndex >= offsets.size) return null
        val start = offsets[lineIndex]
        val end = if (lineIndex + 1 < offsets.size) offsets[lineIndex + 1] - 1 else highlighted.length
        if (start >= highlighted.length || end <= start) return null
        return highlighted.subSequence(start, end)
    }

    /** Compute the start offset of each line in the full text (including the virtual offset after the last line = the full text length). */
    private fun lineOffsets(text: String): IntArray {
        val lines = text.split('\n')
        val offsets = IntArray(lines.size + 1)
        var pos = 0
        for (i in lines.indices) {
            offsets[i] = pos
            pos += lines[i].length + 1 // +1 is the newline character
        }
        offsets[lines.size] = pos
        return offsets
    }
}
