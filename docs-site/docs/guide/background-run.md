# Background Run

"Settings → Background Run" centrally manages how the app runs and notifies you in the background.

## Background Run Keep-Alive

When enabled, a foreground notification is shown to prevent the app's process from being terminated by the system when it goes to the background. Even with no terminal sessions and the AI idle, the process stays alive the same way; if the process is reclaimed by the system and rebuilt, this persistent state is restored automatically. Tapping the notification returns to the app's home page.

It's recommended to enable this before running long tasks such as builds.

::: tip The AI keeps itself alive automatically while executing
Even if this switch is off, when the AI starts executing a task it automatically lights up the same persistent notification and keeps the CPU awake (to avoid replies being interrupted or tools getting stuck after the screen turns off), and automatically dismisses it once all sessions' tasks finish. The point of turning the switch on manually is "stay resident while idle too": if you want background commands you run yourself in the terminal to not be interrupted, keep it on. When the switch is on, finishing an AI task won't turn it off.
:::

## Keep Screen On

When enabled, the screen won't automatically turn off as long as the app stays in the foreground — suitable for continuously watching AI tasks or terminal output. It stops applying automatically when you switch to another app or return to the home screen, doesn't affect the system's auto-lock settings, and needs no extra authorization.

Leaving it on for long periods uses more battery; using it while plugged into a charger is more suitable.

## Agent Completion Notifications

When enabled, if the app is in the background and the AI completes a round of conversation, a system notification pops up (title "Task complete", content is the message you sent; if it was an automatic round triggered by a background task, it shows the corresponding task title). Tap the notification to return to the app and see the result.

No notification is shown while the app is in the foreground, to avoid disturbing the conversation you're looking at.

::: tip Android 13 and above
The first time you enable the two switches above, the "Notifications" permission is requested first. If the system can't pop up the authorization dialog (the permission was turned off or denied before), a dialog will prompt you to tap "Go to Settings" and enable it manually on the system settings page. The switches don't take effect until the permission is granted.
:::

For system permissions (install unknown apps, storage, battery optimization, auto-start, Shizuku), see [App Permissions](/guide/app-permissions).
