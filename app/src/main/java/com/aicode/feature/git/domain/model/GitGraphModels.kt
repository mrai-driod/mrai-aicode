package com.aicode.feature.git.domain.model

/**
 * Domain models for the Git commit topology graph. Parsed by [com.aicode.feature.git.domain.GitRepository.graph]
 * from `git log` output (with `%P` parent hashes) and `git for-each-ref` output, with the lane layout computed in pure Kotlin,
 * for the UI to draw IDE-style coloured branch lines with Canvas. Pure data, no Android dependencies.
 */

/**
 * A single commit record (for the topology graph). Extends [GitCommit] with [parents]: the list of parent commit hashes;
 * one means a regular commit, two means a merge commit, empty means a root commit. Topology lines and lane assignment depend on this field.
 */
data class GraphCommit(
    val hash: String,
    val shortHash: String,
    val author: String,
    val date: String,
    val message: String,
    /** Commit body (the paragraphs after the message; empty string when there is no body). */
    val body: String = "",
    /** Full hashes of the parent commits (in git output order; the first parent is the mainline). */
    val parents: List<String>
) {
    /** Whether this is a merge commit (>= 2 parents). Used to draw merge nodes. */
    val isMerge: Boolean get() = parents.size >= 2
}

/**
 * A reference (branch or tag) pointing at a commit. Used to label branch names next to topology nodes and to colour branches.
 */
data class GitGraphRef(
    val name: String,
    /** true = local/remote branch, false = tag. */
    val isBranch: Boolean,
    /** Whether this is the branch at the current HEAD (only a local branch can be true). */
    val isCurrent: Boolean,
    /** Whether this is a remote branch (under refs/remotes). */
    val isRemote: Boolean
)

/**
 * A single line: connects the lane at [fromLane] to the lane at [toLane], representing the parent-child relation between two adjacent commits.
 * Same column means a vertical line; different columns mean a fork/merge polyline. Colour is mapped from the lane number via a palette, looked up by the UI at render time.
 */
data class GraphEdge(
    val fromLane: Int,
    val toLane: Int,
    /** The lane number this edge belongs to (determines colour): an outgoing (fork) edge takes [fromLane] (the column of the commit that starts the fork),
     *  a merge-in edge takes [toLane] (the parent line keeps its branch's main colour). */
    val lane: Int,
    /** true = merge-in edge (the parent line joins this commit from below; draw a bottom-half Bezier);
     *  false = outgoing edge (this commit forks toward its parent; draw a bottom-half Bezier). */
    val isMergeIn: Boolean
)

/**
 * The topology graph as a whole.
 *
 * @property commits Commits ordered newest to oldest.
 * @property refs Each commit hash -> the list of refs (branches/tags) pointing at it, used for labels next to nodes.
 * @property lanes Each commit hash -> its lane column number (0-based).
 * @property edges All lines between adjacent commits (including cross-lane fork/merge edges), flat and ordered, generated in commit order.
 * @property activeLanes Each commit hash -> the list of active lane column numbers that need a through vertical line on that row (including this commit's own column).
 *   Lanes not owned by this commit but passed through by some branch are also listed here; the UI draws through vertical lines from this to avoid breaks.
 * @property maxLane The maximum lane column number; determines the Canvas width.
 * @property hasMore Whether there are older commits available for paged loading. After the first load and after write-operation refreshes, this is decided by whether the batch returned a full page;
 *   the UI uses it when scrolled to the bottom to decide whether to trigger the next page. False at the end of history.
 */
data class GitGraph(
    val commits: List<GraphCommit>,
    val refs: Map<String, List<GitGraphRef>>,
    val lanes: Map<String, Int>,
    val edges: List<GraphEdge>,
    val activeTopLanes: Map<String, List<Int>>,
    val activeBottomLanes: Map<String, List<Int>>,
    val activeLanes: Map<String, List<Int>>,
    val maxLane: Int,
    val hasMore: Boolean = false
) {
    companion object {
        val EMPTY = GitGraph(emptyList(), emptyMap(), emptyMap(), emptyList(), emptyMap(), emptyMap(), emptyMap(), 0, false)
    }
}
