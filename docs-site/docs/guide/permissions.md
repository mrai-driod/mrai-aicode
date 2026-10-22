# Tool Authorization

Before the AI executes commands or writes files, it needs your authorization. This page manages the authorization rules that have been saved; the entry point is "Settings → Tool Authorization".

## Where Rules Come From

When the AI requests a tool call in conversation, an authorization dialog pops up, and you have two choices:

- **Always allow**: remembers an allow rule in the **current project**; future calls matching the rule are let through automatically. Rules are exact matches (e.g. `git pull`, `rm temp.log`). If a command contains pipes, redirection, or command substitution, it can't be remembered and can only be allowed once.
- **Deny**: denies just this once, producing no rule.

Additionally, the system has a built-in whitelist: a small number of completely harmless, read-only commands that don't spawn subprocesses (`ls`, `pwd`, `cat`, `grep`, `ps`, `top`, `git status`, etc.) are let through without a dialog.

Exception: the `Shizuku` tool (operating the host Android system with the adb shell identity) is high-risk — any command pops up a confirmation, "Always allow" is not supported, and the built-in whitelist doesn't apply. See the [Shizuku Tool](/guide/shizuku).

## Authorization with Multiple Sessions in Parallel

When multiple sessions (including sub-agents) work simultaneously, each session can have its own pending authorization request without blocking each other:

- The top of the authorization dialog marks **the title of the session that initiated the request** (e.g. "Session 'Refactor the login module'"), so you can clearly tell which session a dialog belongs to.
- The dialog is shown in any session's interface; after handling one, it automatically switches to the next pending authorization request.
- In the sidebar session list, a session **waiting for authorization** has an **orange steady** indicator light on the left of its row (executing is a green pulse). This lets you spot background sessions suspended waiting for authorization and tap in to handle them.

## Page Structure

Rules are divided into two groups, "Current Project" and "Global"; tap a title to collapse it.

Each rule shows a verdict icon (a check mark means allow, a cross means deny), the tool name, the match scope, and the verdict — e.g. `git pull · Allowed`, `Whole tool · Allowed`.

- The plus icon on the right of a project rule can **promote the rule to global**, effective for all projects; after promotion it disappears from this project's list.
- Swipe a rule row left to reveal the red delete button.

## Disable Safety Interception

At the top of the page there's a "Disable safety interception" switch, off by default.

- Off (default): in AUTO (auto) mode, catastrophic deletions are still intercepted — operations like `rm` deleting the root directory, critical system directories (`/etc`, `/usr`, etc.), the workspace root, or the user's home directory are rejected outright; commands containing command substitution, sub-shells, absolute-path redirection, and other things that can't be determined statically and are suspected destructive (such as `rm -rf $(...)`, `cat > /etc/xxx`) are also blocked (the deletion target can't be confirmed). At the same time, in AUTO the `Shizuku` tool still pops up a confirmation for each call (not exempted by "allow everything"). This is the bottom line against accidental deletion.
- On: in AUTO mode even this layer of interception is removed — all commands are allowed, and the `Shizuku` tool no longer pops up. Note that "Shizuku cannot be remembered" is unaffected by this switch: in BUILD mode it still confirms each time and cannot be remembered. Safety interception in BUILD / PLAN modes is unaffected.

Before turning the switch on, a confirmation dialog pops up with a **5-second countdown** on the confirm button, which can't be tapped until the countdown ends, to avoid accidental touches.

::: danger Only enable when truly necessary
Once enabled, destructive commands like `rm` in AUTO mode have no fallback protection at all. Only turn it on when you fully understand the risks and truly need it.
:::

## Elevating High-Risk Commands

In non-PLAN modes, catastrophic operations — `rm` deleting the root directory, critical system directories (`/etc`, `/usr`, etc.), the workspace root, or the user's home directory — are rejected directly by the built-in safety guard. If the AI judges that execution is truly necessary, it retries the call with `elevate: true` — at that point it's no longer rejected outright, but a dialog asks you for **one-time authorization** (the dialog title is "High-Risk Operation Elevation Confirmation").

- Available in both BUILD and AUTO modes; in AUTO there are two scenarios that pop up a dialog: catastrophic deletion elevation confirmation, and `Shizuku` tool calls.
- Elevation is only valid for a single call and generates no memory rule ("Always allow" is unavailable).
- PLAN mode remains a hard interception, unaffected by elevation.

## About "Deny" Rules

There is no "Deny" option in the authorization dialog (denying only affects a single call), so normal use produces no deny rules.

Deny rules currently can only be produced by manually editing the configuration file: project rules are written in `<project root>/.aicode/permissions.json`, global rules in `~/.aicode/permissions.json`. After you manually edit and save, the app automatically detects it within a few seconds and refreshes the page; the rules take effect immediately.

Configuration file format:

```json
{
  "permissions": {
    "allow": ["Bash(git pull)", "writeFile"],
    "deny": ["Bash(rm -rf /)"]
  }
}
```

::: tip Global deny takes precedence over project allow
Rules in `deny` block the `allow` rules of the same name. For example, if `Bash(rm -rf /)` is globally denied, no project can allow that command. If you need an extra safety fallback, add rules to the global `deny`.
:::
