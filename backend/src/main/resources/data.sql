-- Idempotent PostgreSQL full-text search index (runs after Hibernate DDL).
-- The expression must match the one used in MessageRepository's native queries
-- so the planner can use this GIN index.
CREATE INDEX IF NOT EXISTS idx_messages_content_fts
    ON messages USING GIN (to_tsvector('english', coalesce(content, '')));

-- B-tree indexes for the hot query paths (history + conversation lookups)
CREATE INDEX IF NOT EXISTS idx_messages_public_ts
    ON messages (is_private, "timestamp" DESC);

CREATE INDEX IF NOT EXISTS idx_messages_participants
    ON messages (sender, receiver, "timestamp" DESC);
