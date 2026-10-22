package com.aicode.feature.credentials.domain.model

/** One Git remote repository credential (one per host, id is the lowercased host). No Android dependencies. */
data class GitCredential(
    val id: String,
    val host: String,
    val username: String,
    val token: String
)