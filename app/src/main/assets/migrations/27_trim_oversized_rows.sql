-- Clean up agent_messages rows that exceed limits because image-generation / multimodal models returned oversized base64 images
-- (SQLiteBlobTooBigException / the CursorWindow ~2MB single-row limit caused a crash on startup).
-- Rows containing embedded data:image base64: truncate to just before the base64 start and append a note;
-- all other overlong fields are truncated to the safe threshold of 200000 characters.
-- Note: SQL string literals in this file must not contain semicolons, otherwise MigrationLoader will split on them and break.

UPDATE agent_messages SET content = CASE
    WHEN instr(content, 'base64,') > 0 AND length(content) > 200000 THEN substr(content, 1, instr(content, 'base64,') - 1) || char(10) || '[图片数据已省略：内嵌图片数据过大]'
    WHEN length(content) > 200000 THEN substr(content, 1, 200000) || char(10) || '[内容过长，已截断]'
    ELSE content
END;

UPDATE agent_messages SET reasoning = CASE WHEN length(reasoning) > 200000 THEN substr(reasoning, 1, 200000) ELSE reasoning END;

UPDATE agent_messages SET toolArgs = CASE WHEN length(toolArgs) > 200000 THEN substr(toolArgs, 1, 200000) ELSE toolArgs END;

UPDATE agent_messages SET toolCallsJson = CASE WHEN length(toolCallsJson) > 200000 THEN substr(toolCallsJson, 1, 200000) ELSE toolCallsJson END;

UPDATE agent_messages SET attachmentsJson = CASE WHEN length(attachmentsJson) > 200000 THEN substr(attachmentsJson, 1, 200000) ELSE attachmentsJson END;
