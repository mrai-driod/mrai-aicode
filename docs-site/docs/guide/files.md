# File Browsing & Code Editing

::: tip Version note
The file tree and code editing pages are available from 1.11.0.
:::

The "Files" tab at the top of the sidebar browses the current workspace; tapping a file opens the full-screen code editing page by default (except images and installation packages — see "Images & Installation Packages" below). Both local workspaces and remote SSH workspaces are supported, and the read/write method is decided automatically by the current mode.

## Browsing Files

The "Files" page is an indented file tree that expands in place from the workspace root.

- The first row is the workspace root `workspace`; it too can be collapsed and expanded (expanded by default), with a refresh button on the right.
- Tap a directory to expand or collapse it in place; tap a file to open the edit page directly (for images and installation packages see "Images & Installation Packages" below) — no need to enter and return level by level.
- Directories come before files; within the same kind, entries are sorted by name.
- Files show different icons by type (code, scripts, configuration, documents, images, audio/video, archives, etc.), using monochrome linear icons consistent with the app globally and tinted with the theme color.
- The coloring rules are similar to VS Code: hidden files starting with `.` are shown in a light, weakened color; entries hit by the workspace root's `.gitignore` are shown in orange (only the root directory's `.gitignore` is considered; those in subdirectories aren't parsed).
- Deep paths or long file names are unaffected: the whole tree shares one horizontal scroll, names aren't truncated into ellipses, and you can swipe left/right to see them fully.
- The directories you've expanded or collapsed are **remembered per workspace**; closing the app and reopening, or switching away and back to the same workspace, restores the last expansion state.
- Browsing is limited to within the workspace; you can't go outside it.

## Images & Installation Packages

