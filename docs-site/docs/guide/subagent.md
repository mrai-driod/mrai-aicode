# Sub-Agents

::: tip Version note
Sub-agents are available from 1.11.0.
:::

A sub-agent is an independent work session spawned by the main session. When you face complex tasks such as time-consuming research, parallel review of multiple modules, or architecture comparison, you can have the AI dispatch sub-agents to handle them in parallel, **without blocking the current main conversation**.

A sub-agent has its own independent context and tool capabilities. After dispatch, it works independently in the background; you can stay in the main session and keep discussing, or tap into the sub-session at any time to view real-time progress, add questions, or pause it manually.

There are two kinds of sub-agents:
- **Default general sub-agent**: automatically inherits the parent session's model configuration and all available tools;
- **Custom sub-agent**: freely specify the model, tool whitelist/blacklist, and a dedicated prompt.

## Usage

Just ask the AI in the main session to dispatch, for example:

- "Dispatch a sub-agent to review `app/build.gradle.kts` and list the potential problems"
- "Dispatch two sub-agents at the same time, one to research the database migration plan, one to research UI component library selection"
- "Dispatch a read-only research sub-agent to figure out the current login and authentication flow"

Before dispatching, the AI pops up a dialog asking for your authorization; after you agree, the sub-agent starts in the background, and the main session immediately returns to idle and can receive your other instructions.

## Position & Management in the Sidebar

The session list in the sidebar clearly shows the parent-child relationship between main sessions and sub-agents:

- **Expand & collapse**: a session that has spawned sub-agents shows the sub-agent count and an expand/collapse arrow at the end of the row. Tap the arrow to expand and view all its sub-agents;
- **Status indicator**: a running sub-agent shows a green running status light;
- **Independent interaction**: tap a sub-agent to switch into its session page, with an interface identical to a regular session — you can converse independently, pause, or regenerate;
- **Session operations**: long-press a sub-agent item to rename, export, or delete it; when the main session is deleted, all its sub-agents are cleaned up along with it.

## Context & Information Passing

::: tip Independent context with two-way sync
A sub-agent's conversation context is completely isolated from the main session; by default it doesn't automatically load the main session's conversation history, keeping it lightweight and focused:

- **Task dispatch**: when dispatching a sub-agent, the AI automatically distills the current task's clear goal, key file paths, verified background, and constraints, packaging them as the initial instructions for the sub-agent. If you manually instruct a dispatch in the main session, it's also recommended to state the key background and expected output clearly;
- **Result summary**: when the sub-agent finishes executing, the main session automatically receives a completion notification and reads its final output to summarize for you; if the main session is calling other tools at the time, a hint below the card shows the sub-agent is ready, and it's followed up automatically after the current operation;
- **Full-process traceability**: the main session usually only reads the sub-agent's final conclusion; if you want to view the sub-agent's complete tool call logs, reasoning traces, or intermediate output, just tap into that sub-session in the sidebar to see all the details.
:::

## Two-Way Communication with the Main Session

Two-way asynchronous communication is supported between a sub-agent and the main session, without blocking each other:

- **Main session → sub-agent**: the main session can append messages to a sub-agent at any time (supplementary instructions, corrections, or follow-ups on existing results), and **can send repeatedly**. A running sub-agent receives the message in its next round of operation; a completed or paused sub-agent is reawakened and continues working;
- **Sub-agent → main session**: while running, a sub-agent can proactively report key progress to the main session, ask for help, or submit matters needing coordination or decisions. The main session receives a system notification and can reply directly.

These messages are presented in the UI as lightweight hint bars, not occupying ordinary chat bubbles; when the main session is busy, messages are followed up automatically after the current operation ends, and when idle, they trigger a new round immediately.

## Automatic Dispatch & Manual Specification

The main session AI knows all currently available sub-agents and their areas of expertise:

