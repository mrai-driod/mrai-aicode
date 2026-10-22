# Memory & Project Rules

This chapter introduces two capabilities — automatic memory and project rules — used to maintain the AI's cross-session context continuity and project consistency.

## Automatic Memory

Automatic memory is a long-term knowledge base the AI maintains itself. Project conventions, user preferences, and architecture decisions formed during conversation are automatically recorded by the AI and reused in later sessions.

### Scope

| Scope | Description | Storage location |
| --- | --- | --- |
| Global | Personal preferences common across projects | `~/.aicode/memory/` |
| Project | Conventions & experience specific to the current project | Local mode: `<project root>/.aicode/memory/`; Remote SSH mode: `~/.aicode/memory/projects/<project name>-<identity hash>/` |

In remote mode, project memory is stored locally on the phone, isolated by "project name + server identity", and is unaffected by container resets.

### Memory Files

Each memory is a Markdown file composed of YAML frontmatter (`name`, `description`) and a body:

```markdown
---
name: conventions
description: Project code standards and naming conventions
---
(body)
```

### How It Takes Effect

At the start of a session, the system injects summaries of all memories (name and description) into the prompt; when details are needed, the AI reads the full content through the `memory` tool.

### The memory Tool

| Parameter | Description |
| --- | --- |
| `action` | `read` / `save` / `edit` / `delete` / `list` |
| `name` | Memory name (i.e. the file name) |
| `description` | Summary, required for `save` |
| `content` | Body (Markdown), required for `save` |
| `edits` | List of partial edits, same semantics as editing a file |
| `scope` | `project` (default) or `global` |

For memories with the same name, project level takes precedence over global. When updating an existing memory, using `edit` for partial edits is recommended; content involving hard-learned lessons should be recorded after the root cause is verified.

## Project Rules

The `AGENTS.md` in the project root directory declares project-specific rules, architecture constraints, and build guides; it is the highest-priority convention the AI follows. When `AGENTS.md` doesn't exist, it falls back to reading `CLAUDE.md`.

### How It Takes Effect

Rule files are loaded automatically at the start of a session, with no manual configuration needed; content over 32,000 characters is truncated.

### Custom Loading Convention

The loading convention for project rules corresponds to the prompt fragment `15-project-rules.md`, which can be overridden via `~/.aicode/prompts.custom/15-project-rules.md` — see [Custom Prompts](/guide/custom-prompts).
