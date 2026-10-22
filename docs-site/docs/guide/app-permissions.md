# App Permissions

"Settings → App Permissions" centrally manages the system-level permissions the app needs. Permission states refresh automatically when the page resumes (including when you return from the system settings page), and tapping an already-granted item does nothing.

The three switches for background keep-alive, keep screen on, and AI completion notifications are on the [Background Run](/guide/background-run) page.

## Install Unknown Apps

Shows whether this is currently allowed. If not allowed, tapping this row jumps to the "Install unknown apps" switch page for this app in system settings; enable it to install APKs.

## Access Storage

Shows whether this is currently granted. If not granted, tapping pops up the system permission request dialog, used for reading/writing workspace files and container images.

If the permission has been permanently denied (the system no longer shows the dialog), a dialog will prompt you to tap "Go to Settings" and enable it manually.

## Ignore Battery Optimization

Shows whether the app is currently exempt. If not allowed, tapping jumps to the system's battery optimization request page to exempt this app from power-saving policies, reducing the chance of the background process being killed.

## Auto-Start Management

Tapping jumps to your device manufacturer's auto-start management settings page (Xiaomi, Huawei, OPPO, vivo, Meizu, etc.), allowing the app to launch automatically at boot or after being terminated by the system. If the manufacturer's settings page can't be opened or no manufacturer matches, it falls back to this app's details page.

Used together with "background run keep-alive" and "ignore battery optimization", this further reduces the chance of being terminated in the background — Chinese OEM ROMs restrict background apps quite aggressively, so if long tasks are frequently interrupted, it's recommended to enable all three.

## Shizuku Authorization

Shizuku lets this app execute system commands with the adb shell identity (uid 2000), and is a prerequisite for the AI's `Shizuku` tool. This row shows the current state:

- **Not installed / service not running**: tap to open the Shizuku app and follow its instructions to install or start the service.
- **Not authorized**: tap to request authorization, and choose "Allow" in the authorization dialog Shizuku pops up.
- **Ready**: authorization is complete, and the AI can call the `Shizuku` tool.

The state refreshes automatically when you return to this page from the Shizuku app. For usage and safety notes, see the [Shizuku Tool](/guide/shizuku).
