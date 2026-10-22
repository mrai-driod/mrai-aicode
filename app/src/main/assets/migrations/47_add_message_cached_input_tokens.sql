-- Stores, on the assistant message, the number of input tokens that hit the server-side cache in this call, so the cache hit rate can be shown under each message bubble.
ALTER TABLE agent_messages ADD COLUMN cachedInputTokens INTEGER NOT NULL DEFAULT 0;
