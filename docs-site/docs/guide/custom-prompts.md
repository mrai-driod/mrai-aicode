# Custom Prompts

AiCode's system prompts can be modified by you. The default prompts are built into the app and updated automatically on upgrade; just put the fragments you want to change or add into the custom directory to override or extend them, without modifying the app itself.

::: tip From v1.12.0
The fragment naming rules in this article (`<two digits>-<name>.md`), adding fragments by number, the `.no-builtin` full-disable switch, and the <span v-pre>`{{AICODE_*}}`</span> variables are supported from **v1.12.0**. Earlier versions could only place files with **exactly the same name** as a default fragment to override it, and didn't support adding by number, `.no-builtin`, or variables.
:::

## Managing in the App

You can also skip the file system and manage directly in **Settings → Prompts**:

- The page lists, by number, each fragment **actually in effect**, with a badge on the right indicating which layer the content comes from:
  - **Project**: the current workspace's `.aicode/prompts.custom/`
  - **Global**: `~/.aicode/prompts.custom/`
  - **Local**: `~/.aicode/prompts/` (the copy released from the built-in version at startup)
  - **Built-in**: the app's own `assets/prompts/`
- For the same number, only the highest-priority copy takes effect, with priority **Project > Global > Local > Built-in**; the rest are shadowed.
- Tap a row to edit its content. Saving writes by default to the layer currently in effect for that number; "Local/Built-in" aren't writable, so after editing it lands on the global layer by default, and you can switch the scope to the project layer on the edit page (when there's a workspace).
- All fragments can be swiped left; for project/global fragments you can delete the current override, after which it automatically falls back to the next layer; the delete button for local/built-in fragments is gray and can't be deleted.
- The "+" at the top right can add a fragment with an unused number. When editing, the summary and body are filled in separately; the summary is shown in the list and preview pages and isn't injected to the AI. When writing files manually, a leading `<!-- summary -->` is likewise recognized as the summary, and the body preview hides that comment.
- On first entry, a usage instructions announcement pops up automatically; the Info button at the top right can re-show it at any time.

Changes in the app are fully equivalent to manually placing files; the two methods can be mixed.

## Directory Structure

