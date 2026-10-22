package com.aicode.feature.git.domain

import com.aicode.feature.git.domain.model.GitGraph
import com.aicode.feature.git.domain.model.GitGraphRef
import com.aicode.feature.git.domain.model.GraphCommit
import com.aicode.feature.git.domain.model.GraphEdge

/**
 * Pure-Kotlin parsing and lane layout for the commit topology graph (IDE style). Stateless, with no Android dependencies:
 * Input is `git log` output (with `%P` parent hashes) plus a refs map; output is a [GitGraph] for the UI to draw with Canvas.
 */
internal object GitGraphBuilder {

    /** Parses `git log --pretty=format:%H%x1f%h%x1f%an%x1f%ar%x1f%s%x1f%P%x1f%b` output into a list of [GraphCommit]. */
    fun parseGraphCommits(raw: String): List<GraphCommit> =
        raw.split('\n').mapNotNull { line ->
            // Fields are separated by 0x1f: commit messages / author names may contain '|' (observed in practice: commit 7aba60f's message contained '|', which once caused
            // split('|') to parse parents as false parents, misclassifying the commit as a merge and producing ghost lanes).
            val parts = line.removeSuffix("\r").split('\u001f')
            if (parts.size < 6) null
            else {
                val parents = parts[5].split(' ').filter { it.isNotBlank() }
                GraphCommit(parts[0], parts[1], parts[2], parts[3], parts[4], parents = parents, body = parts.getOrNull(6) ?: "")
            }
        }

    /**
     * Recomputes the lane layout for the full commit list and assembles a [GitGraph]. [refs] is the full refs-by-commit map passed in from outside,
     * filtered to entries whose hash is in the loaded commits, reducing the data passed to the UI (from 3000+ total to only the few dozen in the current view).
     */
    fun buildGraph(
        commits: List<GraphCommit>,
        refs: Map<String, List<GitGraphRef>>,
        hasMore: Boolean
    ): GitGraph {
        if (commits.isEmpty()) return GitGraph.EMPTY
        val commitHashes = commits.mapTo(HashSet()) { it.hash }
        val filteredRefs = refs.filterKeys { it in commitHashes }
        val layout = computeLanes(commits)
        return GitGraph(
            commits,
            filteredRefs,
            layout.lanes,
            layout.edges,
            layout.activeTopLanes,
            layout.activeBottomLanes,
            layout.activeLanes,
            layout.maxLane,
            hasMore
        )
    }

