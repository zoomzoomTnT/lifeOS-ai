-- life-os schema.sql
-- Single SQLite database for WeChat Life OS
-- Money: integer cents. Times: UTC ISO-8601 with Z (TEXT). Enums enforced by CHECK.
-- Source of truth. Keep in sync with references/conventions.md enums.
-- The only schema file. Applied on every start and idempotent (IF NOT EXISTS / INSERT OR IGNORE).
-- Columns added to tables that already exist in older databases are also listed in
-- SchemaInitializer.REQUIRED_COLUMNS (SQLite has no ADD COLUMN IF NOT EXISTS).

PRAGMA foreign_keys = ON;
PRAGMA journal_mode = WAL;

-- ---------------------------------------------------------------------------
-- Core identity
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS people (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  handle        TEXT    NOT NULL UNIQUE,          -- OpenClaw WeChat peer id (stable)
  display_name  TEXT,                             -- may change
  role          TEXT    NOT NULL DEFAULT 'member'
                CHECK (role IN ('owner','member','guest')),
  timezone      TEXT    NOT NULL DEFAULT 'Asia/Tokyo',
  created_at    TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ','now')),
  updated_at    TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ','now'))
);

CREATE INDEX IF NOT EXISTS idx_people_handle ON people(handle);

-- ---------------------------------------------------------------------------
-- Merchants (shops near home / office, favorites)
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS merchants (
  id             INTEGER PRIMARY KEY AUTOINCREMENT,
  name           TEXT    NOT NULL,
  name_norm      TEXT    NOT NULL UNIQUE,         -- lowercase, strip spaces/punct; Chinese stays
  kind           TEXT    NOT NULL DEFAULT 'other'
                 CHECK (kind IN ('supermarket','restaurant','cafe','market','other')),
  location_tag   TEXT    NOT NULL DEFAULT 'other'
                 CHECK (location_tag IN ('home_nearby','office_nearby','other')),
  favorite_score REAL    NOT NULL DEFAULT 0.0,    -- clamp -2 .. 2
  notes          TEXT,
  created_at     TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ','now')),
  updated_at     TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ','now'))
);

CREATE INDEX IF NOT EXISTS idx_merchants_name_norm ON merchants(name_norm);
CREATE INDEX IF NOT EXISTS idx_merchants_location  ON merchants(location_tag);

-- ---------------------------------------------------------------------------
-- Finance: receipts + line items + multi-user claims
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS receipts (
  id                INTEGER PRIMARY KEY AUTOINCREMENT,
  merchant_id       INTEGER REFERENCES merchants(id),
  payer_id          INTEGER NOT NULL REFERENCES people(id),  -- expense owner
  barcode           TEXT,                                   -- digits / order id / ticket no
  printed_at        TEXT,                                   -- as printed on ticket (local string)
  fingerprint       TEXT    NOT NULL UNIQUE,                -- sha256(barcode|printed_at)[:32] or weak key
  currency          TEXT    NOT NULL DEFAULT 'CNY',
  total_cents       INTEGER NOT NULL,                       -- footer total
  computed_cents    INTEGER,                                -- sum of lines
  tax_cents         INTEGER DEFAULT 0,
  discount_cents    INTEGER DEFAULT 0,
  status            TEXT    NOT NULL DEFAULT 'pending_confirm'
                    CHECK (status IN ('pending_confirm','confirmed','rejected','duplicate')),
  raw_ocr_json      TEXT,                                   -- full vision extract
  image_path        TEXT,
  notes             TEXT,
  created_at        TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ','now')),
  updated_at        TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ','now')),
  confirmed_at      TEXT
);

CREATE INDEX IF NOT EXISTS idx_receipts_fingerprint ON receipts(fingerprint);
CREATE INDEX IF NOT EXISTS idx_receipts_payer       ON receipts(payer_id);
CREATE INDEX IF NOT EXISTS idx_receipts_status      ON receipts(status);
CREATE INDEX IF NOT EXISTS idx_receipts_printed     ON receipts(printed_at);

