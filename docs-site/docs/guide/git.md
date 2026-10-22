# Git Version Management

Open the Git page in a project workspace to manage code versions visually. There are three tabs at the bottom: Status, Branches, Commits. If the current directory isn't a Git repository, you can initialize it as an empty repository with one tap (default branch `main`), or enter a remote address to clone an existing repository directly (**from 1.12.0**).

The key icon at the top right enters the "Credentials & Signature" page, containing two tabs, signature and credentials — **you must configure the signature first before first use, otherwise you can't commit**.

## Status

View the working directory state of the current repository.

The top card shows the current branch name, how many commits it's ahead of or behind the remote (up/down arrows), and the counts of staged, modified, and untracked files.

**Commit** is a full-width primary button — it can be tapped only after you've staged changes and configured the signature; after tapping, fill in the commit message.

A row of four secondary action buttons:

- **Stage All**: adds all changes to the staging area.
- **Stash** (**from 1.12.0**): opens the stash box — see the "Stash" section below.
- **Pull / Push**: requires a configured remote repository. When the current branch has no upstream, pushing automatically establishes the association.

On the right of the group titles there are also:

- **Unstage All**: in the staged group; a second confirmation is required before executing. This operation only unstages and doesn't delete file content.
- **Revert All** (**from 1.11.0**): in the modified group, restores all unstaged changes to the content of the last staging or commit. Staged content is unaffected, but discarded changes can't be recovered; a second confirmation is required before executing.

File changes are divided into three groups — "Staged / Modified / Untracked":

- Staged: tap a row to view the diff between the staging area and the last commit; the "−" at the end of the row unstages it.
- Modified: tap a row to view the diff between the workspace and the last commit; the "+" at the end of the row stages that file. Deleted files are also in this group, with a red D status badge.
- Untracked: tap a row to view the content of this new file (shown entirely as additions); the "+" at the end of the row stages it. Git collapses newly created directories into one row (e.g. `feature/`); tap it to expand the files inside, then tap an individual file to view its content.

If the workspace has uncommitted changes, tapping "Pull" first prompts that a conflict may arise; it executes only after confirmation.

### Commit Dialog (enhanced in 1.12.0)

In the dialog that pops up when you tap "Commit Changes":

- **AI generate**: tap the button on the right of the title bar to have the AI read the diff between the staging area and the workspace and automatically generate a commit message conforming to Conventional Commits. While generating, the button shows "Generating…"; if generation fails, it prompts you to check the model or network.
- **Quick types**: a row of chips above the input box (`feat`, `fix`, `refactor`, `chore`, `docs`, `style`, `perf`, `test`); one tap applies the corresponding prefix to the current message.
- **Append to last commit (--amend)**: a switch at the bottom; when on, this commit merges into the last commit instead of creating a new one.

### Merge Conflicts (from 1.12.0)

After merging a branch, if a conflict arises, the top of the status page shows "Merge conflict detected" and lists the conflict files separately (with a purplish-red U status badge), prompting you to resolve the conflict in the editor and stage, or tap "Abort Merge" to abandon this merge and restore the state before merging.

## Stash (from 1.12.0)

Stash is for temporarily putting away current changes and returning the workspace to a clean state, to pick them up again later. Tap the "Stash" button on the status page to open the stash box:

- **Stash changes**: you can fill in an optional description, and check "Include untracked files (-u)" to put away new files together.
- **Pop**: takes out this stash and removes it from the stash list.
- **Apply**: takes out the content but keeps the stash record.
- **Drop**: deletes a single stash; a second confirmation is required before executing.
- **Clear all**: deletes all stashes; a second confirmation is required before executing.

Drop and clear are both irreversible.

## Revert & Delete (from 1.11.0)

::: tip Version note
The status page's long-press action menu (revert changes, restore file, delete untracked items) and "Revert All" are available from 1.11.0; the menu's "Add to .gitignore" is available from 1.12.0.
:::

**Long-pressing any file row** pops up an action menu from the bottom, with the full path shown at the top of the menu. The available actions differ by which group the file is in:

| Group | Menu items |
| --- | --- |
| Conflict files | View diff, stage, copy path |
| Staged | View diff, revert changes (restore to last commit; both staged and unstaged changes are lost), copy path |
| Modified | View diff, revert changes (restore to last staged or committed content), copy path |
| Deleted files among modified | View diff, **restore file** (bring the file back from the last commit), copy path |
| Untracked files | View diff, **add to .gitignore** (ignore this file / ignore files of the same type), delete file, copy path |
| Untracked directories | **Add to .gitignore** (ignore the whole directory), delete directory, copy path |

