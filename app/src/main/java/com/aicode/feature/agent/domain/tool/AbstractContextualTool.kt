package com.aicode.feature.agent.domain.tool

import kotlinx.serialization.json.JsonElement

/**
 * Base class for tools that can only execute with session context ([com.aicode.feature.agent.domain.model.AgentContext]).
 *
 * The unified workflow `StatefulAgentWorkflow` runs all tools through [executeWithContext] and never calls [execute] directly.
 * Subclasses therefore only need to override [executeWithContext]; this base class implements [execute] to throw `UnsupportedOperationException`,
 * making it explicit at compile time / call time that this tool has no execution path detached from context. Compared with returning a fixed Error,
 * throwing immediately exposes misuse (for example, a future non-context execution branch that forgets to go through context), instead of
 * disguising "missing context" as a seemingly normal tool failure result.
 */
abstract class AbstractContextualTool : AgentTool() {

    /**
     * This tool only supports [executeWithContext]; this path is never provided, and calling it is a programming error.
     * This implementation is kept to satisfy the abstract signature of [AgentTool.execute].
     */
    final override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        throw UnsupportedOperationException(
            "${javaClass.simpleName} can only be executed via executeWithContext and requires AgentContext."
        )
    }
}
