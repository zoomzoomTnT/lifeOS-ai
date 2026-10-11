-- OpenClaw session JSONL v3: event id ≠ session uuid, strip images, usage tokens.
-- Additive; SchemaInitializer ignores duplicate-column errors.

ALTER TABLE ai_session_logs ADD COLUMN event_id TEXT;
ALTER TABLE ai_session_logs ADD COLUMN parent_id TEXT;
ALTER TABLE ai_session_logs ADD COLUMN provider TEXT;
ALTER TABLE ai_session_logs ADD COLUMN model TEXT;
ALTER TABLE ai_session_logs ADD COLUMN stop_reason TEXT;
ALTER TABLE ai_session_logs ADD COLUMN tool_name TEXT;
ALTER TABLE ai_session_logs ADD COLUMN custom_type TEXT;
ALTER TABLE ai_session_logs ADD COLUMN heartbeat INTEGER NOT NULL DEFAULT 0;
ALTER TABLE ai_session_logs ADD COLUMN prompt_tokens INTEGER;
ALTER TABLE ai_session_logs ADD COLUMN completion_tokens INTEGER;
ALTER TABLE ai_session_logs ADD COLUMN cache_read_tokens INTEGER;
ALTER TABLE ai_session_logs ADD COLUMN cache_write_tokens INTEGER;
ALTER TABLE ai_session_logs ADD COLUMN total_tokens INTEGER;
ALTER TABLE ai_session_logs ADD COLUMN cost_micros INTEGER;
ALTER TABLE ai_session_logs ADD COLUMN media_paths_json TEXT;

CREATE INDEX IF NOT EXISTS idx_sess_event ON ai_session_logs(event_id);
CREATE INDEX IF NOT EXISTS idx_sess_hb    ON ai_session_logs(heartbeat, occurred_at);

ALTER TABLE log_ingest_cursors ADD COLUMN last_session_id TEXT;

INSERT OR IGNORE INTO model_prices (provider, model, input_usd_micros_per_mtok, output_usd_micros_per_mtok, notes) VALUES
  ('google', 'gemini-3.1-pro-preview', 2000000, 12000000, 'estimate; prefer invoice / TokenHub');

INSERT OR IGNORE INTO schema_migrations (name) VALUES ('0004_session_v3');
