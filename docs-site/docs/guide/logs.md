# Logs & Troubleshooting

When something goes wrong with the app, logs are the most direct clue. "Settings → Logs" lets you view them right inside the app.

## Viewing Logs in the App

On entry, it positions at today's log by default. On the page you can:

- **Log level**: the settings row at the top shows the current level; tap to choose from a bottom sheet (Verbose / Debug / Info / Warn / Error / None). When hitting a bug, it's recommended to set it to `Debug`; keep `Info` for everyday use. Release builds default to `Info`, filtering out debug noise.
- **Switch files**: tap the "Log File" row to pop up a date list from the bottom; today is selected by default.
- **Search & copy**: keywords filter in real time; one tap at the top right copies the current filtered results.
- **Color coding**: Verbose gray, Debug blue, Info green, Warn orange, Error red.
- **Copy a single line**: tap any line to copy that complete log entry (including time, level, and source); long-press to open a menu to choose the copy range — this entry, this entry plus stack trace, or just the message content.
- **Live scrolling**: on by default, auto-refreshes every 3 seconds and follows the latest; turn it off to stop refreshing and keep your manual browsing position.

To avoid lag from large logs, the app only shows the last portion of the current file under the filter conditions; see the complete file in the file system.

## Where the Log Files Are

```
/storage/emulated/0/Android/data/com.aicode/files/logs/
```

No root permission needed — just open it with the phone's built-in file manager.

Workspace project files and AI configuration are stored in the app's private directory, invisible to ordinary file managers; for how to access them, see [File Browsing & Code Editing](/guide/files#where-workspace-files-live-on-the-phone).

## Filtering by MCP Name

When troubleshooting an MCP connection problem, go to "Settings → MCP Servers", edit a server, and tap the log button at the top right — it jumps to the log page automatically filtered by that server's name (there's a filter badge at the top of the page, clearable with one tap). The log button at the top right of the list page shows all logs.

## How Crashes Are Handled

When the app hits an uncaught exception, it **doesn't crash directly**; instead it shows a full-screen crash error page:

- The page shows the version, device, time, and full stack trace at the moment of the crash.
- Tap "Copy error info" to copy the complete crash report (including the stack trace and log file path); paste it directly to the developer — no need to dig through logs yourself.
- You can choose "Restart app" to return to the main interface, or "Exit app" to quit completely.

Crash logs are also written to the `logs/` directory above.

## What to Do When Long Tasks Get Interrupted in the Background

If the AI executing a long task (such as a build or deep code review) or a terminal running a time-consuming command is often killed when you switch to the background, go to "Settings → App Permissions" and enable **background run keep-alive**, **ignore battery optimization**, and **auto-start management**. See [App Permissions](/guide/app-permissions) for details.
