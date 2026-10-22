# Storage Space

"Settings → Storage Space" shows, by category, the space AiCode occupies on this device, and provides an entry point for cleaning temporary data.

## Overview

At the top of the page are the app's total usage, a composition bar, and the device's remaining space. The full width of the composition bar represents the app's total usage, and its colors correspond one-to-one with the color dots of the categories below. A "Clean Selected" button and the size of the selected data are fixed at the bottom of the page, operable while scrolling the list.

Computing the statistics requires traversing files directory by directory, and the container and workspaces are usually the slowest. To keep the page from sitting empty, each category is displayed as soon as it's computed; categories not yet computed show "—", and a "Computing…" hint appears at the top. To recompute, tap the refresh icon at the top right.

## Usage Details

| Category | What's included |
| --- | --- |
| Chat history | Database files: sessions, messages, tool results, call records, checkpoint index |
| Container | The root file system of each container. Expand to see each container, and the largest few directories in the largest container |
| Container image packages | Downloaded or imported image archives. Once the container is installed it's no longer essential, but without it the container can't be extracted again |
| Workspaces | Project files in the app's built-in workspaces (including `.git`). Expand to see each project; the subtitle marks the chat attachments among them |
| AI configuration & output | Skills, memory, sub-agents, MCP configuration, prompts, docs, as well as tool output archives and image recognition session cache |
| Checkpoints | Snapshots left before the AI modifies files, used for undo |
| Logs | App runtime logs and AI request logs |
| Cache | The system cache directory, clearable at any time |
| Other data | Scattered data such as settings items, background images, terminal fonts, SSH private keys |
| App installation package | The APK itself, not cleanable |

Categories with an arrow next to the name can be tapped to expand the details. "AI configuration & output" only expands to show the cleanable tool output archives and image recognition session cache; non-cleanable content such as Skills, memory, and prompts is no longer displayed item by item, but still counts toward that category's total usage. Therefore, the sum of the expandable cleanable sub-items may be smaller than the category's total size.

::: tip What doesn't count
External workspaces added via "Add local directory" live in your device's shared storage and don't belong to the app's usage, so they aren't counted. The same applies to files on the server in remote SSH mode.
:::

The expanded details of chat history list the sessions taking the most space. The sizes are estimated from message body bytes and are smaller than the database file itself — the database also includes indexes and page alignment overhead. To reduce this item, go to the session list and delete sessions you no longer need; if you only want to clear call records, reset at the top right of the "Token Stats" page.

Containers and workspaces can't be deleted here — go to the "Containers & Images" page to reset or delete a container, and delete workspaces in the workspace switching panel, to avoid accidentally deleting an environment in use. Deleting a workspace also deletes that workspace's chat history together with it; whether an external local workspace's chat history is deleted is decided by the switch in "Preferences".

## Releasable Space

The clean entry points are placed together with the usage details, no longer listed as a separate group. The four cleanable items are as follows; none include chat history, code, or configuration, but some cleaning affects reviewing or following up on historical content:

- **Cache files**: check the "Cache" category. Temporary files are rebuilt automatically afterwards.
- **Runtime logs**: check the "Logs" category. Includes app logs and AI request logs; after clearing, new logs are recorded as usual. Tapping the category still expands to view the usage of the two log types.
- **Tool output archives**: expand "AI configuration & output" and check the corresponding detail item. When AI tool output is too long, a complete copy is saved to disk. After clearing, output paths mentioned in historical messages are no longer readable.
- **Image recognition session cache**: expand "AI configuration & output" and check the corresponding detail item. This is the history used for image recognition follow-ups; after clearing, you can no longer follow up on old images. These two items only appear when they have usage.

Logs and cache are selected by default; tool output and image recognition cache are not selected by default. After checking, the bottom button updates the combined size of the selected items; collapsing a category doesn't cancel the selection. Items with no usage can't be checked, and when no cleanable data is selected the button can't be tapped.

After tapping "Clean Selected", a confirmation dialog lists the cleaning targets, combined size, and each item's impact. After confirming, only these selected items are cleaned; when done, the page automatically recomputes the statistics and shows how much space this cleaning released.

The cleaning scope is data in the local app cache, logs, and global directories; tool output and image recognition cache may be produced by multiple workspaces and aren't limited to the current workspace. It won't clean each workspace's own files, attachments, build cache, or remote server data.

::: warning Cleaning is irreversible
Deleted files can't be recovered. Although these items contain no chat history, code, or configuration, old tool output copies and image recognition session history can't be restored by using the app again.
:::

## How to Handle Excessive Usage

- **Container taking several GB**: the package manager cache inside the container is usually the bulk of it (cache directories of Gradle, npm, pip, etc.). Expand the container category to see which directory is largest, then clean it in the terminal, e.g. `rm -rf ~/.gradle/caches`. Containers no longer in use can be deleted on the "Containers & Images" page.
- **Workspace too large**: build products like `node_modules`, `build`, `.gradle` take up the majority; deleting them and rebuilding restores them.
- **Chat history too large**: tool output in long sessions takes the most space; just delete early sessions you no longer need.
- **Image package still there**: after the container is installed, you can delete the corresponding download file on the "Containers & Images → Download Image" page.