CREATE TABLE IF NOT EXISTS receipt_items (
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  receipt_id      INTEGER NOT NULL REFERENCES receipts(id) ON DELETE CASCADE,
  name            TEXT    NOT NULL,
  name_norm       TEXT    NOT NULL,
  qty             REAL    NOT NULL DEFAULT 1,
  unit            TEXT,
  amount_cents    INTEGER NOT NULL,                 -- line total
  is_food         INTEGER NOT NULL DEFAULT 0,       -- 0/1
  category        TEXT    CHECK (category IS NULL OR category IN
                    ('veg','fruit','meat','seafood','dairy','drink','leftover','staple','other')),
  sort_order      INTEGER NOT NULL DEFAULT 0,
  created_at      TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ','now'))
);

CREATE INDEX IF NOT EXISTS idx_receipt_items_receipt ON receipt_items(receipt_id);
CREATE INDEX IF NOT EXISTS idx_receipt_items_norm    ON receipt_items(name_norm);

-- Multi-user / household claims (who can see / split)
CREATE TABLE IF NOT EXISTS receipt_claims (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  receipt_id  INTEGER NOT NULL REFERENCES receipts(id) ON DELETE CASCADE,
  person_id   INTEGER NOT NULL REFERENCES people(id),
  share_cents INTEGER,                              -- optional split amount
  note        TEXT,
  created_at  TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ','now')),
  UNIQUE (receipt_id, person_id)
);

-- ---------------------------------------------------------------------------
-- Food knowledge & long-term prefs
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS food_knowledge (
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  name_norm       TEXT    NOT NULL UNIQUE,
  display_name    TEXT,
  category        TEXT    CHECK (category IN
                    ('veg','fruit','meat','seafood','dairy','drink','leftover','staple','other')),
  default_days    INTEGER,                          -- shelf life days (fridge)
  aliases_json    TEXT,                             -- JSON array of alternative names
  notes           TEXT,
  created_at      TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ','now'))
);

CREATE TABLE IF NOT EXISTS food_prefs (
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  person_id       INTEGER NOT NULL REFERENCES people(id),
  name_norm       TEXT    NOT NULL,
  preference      INTEGER CHECK (preference BETWEEN 1 AND 5),  -- 1 dislike .. 5 love
  repurchase      TEXT    CHECK (repurchase IN ('yes','maybe','no')),
  last_eaten_at   TEXT,
  last_discarded_at TEXT,
  discard_streak  INTEGER NOT NULL DEFAULT 0,
  notes           TEXT,
  updated_at      TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ','now')),
  UNIQUE (person_id, name_norm)
);

-- ---------------------------------------------------------------------------
-- Fridge
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS fridge_items (
  id                    INTEGER PRIMARY KEY AUTOINCREMENT,
  owner_id              INTEGER NOT NULL REFERENCES people(id),   -- food owner
  added_by_id           INTEGER NOT NULL REFERENCES people(id),   -- who put it in
  name                  TEXT    NOT NULL,
  name_norm             TEXT    NOT NULL,
  category              TEXT    CHECK (category IN
                          ('veg','fruit','meat','seafood','dairy','drink','leftover','staple','other')),
  location              TEXT    NOT NULL DEFAULT 'fridge'
                        CHECK (location IN ('fridge','freezer','pantry','counter')),
  status                TEXT    NOT NULL DEFAULT 'in_stock'
                        CHECK (status IN ('in_stock','eaten','discarded','expired','gifted')),
  qty                   REAL    NOT NULL DEFAULT 1,
  unit                  TEXT,
  purchased_at          TEXT,                                     -- UTC
  expires_at            TEXT,                                     -- UTC
  preference            INTEGER CHECK (preference BETWEEN 1 AND 5), -- this instance
  source_receipt_id     INTEGER REFERENCES receipts(id),
  source_receipt_item_id INTEGER REFERENCES receipt_items(id),
  notes                 TEXT,
  created_at            TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ','now')),
  updated_at            TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ','now'))
);

CREATE INDEX IF NOT EXISTS idx_fridge_owner_status ON fridge_items(owner_id, status);
CREATE INDEX IF NOT EXISTS idx_fridge_expires      ON fridge_items(expires_at) WHERE status = 'in_stock';
CREATE INDEX IF NOT EXISTS idx_fridge_name_norm    ON fridge_items(name_norm);

