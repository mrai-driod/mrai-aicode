# Tablets & Large Screens

::: tip Version note
The large-screen layout described on this page is available from 1.11.0.
:::

On a large screen, AiCode automatically switches to a two-column layout: the sidebar stays resident, and code or a terminal can be opened side by side next to the chat. The interface and operations on phones are completely unchanged.

## When It Switches

The criterion is the **current window's width**, not the screen size:

| Window width | Interface form |
| --- | --- |
| Narrow (most phones in portrait) | Single column; the sidebar slides out via the hamburger button, same as before |
| Medium (tablets held in portrait, phones in landscape) | Single column; the sidebar is slightly wider |
| Wide (tablets in landscape, foldables unfolded, desktop windows) | Sidebar resident; the chat page can open a right column |

Because the criterion is window width, when you drag AiCode to half or a third of the screen on a tablet, it automatically falls back to a single column rather than getting squeezed into a clump.

## Resident Sidebar

When entering the chat page on a wide screen, the session list and file tree are fixed directly on the left, with no need to slide them out each time. At this point the hamburger button in the title bar disappears (the sidebar is already resident).

Tapping a file in the file tree, or selecting another session, doesn't collapse the sidebar, so you can tap through and view continuously.

## Side-by-Side Workbench

On a wide screen, a panel can be displayed side by side on the right of the chat page:

- Tap the **Terminal** icon in the title bar → the right column opens the terminal; tap again to collapse it
- Tap the **Git** icon in the title bar → the right column opens the Git panel; tap again to collapse it
- Tap a file in the file tree, or a file path link in an AI reply → the right column opens the code editor (images in the file tree switch to full-screen preview; APKs switch to the system installer)

Whichever panel the right column currently has open, the corresponding icon in the title bar is highlighted.

Watch the AI change code on one side while checking file contents or executing commands on the right — no need to switch pages back and forth.

**Adjusting the width**: drag the vertical divider in the middle to adjust freely between a 30/70 and 70/30 split; the ratio is remembered.

**Closing the right column**: tap the ✕ at the top left of the right column, or press the back button once. If the editor has unsaved changes, the save prompt still pops up first.

## Two-Column Settings Page

After opening Settings on a wide screen, the left is the settings item list (always displayed) and the right is the selected item's content. Switching settings items only changes the right half, without jumping the whole page. The back arrow at the top left is for leaving Settings and returning to chat.

## Rotation & Split Screen No Longer Interrupt

When switching between landscape and portrait or dragging the split-screen size, the interface re-lays out accordingly but doesn't reload:

- Commands running in the terminal keep executing, output isn't lost
- Unsaved changes in the editor are still there
- The chat list stays at its original position

## Reading Width

On a wide screen, chat content doesn't stretch across the entire screen, but is limited to a reading-friendly width and centered — overly long lines of text increase the reading burden. After the right column opens and the left half narrows, the content automatically narrows along with it.

## Mouse & Touchpad

When a mouse is connected, moving the pointer over the column divider turns it into a drag cursor; hold and drag left or right to adjust the two columns' widths.
