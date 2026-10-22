# Quick Start

From installation to your first AI conversation. To watch an operation demo first, see the [Video Tutorials](/guide/video-tutorials).

## System Requirements

- Android 8.0 (API 26) and above
- arm64-v8a or x86_64 device

## Download & Install

Download from [GitHub Releases](https://github.com/jieapi/aicode/releases/latest); choose among the three packages by device:

| Package | Suitable scenario |
| --- | --- |
| `armsolo` | Real devices (arm64), smallest size |
| `x86solo` | Emulators, Chromebooks (x86_64) |
| `universal` | Universal package, contains both architectures, larger size |

::: warning Choose the right architecture
An architecture mismatch will prevent the built-in container from running. If unsure, use `universal`.
:::

It's recommended to click "Download APK" on the [docs site home page](/) directly — it downloads the latest official `universal` package through an accelerated mirror (automatically falling back to the original GitHub link if the mirror is unavailable).

## 1. Configure an AI Provider

::: tip First launch has an onboarding guide
On first launch, an onboarding guide starts automatically (new in 1.12.0), walking you through the steps below in order; you can also tap "Skip" at any time, or start it again later in "Settings → Re-run onboarding guide".
:::

AiCode doesn't provide models itself; you need to connect a model service first.

1. Tap the menu button at the top left of the home page to open the sidebar, and tap "Settings" at the bottom.
2. Enter "AI Configuration → AI Providers", tap + at the top right to create new, and fill in four items in the "Configuration" tab:
   - **Name**: an alias, e.g. "Official OpenAI".
   - **Upstream format**: one of `OpenAI` / `Anthropic` / `Gemini`. For third-party relay stations, generally choose `OpenAI`.
   - **API Key**: your key, in the form `sk-xxx`.
   - **Base URL**: the API root address; **when left empty, the official address for the upstream format is used**; for a third-party relay, fill in the relay service's address.
3. Switch to the "Models" tab at the bottom to add models:
   - Tap "Fetch Models" to sync the available list from the server; tap the "+" on the right of each row to add one by one, or use "Add All" at the top right to add them all at once.
   - Tap + at the top right to manually enter a model name (e.g. `gpt-4o`). Use this when a relay station's model names aren't fully fetched.
   - Optional: tap a model row to edit metadata (context window, price, and capability switches such as image input and tool calling).

Exiting the edit page saves automatically; there's no separate save button.

## 2. Choose a Model

Return to the chat home page, tap the model button in the toolbar below the input box, and choose the model you just added in the dialog. The search box at the top can filter; models are displayed grouped by provider, and tapping a group title collapses or expands it.

## 3. Prepare the Execution Environment

The built-in local container is used by default, needing no configuration. On first use, enter the terminal from the home page and follow the prompts to complete a one-time initialization (extract the system, install basic tools); after that the AI can execute commands normally.

To use a remote server as the execution backend, see [Remote SSH Mode](/guide/remote-ssh); for detailed container and image settings, see [Containers & Images](/guide/container).

## 4. Start Chatting

Type content in the input box and tap send. The mode button at the far left of the toolbar below the input box can switch between BUILD, PLAN, and AUTO; for the differences see [Three Modes](/guide/modes).

## Useful Tips

- The model selected in an empty session on the home page is recorded as the default model for new sessions, and newly created sessions automatically use it afterwards.
- Image recognition, image generation, and title summary support independently specified dedicated models — see [Default Models](/guide/default-models).
- Before the AI modifies code, a snapshot is created automatically, supporting one-tap safe rollback — see [Checkpoints & Undo](/guide/checkpoint).
- Before executing time-consuming tasks such as builds, it's recommended to enable background run keep-alive in [App Permissions](/guide/app-permissions) to prevent the system from terminating the process when you switch to the background.
