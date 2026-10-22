<p align="center">
  <img src="docs/icon.webp" width="112" alt="Mrai AiCode" />
  <h1 align="center">Mrai AiCode</h1>
  <p align="center">
    Universal AI Coding Agent for Android · Linux dev environment · Local & remote SSH
    <br />
    English-first fork by <b>Mrai</b> — free AI built in, no key needed to start
  </p>
</p>

> **Fork notice**: Mrai AiCode is a fork of [hwsyyds666/Aicode-Glass](https://github.com/hwsyyds666/Aicode-Glass) (Liquid Glass edition), itself a fork of the original project [jieapi/AiCode](https://github.com/jieapi/aicode). All credit to the original authors; this fork is released under the same [GPL-3.0](LICENSE) license.

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-GPL--3.0-blue.svg" alt="License GPL-3.0" /></a>
  <img src="https://img.shields.io/badge/Platform-Android-green.svg" alt="Android Platform" />
  <a href="https://github.com/mrai-driod/mrai-aicode/releases"><img src="https://img.shields.io/github/v/release/mrai-driod/mrai-aicode?display_name=tag&include_prereleases" alt="Latest Release" /></a>
</p>

---

## What this fork changes

- **Works out of the box** — upstream ships with no usable model until you paste an API key. Mrai AiCode seeds two free, keyless providers on first run:
  - **Mrai AI** — the keyless fallback chain from [Mrai Buddy](https://github.com/mrai-driod/mrai-buddy), ported natively: Pollinations (OpenAI-compatible, full tool support) → PowerBrain → Pollinations plain-text GET. Fail-silent, ad-blocks stripped, never reveals which rung answered.
  - **OpenCode Free** — OpenCode Zen's free model catalog (Big Pickle and the other `-free` models), ready to pick.
- **Fully English** — English is the default UI language, the bundled docs are translated, and the agent's built-in prompt pack is rewritten in English. No Chinese defaults anywhere.
- **Your system prompt is absolute** — the built-in prompt pack itself states that your own prompt fragments and memory lead every request and override the pack on conflict.
- **Extra built-in assistant** — alongside the read-only **Explore** subagent, a read-only **Reviewer** subagent audits changes for bugs and security issues.
- Everything else — Liquid Glass UI, container, SSH mode, Git, Skills, MCP, checkpoints — is upstream's work, kept intact.

## Overview

Mrai AiCode is a universal AI coding agent that runs on Android, packing a full Linux development environment into your phone: it bundles an Alpine Linux container and terminal, and the AI agent can read and write files, run shell commands and run build tools, so writing, debugging and building all happen on-device. A remote SSH server can also serve as the execution backend, turning your phone into a mobile workstation for remote projects.

There is nothing to set up beforehand: install the app and start coding with the built-in free providers — no computer, no environment to build yourself, no API key. If you have your own keys, models and endpoints are still fully yours to configure: the OpenAI / Anthropic / Gemini protocols plus custom providers are all supported, with no vendor lock-in.

<p align="center">
  <table>
    <tr>
      <td align="center"><img src="docs/screenshots/home.png" alt="Mrai AiCode home - AI chat interface with code generation and Markdown rendering" width="270"/></td>
      <td align="center"><img src="docs/screenshots/git.png" alt="Mrai AiCode Git integration - visual commit history and branch management" width="270"/></td>
    </tr>
    <tr>
      <td align="center">Home · AI Chat</td>
      <td align="center">Git · Commit History</td>
    </tr>
    <tr>
      <td align="center"><img src="docs/screenshots/container.png" alt="Mrai AiCode container settings - container image management" width="270"/></td>
      <td align="center"><img src="docs/screenshots/models.png" alt="Mrai AiCode model list - multi-provider model management" width="270"/></td>
    </tr>
    <tr>
      <td align="center">Container · Image Manager</td>
      <td align="center">Models · Provider List</td>
    </tr>
  </table>
</p>

## Features

### AI Agent

- **AI Agent** — Built-in tools for file read/write/edit, shell execution, background terminal, code & web search, image recognition, todo lists, and more; streaming output with live Markdown rendering and automatic context compression
- **Parallel subagents** — Spawn subagents with their own isolated context to research, review or compare approaches in the background without blocking the current chat; read-only **Explore** and **Reviewer** subagents are built in, and you can define your own with a custom model, tool set and prompt
- **Three run modes** — BUILD for normal development, PLAN for read-only planning (writes blocked at the tool layer), AUTO to approve everything without prompts — pick the permission scope you trust
- **Checkpoints & Undo** — Snapshots are recorded before the agent edits code; one-tap rollback of code, chat history, or both
- **Skills & Auto Memory** — Global/project-level skills and long-term memory let the AI reuse experience and project conventions across sessions
- **MCP Protocol** — Connect to local (stdio) or remote (HTTP) MCP servers to dynamically extend AI tool capabilities
- **Tool permissions & custom prompts** — Per-tool authorization rules; system prompts can be overridden by the user and survive app upgrades

### Models & Providers

- **Free providers built in** — Mrai AI (keyless chain) and OpenCode Free work immediately, no signup and no key
- **Model-agnostic beyond that** — No vendor lock-in: bring your own models, keys and endpoints whenever you want
- **Protocol support** — Compatible with the OpenAI / Anthropic / Gemini protocols, with presets for several official providers and support for custom ones; model lists and pricing are customizable
- **Multiple keys** — Configure several keys per provider with sequential or round-robin rotation; reasoning effort is adjustable

### Development Environment

- **Built-in Terminal & Container** — A local Linux container built on Termux and PRoot with a built-in Alpine image; supports custom rootfs images and host directory mounts; multi-tab terminals that can stay alive in the background
- **Remote SSH Mode** — Use a remote SSH server as the execution backend: commands, files and terminal all act on the remote project
- **File tree & code editor** — An indented file tree that opens files in a full-screen editor with syntax highlighting for mainstream languages and Markdown preview; `file:line` links in AI replies jump straight to that line, in both local and remote SSH workspaces
- **Git Integration** — Visual management of status, branches, commit history, diffs and tags, plus staging/discarding changes, sign-off and credentials
- **Workspace Sync** — SFTP / FTP synchronization with a built-in FTP server for desktop file management

### Experience

- **Tablet & large screen** — Layout adapts to window width: a persistent sidebar on wide screens with code or terminal beside the chat, falling back to a single pane when narrow
- **Token stats** — Usage and cost estimates per provider and model, with a drill-down into individual calls
- **Appearance & language** — Light/dark themes, preset color schemes, Material You colors, custom background images, and an English-first UI
- **Network proxy** — Configure a global proxy and per-provider proxies separately
- **Backup & Restore** — Encrypted export/import of provider configs, credentials, chat history and workspace files

## Getting Started

| Item | Description |
|------|-------------|
| System requirements | Android 8.0+ (API 26), arm64-v8a / x86_64 |
| Download | [GitHub Releases](https://github.com/mrai-driod/mrai-aicode/releases/latest): pick `armsolo` for real devices, `x86solo` for emulators, `universal` for both |
| Quick start | Just open the app and chat — Mrai AI is already the default. Optionally: Settings → AI Vendors to add your own providers and keys |
| User guide | The docs bundled in the app (`~/.aicode/docs/`), same content as the online docs |

## Feedback & Contribution

- **Bug reports**: open an [Issue](https://github.com/mrai-driod/mrai-aicode/issues) with reproduction steps, device model and OS version
- **Feature requests**: discuss your ideas in [Issues](https://github.com/mrai-driod/mrai-aicode/issues)
- **Contributing**: submit a [Pull Request](https://github.com/mrai-driod/mrai-aicode/pulls)

## Acknowledgements

- **[jieapi/AiCode](https://github.com/jieapi/aicode)** — Original project, the foundation of this fork
- **[hwsyyds666/Aicode-Glass](https://github.com/hwsyyds666/Aicode-Glass)** — The Liquid Glass fork this project builds on
- [OpenCode](https://github.com/anomalyco/opencode) — Terminal-based AI coding tool, the core inspiration for this project, and the source of the OpenCode Free model catalog
- [Termux](https://github.com/termux/termux-app) — Android terminal emulator, provided terminal components and PRoot solution
- [Kelivo](https://github.com/Chevey339/kelivo) — Cross-platform LLM chat client, AI conversation UI design reference
- [PrismalAGSL](https://github.com/styropyr0/PrismalAGSL) — AGSL liquid glass effect library
- [Pollinations](https://pollinations.ai) & PowerBrain — The free inference behind the Mrai AI chain, first shipped in Mrai Buddy

## License

This project is licensed under [GPL-3.0](LICENSE).
