# CLAUDE.md — Mrai AiCode

AI collaboration rules for this repository (read by Claude Code, Mrai AiCode itself, and other AI coding assistants working here). These rules outrank generic defaults.

## This fork

- **Mrai AiCode** is Mrai's fork of [hwsyyds666/Aicode-Glass](https://github.com/hwsyyds666/Aicode-Glass), itself a fork of [jieapi/AiCode](https://github.com/jieapi/AiCode). License: **GPL-3.0** — the fork stays GPL-3.0; keep `LICENSE`, `NOTICE`, and upstream credit intact.
- **Always reply in English.** This fork is English-first: UI strings, agent prompts, docs, and commits are written in English.
- Identity: `applicationId = "com.mrai.aicode"`, app name **Mrai AiCode**. The Kotlin source package remains `com.aicode` (internal only).
- Upstream remote is `upstream` (hwsyyds666/Aicode-Glass); our repo is `mrai-driod/mrai-aicode`. The app fetches provider presets at runtime from OUR `data/providers.json` (see `RepoDataFetcher` defaults).

## Mrai additions (do not regress)

- **Built-in free providers, seeded on first run** (`BuiltInProviderSeeder`): `Mrai AI` (id `mrai`) and `OpenCode Free` (id `opencode-free`), both keyless with the placeholder key `keyless`. Mrai AI becomes the default provider only when the user has not chosen one.
- **Mrai AI is a chain, not an endpoint** (`MraiChainAdapter` in `feature/agent/domain/provider/`): a faithful port of Mrai Buddy's `mrai-ai.py` — Pollinations (OpenAI-compatible, tools work) → PowerBrain → Pollinations plain-text GET, fail-silent, ad-blocks stripped, never revealing which rung answered. Provider creation routes by config id in `StatefulAgentWorkflow.createStandaloneProvider` and `ImageTools.createStandaloneProvider` — keep both in sync.
- **English is the default language**: `LanguageSettingsRepository` falls back to `en` when no language is stored, and `res/values/strings.xml` mirrors `values-en` (English is the fallback resource set). Chinese remains selectable in settings only via the upstream `values` history — do not reintroduce Chinese as a default anywhere.
- **The user's system prompt is absolute**: the built-in prompt pack (`app/src/main/assets/prompts/`) states this itself — user-defined fragments and memory lead every request and override the pack on conflict. Never weaken that clause.
- Built-in sub-agents live in `app/src/main/assets/agents/` (Explore, Reviewer). New built-ins are dropped in as Markdown with the same front-matter shape.

## Basics

- Read and write files with the file tools; do not substitute `cat` / `sed` / `echo >`.
- Stack: Kotlin + Compose + Hilt + Coroutines/Flow. Modules: `:app` / `:terminal-emulator` / `:terminal-view`.
- **Use the project's custom components (hard rule)**:
  - **Text fields**: `core/ui/AppTextField.kt` (with `appTextFieldColors()`, or `dialogTextFieldColors()` in dialogs). Never drop a raw M3 `TextField` with a long underline into a business screen, and never re-wrap `OutlinedTextField` module-locally.
  - **Switches**: always `core/ui/AppSwitch.kt`, never raw M3 `Switch`.
  - **Modal bottom sheets**: always `core/ui/AdaptiveModalBottomSheet.kt` (tablet adaptation + fling fix built in), never raw `ModalBottomSheet`; add `.imePadding()` to long-form containers so the soft keyboard does not cover them.
  - **Grouped cards & list rows**: in settings/form screens reuse `SettingsGroup`, `SettingsRow`, `SettingsDivider`, `SettingsGroupHeader` from `feature/settings/presentation/component/SettingsGroupComponents.kt`.
  - **Swipe-to-delete**: always `core/ui/SwipeToDeleteRow.kt`.
  - **Theme & design tokens**: follow `core/theme/AIEditorTheme.kt` — `Spacing`, `Radius` (incl. `Radius.mdLarge = 12.dp`), `MaterialTheme.semanticColors`. No hard-coded off-spec magic numbers or colours.

## Build & verification

**After changing compiled code (`.kt` / `.gradle.kts` / `AndroidManifest.xml`), run a smoke build before committing; before `git push`, run the unit tests and the migration check.** Docs / resource-text / pure `.md` changes skip all of this.

| Purpose | Command |
| --- | --- |
| Smoke build (daily default) | `./gradlew :app:assembleUniversalDebug` |
| Unit tests before pushing | `./gradlew :app:testUniversalDebugUnitTest` |
| Migration check before pushing | `python3 scripts/check_migrations.py` (or `./gradlew checkMigrations`) |
| Release APK / AAB | `./gradlew assembleRelease` / `./gradlew bundleRelease` |

- **Never use aggregate tasks for daily verification**: `assembleDebug` / `assembleRelease` / `test` / `build` run across all three flavors and take very long.
- Outputs: `app/build/outputs/apk/<flavor>/release/app-<flavor>-release.apk`, `.../bundle/<flavor>/release/app-<flavor>-release.aab`.
- Flavors split by ABI: `universal` (arm64-v8a + x86_64), `armsolo` (arm64-v8a only), `x86solo` (x86_64 only).
- Release signing credentials come from `app/keystore.properties` (`storeFile` / `storePassword` / `keyAlias` / `keyPassword`); the file is gitignored. CI restores the keystore from a GitHub secret to `app/aicode.jks`.
- **`targetSdk` is locked at 28** (`minSdk = 26`): PRoot must execute binaries from the app's writable directory, which Android 10+ W^X / SELinux forbids. Do not raise it.

