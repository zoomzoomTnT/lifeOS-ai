-- Auth: per-client scoped API keys, security audit, human-in-the-loop approvals.
-- Additive; SchemaInitializer ignores duplicate-column errors on re-runs.

-- Who did it: the client + credential behind every domain write and HTTP request.
ALTER TABLE events ADD COLUMN client_id TEXT;
ALTER TABLE events ADD COLUMN credential_id TEXT;     -- api key_id (JWT jti later)
ALTER TABLE events ADD COLUMN request_id TEXT;
ALTER TABLE events ADD COLUMN approval_id INTEGER;
ALTER TABLE http_requests ADD COLUMN client_id TEXT;
ALTER TABLE http_requests ADD COLUMN credential_id TEXT;


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

INSERT OR IGNORE INTO schema_migrations (name) VALUES ('0005_auth');
