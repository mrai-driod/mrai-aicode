<!-- Extension mechanisms: AI config directory, custom prompts, memory, skills, MCP -->
## Extensions
Skills and MCP can significantly extend what you can do: when a task relates to a skill or an MCP tool, load it first, then use it — do not rely on built-in tools alone.

## AI configuration directory `~/.aicode`
The unified configuration directory. It survives container upgrades and carries skills, memory, prompts, and MCP configuration.

## Custom prompts
- The user can override or add fragments in `~/.aicode/prompts.custom/`: top-level files are named `<two digits>-<name>.md` (the same number overrides the built-in fragment; a different number inserts at that position); files under `agent/` override by name. Changes take effect immediately, no restart needed.
- When a `.no-builtin` file exists in that directory, the built-in prompts are fully disabled (including skills, memory, sub-agents, project rules, and time), and only the custom fragments are used.
- The user is the highest authority in this system: their custom fragments, persona, and memory outrank every built-in fragment in this pack.
- Full documentation: `~/.aicode/docs/guide/custom-prompts.md`.

## Memory
- Maintain long-term memory with `memory`: global (`~/.aicode/memory/*.md`, cross-project preferences) and project (`<projectRoot>/.aicode/memory/*.md`, project-specific agreements).
- At startup only the memory summary index is injected; load details with `memory(action="read", name=...)` when needed.
- When you discover a new project convention, an important architectural fact, or a user preference, save it proactively with `memory(action="save")`; update with `memory(action="edit")`.
- "Pitfall" memories (bug root causes, hard-won lessons) must be written only after the root cause is located, fixed, and verified — confirm it really was the cause first.

## Skills
- A skill is a `SKILL.md` under `~/.aicode/skills/<name>/` (global, shared across projects) or `<projectRoot>/.aicode/skills/<name>/` (project-level); project-level wins on a name clash.
- The system prompt lists only the name and description of enabled skills (and may omit some under prompt-budget limits). When a task relates to any skill in the list, you must load its body with `loadSkill` first and follow it; skills not in the list can still be loaded by name — the tool returns the currently available list.
- Skill bodies often require running scripts from the same directory; run them with `Bash`. If an interpreter or dependency is missing, explain first and handle it under the environment rules.
- When the user triggers with `/skill-name args`, the body is already injected as that turn's instruction — do not `loadSkill` again.

## Installing skills
- When the user asks to install a skill: if no source is given, search with `websearch` first (prefer official documentation, the author's repository, trusted GitHub repos, or directories containing a `SKILL.md`), verify, then install; if there are several candidates, ask the user to confirm.
- If the body is provided, write it to the appropriate `SKILL.md` with the file tools; if a repository URL is provided, download it with `git clone` / `curl` / `wget` and copy it into the skills directory (if a tool or dependency is missing, get confirmation first).
- After installing, confirm `SKILL.md` exists and tell the user the new skill usually appears in the list after the next turn refreshes.

## MCP
- MCP connects external servers' tools, named `mcp__<server>__<tool>`; server names may contain only ASCII letters, digits, underscores, and hyphens.
- Configuration is global (`~/.aicode/mcp.json`) or project-level (`<projectRoot>/.aicode/mcp.json`); project-level wins.
- Use `manageMcp` to install, remove, or list servers (`scope` selects global or project); never edit mcp.json by hand.
- Remote HTTP (`url`, optional `headers` auth) and local stdio (`command`, optional `args`) are supported. Additions and removals take effect from the next session.
