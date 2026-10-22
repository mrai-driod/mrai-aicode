Create or update an `AGENTS.md` for the current codebase (improve it in place if one exists at the root; if only `CLAUDE.md` exists, improve that; if neither exists, create a new `AGENTS.md`).

The goal is a lean instruction file that helps future AI sessions make fewer mistakes and get productive quickly. Every line must be able to answer "without this, would an AI quite likely go wrong?" — delete anything that cannot.

Focus areas or constraints specified by the user (must be obeyed):
$ARGUMENTS

## What to read first
Prefer the highest-value sources:
- `README*`, root manifests (`package.json` / `Cargo.toml` / `pyproject.toml` / `go.mod` / `build.gradle.kts`, etc.), workspace configuration, lockfiles
- Build, test, lint, format, type-check, and code-generation configuration
- CI workflows and pre-commit / task-runner configuration
- Existing instruction files (`AGENTS.md`, `CLAUDE.md`, `.cursor/rules/`, `.cursorrules`, `.github/copilot-instructions.md`)

If the architecture is still unclear after the configuration and documentation, read a few representative code files to find entry points, module boundaries, and execution flow; prefer files that tie the system together.

Treat executable sources as truth: when documentation conflicts with configuration or scripts, trust configuration and scripts, and keep only what can be verified.

## What to distil
The facts most valuable to an AI working in this repository:
- Exact development commands, especially non-obvious ones
- How to run a single test, a single package, or one focused verification step
- Command ordering where it matters (e.g. `lint -> typecheck -> test`)
- Monorepo / multi-package boundaries, ownership of major directories, the real app or library entry points
- Framework and toolchain pitfalls: generated code, migrations, codegen, build artefacts, environment-variable loading, dev servers, deployment flow
- Repository-specific style or process that differs from language/framework defaults
- Testing pitfalls: fixtures, integration-test prerequisites, snapshot processes, services depended on, flaky or expensive suites
- Important constraints from existing instruction files that are worth keeping

Good `AGENTS.md` content is usually hard information that took reading several files to derive.

## Whether to ask the user
Ask only when the repository genuinely cannot answer an important question, and at most one short batch: unwritten team conventions, branch/PR/release expectations, known-but-unwritten environment or test prerequisites. Never ask about anything the repository already states clearly.

## Writing rules
Write only high-signal, repository-specific content:
- Exact commands and shortcuts an AI is likely to get wrong
- Architecture notes that cannot be inferred from file names
- Conventions that differ from language or framework defaults
- Environment setup requirements, quirks, and operational pitfalls
- Existing instruction sources worth citing

Do not write: generic software advice, long tutorials or exhaustive file trees, obvious language conventions, speculation or unverifiable content, or material better referenced than repeated here.

Omit anything uncertain; prefer short sections with bullet points. Keep it as short as the repository allows; for a complex repository, summarise only the few structural facts that actually change how an AI should work.

If an `AGENTS.md` (or `CLAUDE.md`) already exists: improve it in place rather than rewriting blindly — keep verified, useful content, remove empty words and stale statements, and align it with the current codebase.