- **Automatic matching**: when your need matches a sub-agent's purpose description (`description`), the AI proactively selects that dedicated sub-agent;
- **Manual specification**: you can also specify explicitly in your instruction, e.g. "Use the Explore sub-agent to search for the entry points of all network requests".

## Built-in Sub-Agent: Explore

On first startup, the system automatically releases the built-in **Explore** read-only exploration sub-agent in the global directory (located at `~/.aicode/agents/explore.md`), ready to use out of the box.

Its core purpose is read-only codebase exploration and logic mapping:
- **Pure read-only safety**: only code and file search tools are open (`readFile`, `list`, `search`, `websearch`, `webfetch`, `loadSkill`), **including no file-writing or terminal command execution tools at all**, so it won't make changes to the project, and doesn't need frequent authorization popups;
- **Model customization**: inherits the current session's model by default. To configure a more economical model for it, just fill in the corresponding `provider` and `model` in `explore.md`;
- **Persistent retention**: this file is only released when it doesn't exist for the first time; your personalized modifications are preserved completely across app upgrades.

## Custom Sub-Agents

There are two ways to create a custom sub-agent, with completely equivalent effect (what the app saves is the same `.md` file):

- **Create in the app** (recommended): the "+" at the top right of "Settings → Sub-Agents" — fill in the form; the model can be selected directly from configured providers;
- **Write the definition file by hand**: put a Markdown file into the corresponding directory yourself, suitable for taking ready-made definitions from other projects or managing with git.

Two optional directories:

- **Global configuration**: `~/.aicode/agents/<name>.md`, shared by all projects, not lost on upgrade. Follows the execution environment: in local mode it's the in-container `/root/.aicode/agents/` (mapped to the app's private directory on the phone); in remote mode it refers to the **server's** `~/.aicode/agents/`, read/written over SSH;
- **Project-level configuration**: `<project workspace>/.aicode/agents/<name>.md`, effective only for the current workspace, and can be committed to version control with the codebase. In remote mode this refers to the workspace on the **server**, read/written over SSH.

For definitions with the same name, project level takes precedence. A file consists of YAML configuration (frontmatter) at the top and the body prompt:

```markdown
---
name: researcher
description: Read-only code and technical research: search code widely, consult online materials, and summarize conclusions, without modifying files
provider: DeepSeek
model: deepseek-reasoner
reasoningEffort: high
mode: plan
tools: [readFile, list, search, websearch, webfetch]
disallowedTools: [Bash, terminal]
inject: [base, projectRules]
---
You are a senior code research expert. First use search to locate entry points, then use readFile to verify in depth.
When outputting conclusions, be sure to mark accurate `file path:line number` citations; unverified inferences must be noted as such honestly.
```

### Configuration Field Reference

| Field | Required | Description |
| --- | --- | --- |
| `name` | No | Sub-agent identifier name; the file name is used when omitted |
| `description` | Recommended | Briefly describes the applicable scenarios and expertise; the main session AI judges when to select this sub-agent based on this description |
| `provider` | No | AI provider name; inherits the parent session's configuration when omitted |
| `model` | No | Model name; inherits the parent session's configuration when omitted |
| `reasoningEffort` | No | Thinking intensity: `none` / `minimal` / `low` / `medium` / `high` / `xhigh` / `max` |
| `mode` | No | Default run mode: `build` / `plan` / `auto`; inherits the parent session's current mode when omitted |
| `tools` | No | Tool whitelist. **When omitted, all available tools are open** |
| `disallowedTools` | No | Tool blacklist, taking precedence over the whitelist, supporting `mcp__*` prefix wildcards |
| `inject` | No | Injected system rule fragments — see the table below |

### inject Rule Fragments

