package com.aicode.feature.agent.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aicode.feature.agent.data.local.entity.CheckpointEntity
import com.aicode.feature.agent.data.local.entity.CheckpointFileSnapshotEntity

@Dao
interface CheckpointDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCheckpoint(checkpoint: CheckpointEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFileSnapshot(snapshot: CheckpointFileSnapshotEntity)

    @Query("SELECT * FROM session_checkpoints WHERE sessionId = :sessionId ORDER BY createdAt ASC")
    suspend fun getCheckpointsForSession(sessionId: String): List<CheckpointEntity>

    // Undo must be scoped to a session: checkpoints from different sessions may hang off history other than that of the same messageId,
    // and a global lookup by messageId would locate a checkpoint from another session and restore the files under it.
    @Query("SELECT * FROM session_checkpoints WHERE sessionId = :sessionId AND userMessageId = :messageId LIMIT 1")
    suspend fun getCheckpointBySessionAndMessage(sessionId: String, messageId: String): CheckpointEntity?

    @Query("SELECT * FROM session_checkpoints WHERE id = :checkpointId LIMIT 1")
    suspend fun getCheckpointById(checkpointId: String): CheckpointEntity?

    @Query("SELECT * FROM checkpoint_file_snapshots WHERE checkpointId = :checkpointId")
    suspend fun getFileSnapshotsForCheckpoint(checkpointId: String): List<CheckpointFileSnapshotEntity>

    @Query("SELECT COUNT(*) FROM checkpoint_file_snapshots WHERE checkpointId = :checkpointId AND filePath = :filePath")
    suspend fun countSnapshot(checkpointId: String, filePath: String): Int

    @Query("DELETE FROM session_checkpoints WHERE sessionId = :sessionId")
    suspend fun deleteCheckpointsForSession(sessionId: String)

    @Query("DELETE FROM checkpoint_file_snapshots WHERE checkpointId IN (SELECT id FROM session_checkpoints WHERE sessionId = :sessionId)")
    suspend fun deleteFileSnapshotsForSession(sessionId: String)

    @Query("DELETE FROM session_checkpoints WHERE createdAt < :cutoffTimestamp")
    suspend fun deleteCheckpointsBefore(cutoffTimestamp: Long)
}