-- ---------------------------------------------------------------------------
-- Memos (the only outbound / proactive channel)
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS memos (
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  owner_id        INTEGER NOT NULL REFERENCES people(id),
  title           TEXT    NOT NULL,
  body            TEXT,
  kind            TEXT    NOT NULL DEFAULT 'reminder'
                  CHECK (kind IN ('reminder','followup','expiry','options','restock','brief','custom')),
  status          TEXT    NOT NULL DEFAULT 'open'
                  CHECK (status IN ('open','snoozed','done','cancelled')),
  priority        INTEGER NOT NULL DEFAULT 3 CHECK (priority BETWEEN 1 AND 5),
  due_at          TEXT,                                   -- UTC next fire (one-shot or computed)
  timezone        TEXT    NOT NULL DEFAULT 'Asia/Tokyo',
  cron_expr       TEXT,                                   -- empty = one-shot
  cron_tz         TEXT,                                   -- IANA when recurring
  source_domain   TEXT,                                   -- finance / fridge / stocks / manual
  source_table    TEXT,
  source_id       INTEGER,
  payload_json    TEXT,
  automation_id   TEXT,                                   -- OpenClaw automation id
  last_fired_at   TEXT,
  created_at      TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ','now')),
  updated_at      TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ','now'))
);

CREATE INDEX IF NOT EXISTS idx_memos_owner_status ON memos(owner_id, status);
CREATE INDEX IF NOT EXISTS idx_memos_due          ON memos(due_at) WHERE status IN ('open','snoozed');
CREATE INDEX IF NOT EXISTS idx_memos_source       ON memos(source_domain, source_table, source_id);

-- ---------------------------------------------------------------------------
-- Stocks (trial)
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS holdings (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  owner_id    INTEGER NOT NULL REFERENCES people(id),
  symbol      TEXT    NOT NULL,
  market      TEXT    NOT NULL CHECK (market IN ('US','HK','CN')),
  name        TEXT,
  qty         REAL    NOT NULL DEFAULT 0,
  avg_cost    REAL,                                       -- per share, in currency
  currency    TEXT    NOT NULL DEFAULT 'USD',
  notes       TEXT,
  created_at  TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ','now')),
  updated_at  TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ','now')),
  UNIQUE (owner_id, symbol, market)
);

CREATE TABLE IF NOT EXISTS stock_events (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  holding_id  INTEGER NOT NULL REFERENCES holdings(id) ON DELETE CASCADE,
  kind        TEXT    NOT NULL CHECK (kind IN ('options_expiry','earnings','dividend','custom')),
  event_date  TEXT,                                       -- date or datetime
  notes       TEXT,
  memo_id     INTEGER REFERENCES memos(id),
  created_at  TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ','now'))
);

-- ---------------------------------------------------------------------------
-- Audit / event log (cross-domain)
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS events (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  domain      TEXT    NOT NULL,                           -- finance / fridge / memos / stocks / system
  action      TEXT    NOT NULL,                           -- create / confirm / update / delete / fire ...
  actor_id    INTEGER REFERENCES people(id),
  entity_table TEXT,
  entity_id   INTEGER,
  payload_json TEXT,
  created_at  TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ','now')),
  client_id   TEXT,                                       -- api client ('grokbot-chat', 'lifeos-admin')
  credential_id TEXT,                                     -- api key_id
  request_id  TEXT,                                       -- X-Request-Id
  approval_id INTEGER                                     -- approval_requests.id, if approved
);

CREATE INDEX IF NOT EXISTS idx_events_domain ON events(domain, created_at);
CREATE INDEX IF NOT EXISTS idx_events_entity ON events(entity_table, entity_id);

-- ---------------------------------------------------------------------------
-- Bootstrap: single owner row (handle updated later by skill / API)
-- ---------------------------------------------------------------------------

INSERT OR IGNORE INTO people (id, handle, display_name, role, timezone)
VALUES (1, 'owner', '主人', 'owner', 'Asia/Tokyo');

