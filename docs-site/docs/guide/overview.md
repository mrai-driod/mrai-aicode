# Feature Overview

This page is a quick tour index of the user manual, organizing the main interface's core workflows and the documentation for each feature entry on the settings page. If you'd like to watch an operation demo first, browse the [Video Tutorials](/guide/video-tutorials).

::: tip Version notes
Entries marked with a version (such as "from 1.11.0") indicate the feature was introduced in that version.
:::

## Core Workflows & Main Interface

| Feature | Description |
| --- | --- |
| Chat interaction & workspaces | Title bar, sidebar, toolbar, message queue, workspace switching → [Doc](/guide/chat) |
| Run modes (three modes) | Build / Plan / Auto permission control and scenarios → [Doc](/guide/modes) |
| Checkpoints & undo | Automatic snapshot before AI changes, one-tap safe rollback → [Doc](/guide/checkpoint) |
| Terminal interaction | Multi-tab sessions, auxiliary key bar, color & font settings → [Doc](/guide/terminal) |
| Built-in browser | WebView browser supporting AI-automated web operations (navigation, clicking, form filling, screenshots, etc.) (from 1.12.0) → [Doc](/guide/browser) |
| File browsing & code editing | Indented tree directory, syntax highlighting, built-in code editor (from 1.11.0), plus where workspace files live on the phone and how to access them → [Doc](/guide/files) |
| Git version management | Visual status management, branch switching, commit history, revert & delete (from 1.11.0) → [Doc](/guide/git) |
| Tablet & large-screen adaptation | Responsive columns, resident sidebar, side-by-side two-column workbench (from 1.11.0) → [Doc](/guide/tablet) |

## Settings & Extension Capabilities Quick Reference

### General Settings

| Entry | Description |
| --- | --- |
| Preferences | Global preferences: invalid model cleanup, session at startup, Enter to send, first-token & interval timeouts, retry count, auto-compaction threshold, sendFile single-file limit (from 1.12.0) → [Doc](/guide/general) |
| Appearance theme, background image, language | Theme light/dark, preset color schemes, Monet color extraction, background image, and multiple languages → [Doc](/guide/appearance) |
| Terminal settings | Terminal colors, font size, and cursor style → [Doc](/guide/terminal) |

### AI Configuration & Extensions

| Entry | Description |
| --- | --- |
| AI providers | Connect model services, manage model lists, multi-key (from 1.11.0), thinking intensity → [Doc](/guide/providers) |
| Default & dedicated models | Default model for new sessions, plus dedicated models for image recognition, image generation, and title summary → [Doc](/guide/default-models) |
| MCP servers | Connect external tools; global and project two-level configuration → [Doc](/guide/mcp) |
| Skills | Specialized capability extension packs loaded on demand → [Doc](/guide/skills) |
| Sub-agents | Spawn independent sessions to execute tasks in parallel; create, edit, enable/disable in settings; custom models and tool sets supported (from 1.11.0) → [Doc](/guide/subagent) |
| Shizuku tool | The AI's `Shizuku` tool: execute system commands and read/write /sdcard with the adb shell (uid 2000) identity (from 1.12.0) → [Doc](/guide/shizuku) |
| Custom prompts | Override and customize the AI system's prompt fragments → [Doc](/guide/custom-prompts) |
| Memory & project rules | Cross-session long-term memory, plus AGENTS.md / CLAUDE.md project rules → [Doc](/guide/memory) |

### Runtime Environment

| Entry | Description |
| --- | --- |
| Containers & images | Local Linux container, custom images, mounting phone directories, remote SSH backend → [Doc](/guide/container) |
| Network proxy | Global proxy and per-provider proxy (from 1.11.0) → [Doc](/guide/proxy) |
| Connections & sync | SFTP / FTP channels, workspace sync, built-in FTP server → [Doc](/guide/sync) |

### Permissions & Background

| Entry | Description |
| --- | --- |
| Tool authorization | Authorization rules for AI tool calls → [Doc](/guide/permissions) |
| App permissions | System permissions: install unknown apps, storage, battery optimization, auto-start, Shizuku → [Doc](/guide/app-permissions) |
| Background run | Background keep-alive, keep screen on, AI completion notifications → [Doc](/guide/background-run) |

### Data & Diagnostics

| Entry | Description |
| --- | --- |
| Token stats | Usage, cost estimation, call details → [Doc](/guide/token-stats) |
| Storage space | View usage by category — chat, container, workspaces — and clean temporary data (from 1.12.0) → [Doc](/guide/storage) |
| Backup & restore | Encrypted export/import of configuration and workspaces → [Doc](/guide/backup) |
| Logs | View runtime logs, crash reports → [Doc](/guide/logs) |
| Common error messages | Retry bubble category meanings, common HTTP error codes & troubleshooting → [Doc](/guide/error-codes) |

### Help & About

| Entry | Description |
| --- | --- |
| About | Version info and update checks → [Doc](/guide/about) |

## Advanced Tutorials

| Topic | Description |
| --- | --- |
| Build an Android app in the container | Set up JDK and Android SDK, produce an APK from source → [Doc](/advanced/build-android-app) |
| Build a Flutter app in the container | Install JDK, Android SDK and Flutter SDK, build a Flutter debug APK → [Doc](/advanced/build-flutter-app) |
| Install Playwright browser automation | Install Chromium in the container and connect Playwright MCP to let the AI operate web pages → [Doc](/advanced/playwright-mcp) |
| Wirelessly debug other devices with adb | Use adb in the container to connect another phone or this device, install apps, capture logs, take screenshots → [Doc](/advanced/adb-wireless-debug) |
| Custom panels | Use scripts to draw balance or usage cards above the input box → [Doc](/advanced/dashboard-cards) |
