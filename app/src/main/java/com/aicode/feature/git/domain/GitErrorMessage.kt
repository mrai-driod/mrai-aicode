package com.aicode.feature.git.domain

/**
 * Matches the raw failure output of a git command to a user-friendly message.
 *
 * Git's stderr is command-line-oriented technical English that is unfriendly to toast directly. This matches common failure cases by pattern:
 * on a match it returns the friendly message; otherwise it falls back to the raw output (preserving troubleshooting info). Matching is based on stable git output prefixes,
 * not full-string equality, so it tolerates dynamic fragments such as paths and branch names.
 */
object GitErrorMessage {

    /**
     * @param raw Raw git stdout+stderr text (i.e. [GitCommandFailureException.output]).
     * @return The friendly message; if nothing matches, returns [raw] unchanged.
     */
    fun friendly(raw: String): String {
        if (raw.isBlank()) return raw
        // Deleting the branch currently checked out
        if (raw.contains("Cannot delete branch") && raw.contains("checked out"))
            return "Cannot delete the current branch. Switch to another branch first"
        // Deleting a branch that is not fully merged
        if (raw.contains("is not fully merged"))
            return "This branch has unmerged commits and cannot be safely deleted"
        // Switching to a branch/ref that does not exist
        if (raw.contains("invalid reference"))
            return "Branch or reference does not exist"
        // Local changes would be overwritten on switch
        if (raw.contains("local changes") && raw.contains("would be overwritten"))
            return "You have uncommitted local changes that would be overwritten. Commit or stash them before switching"
        // Remote repository unavailable / no permission
        if (raw.contains("does not appear to be a git repository") ||
            raw.contains("Could not read from remote repository"))
            return "Remote repository is unavailable or you do not have access"
        // Clone target directory is not empty
        if (raw.contains("already exists and is not an empty directory"))
            return "The current workspace directory is not empty and cannot be cloned into directly"
        // Authentication failed (wrong or unconfigured username/password/token)
        if (raw.contains("Authentication failed") ||
            raw.contains("Invalid username or token") ||
            raw.contains("Permission denied") ||
            raw.contains("Password authentication is not supported"))
            return "Remote authentication failed. Check your credential configuration (username/password/Token)"
        // Repository does not exist or no access permission
        if (raw.contains("Repository not found") || raw.contains("repository") && raw.contains("not found"))
            return "Remote repository does not exist or you do not have access"
        // No upstream tracking info when pulling
        if (raw.contains("no tracking information"))
            return "The current branch is not tracking a remote branch and cannot pull"
        // No configured push destination
        if (raw.contains("No configured push destination"))
            return "No remote repository configured. Cannot push"
        // Push rejected (non-fast-forward / remote has updates)
        if (raw.contains("non-fast-forward") || raw.contains("rejected") && raw.contains("fetch first") ||
            raw.contains("Updates were rejected because the remote contains"))
            return "Push rejected. The remote has newer commits; pull first"
        // Identity not configured (when committing)
        if (raw.contains("Please tell me who you are") || raw.contains("Author identity unknown"))
            return "Commit identity is not configured. Set your username and email in Settings"
        // Merge conflict
        if (raw.contains("CONFLICT") || raw.contains("Automatic merge failed"))
            return "Merge produced conflicts. Go to the Status page to review and resolve them"
        // Uncommitted local changes during merge/pull
        if (raw.contains("Your local changes to the following files would be overwritten by merge"))
            return "You have uncommitted local changes. Commit or stash them before merging"
        // Stash-related
        if (raw.contains("No local changes to save"))
            return "There are no local changes to stash"
        return raw
    }
}
