# Shizuku Tool

Shizuku lets ordinary apps execute commands with the adb shell (uid 2000) identity **without root**. After AiCode integrates it, the AI gets a tool named `Shizuku`, which can run `pm`, `am`, `cmd` and other commands directly on the phone system and read/write `/sdcard`, making up for the local container (PRoot) not being able to obtain system permissions.

Shizuku has the shell identity and **cannot read other apps' private data directories** (that's something only root can do).

## Prerequisite: Install & Start Shizuku

1. Install Shizuku from the [Shizuku official website](https://shizuku.rikka.app/download/) or an app store.
2. Open Shizuku and follow the in-app instructions to start the service:
   - Android 11 and above: enable "Wireless debugging" and you can start it directly on the phone;
   - Android 10 and below: you need to connect to a computer and start it with adb.

## Authorize in AiCode

Open "Settings → App Permissions → Shizuku"; the page shows the current state:

- **Not installed**: tap to go download and install.
- **Service not running**: tap to open Shizuku and start the service.
- **Not authorized**: tap to request authorization; Shizuku pops up an authorization dialog — choose "Allow".
- **Ready**: authorization is complete, and the AI can use the `Shizuku` tool.

When you return to AiCode from the Shizuku app, the state refreshes automatically.

## Usage & Safety

`Shizuku` operates the host Android system directly with the adb shell identity — high permissions, high risk — so in tool authorization it's treated as a **high-risk tool**:

- **Every call pops up an authorization confirmation**: including read-only commands like `ls`; it doesn't go through the built-in safety whitelist, and won't be let through automatically because of an existing rule.
- **"Always allow" is not supported**: the "Always allow" button in the dialog is unavailable (the reason is shown); only one-time allowances, generating no memory rules.
- **It pops up in AUTO (auto) mode too**: AUTO's "allow everything" doesn't apply to it; only when "Settings → Tool Authorization → Disable safety interception" is enabled does AUTO restore automatic allowance (BUILD mode still confirms each time and cannot remember).
- **In PLAN (plan) mode**, this tool is intercepted like other write operations.
- **Deny rules** in project permission rules still apply to it (hitting `deny` is rejected directly).

## FAQ

**The command reports "Shizuku not ready"**

Confirm in order as prompted on the settings page: installed → service started → authorized. After a device restart, the Shizuku service usually needs to be started again (on Android 11+, you can re-enable wireless debugging startup within Shizuku).

**The `Shizuku` tool fails to execute but shows no obvious error**

Some commands need specific system permissions that the shell identity doesn't have (such as accessing other apps' private directories). You can compare with `Bash` in the terminal to confirm whether it's the command itself or a permission problem.
