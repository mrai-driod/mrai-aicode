package com.aicode.feature.agent.data.local.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aicode.feature.agent.data.local.entity.LlmCallRecordEntity
import kotlinx.coroutines.flow.Flow

/** Per-day call statistics aggregated by day (local time zone), for the trend chart. day is the epoch day number in the local time zone. */
data class DayCallStats(
    val day: Long,
    val inputTokens: Long,
    val outputTokens: Long,
    val cachedInputTokens: Long,
    val calls: Int,
    val errors: Int,
    val avgTtfbMillis: Double?,
    val avgDurationMillis: Double?,
    val retryCount: Long = 0
)

/** Call statistics aggregated by channel (provider), for the channel ranking. */
data class ProviderCallStats(
    val providerId: String?,
    val providerName: String?,
    val calls: Int,
    val inputTokens: Long,
    val outputTokens: Long,
    val cachedInputTokens: Long,
    val errors: Int,
    val retryCount: Long = 0
)

/** Call statistics aggregated by model, for the model ranking. */
data class ModelCallStats(
    val model: String?,
    val calls: Int,
    val inputTokens: Long,
    val outputTokens: Long,
    val cachedInputTokens: Long,
    /** Sum of the tokens written to the cache (Anthropic cache_creation), counted at a separate unit price in cost estimates. */
    val cacheCreationTokens: Long,
    val avgTtfbMillis: Double?,
    val avgDurationMillis: Double?,
    val errors: Int,
    val retryCount: Long = 0
)

/**
 * Usage aggregated by "channel + model", specifically for estimating the total cost for a period.
 * The unit price is determined jointly by the channel (custom metadata is keyed by "provider ID:model name") and the model;
 * aggregating by model alone would lose the channel dimension and could not account for custom unit prices.
 */
data class ModelProviderCostStats(
    val providerId: String?,
    val model: String?,
    val inputTokens: Long,
    val cachedInputTokens: Long,
    val outputTokens: Long,
    val cacheCreationTokens: Long
)

/** Overall summary for the current period, for the overview card. */
data class CallSummary(
    val calls: Int,
    val inputTokens: Long,
    val outputTokens: Long,
    val cachedInputTokens: Long,
    val cacheCreationTokens: Long,
    val errors: Int,
    val avgTtfbMillis: Double?,
    val avgDurationMillis: Double?,
    val retryCount: Long = 0
)

/** Paged call-detail record: a single call + channel name (taken via LEFT JOIN ai_providers; still shown after the channel is deleted). */
data class RecentCallRecord(
    @Embedded val record: LlmCallRecordEntity,
    val providerName: String?
)