Prompts live under the AI configuration directory `~/.aicode/` (in local mode, inside the container it's `/root/.aicode/`; in remote mode it's the `.aicode/` under the server user's home, with reads/writes based on the remote side):

```
~/.aicode/
├── prompts/          Default prompts (fully released at app startup, automatically overwritten on upgrade)
│   ├── 00-identity.md
│   ├── 10-communication.md
│   ├── ...           Top level is "static baseline" fragments, sorted by two-digit prefix (the last built-in one is 99-runtime-context.md)
│   └── agent/        On-demand fragments (not in the static baseline, read only when used)
│       ├── plan-mode.md           PLAN mode reminder
│       ├── auto-mode.md           AUTO mode reminder
│       ├── subagent-base.md       Sub-agent basic runtime rules
│       ├── subagent-context.md    Sub-agent runtime context (identity/workspace/time)
│       ├── compact-summary.md     Long conversation context compaction
│       ├── title-generator.md     Session title generation
│       └── init.md                Instruction body of the /init command
├── prompts.custom/   Your custom fragments (override or add; upgrades don't touch this)
│   ├── 50-my-safety-rules.md
│   ├── 25-extra-requirements.md
│   └── agent/
│       └── plan-mode.md           Override an on-demand fragment
├── skills/
└── docs/
```

Fragments come in two kinds, with different naming rules:

- **Static baseline** (top level of `prompts/`): `<two digits>-<name>.md`; the number is its identity and decides its position in the system prompt.
- **On-demand fragments** (`prompts/agent/`): no numbers; read only when used (such as switching modes, compacting context, dispatching sub-agents, generating titles).

## Naming Rules

Custom fragments go in `prompts.custom/`:

- **Top-level files** must be `<two digits>-<name>.md` (e.g. `05-opening.md`, `50-my-safety-rules.md`). The number is the identity:
  - If the number matches a built-in static baseline fragment (built-in numbers are `00`, `10`, `15`, `20`, `30`, `40`, `50`, `60`, `70`, `99`) → **overrides** that fragment; the trailing name can be anything;
  - If the number isn't in the set above → an **added fragment**, inserted into the baseline at the position corresponding to its number size (e.g. `05-opening.md` ranks between `00` and `10`).
- Files not matching this format (such as `notes.md`, `2024-memo.md`) are ignored.
- When multiple files share the same number, only the one whose file name sorts first is taken and the rest are ignored — keep numbers unique.
- Fragments in the **`agent/` subdirectory** have no number identity and override by **exact same name** (e.g. `prompts.custom/agent/plan-mode.md`).

## Loading Priority

Top-level numbered fragments (`<two digits>-<name>.md`) are looked up from four levels of sources; for the same number, only the highest-priority copy takes effect, and the search stops when found:

1. **Project**: `<workspace>/.aicode/prompts.custom/` (in remote mode, the corresponding project directory in the app's private directory)
2. **Global**: `~/.aicode/prompts.custom/`
3. **Local**: `~/.aicode/prompts/<same-named file>` — the copy released at startup
4. **Built-in**: the app's own `assets/prompts/`

On-demand fragments under `agent/` (no numbers) have no project layer and are looked up in three levels: "global custom (`prompts.custom/agent/xxx.md`) → local → built-in".

So you only need to place the few fragments you want to modify or add; the rest automatically use the default versions.

## How to Modify

1. Place fragments in `~/.aicode/prompts.custom/` (create it manually if it doesn't exist): to override a built-in fragment, name it with the same number; to add a fragment, name it with an unused number; subdirectory fragments (such as `agent/plan-mode.md`) go correspondingly under `prompts.custom/agent/`.
2. Edit the content.
3. **Takes effect immediately on save** (no app restart needed).

::: tip Prompt fragments take effect instantly
Custom prompt fragments are re-read on every request, so changes take effect right away without restarting the app. Note: the Skills list, memory, and sub-agent list still only refresh on a **new session**.
:::

There are three ways to edit: modify directly with `vi` / `nano` in the terminal; have the AI write it for you (its file tools can read and write this directory directly); or [access the app's private directory with a file manager](/guide/files#where-workspace-files-live-on-the-phone).

## Fully Disabling Built-in Prompts

Create a file named `.no-builtin` under `prompts.custom/` (content arbitrary; its existence is what takes effect), and the **main agent's** system prompt will consist only of the numbered fragments you wrote at the top level of `prompts.custom/`:

- No built-in fragments are injected anymore, and the Skills list, sub-agent list, memory, project rules, workspace, and current time are no longer appended automatically;
- For whatever dynamic content you need, retrieve it on demand in your own fragments using the variables below;
- It only applies to the main agent; sub-agents still inject built-in fragments per their own definitions;
- If there are no recognizable numbered fragments in `prompts.custom/`, the system prompt will be empty — make sure there's at least one `NN-xxx.md`.

::: danger This is a "complete takeover"
Once enabled, the AI's rules of conduct are entirely determined by the fragments you wrote. Be sure to provide your own identity, tool instructions, safety boundaries, and so on, or the AI will lack necessary context.
:::

## Available Variables

Write these placeholders in a fragment's body, and at runtime they're replaced with real content; when a list is empty, they're replaced with an empty string:

::: v-pre
| Variable | Replaced with |
| --- | --- |
| `{{AICODE_SKILLS}}` | Available Skills list (one per line) |
| `{{AICODE_SUBAGENTS}}` | Available sub-agents list (one per line) |
| `{{AICODE_MEMORY_GLOBAL}}` | Global memory list (one per line) |
| `{{AICODE_MEMORY_PROJECT}}` | Project memory list (one per line) |
| `{{AICODE_PROJECT_RULES}}` | Project rules body (`AGENTS.md` / `CLAUDE.md`) |
| `{{AICODE_WORKSPACE}}` | Project root directory (`~/workspace`; a prompt text when no workspace is selected) |
| `{{AICODE_ENVIRONMENT}}` | Current runtime environment (local container (PRoot) / remote SSH server) |
| `{{AICODE_DATE}}` | Current date (`yyyy-MM-dd`) |
| `{{AICODE_SUBAGENT_NAME}}` | Current sub-agent name (only has a value when rendering sub-agent prompts) |
:::

::: tip Variables expand in place in any fragment
The Skills, sub-agents, memory, project rules, workspace, and current time in the system prompt are carried by the built-in fragment `99-runtime-context.md` using these variables by default; you can also write variables in your own fragments, and they'll likewise be replaced with real content at that spot. To change the position or order of this content, edit or override `99-runtime-context.md`.
Variables are only replaced with **pure data** (list rows, file bodies, paths, dates); headings and lead-in text like "Available Skills:" are written in the fragment file and edited along with the fragment.
:::

When there's no override of `99-runtime-context.md` in `prompts.custom/`, the runtime context is injected by the built-in version as usual; if you override it but write no variables, the corresponding content won't appear.

Other placeholders (such as the <span v-pre>`{{INSTRUCTION}}`</span> used by the compaction prompt) are unaffected.

## Behavior on Upgrade

- The `prompts/` directory is fully overwritten by the built-in version on every startup, so the default prompts update automatically with app upgrades.
- The App never writes to or deletes the `prompts.custom/` directory automatically, so your customizations are preserved completely after upgrades.

::: danger Do not modify the prompts/ directory directly
The content of the `prompts/` directory is fully reset on every startup; manually modified content is overwritten on app upgrade or restart. For persistent customization, be sure to keep it in the `prompts.custom/` directory.
:::

## Fragment Descriptions

| File name | Content |
| --- | --- |
| `00-identity.md` | AI identity and role definition |
| `10-communication.md` | Communication and reply style |
| `15-project-rules.md` | Project rules loading convention (AGENTS.md / CLAUDE.md) — see [Memory & Project Rules](/guide/memory) |
| `20-coding-discipline.md` | Coding discipline |
| `30-comments.md` | Code comment standards |
| `40-approach.md` | Working method and workflow |
| `50-safety.md` | Safety and trust boundaries |
| `60-tools-and-paths.md` | Tool descriptions and path conventions |
| `70-skills-and-mcp.md` | Skills and MCP descriptions |
| `99-runtime-context.md` | Runtime context: Skills, sub-agents, memory, project rules, workspace, and current time (carried via variables) |
| `agent/plan-mode.md` | PLAN mode reminder (injected on demand) |
| `agent/auto-mode.md` | AUTO mode reminder (injected on demand) |
| `agent/subagent-base.md` | Sub-agent basic runtime rules (injected on demand) |
| `agent/subagent-context.md` | Sub-agent runtime context: identity, workspace, and time (injected on demand) |
| `agent/compact-summary.md` | Prompt for long conversation context compaction |
| `agent/title-generator.md` | Prompt for session title generation |
| `agent/init.md` | Instruction body of the `/init` command (analyze the codebase and generate/improve `AGENTS.md`), triggerable with `/init` in the chat input box |

## Restoring Defaults

Delete the corresponding file under `~/.aicode/prompts.custom/`, and that fragment returns to the default version; delete the whole `prompts.custom/` directory to restore everything (once `.no-builtin` is deleted along with it, the built-in prompts are re-enabled).

## Notes

- Older versions placed `80-plan-mode.md`, `81-auto-mode.md`, `90-subagent-base.md` at the top level of `prompts/`; **from v1.12.0** they've moved to `prompts/agent/` with numbers removed; corresponding custom overrides should switch to paths like `prompts.custom/agent/plan-mode.md`.
- Fragments like `60-tools-and-paths.md` update as tools change. If you override one, after an app upgrade you won't automatically get the new version's tool descriptions, and the tool definitions the AI sees may not match reality. When you need an update, sync manually, or delete your custom version to let the default version take effect again.
