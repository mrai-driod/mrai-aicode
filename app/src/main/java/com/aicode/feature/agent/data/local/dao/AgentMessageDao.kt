package com.aicode.feature.agent.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.aicode.feature.agent.data.local.entity.AgentMessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AgentMessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: AgentMessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(messages: List<AgentMessageEntity>)

    @Query("SELECT * FROM agent_messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun getMessagesBySession(sessionId: String): Flow<List<AgentMessageEntity>>

    @Query("SELECT * FROM (SELECT * FROM agent_messages WHERE sessionId = :sessionId ORDER BY timestamp DESC LIMIT :limit) ORDER BY timestamp ASC")
    fun getMessagesBySessionPaged(sessionId: String, limit: Int): Flow<List<AgentMessageEntity>>

    /** One-shot read (not a Flow), used to rebuild context history across requests. */
    @Query("SELECT * FROM agent_messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun getMessagesBySessionOnce(sessionId: String): List<AgentMessageEntity>

    @Query("DELETE FROM agent_messages WHERE sessionId = :sessionId")
    suspend fun deleteBySession(sessionId: String)

    @Query("DELETE FROM agent_messages WHERE sessionId = :sessionId AND timestamp < :cutoffTimestamp")
    suspend fun deleteMessagesBeforeTimestamp(sessionId: String, cutoffTimestamp: Long)

    /** Marks all messages in the given session before the cutoff timestamp as compacted (isCompacted=1), so they no longer take part in context replay or UI display. */
    @Query("UPDATE agent_messages SET isCompacted = 1 WHERE sessionId = :sessionId AND timestamp < :cutoffTimestamp")
    suspend fun markMessagesCompactedBeforeTimestamp(sessionId: String, cutoffTimestamp: Long)

    @Query("UPDATE agent_messages SET isCompacted = 1, compactedBySummaryId = :summaryId WHERE sessionId = :sessionId AND id IN (:headIds) AND isContextExcluded = 0")
    suspend fun markMessagesCompactedByIds(sessionId: String, headIds: List<String>, summaryId: String)

    @Query("UPDATE chat_sessions SET lastInputTokens = 0 WHERE id = :sessionId")
    suspend fun resetLastInputTokens(sessionId: String)

    @Transaction
    suspend fun commitCompaction(
        sessionId: String,
        headIds: List<String>,
        messages: List<AgentMessageEntity>,
        summaryId: String
    ) {
        headIds.chunked(900).forEach { ids ->
            markMessagesCompactedByIds(sessionId, ids, summaryId)
        }
        insertAll(messages)
        resetLastInputTokens(sessionId)
    }

    @Query("DELETE FROM agent_messages WHERE sessionId = :sessionId AND (isCompactionMarker = 1 OR isContextSummary = 1)")
    suspend fun deleteCompactionMessages(sessionId: String)

    @Query("UPDATE agent_messages SET isCompacted = 0, compactedBySummaryId = NULL WHERE sessionId = :sessionId AND timestamp < :cutoff AND isCompacted = 1 AND isContextExcluded = 0 AND isContextSummary = 0 AND isCompactionMarker = 0")
    suspend fun restoreCompactedMessagesBefore(sessionId: String, cutoff: Long)

    @Transaction
    suspend fun rewindConversation(sessionId: String, cutoff: Long) {
        deleteCompactionMessages(sessionId)
        restoreCompactedMessagesBefore(sessionId, cutoff)
        deleteMessagesFromTimestamp(sessionId, cutoff)
        resetLastInputTokens(sessionId)
    }

    @Query("DELETE FROM agent_messages")
    suspend fun deleteAllMessages()

    @Query("SELECT * FROM agent_messages WHERE id = :id LIMIT 1")
    suspend fun getMessageById(id: String): AgentMessageEntity?

    /** Whether the session already has any messages (quick LIMIT 1 check, avoiding a full read). */
    @Query("SELECT EXISTS(SELECT 1 FROM agent_messages WHERE sessionId = :sessionId LIMIT 1)")
    suspend fun hasMessages(sessionId: String): Boolean

    @Query("UPDATE agent_messages SET content = :content WHERE id = :id")
    suspend fun updateMessageContent(id: String, content: String)

    @Query("DELETE FROM agent_messages WHERE id = :id")
    suspend fun deleteMessageById(id: String)

    @Query("DELETE FROM agent_messages WHERE sessionId = :sessionId AND timestamp >= :cutoffTimestamp")
    suspend fun deleteMessagesFromTimestamp(sessionId: String, cutoffTimestamp: Long)

    @Query("DELETE FROM agent_messages WHERE sessionId = :sessionId AND timestamp > :cutoffTimestamp")
    suspend fun deleteMessagesAfterTimestamp(sessionId: String, cutoffTimestamp: Long)

    /**
     * Marks any leftover "running" tool rows (whose content starts with the placeholder marker) as "interrupted", in bulk.
     * Used on cold start: a tool that was running when the previous process was killed cannot still be running, otherwise its placeholder row would show a spinner forever.
     * Returns the number of affected rows.
     */
    @Query("UPDATE agent_messages SET content = :interruptedContent, isError = 1 WHERE role = :toolRole AND content LIKE :pendingPrefix")
    suspend fun markPendingToolsInterrupted(
        toolRole: String,
        pendingPrefix: String,
        interruptedContent: String
    ): Int

    @Query("SELECT * FROM agent_messages WHERE content LIKE '%' || :query || '%' ORDER BY timestamp ASC")
    suspend fun searchMessages(query: String): List<AgentMessageEntity>

    /**
     * Searches chat history across sessions under a workspace: matches user / assistant body text, excluding compacted rows and internal summary rows.
     * [escapedQuery] has already had its LIKE wildcards (`!` `%` `_`) escaped by the caller, matching the ESCAPE '!' in the SQL.
     */
    @Query(
        """
        SELECT m.id AS messageId,
               m.sessionId AS sessionId,
               s.title AS sessionTitle,
               m.role AS role,
               m.content AS content,
               m.timestamp AS timestamp
        FROM agent_messages m
        JOIN chat_sessions s ON s.id = m.sessionId
        WHERE s.workspacePath = :workspacePath
          AND m.isCompacted = 0
          AND m.isContextSummary = 0
          AND m.isCompactionMarker = 0
          AND m.role IN ('USER', 'ASSISTANT')
          AND m.content LIKE '%' || :escapedQuery || '%' ESCAPE '!'
        ORDER BY m.timestamp DESC
        LIMIT :limit
        """
    )
    suspend fun searchInWorkspace(workspacePath: String, escapedQuery: String, limit: Int): List<ChatSearchMatch>

    /** The number of messages in the given session whose timestamp is not earlier than [timestamp] (including ties), used when locating a position to determine the required page limit. */
    @Query("SELECT COUNT(*) FROM agent_messages WHERE sessionId = :sessionId AND timestamp >= :timestamp")
    suspend fun countMessagesFromTimestamp(sessionId: String, timestamp: Long): Int

    @Query("SELECT * FROM agent_messages ORDER BY timestamp ASC")
    suspend fun getAllOnce(): List<AgentMessageEntity>

    /** Paged read (keyset: takes [limit] rows in timestamp,id lexicographic order), for streaming backup export. */
    @Query("SELECT * FROM agent_messages WHERE timestamp > :lastTimestamp OR (timestamp = :lastTimestamp AND id > :lastId) ORDER BY timestamp ASC, id ASC LIMIT :limit")
    suspend fun getPageAfter(lastTimestamp: Long, lastId: String, limit: Int): List<AgentMessageEntity>

    /** Paged read by session (keyset), for streaming export of a single-session backup. */
    @Query("SELECT * FROM agent_messages WHERE sessionId = :sessionId AND (timestamp > :lastTimestamp OR (timestamp = :lastTimestamp AND id > :lastId)) ORDER BY timestamp ASC, id ASC LIMIT :limit")
    suspend fun getPageBySessionAfter(sessionId: String, lastTimestamp: Long, lastId: String, limit: Int): List<AgentMessageEntity>

    /**
     * The number of bytes taken up by the message body text of each session (top [limit], descending), so the storage page can break down what makes up "chat history".
     *
     * `LENGTH(CAST(x AS BLOB))` gives the UTF-8 byte count - calling `LENGTH(x)` directly on text returns the character count, which would undercount Chinese text by two thirds.
     * Only a few large fields are counted, so this is an estimate: it excludes indexes, page alignment and WAL overhead, and is necessarily smaller than the database file itself.
     */
    @Query(
        """
        SELECT m.sessionId AS sessionId,
               s.title AS title,
               COUNT(*) AS messageCount,
               SUM(
                   LENGTH(CAST(m.content AS BLOB))
                   + LENGTH(CAST(IFNULL(m.toolCallsJson, '') AS BLOB))
                   + LENGTH(CAST(IFNULL(m.toolArgs, '') AS BLOB))
                   + LENGTH(CAST(IFNULL(m.reasoning, '') AS BLOB))
                   + LENGTH(CAST(IFNULL(m.thinkingBlocksJson, '') AS BLOB))
                   + LENGTH(CAST(IFNULL(m.attachmentsJson, '') AS BLOB))
               ) AS bytes
        FROM agent_messages m
        LEFT JOIN chat_sessions s ON s.id = m.sessionId
        GROUP BY m.sessionId
        ORDER BY bytes DESC
        LIMIT :limit
        """
    )
    suspend fun sessionStorageUsage(limit: Int): List<SessionStorageUsage>
}

/** Projection for a hit from the cross-session chat-history search (projection of [AgentMessageDao.searchInWorkspace]). */
data class ChatSearchMatch(
    val messageId: String,
    val sessionId: String,
    val sessionTitle: String,
    val role: String,
    val content: String,
    val timestamp: Long
)

/** Estimated message usage for a single session (projection of [AgentMessageDao.sessionStorageUsage]). */
data class SessionStorageUsage(
    val sessionId: String,
    val title: String?,
    val messageCount: Int,
    val bytes: Long
)
