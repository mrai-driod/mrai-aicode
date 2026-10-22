package com.aicode.feature.git.domain

/**
 * A git command ended with a non-zero exit code (a real failure), as opposed to a process crash. Carries git's own output text (stderr preferred),
 * so the UI can correct a false "success" report into "failure + reason". Typical cases: committing without a configured identity, unauthorized access, merge conflicts, no remote, etc.
 *
 * @param output Combined git stdout+stderr text, used as [message] for UI display and log troubleshooting.
 */
open class GitCommandFailureException(val output: String) : Exception(output)

/**
 * Git output exceeded the engine limit ([com.aicode.feature.agent.domain.container.MAX_UNBOUNDED_CHARS]) and was truncated.
 * Extends [GitCommandFailureException], so existing failure handling (toast) applies automatically; callers that need the full content
 * (e.g. diff fetching file contents) catch it separately and degrade to a "file too large" hint instead of parsing truncated data.
 */
class GitOutputTooLargeException : GitCommandFailureException("Output too large and was truncated. Narrow the scope and try again")
