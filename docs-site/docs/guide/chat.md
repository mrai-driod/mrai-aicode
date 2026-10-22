# Chat Interface Tour

This page explains what each button and area on the main interface does.

When you start a new empty conversation, the welcome message is shown slightly above the center of the page. Tapping the input box at the bottom to bring up the keyboard moves the welcome message up with the input box, keeping it as much as possible between the top title bar and the input box; after the keyboard is dismissed it returns to its original position.

## Top Title Bar

- **Menu button** (top left): opens the sidebar, for managing the session list and browsing files.
- **Session title and model name**: the top line is the current session name, the line below is the model currently in use.
- **New session**: creates a new conversation.
- **Git entry**: goes to the Git version management page.
- **Terminal entry**: goes to the terminal page.

In remote SSH mode, an extra row appears below the title bar: on the left is the SSH connection status (a dot plus text), and on the right is the current session's token totals (up arrow for input, down arrow for output). This row isn't shown in local mode.

## Sidebar

Tap the menu button in the top left, or swipe right from the left edge of the screen. At the top are two tabs: "Sessions" and "Files".

### Sessions Tab

Sessions are ordered by last reply time from newest to oldest and grouped by time (Today / Yesterday / Within 7 days / Within 30 days / Earlier by month). Pinned sessions are placed separately in a "Pinned" group at the very front, with a light-blue card background.

A short tap switches sessions; a **long press pops up the action menu**:

- **Pin / Unpin**: pinned sessions stay fixed at the very front.
- **Rename**: changes the session title; doesn't affect ordering.
- **Export**: exports this session.
- **Batch select**: enters multi-select mode, supporting checking multiple sessions, one-tap "Select all", and "Batch delete" (with a second confirmation). Tap the × in the top left or the back key to exit multi-select mode.
- **Delete**: a confirmation dialog appears; confirming deletes the session and all its messages.

A session that has spawned sub-agents shows the sub-agent count and an expand arrow at the end of its row; tapping to expand shows its sub-agents below (**from 1.11.0**), see [Sub-Agents](/guide/subagent).

Below the sidebar tab bar is **search chat history** (**from 1.12.0**): entering a keyword searches message content across all sessions in the current workspace, and the list area shows the hits in place (session title plus the matching snippet, keyword highlighted). Tapping a result switches to the corresponding session and jumps to that message; tapping the × on the right of the search box or clearing the content returns to the session list. Search only covers the current workspace, and only matches the body text of you and the AI, not tool execution output.

### Files Tab

Browse the files of the current workspace; tapping a file opens the code editing page (**from 1.11.0**), see [File Browsing & Code Editing](/guide/files).

At the bottom of the sidebar are two entries: "Browser" (**from 1.12.0**) and "Settings".

## Toolbar Below the Input Box

From left to right:

- **Mode button**: cycles through BUILD, PLAN, AUTO — see [The Three Modes](/guide/modes).
- **Model button**: shows the current model; tapping pops up the selection dialog. There's a search box at the top for fuzzy filtering; models are grouped by provider; tapping a group title collapses or expands it (the collapsed state is remembered and persists when the dialog is reopened or after restart; when searching, matching groups expand automatically), each row is labeled with capability tags (`Image`, `Tools`, input/output lengths), and tapping switches.
- **Workspace button**: shows the current workspace name; tapping pops up the workspace management panel (see below).
- **File button**: uploads a file as an attachment; the AI can read its content.
- **Image button**: uploads an image as an attachment. Tapping opens the system gallery directly, with multi-select. Requires the current model to support image input.
- **Send button**: when the AI is idle it's a plain send; when the AI is working, if the input box is empty it shows the stop button, and if there's content it shows the orange send button (the message will queue).

When the text you input grows beyond the input box height, an **expand button** appears in the top right of the input box: tapping edits this draft fullscreen, and the "Collapse" in the top right or the system back key exits fullscreen. Edits in fullscreen sync back to the input box in real time — both are the same draft — and after collapsing you just press send.

