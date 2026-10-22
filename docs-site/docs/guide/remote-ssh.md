# Remote SSH Mode

After selecting a "Remote SSH" container, AiCode enters remote workspace mode: the AI's **file reading/writing, command execution, and terminals all happen on the remote server**, `~/workspace` maps to a specified directory on the server, and the phone serves only as the operating interface.

Suitable for scenarios where the server already has a complete toolchain, or where you need the AI to directly modify a remote project.

## Prerequisite: Create an SFTP Channel First

Remote SSH reuses the connection info (host, port, username, password) of an **SFTP** channel from the "Connections & Sync" page; you need to create an SFTP connection on that page first — see [Workspace Sync](/guide/sync).

FTP channels don't support command execution and won't appear in the selectable list.

## Creating a Remote SSH Container

In "Settings → Containers & Images", tap + at the top right and choose "Remote SSH":

- **Name**: an alias for the configuration.
- **SSH connection channel**: dropdown to select a configured SFTP channel. When no channel is available, the UI prompts you to go configure one on the "Connections & Sync" page first, and the save button is unavailable.
- **Remote workspace path**: a directory on the server; the AI's `~/workspace` maps to it. When a channel is selected, the default `~/.aicode/workspaces` is filled in automatically (resolved against that user's real home on the server, e.g. `/root/.aicode/workspaces` for the root user), and can be modified.

After saving, select it in the list to take effect.

The authentication method follows the selected SFTP channel's configuration, supporting **password authentication** and **SSH private key authentication** (including password-protected private keys).

## Connection Behavior

- **Auto-connect**: at app startup, if currently in remote mode, it connects automatically in the background; a connection failure doesn't block the UI, and the first command execution retries.
- **Keep-alive & auto-reconnect**: after connecting, a heartbeat is sent every 30 seconds to prevent idle disconnection; after a disconnect, the background reconnects automatically (backoff starting at 5 seconds, up to 30 seconds), and also retries immediately when the app returns to the foreground.
- **Status display**: the top of the conversation page shows "SSH: Connected / Connecting / Connection failed / Not connected". When not connected, the message area shows a waiting prompt.
- **Two independent connections**: file reading/writing goes over SFTP, while terminals and commands go over exec — each occupies its own SSH connection without affecting the other; when the file channel has an anomaly it's rebuilt automatically, without interrupting running commands.
- **Path bootstrap**: if the remote workspace path doesn't exist, it's created automatically; if there are no subdirectories under it, a workspace named `default` is created automatically, ensuring the AI always has a usable `~/workspace`.

## Local Data vs Data Following the Execution Environment

In remote mode, commands, files, terminals, and workspaces are all on the server. Most configuration is still stored locally on the phone; a few "execution environment resources" follow the environment, with the remote side authoritative:

| Item | Description |
| --- | --- |
| Chat history, model & provider configuration, API keys, app settings | All local |
| Memory | Local. Project-level memory is isolated by "project name + server identity", so same-named workspaces on different servers don't get confused |
| Global Skills, sub-agents & panel scripts | **Follow the execution environment**: in remote mode, reads/writes the **server's** `~/.aicode/skills/`, `~/.aicode/agents/`, `~/.aicode/scripts/`, independent from the local mode copies |
| Project-level Skills & sub-agents | Directly read/write `<workspace>/.aicode/skills/`, `agents/` in the **server workspace**, and can be committed to git with the codebase |
| Project-level configuration (tool authorization rules / MCP / disable lists for Skills & sub-agents) | Local on the phone. In remote mode, isolated in the app's private directory by "project name + server identity", not written to the server |
| MCP servers | HTTP type is connected directly by the app; stdio type runs on the "default container" (see below), not on the remote server |
| Logs & tool output | Local |

After a successful connection, AiCode syncs the built-in docs to the server's `~/.aicode/docs/`, so in remote mode the AI can also consult these instruction documents.

## Default Container

In remote mode, some features still need to run locally — for example, stdio-type MCP servers. They run on the **default container**, which defaults to the built-in Alpine, and can be changed to any local container in the "Default Container" group below the container list.

After switching containers (including switching to remote SSH), all stdio MCPs automatically disconnect and reconnect with the new container; HTTP MCPs don't depend on the container and are unaffected.

## Where Git Signatures & Credentials Go

The **signature** saved on the Git page is written to the current execution environment — in remote mode, that's the server's git configuration.

**Credentials** use the credential file on the phone as the sole source. In remote mode, you can enable "Auto-inject credentials" on the credentials page (remembered per server, asked on first entry) to sync credentials to the server, effective only for repositories under the workspace directory; it doesn't affect repositories in other directories on the server, nor does it change the server user's global git configuration.