## Architecture map

Feature-based layering + DDD. Entry point `AIEditorApp` initialises `FileLogger`, `TerminalKeepaliveService`, `McpManager`.

- **`core/`**: cross-feature infrastructure — `db/` (incl. `MigrationLoader.kt`), `net/`, `theme/`, `ui/`, `util/` (incl. `FileLogger`).
- **`feature/`** (`app/src/main/java/com/aicode/feature/`):
  - `agent`: the AI agent core — prompts, tool registration & permissions, MCP, provider adapters (`data/remote/` under `anthropic` / `openai` / `gemini`).
  - `terminal`: terminal & sessions. Local mode uses Termux components + PRoot (`LinuxContainerEngine`); remote mode uses sshj.
  - `workspace`: workspace & DocumentsProvider; remote via `RemoteSftpFileAccess`.
  - `editor`: sora-editor. `git`: Git operations. `settings`: providers, logs, keepalive, etc.
  - `backup`: backup/restore & encryption. `credentials`: credential management & container injection.
- **Remote SSH path**: `RemoteSshConnection` (shared sshj client) + `RemoteSshEngine` (command execution) + `RemoteSftpFileAccess` (files) + `RemoteTerminalSessionManager` (terminal).
- **Tool system**: tools under `feature/agent/domain/tool/` register via `ToolRegistry`; execution permissions are governed by `ToolPermissionManager` + `ToolPermissionPolicyEngine`.
- **MCP**: `feature/agent/domain/mcp/` connects remote servers and registers their tools dynamically. **DI**: Hilt; each feature carries its own module.

### Database & migrations

Room (`feature/agent/data/local/database/AgentDatabase.kt` + DAOs). Two migration systems — **a version uses exactly one**:

- **File-based (default)**: `core/db/MigrationLoader.kt` reads `app/src/main/assets/migrations/{VERSION}_description.sql`, split into statements by `SqlScriptSplitter` (comment- and string-literal-aware — `;` inside string literals is safe, no `char(59)` workarounds needed); the whole migration runs in one transaction, any failure rolls back, success is recorded in `migration_history`.
- **AutoMigration**: pure schema changes (add column/table/index) may use `@AutoMigration(from = N-1, to = N)` generated at compile time; forgetting a migration for an entity change fails the build. Versions involving data cleanup / renames / constraint changes MUST use the file-based path.

Changing the schema takes three steps:

1. Increment `SCHEMA_VERSION` in `AgentDatabase.kt` (currently 55).
2. File-based: add `{VERSION}_description.sql` in `app/src/main/assets/migrations/` (**numbers must be consecutive**); AutoMigration: add the annotation, ensuring `to == SCHEMA_VERSION` and `from` connects to the largest file-based version.
3. Write the DDL/SQL; it executes automatically at startup and is recorded in `migration_history`.

**Cross-branch conflict rules (hard)** — when RC/hotfix branches run parallel to `main`:

- **Release means frozen**: a migration file whose version shipped under a `v*` tag may never be edited or renumbered; fixes ship as NEW migrations.
- **Shift on merge**: if an RC/hotfix migration number has shipped under a tag, it owns that number; when merging back to `main`, every unpublished `main` migration numbered ≤ the shipped maximum must be renumbered above it (content unchanged, numbers consecutive), with `SCHEMA_VERSION` incremented in step. Shipped numbers are never reused.
- **Reconciliation script**: `python3 scripts/check_migrations.py` (or `./gradlew checkMigrations`) verifies consecutive numbering, `SCHEMA_VERSION` consistency, and that shipped migrations were not tampered with or reused. **Run it before every push**; CI (ci.yml and android-release.yml) enforces it before building.

## Asset sync (hard rules)

`app/src/main/assets/prompts/` (agent prompts) and `docs-site/docs/` (user docs) are the AI agent's knowledge sources and MUST stay in sync with the code:

- **AI workflow changes → sync `prompts/`**: tool additions/removals/renames, parameter signature changes, agent behaviour or prompt-logic changes all update the matching files under `prompts/` (find them; create if absent).
- **Feature or tool changes → check `docs-site/docs/`** for user docs that need updating.
- **UI changes (new screens, changed interactions, layout, wording) → MUST update `docs-site/docs/`**; new doc pages are also added to the sidebar in `docs-site/.vitepress/config.ts` and the index in `docs-site/docs/guide/overview.md`.
- **User-visible wording → English strings**: write new strings into BOTH `values/strings.xml` and `values-en/strings.xml` (this fork keeps them identical English), and reference them from code with `stringResource(R.string.xxx)`. **Never hard-code user-visible text in `.kt`.** Names are semantic English snake_case; cross-screen reuse gets the `common_` prefix. (Existing upstream code comments are Chinese in places; new code is commented in English.)
- **Provider presets live ONLY in the repo data file**: add or adjust built-in AI providers **only** in `data/providers.json` (the runtime source the app fetches from this repo). Do **not** mirror edits into `app/src/main/assets/providers.json` (the bundled fallback snapshot, refreshed manually per release) or into `docs-site/` docs — except when cutting a release, when the snapshot is deliberately refreshed to match.

**Docs layout**: `docs-site/docs/` is the single source of truth for docs; `guide/` holds feature guides and `advanced/` holds environment setup and advanced tutorials. At build time the `syncAiDocs` task copies them into `assets/docs/`, so the AI inside the container sees `~/.aicode/docs/{guide,advanced}/*.md`. **Write for the user**: explain what to do, what they will see, and what to do when it goes wrong. Variable names, error codes, and internal implementation paths belong in `prompts/`, not in user docs.
