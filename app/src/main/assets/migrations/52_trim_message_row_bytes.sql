-- Fallback cleanup of agent_messages rows whose total text exceeds the limit.
-- When the sum of all large text fields in a single row exceeds the SQLite CursorWindow (~2MB) safety budget, the row may fail to be
-- loaded into the window, throwing IllegalStateException "Couldn't read row N, col 0 from CursorWindow" when messages are read,
-- crashing on entering the chat page. The storage side now caps each field by UTF-8 bytes; this cleans up historical rows.
-- The character cap is one third of the byte budget (a Chinese character takes up to 3 bytes), ensuring the actual byte count after trimming falls within budget.
UPDATE agent_messages SET
    content = substr(content, 1, 50000),
    toolCallsJson = CASE WHEN toolCallsJson IS NULL THEN NULL ELSE substr(toolCallsJson, 1, 33333) END,
    toolArgs = CASE WHEN toolArgs IS NULL THEN NULL ELSE substr(toolArgs, 1, 666) END,
    reasoning = CASE WHEN reasoning IS NULL THEN NULL ELSE substr(reasoning, 1, 33333) END,
    signature = CASE WHEN signature IS NULL THEN NULL ELSE substr(signature, 1, 33333) END,
    attachmentsJson = CASE WHEN attachmentsJson IS NULL THEN NULL ELSE substr(attachmentsJson, 1, 6666) END,
    thinkingBlocksJson = CASE WHEN thinkingBlocksJson IS NULL THEN NULL ELSE substr(thinkingBlocksJson, 1, 33333) END
WHERE (length(CAST(content AS BLOB))
    + coalesce(length(CAST(toolCallsJson AS BLOB)), 0)
    + coalesce(length(CAST(toolArgs AS BLOB)), 0)
    + coalesce(length(CAST(reasoning AS BLOB)), 0)
    + coalesce(length(CAST(signature AS BLOB)), 0)
    + coalesce(length(CAST(attachmentsJson AS BLOB)), 0)
    + coalesce(length(CAST(thinkingBlocksJson AS BLOB)), 0)) > 600000;
