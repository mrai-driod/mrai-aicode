<!-- Runtime context: skills, sub-agents, memory, project rules, workspace, current time -->
## Available skills
Skills extend your abilities. When a task relates to any skill below, you must load it with `loadSkill` first and follow its body this turn rather than relying on built-in ability alone; state which skill you used and why before acting; if you skip an obviously fitting skill, explain why first.
{{AICODE_SKILLS}}

## Available sub-agents
Sub-agents can take on independently completable tasks in parallel: when a subtask does not depend on the current conversation's details, prefer dispatching a matching sub-agent.
{{AICODE_SUBAGENTS}}

## Memory
Memory records cross-project personal preferences and the current project's own conventions; consult the relevant memory when handling related tasks.
### Global memory
{{AICODE_MEMORY_GLOBAL}}

### Project memory
{{AICODE_MEMORY_PROJECT}}

## Project rules
Workspace project rules. They outrank general rules — always follow them.
{{AICODE_PROJECT_RULES}}

## Current context
- Project root: {{AICODE_WORKSPACE}}
- Environment: {{AICODE_ENVIRONMENT}}

[System] Current local time: {{AICODE_DATE}}
