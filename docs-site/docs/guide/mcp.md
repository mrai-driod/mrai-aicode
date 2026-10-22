# MCP Servers

MCP (Model Context Protocol) lets the AI connect to external tools — querying databases, searching the web, controlling a browser, and so on. Once connected, these tools automatically appear in the AI's tool list and are called just like built-in tools.

The entry point is "Settings → MCP Servers".

## Two Levels of Scope

MCP configuration has two levels, global and project:

- **Global**: shared by all projects; configuration stored in `~/.aicode/mcp.json`.
- **Project level**: only effective in the current workspace; configuration stored in `<workspace>/.aicode/mcp.json`, and can be committed to git together with the project.

What actually takes effect is the merge of both, with **project level taking precedence** — for a server with the same name, the project configuration overrides the global one.

## Server List

Each row shows the server name, source (global / project), type (built-in / HTTP), and connection status; when connected it also shows the tool count, and for a local stdio service that isn't connected it shows the launch command.

- Tap a row to edit.
- Swipe left to reveal the delete button; deletion applies to the scope marked on that row.
- "Reconnect" at the top right reconnects all servers with one tap; "+" adds a new server.

If you edit `mcp.json` directly inside the container or with an external editor, the app automatically discovers and refreshes within a few seconds: newly added or enabled servers connect automatically, deleted or disabled ones disconnect automatically — no restart needed.

## Adding a Server

At the top of the edit page, first select the **scope** (defaults to "Project" when adding new; editing an existing entry keeps its original scope). The "Enabled" option below controls whether it participates in connections. The remaining fields fall into two kinds by transport type.

The **name** is only an alias, but it's spliced into the tool names given to the model (`mcp__name__toolname`), so it can only contain letters, digits, underscores, and hyphens; entering characters like Chinese will trigger an error and prevent saving.

### Remote HTTP

- **Name**: an alias, e.g. `fetch`, `github`.
- **Server address**: the remote server's Streamable HTTP link.
- **Custom headers**: key-value pairs, for fixed headers such as authentication tokens.

### Local stdio

- **Name**: an alias.
- **Launch command**: `npx`, `node`, `python`, etc.
- **Command arguments**: added item by item, e.g. `-y`, `@modelcontextprotocol/server-xxx`.
- **Environment variables**: key-value pairs passed to the process, e.g. API_KEY.

stdio servers need to run inside the container: in local mode they run on the current container; in remote SSH mode they run on the "default container". After switching containers, all stdio servers automatically disconnect and reconnect with the new container; HTTP servers are unaffected.

## Tool Management

After a successful connection, the "Tools" tab of the edit page lists all tools provided by this server, one card per tool, showing the description and parameter names.

- The switch at the top right can **disable an individual tool**; once disabled it's no longer offered to the model, saving some context.
- MCP tools use the same permission mechanism as built-in tools: by default each call needs your confirmation, and you can choose "Always allow" in the authorization dialog to remember it.
- If no tools are detected, tap refresh at the top right to fetch again.

The log button at the top right of the edit page jumps to the "Logs" page automatically filtered by this server's name — very useful when troubleshooting connection failures.

## Letting the AI Configure It for You

Complex MCP configurations can be handed to the AI to complete; its management tool can specify installing to global or project scope. After adding or removing, the configuration **takes effect in the next session** (when a new session is created, unconnected servers are connected automatically).
