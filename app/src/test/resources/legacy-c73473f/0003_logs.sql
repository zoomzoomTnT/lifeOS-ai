-- Application logs vs AI session transcripts (privacy-separated).
-- Safe to re-run.

CREATE TABLE IF NOT EXISTS app_logs (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  occurred_at  TEXT    NOT NULL,
  ingested_at  TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ','now')),
  source       TEXT    NOT NULL
               CHECK (source IN ('app','scheduler','http','openclaw_gateway','docker','other')),
  level        TEXT    NOT NULL DEFAULT 'INFO',
  logger       TEXT,
  message      TEXT,
  meta_json    TEXT
);

CREATE INDEX IF NOT EXISTS idx_app_logs_ts     ON app_logs(occurred_at);
CREATE INDEX IF NOT EXISTS idx_app_logs_source ON app_logs(source, occurred_at);

-- PRIVATE: WeChat / model conversation. Do not mix with app_logs or /ops cards.
CREATE TABLE IF NOT EXISTS ai_session_logs (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  occurred_at  TEXT    NOT NULL,
  ingested_at  TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ','now')),
  source       TEXT    NOT NULL DEFAULT 'openclaw_session'
               CHECK (source IN ('openclaw_session','trajectory','transcript')),
  agent_id     TEXT,
  session_id   TEXT,
  session_key  TEXT,
  event_type   TEXT,
  role         TEXT,
  content      TEXT,
  content_len  INTEGER,
  usage_json   TEXT,
  file_path    TEXT    NOT NULL,
  line_no      INTEGER NOT NULL,
  raw_json     TEXT,
  UNIQUE (file_path, line_no)
);

CREATE INDEX IF NOT EXISTS idx_sess_ts      ON ai_session_logs(occurred_at);
CREATE INDEX IF NOT EXISTS idx_sess_session ON ai_session_logs(session_id, occurred_at);
CREATE INDEX IF NOT EXISTS idx_sess_source  ON ai_session_logs(source, occurred_at);

CREATE TABLE IF NOT EXISTS log_ingest_cursors (
  file_path     TEXT PRIMARY KEY,
  offset_bytes  INTEGER NOT NULL DEFAULT 0,
  line_no       INTEGER NOT NULL DEFAULT 0,
  updated_at    TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ','now'))
);

INSERT OR IGNORE INTO settings (key, value) VALUES
  ('session_log_retain_days', '90');

INSERT OR IGNORE INTO schema_migrations (name) VALUES ('0003_logs');
