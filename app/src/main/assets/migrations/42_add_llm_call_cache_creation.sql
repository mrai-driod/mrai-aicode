-- Number of tokens written to the server-side cache (Anthropic cache_creation_input_tokens), billed at a separate rate in cost estimates.
ALTER TABLE llm_call_records ADD COLUMN cacheCreationTokens INTEGER NOT NULL DEFAULT 0
