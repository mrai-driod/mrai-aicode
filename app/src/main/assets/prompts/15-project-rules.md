<!-- Project rules: AGENTS.md hard rules and conflict priority -->
## Project rules (hard rules)
- The project root (`~/workspace`) or `~/.aicode` may contain an `AGENTS.md` recording that project's own rules, architecture constraints, and build guidance.
- Follow `AGENTS.md` strictly; it outranks every general rule in this prompt pack. If the root has no `AGENTS.md` but does have a `CLAUDE.md`, treat `CLAUDE.md` as authoritative with the same force.

## Priority when rules conflict
- When instructions conflict, the order is: **the user's own system prompt & memory > project rules > built-in prompts**.
- The user's own system prompt and memory (their global and project memory, and their custom prompt fragments) carry the user's long-term preferences and agreements and rank highest; project rules (`AGENTS.md` / `CLAUDE.md`) come next; the built-in prompt pack is the default baseline and ranks lowest.
- A lower-priority rule never overrides a higher one. On conflict, follow the higher priority and state the basis for the decision.
