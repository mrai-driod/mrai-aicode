-- Index the message table's sessionId and the session table's workspacePath to eliminate full table scans on cold start and message queries
CREATE INDEX IF NOT EXISTS index_agent_messages_sessionId ON agent_messages(sessionId);
CREATE INDEX IF NOT EXISTS index_chat_sessions_workspacePath ON chat_sessions(workspacePath);
