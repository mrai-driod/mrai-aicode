package com.aicode.feature.agent.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A single LLM call record: the data source for the Token statistics page.
 * Written by instrumentation in the workflow main loop (chat) and the context compaction path (compaction),
 * covering channel, model, input/output/cache tokens, first-token latency, duration and success/failure.
 */
@Entity(
    tableName = "llm_call_records",
    indices = [
        Index(value = ["createdAt"]),
        Index(name = "index_llm_call_records_provider_model", value = ["providerId", "model"])
    ]
)
data class LlmCallRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String? = null,
    /** Channel (ai_providers.id); the channel name is taken via LEFT JOIN when displaying. */
    val providerId: String? = null,
    /** The model actually called (including the compaction fallback model). */
    val model: String? = null,
    /** The reasoning effort for this call (ReasoningEffort.apiValue, e.g. "medium"); null when unset / for models without levels. */
    val reasoningEffort: String? = null,
    /** Call type: chat (normal conversation) / compaction (context compaction). Historical data contains the deprecated vision (image-recognition turn). */
    val kind: String = "chat",
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    /** The part of this turn's input that hit the server-side cache (OpenAI cached_tokens / Anthropic cache_read_input_tokens / Gemini cachedContentTokenCount). */
    val cachedInputTokens: Int = 0,
    /** The number of tokens written to the server-side cache this turn (Anthropic cache_creation_input_tokens), billed at a higher unit price than input. */
    val cacheCreationTokens: Int = 0,
    /** First-token latency (milliseconds): arrival of the first text chunk in a streaming call - request sent; null for non-streaming or failures. */
    val ttfbMillis: Int? = null,
    /** Total duration (milliseconds): finished - request sent. */
    val durationMillis: Int? = null,
    /** Call result: success / error. */
    val status: String = "success",
    val errorMessage: String? = null,
    /** Reason the model finished: end_turn / tool_use / max_tokens / stop / length. */
    val stopReason: String? = null,
    /** The number of network retries at the underlying layer for this interaction (0 means the first attempt succeeded or the failure was not a retryable one). */
    val retryCount: Int = 0,
    /** Time the request was sent (epoch milliseconds). */
    val createdAt: Long
)
