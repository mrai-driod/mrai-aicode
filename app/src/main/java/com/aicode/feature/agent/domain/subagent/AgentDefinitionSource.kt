package com.aicode.feature.agent.domain.subagent

import com.aicode.core.util.FileLogger
import com.aicode.feature.workspace.domain.FileAccessProvider

/** Subagent definition source: the `*.md` files under one directory, one agent per file. */
interface AgentDefinitionSource {
    fun listDefinitions(): List<AgentDefinition>
}

/** Directory scan: takes only top-level `*.md` files, so unrelated content such as skill directories is not mistaken for agent definitions. */
internal object AgentDefinitionDirectoryScanner {
    private const val TAG = "AgentDefinitionScanner"

    /**
     * Scans the top-level `*.md` files under [root], one definition per file. Returns an empty list when the directory does not exist;
     * when scanning throws (IO failure, remote workspace not connected, etc.) it degrades to an empty list and logs, without propagating.
     */
    fun scan(provider: FileAccessProvider, root: String): List<AgentDefinition> = runCatching {
        if (!provider.isDirectory(root)) return@runCatching emptyList()
        val base = root.trimEnd('/')
        val paths = provider.listFiles(root)
            .filter { !it.isDirectory && it.name.endsWith(".md", ignoreCase = true) }
            .map { "$base/${it.name}" }
        if (paths.isEmpty()) return@runCatching emptyList()
        // One batch read (one su round trip on the ROOT channel), instead of the N subprocesses of parsing one by one.
        val contents = provider.readFiles(paths)
        paths.mapNotNull { path -> contents[path]?.let { AgentDefinitionParser.parseText(it, path) } }
            .sortedBy { it.name.lowercase() }
    }.getOrElse { e ->
        FileLogger.w(TAG, "Failed to scan subagent definition directory: $root", e)
        emptyList()
    }
}
