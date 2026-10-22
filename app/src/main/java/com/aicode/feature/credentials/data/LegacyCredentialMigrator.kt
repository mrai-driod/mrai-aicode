package com.aicode.feature.credentials.data

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.data.local.database.AgentDatabase
import com.aicode.feature.credentials.data.repository.FileCredentialRepository
import com.aicode.feature.credentials.domain.model.GitCredential
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One-time migration of the old Room `git_credentials` table to the credential file, then drops the table.
 *
 * Background: the credential source of truth moved from Room to the `git-credentials` file (one per host). After the Room entity was removed from [AgentDatabase]
 * the old table still remains in the database file (Room does not automatically drop unmanaged tables), so this class reads it at app startup → writes the file → DROPs it.
 * If the table does not exist or was already migrated, it skips directly (idempotent).
 */
@Singleton
class LegacyCredentialMigrator @Inject constructor(
    private val database: AgentDatabase,
    private val fileCredentialRepository: FileCredentialRepository
) {
    private companion object {
        const val TAG = "LegacyCredentialMigrator"
        const val TABLE = "git_credentials"
    }

    /** Read the old table (for each host take the default / first entry), write it to the credential file, then drop the table. Safe to call repeatedly; idempotently skips when the table does not exist / was already dropped. */
    suspend fun migrateIfNeeded() = withContext(Dispatchers.IO) {
        try {
            val db = database.openHelper.writableDatabase
            // SupportSQLiteDatabase has no rawQuery, so use query to run raw SQL; the returned Cursor is not AutoCloseable, so close it manually.
            var hasTable = false
            val tableCursor = db.query(
                "SELECT name FROM sqlite_master WHERE type='table' AND name='$TABLE'", emptyArray()
            )
            try {
                hasTable = tableCursor.moveToFirst()
            } finally {
                tableCursor.close()
            }
            if (!hasTable) return@withContext
            val rowsCursor = db.query(
                "SELECT host, username, token, isDefault FROM $TABLE ORDER BY isDefault DESC", emptyArray()
            )
            val rows = mutableListOf<GitCredential>()
            try {
                while (rowsCursor.moveToNext()) {
                    val host = rowsCursor.getString(0)?.lowercase() ?: continue
                    val username = rowsCursor.getString(1) ?: continue
                    val token = rowsCursor.getString(2) ?: continue
                    if (host.isBlank() || username.isBlank()) continue
                    // Take only the first entry per host (default first, because of ORDER BY isDefault DESC)
                    if (rows.any { it.host == host }) continue
                    rows.add(GitCredential(id = host, host = host, username = username, token = token))
                }
            } finally {
                rowsCursor.close()
            }
            if (rows.isNotEmpty()) {
                rows.forEach { fileCredentialRepository.save(it) }
                FileLogger.i(TAG, "Migrated ${rows.size} git credentials from the old table to file")
            }
            db.execSQL("DROP TABLE IF EXISTS $TABLE")
            FileLogger.i(TAG, "Deleted old table $TABLE")
        } catch (e: Exception) {
            FileLogger.w(TAG, "Failed to migrate old git credentials (can be ignored, the user re-enters them when the file is empty): ${e.message}")
        }
    }
}