-- ---------------------------------------------------------------------------
-- Ops: HTTP access log + AI usage (cost in USD micros, not CNY cents)
-- 1 USD = 1_000_000 micros. Display: cost_micros / 1e6 as $x.xxxxxx
-- ---------------------------------------------------------------------------

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
  input_usd_micros_per_mtok  INTEGER NOT NULL,   -- USD micros per 1M input tokens
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
  body_excerpt    TEXT,                          -- truncated JSON, never images
  error           TEXT,
  created_at      TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ','now')),
  client_id       TEXT,
  credential_id   TEXT
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
  cost_micros      INTEGER NOT NULL DEFAULT 0,   -- USD micros
  currency         TEXT    NOT NULL DEFAULT 'USD',
  latency_ms       INTEGER,
  status           TEXT    NOT NULL DEFAULT 'ok'
                   CHECK (status IN ('ok','error','skipped')),
  error            TEXT,
  http_request_id  INTEGER REFERENCES http_requests(id),
  meta_json        TEXT,                         -- no full prompts by default
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
  ('ai_daily_budget_usd_micros', '5000000'),  -- $5 / day default
  ('http_body_log_max_bytes', '4096');

-- Prices are estimates; PATCH /api/ops/prices when you have the real rate card.
-- input/output = USD micros per 1 million tokens ($3.00 / 1M = 3000000).
INSERT OR IGNORE INTO model_prices (provider, model, input_usd_micros_per_mtok, output_usd_micros_per_mtok, notes) VALUES
  ('xai', 'grok-4',            3000000, 15000000, 'estimate'),
  ('xai', 'grok-4-fast',        200000,   500000, 'estimate'),
  ('xai', 'grok-2-vision-1212', 2000000, 10000000, 'estimate, vision'),
  ('openai', 'gpt-4o',          2500000, 10000000, 'list price-ish'),
  ('openai', 'gpt-4o-mini',      150000,   600000, 'list price-ish'),
  ('anthropic', 'claude-sonnet-4', 3000000, 15000000, 'list price-ish');

INSERT OR IGNORE INTO schema_migrations (name) VALUES ('0001_base'), ('0002_ops'), ('0003_logs'), ('0004_session_v3'), ('0005_auth');

-- ---------------------------------------------------------------------------
-- Logs: app vs AI session (privacy). AI conversation NEVER lives in app_logs.
-- ---------------------------------------------------------------------------

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

