-- Clean up oversized JSON snapshot fields in agent_messages to keep single rows under the SQLite CursorWindow ~2MB limit
-- which otherwise throws SQLiteBlobTooBigException when reading messages and crashes on entering the chat page.
-- toolCallsJson / thinkingBlocksJson / attachmentsJson previously had no length cap when stored,
-- so huge tool arguments (e.g. writeFile writing a large file) or long thinking snapshots could blow up a row. Truncated JSON no longer parses,
-- readers degrade via runCatching to an empty list / empty snapshot instead of crashing.
UPDATE agent_messages SET toolCallsJson = CASE WHEN length(toolCallsJson) > 200000 THEN substr(toolCallsJson, 1, 200000) ELSE toolCallsJson END;
UPDATE agent_messages SET thinkingBlocksJson = CASE WHEN length(thinkingBlocksJson) > 200000 THEN substr(thinkingBlocksJson, 1, 200000) ELSE thinkingBlocksJson END;
UPDATE agent_messages SET attachmentsJson = CASE WHEN length(attachmentsJson) > 200000 THEN substr(attachmentsJson, 1, 200000) ELSE attachmentsJson END;