Uploaded attachments are shown as preview cards above the input box and can be removed before sending. Uploading a larger file takes some time, during which an "uploading" hint shows above the input box, and when done it automatically becomes a preview card. Tapping an image thumbnail lets you [view it fullscreen](#fullscreen-image-viewing).

Unsent drafts are saved per session: after typing content in the current session and switching to another session, the draft is still there when you return. Drafts persist to local storage and still exist after force-closing the app and reopening (process restart).

When scrolling the message list, the input box area fades to 40% opacity to reduce distraction, and restores when scrolling stops.

## Workspace Management

In the panel that pops up from the workspace button you can:

- **Switch workspace**: tap to switch. If there's a running AI session or terminal tab, a confirmation dialog appears first — switching stops them.
- **New workspace**: enter a name to create one, corresponding to an independent folder on the phone.
- **Add local directory** (local mode): use an existing directory on the device as a workspace. The first tap shows a risk confirmation dialog (the AI will directly read and write your real files), with a "Don't show again" checkbox. Only folders from "Internal storage" or "SD card" can be selected; other file providers such as cloud drives aren't supported.
- **Delete workspace**: internal workspaces are deleted together with their files; external workspaces added via "Add local directory" are only unlinked — **their directory contents are not deleted**.

The workspace is the project root directory for the AI's operations; the AI's code reads/writes and command execution are all confined to the current workspace.

## Slash Commands

Typing `/` in the input box pops up the command menu, with **built-in commands first (alphabetical), Skills after (also alphabetical)**.

Built-in commands:

- `/compress`: manually triggers context compaction for the current session.
- `/init`: has the AI analyze the current workspace codebase and generate or improve the project rules file `AGENTS.md` (if the project root already has `CLAUDE.md` but no `AGENTS.md`, it improves `CLAUDE.md`). Can take an argument specifying a focus, e.g. `/init focus on testing and release flow`.
- `/usage`: views today's and cumulative call counts, token usage, and estimated cost (global statistics, across sessions).

Skills:

- Every enabled Skill can be triggered directly with `/Skill name`; the AI executes the Skill's body as this turn's instructions; Skills in the menu carry a "Skill" mark on the right.
- A Skill can take arguments, e.g. `/pdf-report a4`: the arguments replace the `$ARGUMENTS` placeholder in the Skill body; if the body has no placeholder, the arguments are appended at the end.

Commands queue when the AI is busy and execute automatically when it's idle. The command text is displayed in the conversation as a user message and enters the context.

## Context Usage & Compaction

The ring around the send button reflects occupancy of the model's available input budget, not cumulative consumption. Before sending, it estimates the usage the new input, reply, and tool results will add; after the model returns, it calibrates with the actual report; screen-reader descriptions distinguish estimated usage. After switching models, compacting, or rolling back, the old usage baseline no longer carries over, and subsequent requests recompute.

When the context approaches its threshold, earlier conversation is turned into a handover summary and recent messages are kept. When the compaction model's window is small, it's processed in chunks, which may increase waiting time and call cost; if the summary is empty, truncated, or can't free enough space, the original history isn't replaced. An explicit context overflow auto-compacts and retries at most once; if it still fails, shorten the input, reduce tools, or check the model window settings.

## Message Queue

The input box remains usable while the AI works. Typing content and tapping the orange send button doesn't interrupt the current task but adds it to the **pending send queue**:

- The queue panel shows above the input box, listing each queued message's number and summary; tapping the title bar collapses/expands it, and it auto-collapses when a dialog or the keyboard pops up.
- After the current turn's task ends, the next message in the queue sends automatically; multiple messages execute in order.
- **Drag to reorder**: press and hold the drag handle (☰) on the left of each row and drag up or down to adjust the execution order of queued messages.
- **Edit**: tap the pencil icon on each row to modify that message's content; confirming updates it.
- **Insert immediately**: tapping the lightning icon on a row sends that message to the working AI immediately — no longer waiting for the current turn to end, but injected directly into the AI's in-progress tool results, so it adjusts to the new content in the same turn. If the AI isn't calling tools in the current turn (it's outputting plain text), it will be sent as the next message when this turn ends.
- Tapping "Stop" skips the current turn and immediately executes the next message in the queue.
- The × on the right of each message removes it from the queue.

::: warning The queue lives only in memory
Switching workspaces or the process being terminated by the system clears the queue; don't keep important content only in the queue.
:::

## Replies & Tool Cards

When the AI generates a reply it's displayed character by character with a **typewriter effect**, its speed auto-adjusting to the model's output speed: when the model outputs fast the display accelerates to follow, lagging only the last small segment, never falling further and further behind. The thinking-process preview row and tool execution output still update in real time, unaffected by the typewriter; at the end of generation, the remaining small segment completes quickly (about a fraction of a second) rather than appearing all at once.

If you leave midway and come back (switching to the terminal, a settings page, or another session that's also outputting), the already-output part is shown directly at the current progress rather than re-typing from the beginning; newly produced content afterwards continues character by character.

The message area uses a **flat document flow** layout: what you sent is a light-colored bubble on the right, with the send time labeled below; the AI's replies are laid directly on the page, no longer wrapped in a bordered card.

Each AI reply is wrapped in a **task fold** (**from 1.13.0**), gathering the whole turn's process together:

- The fold header shows this turn's state: while running it's "Running", and when done it becomes "Done Xs" (X is this turn's duration); tap it to expand or collapse.
- During the run, the body text, thinking, and tool calls are shown in the order they occurred — explanatory text written before a tool call isn't moved after the tool; only after the task ends is the final answer kept separately below the process.
- **Auto-collapses when the task completes**, leaving only the final answer; expanding lets you review this turn's process (thinking, tool calls, transitions in between).
- After you manually expand or collapse, your choice is kept (unchanged by switching sessions or scrolling off screen and back); it auto-collapses on task completion only if you haven't manually operated it.
- If the AI answers in one go this turn, with neither thinking nor tool calls, no fold header is shown, same as before.

Below **every message you send** there's a permanent send time and "Copy / Rollback / More" buttons, so you can copy it or roll back to it at any time. **The AI reply buttons are only attached to the latest reply in the whole session** (within one turn the AI may take multiple steps with tool calls in between; the buttons are attached only to the last step, and only appear when this turn completed normally — not on manual pause or error). Each turn's usage, cache hit rate, and duration are totaled per turn and attached below the final reply of the turn — see [Usage and Time Below Replies](#usage-and-time-below-replies).

The AI's thinking process occupies its own row, **collapsed by default**, showing only one line when collapsed: a thinking icon at the start of the row plus a one-line content preview — while thinking, it shows the line currently being written (scrolling with the content); after thinking ends it fixes on the first line of the thinking, truncated with `…` if one line doesn't fit; tap this row anytime to expand and view the full text, tap again to collapse. When body text starts outputting or preparing a tool call begins, the thinking state becomes "Thinking done" and the duration stops increasing, not including subsequent body generation time. The expanded thinking content is viewed in a fixed-height window with scrolling (it won't stretch the whole page very long), with a vertical line on the left marking this segment as thinking.

While waiting for the model's response, a status line shows at the bottom of the message area: a white highlight sweeps back and forth across the line of text, followed by three jumping dots (the dots don't take part in the highlight). When the model is preparing a tool call (the tool name arrives first, the parameters still transmitting, possibly taking a few seconds), this line's text directly states the action about to be performed rather than always showing "Thinking" — e.g. `Editing file`, `Executing command`, `Searching online`.

Tool calls are folded by "consecutive batch" into one row:

- **`N tool calls`**: consecutive multiple tool calls in one turn are merged into this row, **collapsed by default** — they don't auto-expand while running either; open it yourself if you need the details; as long as any tool in this batch is still running, **this row's text itself** carries a sweeping highlight. Tapping this row lets you manually expand or collapse at any time, and **after a manual choice it always displays per your choice** — it won't be changed back by automatic rules, nor revert to the default state because you scrolled off screen or went to the terminal or a settings page and came back.
- After expanding, each call occupies one row, and the **whole block indents one level inward**, so at a glance you can see these rows belong to the group above rather than being independent content at the same level as the group header.
- The row of the currently running tool shows at its end **what it's doing** (also visible in the collapsed state) rather than a generic "Running": `Executing command`, `Editing file`, `Reading file`, `Searching online`, `Generating image`, `Organizing todos`, `Starting sub-agent`, `Reading/writing memory`; tools whose concrete action can't be recognized (such as MCP tools) show `Calling tool`.
- Tapping a single row lets you view the "Instruction" and "Execution result"; when content is too long it's viewed by scrolling in a fixed-height window, with a fade at the bottom hinting there's more below. A single row's expanded/collapsed state is likewise remembered — scrolling away and back won't auto-close it. While a tool is still outputting, scrolling inside the window temporarily fixes the content being browsed, avoiding jumps from new output replacing text; scrolling back to the window bottom and stopping restores real-time updates. While browsing, the tool keeps executing, and the outer chat doesn't auto-scroll to steal your position. Inside tool and thinking windows, press-dragging to the top or bottom and continuing to drag can drive the outer chat list; after a fast swipe and release, the window's inertial scrolling stops at the boundary and doesn't drive the outer list.
- File-changing tools (write file, edit file) are **collapsed by default like other tools**; after expanding, it's a **diff card**: the file path in the top left, "Copy" in the top right to copy this diff, the body is a colored `+ / −` diff with line numbers, and the bottom summarizes this change (e.g. `+3 −0 · 1 file`).

If the bottom of a tool card shows a "Background task / sub-agent ended" hint (a green dot means success, a red dot means failure or manual termination), it means a background task or sub-agent completed within that turn and was captured by the current session, and the AI will automatically continue processing.

## Fullscreen Image Viewing

Images in the chat can all be tapped to view large, consistently in four places:

- Image attachments pending send above the input box;
- Image attachments in sent message bubbles;
- Images displayed in the AI's reply body (charts and screenshots the AI generated, or images it read from the workspace);
- Images the AI sent you with the send tool (or image generation).

After going fullscreen: pinch with two fingers to zoom, drag with one finger after zooming to view details, double-tap to toggle between original and zoomed. Tap the screen, press the back key, or tap the × in the top right to close (tapping the screen while zoomed won't close, avoiding accidental touches while viewing details). On a tablet, whether the chat area occupies half the screen or all of it, the large image fills the whole screen.

If an image file has been moved or deleted it can't be opened, and you'll be told "Image failed to load". GIFs only show the first frame.

## Files Sent by the AI

Files the AI sends with `sendFile` (and images it generates) are shown in the chat area **one file per row**: on the left a thumbnail or type icon, in the middle the file name and "size · path", and on the right a `›` hint that it's tappable.

- Tap an image row → fullscreen viewing in the app; tap another type → handed to the system's corresponding app to open (installation packages first confirm "Allow installing unknown apps").
- After a file has been moved or deleted, tapping it open shows "File doesn't exist or has been moved"; when its location doesn't support sharing to other apps, a corresponding explanation is given. Files in external workspaces added via "Add local directory", and in directories mounted in from custom images, can likewise be tapped open.
- Each row is placed at the **position of the tool call that sent this file**, so when flipping through history you can clearly see "At which step did the AI send this file"; tools carrying files **don't participate in the "N tool calls" folding**, and whether the group is collapsed doesn't affect their display.
- One call can send at most 10 files, and the single-file size limit defaults to 100MB (adjustable in "Preferences → Tools"); if just one doesn't exist or exceeds the limit, the whole batch isn't sent, and the AI receives the failure reason and retries after fixing.

## Usage and Time Below Replies

Below the AI reply body there's a row of statistics, **calculated as "this turn's total", attached only below the last reply of each turn** (the buttons are arranged "Copy → Statistics → More options", with a `···` action entry at the row's end):

- **Token usage**: `↑` is this turn's input tokens, `↓` is output tokens. Within one turn's task the model may be called multiple times (each tool call requests again), and this is the sum of all requests in this turn, not one step's number.
- **Cache hit rate** (**from 1.11.0**): the percentage next to the database icon, the proportion of this turn's input that hit the server-side cache out of this turn's total input. The higher the hit, the cheaper and faster. Not shown when there's no cache hit or the channel doesn't return cache data. This field is recorded from 1.11.0; older messages can't see it.
- **This turn's total duration** (**from 1.11.0**): the time next to the clock icon, attached only below the last reply of a turn's task. Counted from when you press send until the AI completes, including tool execution and time waiting for you to tap authorization in between. Under 1 minute it's shown like `12s`; longer it's shown as `2:05` (minutes:seconds) or `1:02:05` (hours:minutes:seconds). Not shown while the task is still running; appears only after completion.

For the cumulative usage and cache hit rate of a whole session or a whole channel, see [Token Usage Statistics](/guide/token-stats).
