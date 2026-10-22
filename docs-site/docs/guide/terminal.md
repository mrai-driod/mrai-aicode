# Terminal

AiCode has a complete built-in terminal. In local mode it connects to the Linux container on the phone; in remote mode it connects to your SSH server — in both cases it's a real shell where you can install packages, compile, and run services.

Enter it from "Terminal" in the title bar at the top of the chat page. Tap the back button at the top left to return to the chat page; repeated taps during the return won't exit the chat page further. Returning doesn't close the terminal tabs.

## Tab Management

The terminal supports multiple tabs simultaneously; the tab bar at the top can be swiped horizontally, and when switching, the current tab automatically scrolls into visible position.

- The dot before a tab name indicates its state: green means running, gray means ended.
- Tap the × on the right of a tab to close it, and tap the + at the end of the tab bar to create a new one. After closing the current tab, it automatically switches to an adjacent one.
- Terminals the AI opens when executing commands also appear here; you can switch over at any time to view what's running.

## Input & Copy

Tap the terminal area to focus it and type directly. Long-press to select and copy output content.

## Auxiliary Key Bar

Phone keyboards have no Esc, Ctrl, or arrow keys, so the bottom of the terminal provides a horizontally swipeable auxiliary key bar:

- **Control keys**: `ESC`, `TAB`, `CTRL`, `ALT`. Ctrl and Alt highlight after being pressed; pressing a letter key after them forms a key combination.
- **Arrow keys**: up, down, left, right; long-press sends continuously, for quickly moving the cursor.
- **Common combinations**: `C-c` interrupts the current command, `C-d` sends EOF or exits, `C-z` suspends the process, `C-l` clears the screen.
- **Common symbols**: `|`, `~`, `/`, `-`, `_`, `>`, `:`, `$`, `"`, `'`, etc., saving you from switching to the symbol keyboard.

## Appearance Settings

Tap the gear icon at the top right of the terminal page (or enter from "Settings → General Settings → Terminal Settings") to adjust:

- **Color theme**: five built-in color schemes — Termius Dark, Dracula, One Dark, Monokai, GitHub Dark. Selecting one takes effect immediately, and all open tabs change together.
- **Font size**: stepless adjustment between 5 and 22, with a live preview at the top of the panel; you can also pinch with two fingers directly in the terminal area to zoom, which is the same value as here.
- **Font** (**from 1.11.0**): the default is JetBrains Mono (no-ligature version), which clearly distinguishes `0` from `O`, and `1` from `l`. You can also switch to the system monospace font, or tap "Import" to choose a `ttf` / `otf` / `ttc` font file from your phone; imported fonts appear in the list, and tapping the × on a font chip deletes it — deleting the font in use automatically falls back to system monospace. Chinese characters are always supplemented by the system font. The font only affects the terminal, not the AI chat area.
- **Cursor style**: block, underline, and bar.