@Dao
interface LlmCallRecordDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: LlmCallRecordEntity)

    @Query("DELETE FROM llm_call_records")
    suspend fun deleteAll()

    /**
     * Aggregates by day (local time zone: shifts createdAt by [tzOffsetMillis], then rounds down to whole days by 86400000).
     * [tzOffsetMillis] is provided by the caller from the current system time zone (TimeZone.getDefault().getOffset(now)).
     */
    @Query(
        """
        SELECT (createdAt + :tzOffsetMillis) / 86400000 AS day,
               SUM(inputTokens) AS inputTokens,
               SUM(outputTokens) AS outputTokens,
               SUM(cachedInputTokens) AS cachedInputTokens,
               COUNT(*) AS calls,
               SUM(CASE WHEN status = 'error' THEN 1 ELSE 0 END) AS errors,
               AVG(ttfbMillis) AS avgTtfbMillis,
               AVG(durationMillis) AS avgDurationMillis,
               SUM(retryCount) AS retryCount
        FROM llm_call_records
        WHERE createdAt >= :start
          AND (:providerId IS NULL OR providerId = :providerId)
          AND (:model IS NULL OR model = :model)
        GROUP BY day
        ORDER BY day ASC
        """
    )
    fun getDayStats(
        start: Long,
        tzOffsetMillis: Long,
        providerId: String? = null,
        model: String? = null
    ): Flow<List<DayCallStats>>

    /** Aggregates by hour (local time zone); the "today" period shows the trend at hourly granularity. */
    @Query(
        """
        SELECT (createdAt + :tzOffsetMillis) / 3600000 AS day,
               SUM(inputTokens) AS inputTokens,
               SUM(outputTokens) AS outputTokens,
               SUM(cachedInputTokens) AS cachedInputTokens,
               COUNT(*) AS calls,
               SUM(CASE WHEN status = 'error' THEN 1 ELSE 0 END) AS errors,
               AVG(ttfbMillis) AS avgTtfbMillis,
               AVG(durationMillis) AS avgDurationMillis,
               SUM(retryCount) AS retryCount
        FROM llm_call_records
        WHERE createdAt >= :start
          AND (:providerId IS NULL OR providerId = :providerId)
          AND (:model IS NULL OR model = :model)
        GROUP BY day
        ORDER BY day ASC
        """
    )
    fun getHourStats(
        start: Long,
        tzOffsetMillis: Long,
        providerId: String? = null,
        model: String? = null
    ): Flow<List<DayCallStats>>

    /** Aggregates by channel, taking the channel name via LEFT JOIN ai_providers (records still show after the channel is deleted). */
    @Query(
        """
        SELECT r.providerId AS providerId,
               p.name AS providerName,
               COUNT(*) AS calls,
               SUM(r.inputTokens) AS inputTokens,
               SUM(r.outputTokens) AS outputTokens,
               SUM(r.cachedInputTokens) AS cachedInputTokens,
               SUM(CASE WHEN r.status = 'error' THEN 1 ELSE 0 END) AS errors,
               SUM(r.retryCount) AS retryCount
        FROM llm_call_records r
        LEFT JOIN ai_providers p ON p.id = r.providerId
        WHERE r.createdAt >= :start
          AND (:providerId IS NULL OR r.providerId = :providerId)
          AND (:model IS NULL OR r.model = :model)
        GROUP BY r.providerId
        ORDER BY (SUM(r.inputTokens) + SUM(r.outputTokens)) DESC
        """
    )
    fun getProviderStats(
        start: Long,
        providerId: String? = null,
        model: String? = null
    ): Flow<List<ProviderCallStats>>

    /** Aggregates by model, in descending order of total consumption. */
    @Query(
        """
        SELECT model AS model,
               COUNT(*) AS calls,
               SUM(inputTokens) AS inputTokens,
               SUM(outputTokens) AS outputTokens,
               SUM(cachedInputTokens) AS cachedInputTokens,
               SUM(cacheCreationTokens) AS cacheCreationTokens,
               AVG(ttfbMillis) AS avgTtfbMillis,
               AVG(durationMillis) AS avgDurationMillis,
               SUM(CASE WHEN status = 'error' THEN 1 ELSE 0 END) AS errors,
               SUM(retryCount) AS retryCount
        FROM llm_call_records
        WHERE createdAt >= :start AND model IS NOT NULL AND model != ''
          AND (:providerId IS NULL OR providerId = :providerId)
          AND (:model IS NULL OR model = :model)
        GROUP BY model
        ORDER BY (SUM(inputTokens) + SUM(outputTokens)) DESC
        """
    )
    fun getModelStats(
        start: Long,
        providerId: String? = null,
        model: String? = null
    ): Flow<List<ModelCallStats>>

    /** Aggregates token usage by "channel + model", for estimating the total cost for a period (cost needs the channel to account for custom unit prices). */
    @Query(
        """
        SELECT providerId AS providerId,
               model AS model,
               SUM(inputTokens) AS inputTokens,
               SUM(cachedInputTokens) AS cachedInputTokens,
               SUM(outputTokens) AS outputTokens,
               SUM(cacheCreationTokens) AS cacheCreationTokens
        FROM llm_call_records
        WHERE createdAt >= :start AND model IS NOT NULL AND model != ''
          AND (:providerId IS NULL OR providerId = :providerId)
          AND (:model IS NULL OR model = :model)
        GROUP BY providerId, model
        """
    )
    fun getModelProviderCostStats(
        start: Long,
        providerId: String? = null,
        model: String? = null
    ): Flow<List<ModelProviderCostStats>>

    /** Overall summary for the period (no GROUP BY, always returns one row). */
    @Query(
        """
        SELECT COUNT(*) AS calls,
               SUM(inputTokens) AS inputTokens,
               SUM(outputTokens) AS outputTokens,
               SUM(cachedInputTokens) AS cachedInputTokens,
               SUM(cacheCreationTokens) AS cacheCreationTokens,
               SUM(CASE WHEN status = 'error' THEN 1 ELSE 0 END) AS errors,
               AVG(ttfbMillis) AS avgTtfbMillis,
               AVG(durationMillis) AS avgDurationMillis,
               SUM(retryCount) AS retryCount
        FROM llm_call_records
        WHERE createdAt >= :start
          AND (:providerId IS NULL OR providerId = :providerId)
          AND (:model IS NULL OR model = :model)
        """
    )
    fun getSummary(
        start: Long,
        providerId: String? = null,
        model: String? = null
    ): Flow<CallSummary>

    /** Total number of calls in the current period, for showing the total page count in pagination. */
    @Query(
        """
        SELECT COUNT(*) FROM llm_call_records
        WHERE createdAt >= :start
          AND (:providerId IS NULL OR providerId = :providerId)
          AND (:model IS NULL OR model = :model)
        """
    )
    fun getCallsCount(
        start: Long,
        providerId: String? = null,
        model: String? = null
    ): Flow<Int>

    /** Paged call details (descending, taking [limit] rows starting at [offset]), with the channel name via LEFT JOIN. */
    @Query(
        """
        SELECT r.*, p.name AS providerName
        FROM llm_call_records r
        LEFT JOIN ai_providers p ON p.id = r.providerId
        WHERE r.createdAt >= :start
          AND (:providerId IS NULL OR r.providerId = :providerId)
          AND (:model IS NULL OR r.model = :model)
        ORDER BY r.createdAt DESC
        LIMIT :limit OFFSET :offset
        """
    )
    fun getRecentCalls(
        start: Long,
        limit: Int,
        offset: Int,
        providerId: String? = null,
        model: String? = null
    ): Flow<List<RecentCallRecord>>
}