| Value | Meaning |
| --- | --- |
| `base` | Sub-agent dedicated lightweight baseline: includes tool usage rules and path conventions; lightweight and token-saving |
| `mainRules` | Complete general rules: a behavior standard and rule baseline completely consistent with the main session |
| `skills` | Skills support: injects the available Skills list, allowing the sub-agent to load and execute project Skills |
| `memory` | Memory support: injects global and project long-term memory summaries — see [Memory & Project Rules](/guide/memory) |
| `projectRules` | Project rules: injects the workspace's `AGENTS.md` or `CLAUDE.md` constraints — see [Memory & Project Rules](/guide/memory) |

`base` and `mainRules` are the abridged and complete versions of the same rule set; **normally choose only one**. Enabling both at the same time doesn't error, but injects overlapping rule text twice, needlessly increasing context usage.

If the `inject` field is omitted, it defaults to `[base, skills, memory, projectRules]`. If you fill in `inject: [none]`, only the prompt you wrote in the Markdown body is used.

## Managing in Settings

Enter "Settings → Sub-Agents" to browse all recognized sub-agents grouped by "Current Project / Global".

- **Create**: tap the "+" at the top right. In the form you can fill in the name and purpose description, choose the scope (current project / global), **select the model and thinking intensity** from configured providers, check the tool whitelist and blacklist, toggle the prompt fragments to inject, and finally write the agent prompt body;
- **Edit**: tap into a sub-agent's details page, then tap the pencil icon at the top right. Modifying the name also renames its definition file;
- **Enable / Disable**: the switch at the top of the details page. Once disabled, it no longer appears in the main session AI's dispatchable list, and the AI can't dispatch it by name, but the definition file is kept, and sub-sessions already running are unaffected;
- **Delete**: swipe an entry left in the list; this deletes the corresponding `.md` definition file, irreversibly.

The enable/disable state is stored in `agents.json`, using the same convention as the Skills disable list: the global list is at `~/.aicode/agents.json` of the current execution environment — in local mode stored in the app's private directory on the phone, in remote mode stored on the server; the two are independent and don't migrate or sync automatically. In local mode the project-level list is at `<workspace>/.aicode/agents.json`; in remote mode it's still stored in the app's private directory on the phone, isolated by server and workspace. The two levels are unioned — disabling in either scope takes effect.

After toggling the enable/disable state in the app, the list refreshes. In local mode, manually modifying definitions or the list refreshes automatically within a few seconds; in remote mode it doesn't watch file changes on the server, and after manual modification you need to exit and re-enter the settings page to refresh the list.

## Runtime Mechanics & Limitations

- **Concurrency limit**: the system supports at most **5 sub-agents running simultaneously**; beyond that you need to wait for existing tasks to complete or pause them manually;
- **Anti-nesting**: a sub-agent cannot spawn a next level of sub-agents;
- **Two-way communication**: the main session can append messages to a running or completed sub-agent at any time (repeatable), and a running sub-agent can also proactively report to or ask the main session for help; messages are delivered asynchronously without blocking each other.
- **Default mode**: a sub-agent inherits the main session's mode at the time of dispatch by default. A sub-agent dispatched in plan (PLAN) mode can likewise only explore read-only; in auto (AUTO) mode it also no longer pops up dialogs one by one, like the main session. A custom sub-agent can override this default with the `mode` field — e.g. fix a read-only sub-agent to `mode: plan` so it stays read-only whenever it's dispatched;
- **Authorization control**: sub-agents follow the same tool authorization safety mechanism as the main session. If a sub-agent attempts to execute a file write or system command, it likewise triggers an authorization confirmation dialog (the dialog clearly marks which sub-agent it comes from);
- **Your manual stop is also reported to the main session**: when you tap stop in a sub-session, or directly delete a running sub-agent, the main session receives a "manually terminated" notification (rather than an execution failure), the AI won't re-dispatch on its own, and the concurrency slot is released immediately;
- **Lifecycle**: when a parent session is deleted, all its sub-agent sessions are cascade-cleaned along with it;
- **When definition changes take effect**: after adding, renaming, or modifying a sub-agent definition, sessions already open still use the list from when they started; **open a new session** for the AI to see the changes.