CREATE TABLE IF NOT EXISTS ai_session_logs (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  occurred_at  TEXT    NOT NULL,
  ingested_at  TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ','now')),
  source       TEXT    NOT NULL DEFAULT 'openclaw_session'
               CHECK (source IN ('openclaw_session','trajectory','transcript')),
  agent_id     TEXT,
  session_id   TEXT,
  session_key  TEXT,
  event_id     TEXT,
  parent_id    TEXT,
  event_type   TEXT,
  role         TEXT,
  provider     TEXT,
  model        TEXT,
  stop_reason  TEXT,
  tool_name    TEXT,
  custom_type  TEXT,
  heartbeat    INTEGER NOT NULL DEFAULT 0,
  prompt_tokens INTEGER,
  completion_tokens INTEGER,
  cache_read_tokens INTEGER,
  cache_write_tokens INTEGER,
  total_tokens INTEGER,
  cost_micros  INTEGER,
  media_paths_json TEXT,
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
CREATE INDEX IF NOT EXISTS idx_sess_event   ON ai_session_logs(event_id);
CREATE INDEX IF NOT EXISTS idx_sess_hb      ON ai_session_logs(heartbeat, occurred_at);

CREATE TABLE IF NOT EXISTS log_ingest_cursors (
  file_path        TEXT PRIMARY KEY,
  offset_bytes     INTEGER NOT NULL DEFAULT 0,
  line_no          INTEGER NOT NULL DEFAULT 0,
  last_session_id  TEXT,
  updated_at       TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ','now'))
);

INSERT OR IGNORE INTO settings (key, value) VALUES ('session_log_retain_days', '90');

INSERT OR IGNORE INTO model_prices (provider, model, input_usd_micros_per_mtok, output_usd_micros_per_mtok, notes) VALUES
  ('google', 'gemini-3.1-pro-preview', 2000000, 12000000, 'estimate; prefer invoice / TokenHub');

-- ---------------------------------------------------------------------------
-- Auth (0005): per-client scoped API keys, security audit, approvals
-- ---------------------------------------------------------------------------

-- A caller of the API (an agent like Grok Bot, a script, the admin bot). Acts on behalf of person_id.
-- Exactly one client may be kind 'admin' (the only holder of ops:admin, see AdminKeyBootstrap).
CREATE TABLE IF NOT EXISTS api_clients (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  client_id     TEXT    NOT NULL UNIQUE,            -- 'grokbot-chat', 'grokbot-routine', 'lifeos-admin'
  display_name  TEXT    NOT NULL,
  kind          TEXT    NOT NULL CHECK (kind IN ('agent','human_app','service','admin')),
  person_id     INTEGER NOT NULL REFERENCES people(id),
  max_scopes    TEXT    NOT NULL,                   -- ceiling, space-separated
  created_at    TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ','now')),
  disabled_at   TEXT
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_api_clients_one_admin ON api_clients(kind) WHERE kind = 'admin';

-- Key = 'lok_<key_id>_<secret>'. Only HMAC-SHA256(LIFE_KEY_PEPPER, secret) is stored; for the
-- admin client's key, the SHA-256 of the secret (from the LIFE_ADMIN_KEY_SHA256 deploy secret).
-- At most two active (unrevoked, unexpired) keys per client: old + new during rotation.
CREATE TABLE IF NOT EXISTS api_keys (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  key_id        TEXT    NOT NULL UNIQUE,            -- public lookup part (12 base32 chars)
  api_client_id INTEGER NOT NULL REFERENCES api_clients(id),   -- api_clients.id (not the client_id slug)
  secret_hash   TEXT    NOT NULL,                   -- hex HMAC-SHA256 (admin: SHA-256); never the secret
  scopes        TEXT    NOT NULL,                   -- subset of the client's max_scopes
  expires_at    TEXT,                               -- NULL = no expiry
  last_used_at  TEXT,
  last_used_ip  TEXT,
  created_at    TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ','now')),
  revoked_at    TEXT
);
CREATE INDEX IF NOT EXISTS idx_api_keys_client ON api_keys(api_client_id);

-- Append-only security audit: auth failures, key create/revoke/new IP, scope denials,
-- approvals. Not touched by /api/ops/purge.
CREATE TABLE IF NOT EXISTS security_events (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  at            TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ','now')),
  type          TEXT    NOT NULL,
  client_id     TEXT,
  credential_id TEXT,                               -- api key_id
  person_id     INTEGER,
  ip            TEXT,
  method        TEXT,
  path          TEXT,
  outcome       TEXT,
  request_id    TEXT,                               -- = http_requests.correlation_id = events.request_id
  detail_json   TEXT
);
CREATE INDEX IF NOT EXISTS idx_security_events_at   ON security_events(at);
CREATE INDEX IF NOT EXISTS idx_security_events_type ON security_events(type, at);

-- Human-in-the-loop: one row per risky agent request. Single use, bound to the exact request
-- (method + path + canonical body + client), expires 5 minutes after creation. Decided only
-- by the admin client (never the requester).
CREATE TABLE IF NOT EXISTS approval_requests (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  public_id     TEXT    NOT NULL UNIQUE,            -- unguessable, used in links + Life-Approval
  client_id     TEXT    NOT NULL,
  credential_id TEXT,
  person_id     INTEGER NOT NULL,
  action        TEXT    NOT NULL,                   -- 'receipt.confirm', 'ops.prices.update'
  method        TEXT    NOT NULL,
  path          TEXT    NOT NULL,
  request_hash  TEXT    NOT NULL,                   -- SHA-256 of method, path, client, canonical body
  summary_json  TEXT    NOT NULL,                   -- rendered by the server from its own data
  risk_tier     INTEGER NOT NULL,
  status        TEXT    NOT NULL CHECK (status IN ('pending','approved','denied','expired','consumed')),
  created_at    TEXT    NOT NULL,
  expires_at    TEXT    NOT NULL,
  decided_at    TEXT,
  decided_by    INTEGER,                            -- people.id of the admin client
  decided_via   TEXT,                               -- 'admin_key'
  decided_by_client     TEXT,                     -- api_clients.client_id of the approver
  decided_by_credential TEXT,                     -- api_keys.key_id of the approver
  consumed_at   TEXT
);
CREATE INDEX IF NOT EXISTS idx_approvals_status ON approval_requests(status, expires_at);

-- Risk-tier thresholds (owner-editable). Money in integer cents, like everything else.
INSERT OR IGNORE INTO settings (key, value) VALUES
  ('approval.receipt_total_cents', '5000000'),     -- confirm >= 50,000.00 needs approval
  ('approval.bulk_rows', '20');                    -- a confirm writing more rows than this, too