    /**
     * Pure-Kotlin lane assignment algorithm (IDE-style topology layout).
     *
     * Maintains an "active lanes" array `active: Array<String?>`, where each slot records the hash of the commit currently occupying that column.
     * Commits are processed newest to oldest (matching git log output order):
     * - Find whether the current commit already occupies a lane (its hash is already in active, i.e. reserved as a parent reference by some child commit) -> reuse that lane as the current column.
     * - If it occupies none, take the leftmost free slot as a new lane (this commit is the tip of some branch, not referenced by any child commit).
     * - Record the current commit's lane.
     * - Snapshot the column numbers of all non-null slots in [active] at this moment as this commit's [GitGraph.activeLanes]: these lanes
     *     continue from the previous row and pass through this row, so the UI must draw through vertical lines for them to avoid branch lines breaking on intermediate rows.
     * - Handle parents: if the first parent has no slot, it reuses the current commit's lane (mainline continuation); if it is already in another active lane,
     *     emit a cross-column outgoing edge and release the current lane (mainline column jump); each remaining parent (the second and later parents of a merge) gets its own lane --
     *     reusing an active lane if that parent is already in one, otherwise taking the leftmost free slot.
     * - If the current commit has no parents (root commit), release its lane.
     * - Also emit one [GraphEdge] per parent: when the first parent is in a different column it is an outgoing edge (fromLane=current column, toLane=parent column,
     *     lane=current column); when the first parent is in the same column it is a vertical edge. Remaining parents are merge-in edges (fromLane=current column, toLane=parent column,
     *     lane=parent column); a cross-column edge is a merge polyline.
     *
     * A parent commit may fall outside this limit window (its hash has no commit record in active); the lane reservation is still kept
     * to preserve line continuity until a later commit releases it.
     */
    private fun computeLanes(commits: List<GraphCommit>): GraphLayout {
        val commitMap = commits.associateBy { it.hash }
        val lanes = mutableMapOf<String, Int>()
        val edges = mutableListOf<GraphEdge>()
        val activeTopLanes = mutableMapOf<String, List<Int>>()
        val activeBottomLanes = mutableMapOf<String, List<Int>>()
        val activeLanes = mutableMapOf<String, List<Int>>()
        // Active lanes: the slot index is the column number; the value is the hash of the commit occupying that column (or null = free).
        val active = mutableListOf<String?>()
        var maxLane = 0

        for (commit in commits) {
            // Whether the current commit is already reserved as a parent reference by some child commit.
            var lane = active.indexOf(commit.hash)
            if (lane < 0) {
                // Not reserved: take the leftmost free slot.
                lane = active.indexOf(null)
                if (lane < 0) {
                    lane = active.size
                    active.add(commit.hash)
                } else {
                    active[lane] = commit.hash
                }
            }
            if (lane > maxLane) maxLane = lane
            lanes[commit.hash] = lane

            // Snapshot the active lanes for the top half (0 -> centerY): at this point active reflects the branch channels above, before entering this node.
            val topSnapshot = active.mapIndexedNotNull { idx, h -> if (h != null) idx else null }
            activeTopLanes[commit.hash] = topSnapshot

            if (commit.parents.isEmpty()) {
                // Root commit: release the current lane, then snapshot the bottom half.
                active[lane] = null
                val botSnapshot = active.mapIndexedNotNull { idx, h -> if (h != null) idx else null }
                activeBottomLanes[commit.hash] = botSnapshot
                activeLanes[commit.hash] = botSnapshot
                continue
            }

            // The first parent reuses the current lane (mainline continuation).
            val parents = commit.parents
            val firstParent = parents[0]
            val firstParentLane = active.indexOf(firstParent)
            if (firstParentLane >= 0 && firstParentLane != lane) {
                edges.add(GraphEdge(lane, firstParentLane, lane, isMergeIn = false))
                active[lane] = null
            } else {
                edges.add(GraphEdge(lane, lane, lane, isMergeIn = false))
                active[lane] = firstParent
            }

            // Remaining parents (second and later parents of a merge): prefer reusing a lane in the active list that can later inherit that parent, otherwise assign a new lane.
            for (i in 1 until parents.size) {
                val p = parents[i]
                val existing = active.indexOf(p)
                val pLane = if (existing >= 0) {
                    existing
                } else {
                    // Look for a reserved commit X in active (not in the current column) whose parent references directly include p.
                    // If found, p is X's parent and will naturally inherit that column once X is processed, so the incoming edge points directly at that column without reserving active.
                    val reuse = active.indexOfFirst { aHash ->
                        aHash != null && active.indexOf(aHash) != lane && commitMap[aHash]?.parents?.contains(p) == true
                    }
                    if (reuse >= 0) {
                        reuse
                    } else {
                        val free = active.indexOf(null)
                        if (free < 0) {
                            active.add(p)
                            active.size - 1
                        } else {
                            active[free] = p
                            free
                        }
                    }
                }
                if (pLane > maxLane) maxLane = pLane
                edges.add(GraphEdge(lane, pLane, pLane, isMergeIn = true))
            }

            // Snapshot the active lanes for the bottom half (centerY -> height): after all parents are assigned, this reflects the branch channels leaving this node downward.
            val botSnapshot = active.mapIndexedNotNull { idx, h -> if (h != null) idx else null }
            activeBottomLanes[commit.hash] = botSnapshot
            activeLanes[commit.hash] = botSnapshot
        }
        return GraphLayout(lanes, edges, activeTopLanes, activeBottomLanes, activeLanes, maxLane)
    }

    /** Output of [computeLanes]: lane map + edge list + per-row active-lane snapshots + maximum column number. */
    private class GraphLayout(
        val lanes: Map<String, Int>,
        val edges: List<GraphEdge>,
        val activeTopLanes: Map<String, List<Int>>,
        val activeBottomLanes: Map<String, List<Int>>,
        val activeLanes: Map<String, List<Int>>,
        val maxLane: Int
    )
}
