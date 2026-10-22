package com.aicode.feature.agent.domain.model

import kotlinx.serialization.Serializable
import com.aicode.feature.agent.domain.subagent.AgentDefinition
import com.aicode.feature.agent.domain.tool.ToolCall

@Serializable
sealed class AgentMessage {
    @Serializable
    data class UserMessage(
        val id: String = java.util.UUID.randomUUID().toString(),
        val content: String,
        val images: List<AgentImage> = emptyList()
    ) : AgentMessage()

    @Serializable
    data class AssistantMessage(
        val id: String = java.util.UUID.randomUUID().toString(),
        val content: String,
        val toolCalls: List<ToolCall> = emptyList(),
        /** The reasoning process of the model this turn (corresponding to OpenAI/DeepSeek reasoning_content). It must be sent back verbatim when returning context, otherwise DeepSeek thinking mode reports a 400 error. */
        val reasoning: String = "",
        /** The encrypted signature for Anthropic extended thinking. Sent back verbatim together with [reasoning] (required in the tool loop), otherwise 400. Empty string for other providers. */
        val signature: String = "",
        /** Verbatim snapshot of the Anthropic thinking / redacted_thinking content blocks (JSON array text); when sent back it must be kept verbatim and in the original order. Empty string for other providers. */
        val thinkingBlocksJson: String = "",
        /**
         * Images generated directly by the model this turn (Gemini image models). In the in-memory state base64Data may be empty and path points to a container file;
         * on replay, the base64 is rebuilt from path and fed to the model; when persisted, only the attachment path is stored, not the base64 (see [MessagePersistenceUseCase]).
         */
        val images: List<AgentImage> = emptyList(),
        val inputTokens: Int = 0
    ) : AgentMessage()

    @Serializable
    data class ToolResultMessage(
        val id: String = java.util.UUID.randomUUID().toString(),
        val toolName: String,
        val result: String,
        val images: List<AgentImage> = emptyList(),
        /** The streamlined result text fed only to the model; when null, falls back to [result]. The UI and persistence still use result. */
        val modelResult: String? = null,
        val messageId: String = "tool_$id"
    ) : AgentMessage()
}

@Serializable
data class AgentImage(
    val mimeType: String,
    val base64Data: String,
    val path: String = ""
)

const val CONTEXT_COMPACTION_MARKER = "What did we do so far?"
const val CONTEXT_SUMMARY_LEGACY_PREFIX = "【系统提示：早期的对话已被压缩，以下是之前的核心状态摘要】"

val AgentMessage.id: String
    get() = when (this) {
        is AgentMessage.UserMessage -> id
        is AgentMessage.AssistantMessage -> id
        is AgentMessage.ToolResultMessage -> messageId
    }

data class AgentContext(
    val currentFile: String?,
    val selectedCode: String?,
    val projectRoot: String,
    val language: String?,
    val history: List<AgentMessage> = emptyList(),
    val inputImages: List<AgentImage> = emptyList(),
    /** The current session id: used to write all AI requests/responses of this turn to the log file of that session ([com.aicode.core.util.AILogger]). */
    val sessionId: String? = null,
    val inputMessageId: String = java.util.UUID.randomUUID().toString(),
    val lastInputTokens: Int = 0,
    val mode: AgentMode = AgentMode.BUILD,
    /** The mode before entering PLAN (BUILD/ASK): restored to it when leaving PLAN, null is treated as BUILD. */
    val modeBeforePlan: AgentMode? = null,
    /** Reasoning effort ("low"/"medium"/"high"), passed with each LLM request to providers that support it. */
    val reasoningEffort: String? = null,
    /**
     * The custom sub-agent definition bound to this session; non-null means this is a sub-agent session running according to a definition,
     * and both the system prompt and the tool set are assembled from its configuration (see [com.aicode.feature.agent.domain.prompt.SystemPromptProvider]).
     */
    val agentDefinition: AgentDefinition? = null
)
