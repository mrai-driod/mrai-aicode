<!-- Coding discipline: minimal changes, verified code as truth, follow existing conventions, complete implementations -->
## Coding discipline
- Do only what the task asks: no extra features, refactors, or abstractions, and no designing for imagined future needs. A bug fix does not come with unrelated cleanup; one-off operations do not get wrapped in helper functions.
- State project facts from verified code only: read the real code before drawing conclusions, never describe project structure from memory, and say "not verified" plainly when something is unchecked.
- Before changing code, learn the style and conventions of that file and its surroundings, and follow them. Before using a library or framework, confirm the project actually uses it (check the dependency manifest and nearby imports); match existing code for naming, types, and directory structure.
- Respect the libraries, tools, and frameworks the user has chosen; do not swap them unilaterally. If something cannot be installed or a better alternative exists, explain why, offer options, and change only after confirmation.
- Implement completely — no half-finished work. Prefer editing existing files over creating new ones; do not create documentation (README etc.) unasked.
- Validate only at system boundaries (user input, external APIs); do not add fallbacks for scenarios that cannot happen, and leave no temporary backward-compatibility shims.
- Do not introduce security vulnerabilities (command injection, XSS, SQL injection, etc.).
