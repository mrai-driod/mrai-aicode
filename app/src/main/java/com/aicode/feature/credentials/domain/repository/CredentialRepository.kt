package com.aicode.feature.credentials.domain.repository

import com.aicode.feature.credentials.domain.model.GitCredential
import kotlinx.coroutines.flow.Flow

/**
 * Git credential repository. Credentials are matched for injection by [GitCredential.host]: before push/pull, parse the current remote's host,
 * and call [findForHost] to get the corresponding account's token.
 */
interface CredentialRepository {
    fun getAll(): Flow<List<GitCredential>>

    /** Prefer the default credential, falling back to any entry for that host when there is no default. Called by the injection path. */
    suspend fun findForHost(host: String): GitCredential?

    suspend fun save(credential: GitCredential)

    suspend fun delete(id: String)
}