The consequences of the two kinds of operations are completely different — note the distinction:

- **Revert changes / Restore file**: for files Git has already tracked. The content exists in the repository, so whether the file was deleted by you, by a terminal command, or by the AI, it can be brought back. The cost is that this round's uncommitted changes are lost.
- **Delete file / Delete directory**: only appear on untracked items. These files never entered the repository, so **after deletion Git can't restore them**.

All revert and delete operations pop up a confirmation dialog before executing, explaining what will be lost.

## Branches

Displayed in four groups: HEAD, local branches, remote branches, tags. The current branch card shows the branch name and the counts of the three kinds of references. Local branches are displayed as a path tree, with the current branch highlighted.

**Switch branch**: tap any branch or tag and switch after confirmation. Tapping a remote branch creates a local tracking branch and then switches to it. Switching to a tag enters a detached HEAD state (commits in this state belong to no branch — be careful not to work in this state long-term).

**Tapping a branch row** pops up an action menu:

- **Switch**: switch to that branch.
- **Merge branch** (**from 1.12.0**): merge that branch into the current branch; in the confirmation dialog you can choose "Create a merge commit (--no-ff)". The current branch itself doesn't show this item.
- **Rename**: change the local branch name.
- **Delete**: local branches use safe deletion — only already-merged branches can be deleted; unmerged branches are rejected with a reason given. Deleting a remote branch acts directly on the remote and is **irreversible**. The branch you're currently on can't be deleted.

**New branch**: tap the + on the right of the local branch group title, fill in the branch name, choose the base branch (default current branch); the "Create and switch" switch is on by default.

**Tags**: tap the + on the right of the tags group title to create a lightweight tag from the current HEAD; tap an existing tag → action menu → Delete to delete the local tag.

### Naming Advice for First Pushing a master Branch (from 1.12.0)

If the current local branch is called `master` and has no upstream yet, the first push pops up a dialog suggesting renaming it to `main` (the default main branch of GitHub and GitLab). You can choose "Rename to main and push", or "Keep master and push".

## Commits

Shows the most recent commits, with a colored branch topology graph. Each shows the short hash, author, relative time, commit message, and the marks of its branch or tag.

**Search filter** (**from 1.12.0**): there's a search box above the list; entering a keyword filters in real time by commit message, author, or short hash.

Tapping a commit pops up a bottom details panel: the full commit message, author and time, the full hash (both the short and full hashes can be copied), whether it's a merge commit, and the list of files changed this time. Tap a file to view that file's diff.

Above the details panel there's also a row of deep operations (**from 1.12.0**):

- **Branch**: create a new branch based on this commit and switch to it.
- **Tag**: tag this commit.
- **Reset**: reset the current branch to this commit, choosing `Soft` (keep workspace and staging area) or `Mixed` (keep workspace, clear staging area).

Scrolling down near the bottom automatically loads earlier commits; when all are loaded it shows "No more".

## Diff View

Tapping a file or commit to view the diff enters a separate diff page:

- The diff is shown in color with line numbers as `+ / −`; the top bar can **open the file in the editor** (**from 1.12.0**), jumping directly to the corresponding file in the code editor.
- When a single-side file exceeds 2MB, it directly prompts "File too large" and doesn't display content (same for binary files).

## Oversized Output

To protect phone memory, the Git page doesn't read oversized content in full: when reading git output for status, branches, commit lists, etc. exceeds the limit, it prompts "Output too large, truncated — narrow the scope and retry", and doesn't try to load further.

## Common Error Messages

When an operation fails, Git's original error is translated into a Chinese prompt:

| Scenario | Prompt |
| --- | --- |
| Deleting the branch you're on | Cannot delete the branch you're currently on; switch to another branch first |
| Deleting a branch with unmerged commits | This branch has unmerged commits and cannot be safely deleted |
| Switching to a nonexistent branch | Branch or reference does not exist |
| Local changes would be overwritten when switching | There are uncommitted local changes that would be overwritten; commit or stash first, then switch |
| Push rejected (remote has updates) | Push rejected; the remote has newer commits — pull first |
| Remote authentication failed | Remote authentication failed; check credential configuration (username/password/token) |
| Commit signature not configured | Commit signature not yet configured; fill in the username and email in settings |
| Remote repository not configured | No remote repository configured; cannot push/pull |
