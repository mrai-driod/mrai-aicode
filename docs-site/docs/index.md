---
layout: home
title: AiCode - AI Coding Tool & Built-in Linux Terminal for Android
titleTemplate: false
description: AiCode is an open-source AI coding tool for Android. No computer needed — complete the full development workflow right on your phone. It ships a built-in Linux container and terminal so the AI Agent can read/write code and run builds directly, with Git version control, the MCP protocol, and remote SSH development.
head:
  - - meta
    - name: keywords
      content: AiCode, Android AI coding, mobile coding, mobile development, Linux terminal, PRoot, Termux, AI Agent, MCP protocol, mobile code editor, Git client, mobile workstation, terminal on phone, remote SSH development

hero:
  name: AiCode
  text: AI Coding Tool & Mobile Workstation on Your Phone
  tagline: No computer needed — write code, debug, and commit entirely from your phone. A built-in Linux container and terminal let the AI Agent read/write files, run shell commands, and execute builds on its own, with Git, MCP, remote SSH, and a full model ecosystem.
  image:
    src: /logo.png
    alt: AiCode
  actions:
    - theme: brand
      text: Quick Start
      link: /guide/quick-start
    - theme: alt
      text: Download APK
      link: https://aicode.murk.top/download
    - theme: alt
      text: GitHub
      link: https://github.com/jieapi/aicode

features:
  - title: AI Agent
    details: Supports OpenAI / Anthropic / Gemini compatible protocols, with multiple providers switchable at any time; built-in tools for file read/write, shell execution, background terminal, search, and image recognition, with streaming output and automatic context compression for long conversations.
  - title: Built-in Terminal & Linux Container
    details: A local container built on Termux components and PRoot, shipping with an Alpine image. Import custom rootfs, mount host directories, and keep terminals running in the background.
  - title: Remote SSH Mode
    details: Use a remote server as the execution backend — commands over the exec channel, files over SFTP, terminals over the shell channel — and work on remote projects directly from your phone.
  - title: Checkpoints & Undo
    details: File snapshots are recorded automatically before the AI modifies code, so you can roll back with one tap in the conversation — restore code only, conversation only, or both.
  - title: MCP, Skills & Memory
    details: Connect local stdio and remote HTTP MCP servers to dynamically extend tools; global and project-level Skills plus long-term memory let the AI reuse experience across sessions.
  - title: Git & Workspace Sync
    details: Built-in status, branch, commit, diff, and tag management; SFTP / FTP sync support, plus a built-in FTP server for managing files from your computer.
---
