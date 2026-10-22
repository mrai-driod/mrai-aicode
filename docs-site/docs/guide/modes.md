# Three Modes: Build / Plan / Auto

The mode button to the left of the input box cycles on tap: BUILD → PLAN → AUTO → BUILD. The difference between the three modes is the AI's **permission scope**.

## BUILD (Build Mode)

The default mode, with full functionality. The AI can write files, change code, and run build commands, but every operation that needs authorization pops up a dialog asking you.

This mode is recommended for everyday development. For high-risk commands blocked by the built-in safety guard (such as `rm` on the root directory), the AI can retry with `elevate: true`, handing it over to you for one-time authorization (see [Tool Authorization](/guide/permissions)).

## PLAN (Plan Mode)

Once you enter this mode, the AI's write permissions are blocked and disabled directly at the lower layer (intercepted at the tool layer, not merely constrained by the prompt). The AI can only read code, search files, and devise plans; it can't modify any file or execute write-operation commands.

Suitable for these scenarios:

- You're not sure how to make the change yet and want the AI to explore the codebase and propose a plan first.
- The change involves multiple files or architecture decisions, and you want to see the plan before deciding.
- You just want to ask questions and understand the existing implementation, without it touching anything.

Once the plan is discussed and clear, switch back to BUILD to get to work.

## AUTO (Auto Mode)

Permissions fully open. All tool calls (writing files, executing commands, terminal operations) are approved automatically, with no authorization dialogs at all.

::: danger This mode grants very large permissions
Only catastrophic deletions (such as `rm` on the root or system directories) and suspected destructive commands that can't be determined statically are still intercepted; everything else is allowed (you can turn off this fallback layer by enabling "Disable safety interception" in "Settings → Tool Authorization"; the AI can also retry with `elevate: true`, in which case a dialog asks you for one-time authorization). Suitable for scenarios where you clearly understand the risks and want the AI to execute batch tasks fully automatically. Not recommended for unfamiliar projects or when you're unsure what operations the AI will perform.
:::

## The AI Can Also Request a Switch

Besides you tapping the button manually, the AI can also proactively request to enter or exit PLAN mode, which you approve with one tap in the UI. For example, after completing a plan in PLAN mode, it will ask to exit PLAN and start implementing — after exiting, it automatically returns to the mode before entering PLAN.

There is a directional restriction: **AUTO mode can only be entered manually by you**; the AI cannot switch into AUTO through a tool. Conversely, while in AUTO mode the AI can proactively switch to PLAN — that's the AI's only path out of AUTO.

## Exiting PLAN Returns to the Previous Mode

PLAN is a temporary state: when entering PLAN, the current mode is noted down, and it's restored automatically on exit. Entering PLAN from AUTO returns to AUTO after the plan is done and you exit; entering from BUILD returns to BUILD. It no longer always falls back to BUILD.

Note: exiting PLAN still needs your approval on the plan review panel (tap "Approve and implement"); the mode is truly restored only after approval.

## Switching Works While Running

The mode button is also available while the AI is executing a task. If you switch modes while the AI is executing, it **takes effect immediately in the current round**: the AI receives a mode-switch notification, and from that moment the task at hand is handled under the new mode (for example, if you switch to AUTO midway, subsequent operations no longer pop up dialogs one by one; switch to PLAN, and subsequent write operations are intercepted) — no need to wait for the current round to end.
