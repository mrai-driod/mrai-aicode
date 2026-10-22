-- Verbatim snapshots (JSON text) of Anthropic thinking / redacted_thinking content blocks and Gemini model-turn parts.
-- These blocks must be sent back unmodified and in their original order; a single signature field cannot carry redacted opaque data and per-part signatures.
ALTER TABLE agent_messages ADD COLUMN thinkingBlocksJson TEXT DEFAULT NULL
