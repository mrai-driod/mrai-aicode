package com.aicode.feature.agent.domain.model

/**
 * Reasoning effort levels. apiValue matches the values in models.dev reasoning_options (of effort type),
 * and is passed straight through to providers that support the parameter (OpenAI reasoning_effort / Gemini thinkingLevel);
 * for Anthropic, the adapter maps it by model generation to adaptive effort or budget_tokens.
 */
enum class ReasoningEffort(val apiValue: String) {
    NONE("none"),
    MINIMAL("minimal"),
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high"),
    XHIGH("xhigh"),
    MAX("max");

    companion object {
        /** Parses models.dev effort values into the list of recognizable levels (preserving the order of values). */
        fun fromValues(values: List<String>): List<ReasoningEffort> =
            values.mapNotNull { v -> entries.firstOrNull { it.apiValue == v } }
    }
}