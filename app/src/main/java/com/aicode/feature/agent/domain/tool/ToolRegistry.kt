package com.aicode.feature.agent.domain.tool

import java.util.Collections
import javax.inject.Singleton

@Singleton
class ToolRegistry {
    // LinkedHashMap preserves registration order (built-in tools in a fixed order + MCP appended dynamically), avoiding tool additions/removals
    // drifting the prefix order and breaking the implicit prefix cache; synchronizedMap keeps concurrent registration/reads safe.
    private val tools = Collections.synchronizedMap(LinkedHashMap<String, AgentTool>())

    fun register(name: String, tool: AgentTool) {
        tools[name] = tool
    }

    fun getTool(name: String): AgentTool? {
        return tools[name]
    }

    fun unregister(name: String) {
        tools.remove(name)
    }

    fun getToolNames(): Set<String> {
        return tools.keys.toSet()
    }

    /**
     * Returns the list of available tools. In PLAN mode all tool definitions are still returned (so the AI knows these tools exist and can reference them in a plan);
     * write-operation tools are intercepted at runtime by [com.aicode.feature.agent.domain.permission.ToolPermissionPolicyEngine], which returns PLAN_MODE_REJECTED.
     */
    fun getAvailableTools(): List<AgentTool> {
        synchronized(tools) {
            return tools.values.toList()
        }
    }

    fun hasTool(name: String): Boolean {
        return tools.containsKey(name)
    }
}
