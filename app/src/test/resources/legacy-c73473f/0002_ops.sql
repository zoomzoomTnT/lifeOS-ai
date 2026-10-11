-- Additive ops schema for databases created before 0002.
-- Safe to re-run: IF NOT EXISTS / OR IGNORE.

CREATE TABLE IF NOT EXISTS schema_migrations (
  name        TEXT PRIMARY KEY,
  applied_at  TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ','now'))
);

CREATE TABLE IF NOT EXISTS settings (
  key         TEXT PRIMARY KEY,
  value       TEXT NOT NULL,
  updated_at  TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ','now'))
);

CREATE TABLE IF NOT EXISTS model_prices (
  id                         INTEGER PRIMARY KEY AUTOINCREMENT,
  provider                   TEXT    NOT NULL,
  model                      TEXT    NOT NULL,
  input_usd_micros_per_mtok  INTEGER NOT NULL,
  output_usd_micros_per_mtok INTEGER NOT NULL,
  notes                      TEXT,
  updated_at                 TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ','now')),
  UNIQUE (provider, model)
);

CREATE TABLE IF NOT EXISTS http_requests (
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  correlation_id  TEXT,
  actor_id        INTEGER REFERENCES people(id),
  method          TEXT    NOT NULL,
  path            TEXT    NOT NULL,
  query           TEXT,
  status          INTEGER,
  latency_ms      INTEGER,
  request_bytes   INTEGER,
  response_bytes  INTEGER,
  body_excerpt    TEXT,
  error           TEXT,
  created_at      TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ','now'))
);

CREATE INDEX IF NOT EXISTS idx_http_created ON http_requests(created_at);
CREATE INDEX IF NOT EXISTS idx_http_path    ON http_requests(path, created_at);
CREATE INDEX IF NOT EXISTS idx_http_corr    ON http_requests(correlation_id);

CREATE TABLE IF NOT EXISTS ai_calls (
  id               INTEGER PRIMARY KEY AUTOINCREMENT,
  correlation_id   TEXT,
  actor_id         INTEGER REFERENCES people(id),
  source           TEXT    NOT NULL DEFAULT 'skill'
                   CHECK (source IN ('skill','heartbeat','automation','app','other')),
  purpose          TEXT    NOT NULL DEFAULT 'other'
                   CHECK (purpose IN ('receipt_ocr','classify','heartbeat','memo','chat','embedding','other')),
  provider         TEXT    NOT NULL,
  model            TEXT    NOT NULL,
  prompt_tokens    INTEGER NOT NULL DEFAULT 0,
  completion_tokens INTEGER NOT NULL DEFAULT 0,
  total_tokens     INTEGER NOT NULL DEFAULT 0,
  cost_micros      INTEGER NOT NULL DEFAULT 0,
  currency         TEXT    NOT NULL DEFAULT 'USD',
  latency_ms       INTEGER,
  status           TEXT    NOT NULL DEFAULT 'ok'
                   CHECK (status IN ('ok','error','skipped')),
  error            TEXT,
  http_request_id  INTEGER REFERENCES http_requests(id),
  meta_json        TEXT,
  created_at       TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ','now'))
);

CREATE INDEX IF NOT EXISTS idx_ai_created ON ai_calls(created_at);
CREATE INDEX IF NOT EXISTS idx_ai_model   ON ai_calls(provider, model, created_at);
CREATE INDEX IF NOT EXISTS idx_ai_purpose ON ai_calls(purpose, created_at);

CREATE VIEW IF NOT EXISTS v_ai_daily AS
SELECT
  substr(created_at, 1, 10) AS day,
  COUNT(*)                  AS calls,
  SUM(prompt_tokens)        AS prompt_tokens,
  SUM(completion_tokens)    AS completion_tokens,
  SUM(total_tokens)         AS total_tokens,
  SUM(cost_micros)          AS cost_micros
FROM ai_calls
GROUP BY substr(created_at, 1, 10);

INSERT OR IGNORE INTO settings (key, value) VALUES
  ('ai_daily_budget_usd_micros', '5000000'),
  ('http_body_log_max_bytes', '4096');

INSERT OR IGNORE INTO model_prices (provider, model, input_usd_micros_per_mtok, output_usd_micros_per_mtok, notes) VALUES
  ('xai', 'grok-4',            3000000, 15000000, 'estimate'),
  ('xai', 'grok-4-fast',        200000,   500000, 'estimate'),
  ('xai', 'grok-2-vision-1212', 2000000, 10000000, 'estimate, vision'),
  ('openai', 'gpt-4o',          2500000, 10000000, 'list price-ish'),
  ('openai', 'gpt-4o-mini',      150000,   600000, 'list price-ish'),
  ('anthropic', 'claude-sonnet-4', 3000000, 15000000, 'list price-ish');

INSERT OR IGNORE INTO schema_migrations (name) VALUES ('0002_ops');
