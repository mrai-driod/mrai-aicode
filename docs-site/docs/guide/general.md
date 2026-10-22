# Preferences

"Settings → General Settings → Preferences" collects global preference switches that don't target a specific provider and don't belong to a specific purpose. Changes take effect immediately, with no need to save.

## Models

**Automatically remove invalid models after fetching** (on by default)

When on: in the [provider edit page](/guide/providers), tap "Fetch Models" — as long as the server successfully returns a model list, models in the local list that the server no longer returns are automatically removed, with a prompt telling you how many were removed.

- If the request fails or the server returns an empty list, the local list isn't touched, avoiding the case where a network anomaly wipes out already-added models.
- Manually added models that happen to not be in the server's returned results are also removed together. If that bothers you, turn the switch off.
- When off, fetching only lists new models for you to check, without modifying the existing list.

**Auto-compaction threshold** (default 90%, an integer from 1–100)

When the estimated next request would use this proportion of the model's available input budget, context compaction is triggered automatically (early conversation is summarized into a handover summary). The budget considers the model's input limit and reserves space for the reply, so it isn't necessarily equal to the model's nominal total window. A larger value compacts later; a smaller value compacts earlier but summarizes more frequently. Even at 100%, the hard input limit can't be bypassed.

## Sessions

**Session at startup** (default "New session")

- **New session**: every time you open the app, it starts from a new session. If the current workspace still has an empty session with no messages sent, it's reused directly instead of creating another one.
- **Open most recent session**: goes directly into the most recently updated session in the current workspace, continuing where you left off. If the workspace has no sessions yet, a new one is created. Pinned sessions don't take priority — even if there's a pinned session, it still enters the most recently updated one.

Switching workspaces is handled by the same preference.

**Delete external workspace chat history** (off by default)

Controls how chat history is handled when removing an external local workspace added via "Add local directory". When on, removing an external local workspace also deletes its sessions and messages; when off, it only unlinks and keeps the chat history, so re-adding the same directory later still shows the history.

Deleting built-in workspaces and remote workspaces is unaffected by this switch: when you delete them, the directory and chat history are deleted together.

## Input

**Enter key sends message** (off by default)

When on, the soft keyboard's Enter key becomes the send key — one press sends; when off, Enter is a newline and you send with the send button. Keeping it off is recommended for multi-line input.

## Network

Both timeouts are in seconds; enter a number in the input box — **0 means no limit**; after saving, they take effect immediately for subsequent requests, with no restart needed.

**First-token timeout** (default 300 seconds)

After each request attempt starts, the maximum time to wait for the first valid content (body text, thinking, or a tool call), including the time waiting for the server's response headers. Exceeding it cancels this request and retries according to the retry count setting, with the timer restarted for each retry. Models with slow starts and long thinking can have this raised. Once the first valid content is received, this timeout no longer limits the duration of the rest of the reply.

**Data chunk interval timeout** (default no limit)

During a streaming reply, the maximum allowed wait between adjacent response chunks. Set to a specific value, a stuck connection is disconnected and retried sooner; keep it unlimited when your network is stable.

**Retry count** (default 6)

The app periodically probes HTTP/2 connections; when it finds a connection unresponsive, it disconnects and handles it per the retry settings; HTTP/1.1 connections don't support this probing. When the UI shows "Retrying", it may be waiting for backoff, or may have already sent the next request and be waiting for content.

The number of exponential-backoff retries after a network request fails (excluding the first request; waits start at 0.5 seconds, double each time, and cap at 10 seconds per wait). Within the same request, even if it disconnects again after already outputting partial content, the network retry count keeps accumulating and doesn't restart. 0 means fail with an error directly, no retries; it's also affected by multi-key auto-switching — failures classified as key invalidation try switching keys and resending first.

## Tools

**sendFile single-file limit** (default 100MB, an integer greater than 0)

The maximum size allowed for a single file when the AI uses `sendFile` to send a file to the chat area. Files over the limit aren't sent, and the AI receives the failure reason. Raising it allows larger files to be sent, but a single file is first copied to the app's private directory and then displayed, so overly large files may take up a lot of storage.
