# Skills

Skills are "specialized capability extension packs" for the AI — they distill the standard workflow, best practices, and dedicated knowledge for a class of tasks into a normative guide, so when the AI encounters a related task it automatically loads and executes it on demand, without you having to repeat the background rules in every conversation.

The entry point is "Settings → Skills". Enabled Skills appear in the `/` menu of the chat input box, and can be triggered directly with `/skill-name`, optionally with arguments (e.g. `/pdf-report a4`) — see the 〈Slash Commands〉 section of [Chat & Messages](./chat.md).

## Where Skills Come From

A Skill is a directory containing a `SKILL.md` (or a compatible `CLAUDE.md`), which is recognized automatically. Two levels:

- **Global Skills**: placed in `~/.aicode/skills/`, shared across projects, and not lost on app upgrades. They follow the execution environment: in local mode it's the in-container `/root/.aicode/skills/` (mapped to the app's private directory on the phone); in remote mode it refers to the **server's** `~/.aicode/skills/`, read/written over SSH.
- **Project-level Skills**: placed in `<workspace>/.aicode/skills/`, effective only in the current workspace, and can be committed to git with the project. In remote mode this refers to the workspace on the **server**, read/written over SSH.

For Skills with the same name, project level takes precedence.

You can create Skills directly in the app (see below), manually create Skill directories in the container, or have the AI install them from GitHub. In local mode the app watches the Skills directory, so manually adding, deleting, or renaming refreshes the list automatically within a few seconds, with no need to re-enter the page. In remote mode it doesn't watch file changes on the server; after manual modifications you need to exit and re-enter the settings page to refresh the list.

## List Page

Shown in two collapsible groups, "Current Project / Global"; tap a group title to expand or collapse it.

Each row is a Skill: icon, name, description summary, and a colored badge to the right of the name indicating enabled or disabled state. Tap a row to enter details; swipe left to reveal the delete button — deletion pops up a confirmation dialog, and it directly removes the Skill directory, **irreversibly**.

The "+" at the top right adds a Skill (see 〈Add a Skill〉 below).

## Adding a Skill

Tap the "+" at the top right to pop up a bottom selection sheet; first choose the **scope** (global / current project, default global), then choose the add method:

- **Create manually**: enter the edit page and fill in the name, description, and body to create a Skill from scratch.
- **Import from file**: choose an `.md` file (`.markdown` / `.txt` also work). The app reads the file content and builds the Skill from its frontmatter `name`/`description`; if `name` is missing, the file name is used as a fallback. It's written to `<scope>/skills/<name>/SKILL.md`.
- **Import from archive**: choose a `.zip`. After extracting, the app identifies all directories in the archive containing a `SKILL.md` (or `CLAUDE.md`) and imports them one by one — one archive can contain multiple Skills, which is common for packaged downloads of GitHub repositories.

Importing is equivalent to creating manually: files are always normalized to `SKILL.md` on disk, and afterwards can likewise be modified with the editor or container commands.

**Cases that get skipped** (a dialog lists the reasons one by one, without affecting the other Skills):

- The Skill name is invalid (contains characters that can't be in a directory name such as `/` or `:`, is empty, or is too long);
- A Skill with the same name already exists in the target scope (not overwritten);
- The Skill body is empty;
- The archive contains no directory with a `SKILL.md` at all (the whole import fails).

## Details Page

Three cards: "Enabled" (name, source, and the enable/disable switch), "Summary" (description), and "Body" (the complete content of `SKILL.md`, in monospace font, selectable and copyable).

The pencil button at the top right enters the edit page.

## Creating & Editing

The edit page has two groups of content:

- **Basic info**: name, description, scope. The name is the identifier the AI uses when loading the Skill; English lowercase with hyphens is recommended (e.g. `pdf-report`); the description should state "when it should be used", and the AI judges whether to load it based on that sentence; the scope, "Current Project" or "Global", decides which level the Skill directory lands at.
- **Instruction body**: the Skill's complete instructions, supporting Markdown. Write clearly the standard workflow, constraints, and notes for this class of tasks.

Only after both the name and body are filled in can you tap "Save" at the top right. After saving, it's written as `<skill directory>/SKILL.md`, fully equivalent to a hand-written Skill, and can likewise be modified afterwards with a text editor or container commands.

A few points to note:

- **The scope can only be chosen when creating**. An existing Skill's scope is grayed out — changing scope means relocating the directory, which you need to do yourself in the container.
- **Renaming only changes the `name` in `SKILL.md`, not the directory name**. Skill bodies often reference scripts in the same directory as `~/.aicode/skills/<directory>/run.py`; renaming the directory along with it would break those references.
- When editing a Skill installed from GitHub, if it uses `CLAUDE.md` instead of `SKILL.md`, saving writes back to that original file and doesn't create an extra one.
- If a Skill with the same name already exists in the same scope, the name contains characters like `/` or `:` that can't be in a directory name, or the body is empty, saving fails and a red prompt appears at the top of the page.

## Enabling & Disabling

The switch on the details page controls whether the Skill participates in AI sessions. Once disabled, it no longer appears in the available Skills list in the system prompt, and the AI can't load it, which reduces context usage — this switch is very useful when you have many Skills.

The disabled list is written in `skills.json`: the global list is located at `~/.aicode/skills.json` of the current execution environment — in local mode it's stored in the app's private directory on the phone; in remote mode it's stored on the server; the two are independent and don't migrate or sync automatically. In local mode the project-level list is at `<workspace>/.aicode/skills.json`; in remote mode it's still stored in the app's private directory on the phone, isolated by server and workspace. The format is:

```json
{ "disabled": ["skill-name"] }
```

The two levels are unioned — disabling in either scope takes effect. You can also edit the list manually; it takes effect on the next read. In local mode the list refreshes automatically within a few seconds; in remote mode, after manually modifying the list on the server you need to exit and re-enter the settings page to refresh the list.