**Images** in the file tree (png / jpg / jpeg / gif / webp / bmp) open the in-app full-screen viewer when tapped, with pinch zoom and double-tap to enlarge, consistent with viewing images in the chat area; **APK / APKS / XAPK** invoke the system installer directly when tapped (the first time, you're guided to enable "Allow installing unknown apps"). Neither of these enters the code editor; other files still open the edit page as usual.

## Opening Files from Conversation

When the AI references code in conversation, it writes the path as a clickable link (an underlined `file path:line number`). Tapping such a link opens the edit page directly; with a line number, it also automatically scrolls to that line and keeps it **as close to the middle of the screen as possible** (when the target line is near the start or end of the file, it's limited by the scroll range and hugs the edge). Once you scroll, the automatic positioning is handed back to you and won't drag the view back to the target line.

Files within the workspace, the AI configuration directory (`~/.aicode/...`), and container system files (`/etc/...`, `/root/...`) are supported, and can also be opened in remote SSH workspaces. Links to ordinary URLs are unaffected and still open in the system browser.

When you tap a path that doesn't exist, a directory, or a file over the size limit, the edit page gives the corresponding failure prompt.

## List Refresh

In local workspaces, file changes **refresh automatically** — not limited to changes the AI made: commands you execute in the terminal, or changes made with other apps, are also perceived.

It watches the workspace root and all currently **expanded** directories, without recursing the whole tree, so even large repositories don't consume a lot of system resources; after you collapse a directory, it stops watching it.

Two other refresh moments: the sidebar re-reads each time it appears (in the wide-screen resident sidebar, returning to the chat page from another page also counts); the refresh button on the right of the workspace root row can re-read manually at any time.

Remote SSH workspaces can't watch remote file system changes and can **only** rely on these two moments to refresh; when you notice the list doesn't match reality, tap the refresh button. Reading directories in remote mode goes over the network, so there may be a brief loading state; on failure, the specific reason is shown at the list position.

## Create, Rename, Delete

**Long-press a directory row** (including the workspace root at the very top) and choose "New File" or "New Folder" from the bottom menu; the new entry lands in that directory. If the long-pressed directory is collapsed, it first expands automatically so you can see the result.

The name can only be a single entry name: if it contains `/` or `\`, or is `.` or `..`, the confirm button can't be tapped — avoiding accidental creation in other directories. If an entry with the same name already exists or the directory isn't writable, a failure prompt appears and the original file isn't overwritten.

**Long-press a file or subdirectory row** to rename and delete (the workspace root node can only create new):

- **Rename**: renames only within the same directory; no cross-directory move.
- **Delete**: files are deleted directly; folders are deleted recursively together with all their content, so a confirmation dialog appears first.

::: danger Deletion is irreversible
Deletion doesn't go to the recycle bin, and isn't protected by checkpoints either. Think clearly before confirming, especially when deleting folders.
:::

## Copy, Cut & Paste

Long-press a file or subdirectory and choose "Copy" or "Cut", then long-press the target directory and choose "Paste Here". If an entry with the same name already exists at the target, you're first asked whether to overwrite; overwriting replaces the original entry and its content, irreversibly.

You can't paste over the source entry in the original directory, and you can't copy or move a folder into itself or its subdirectories. Such operations are rejected and the source file stays unchanged.

## Code Editing Page

After tapping a file, the sidebar collapses and you enter the full-screen edit page.

At the top, the top left is back; the top right has, in order, undo, redo, save, settings. Undo and redo are grayed out when there's nothing to operate on; the save button can only be tapped when there are unsaved changes.

- **Undo steps by typing pauses**: continuous fast typing counts as one step; pausing about half a second and then typing counts as a new step. After undoing all the way back to how it was when opened, the asterisk before the file name disappears and the save button grays out; exiting at this point won't prompt about saving.
- When you open a Markdown file, a **preview toggle** button appears to the left of the undo button; tap to switch to the rendered effect, tap again to switch back to source. Preview and source share the same editing state, so switching doesn't lose unsaved changes.
- The preview is a complete Markdown rendering: **HTML embedded in the document (centered titles, images, table layouts, badges, etc.) displays as usual**, code blocks have syntax highlighting, and colors follow the app's light/dark theme. Relative-path images in the document (such as `docs/a.png`) are read from the current workspace, and remote workspaces can display them too; network images (badges, online charts, etc.) need an internet connection to load. Tapping a link in the preview page doesn't navigate, avoiding leaving the current document.
- The preview doesn't include LaTeX formula rendering; for documents with formulas, switch back to source to view.
- Below the navigation bar is a small file name bar: on the left is the file name (with an asterisk in front when there are unsaved changes); on the right are the cursor's row/column position and the file encoding.
- The **quick symbol bar at the bottom** can be scrolled horizontally; the far left is cursor left/right movement and indent (Tab), followed by common programming symbols (brackets, quotes, operators, `;` `:` `|` `&`, etc.) — tap to insert at the cursor. When the soft keyboard pops up, the symbol bar floats above the keyboard.
- With unsaved changes, tapping back or pressing the system back key first pops up a confirmation dialog; you can choose "Don't Save" or "Save and Exit".
- **At the end of the file you can keep scrolling up half a screen**: the last few lines won't hug the bottom edge of the screen, making it convenient to edit at the end of the file.

### Syntax Highlighting

These languages are built in: Kotlin, Java, Python, JavaScript/JSX, TypeScript/TSX, JSON/JSONC, YAML, Shell, Go, Rust, C/C++, CSS, XML, HTML, Markdown, PHP, Lua. `.kts` is highlighted as Kotlin. Unrecognized types are displayed as plain text, without affecting editing.

The colors use the VS Code default themes (dark Dark+ / light Light+), switching automatically with the app theme; switching themes during editing doesn't lose unsaved content.

### Editor Settings

Tap the settings button at the top right to enter a separate settings page; changes take effect immediately and are remembered for next time:

- **Font size**: adjust with the slider.
- **Auto wrap**: when on, long lines wrap for display and no longer scroll horizontally.
- **Show indent guides**: on by default.
- **Show wrap arrows**: when auto wrap is on, draws indicator arrows at wrap points; off by default.
- **Show whitespace symbols**: visualizes spaces, tabs, and newlines; off by default.

### Saving & Concurrency

Saving is a **full overwrite write-back**; after success it prompts "Saved".

::: warning No external concurrency detection
AI tools or the terminal may be changing the same file at the same time, while saving directly overwrites with the editor's content, without prompting that the file was changed externally. If you and the AI might touch the same file simultaneously, let one side finish first before the other starts, to avoid overwriting each other.
:::

### Limitations

- **File size limit 2 MB**. Files over the limit aren't opened — the content needs to be read fully into memory, and larger sizes would noticeably lag or even crash.
- **Binary files are not supported**. When opening, the start of the file is sniffed; if it's identified as binary (images, executables, archives, etc.), a prompt appears directly, without loading garbled text as text.

## Where Workspace Files Live on the Phone

The "Files" page browses workspace content, while workspace files and AI configuration are stored in the app's private directory, invisible to ordinary file managers by default. When you want to open or back them up with external tools, there are several ways:

**File manager mount (recommended)**: the app implements the standard DocumentsProvider, so you can use the phone's built-in "Files" manager or a third-party file manager (MT Manager, Solid Explorer, etc.) to find and mount the app's private root directory. After mounting you can see:

- `projects/`: workspace projects. External workspaces added via "Add local directory" aren't here; they're in the device directory you chose yourself.
- `aicode/`: corresponds to `~/.aicode` inside the container, containing `skills/` (Skills), `docs/` (docs), `mcp.json` (MCP configuration), etc.

Mounting steps with MT Manager as an example: open MT Manager → tap the menu at the top left (three horizontal lines) → the three dots at the top right of the sidebar → "Add local storage" → after the system file picker opens, tap the three dots at the top left and choose "AiCode" → tap "Use this folder" at the bottom.

**Built-in terminal**: switch to the terminal page, `cd ~/.aicode` to look directly, and view/edit with `vi` or `cat`.

**Hand it to the AI**: the AI runs inside the container, so you can say "print out `~/.aicode/mcp.json` for me to see" or "write a new Skill under `~/.aicode/skills/`".

**Rooted devices**: go directly to `/data/data/com.aicode/files/` (on some systems the equivalent path is `/data/user/0/com.aicode/files/`). Among them, `projects/` is the workspace root, `aicode/` corresponds to `~/.aicode`, and `rootfs/` is the container system. Note that the debug build's package name is `com.aicode.debug`, and the two private directories are completely isolated.
