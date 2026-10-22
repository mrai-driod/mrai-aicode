-- Add a retry-count column to the LLM call record table, recording how many low-level retries occurred within a single interaction
ALTER TABLE llm_call_records ADD COLUMN retryCount INTEGER NOT NULL DEFAULT 0;
