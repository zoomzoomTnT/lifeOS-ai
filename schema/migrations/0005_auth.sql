-- Auth: per-client scoped API keys, security audit, human-in-the-loop approvals, passkeys.
-- Additive; SchemaInitializer ignores duplicate-column errors on re-runs.

-- Who did it: the client + credential behind every domain write and HTTP request.
ALTER TABLE events ADD COLUMN client_id TEXT;
ALTER TABLE events ADD COLUMN credential_id TEXT;     -- api key_id / 'passkey' (JWT jti later)
ALTER TABLE events ADD COLUMN request_id TEXT;
ALTER TABLE events ADD COLUMN approval_id INTEGER;
ALTER TABLE http_requests ADD COLUMN client_id TEXT;
ALTER TABLE http_requests ADD COLUMN credential_id TEXT;


-- A caller of the API (an agent like Grok Bot, a script, ...). Acts on behalf of person_id.
CREATE TABLE IF NOT EXISTS api_clients (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  client_id     TEXT    NOT NULL UNIQUE,            -- 'grokbot-chat', 'grokbot-routine'
  display_name  TEXT    NOT NULL,
  kind          TEXT    NOT NULL CHECK (kind IN ('agent','human_app','service')),
  person_id     INTEGER NOT NULL REFERENCES people(id),
  max_scopes    TEXT    NOT NULL,                   -- ceiling, space-separated
  created_at    TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ','now')),
  disabled_at   TEXT
);

-- Key = 'lok_<key_id>_<secret>'. Only HMAC-SHA256(LIFE_KEY_PEPPER, secret) is stored.
-- At most two active (unrevoked, unexpired) keys per client: old + new during rotation.
CREATE TABLE IF NOT EXISTS api_keys (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  key_id        TEXT    NOT NULL UNIQUE,            -- public lookup part (12 base32 chars)
  api_client_id INTEGER NOT NULL REFERENCES api_clients(id),   -- api_clients.id (not the client_id slug)
  secret_hash   TEXT    NOT NULL,                   -- hex HMAC-SHA256; never the secret
  scopes        TEXT    NOT NULL,                   -- subset of the client's max_scopes
  expires_at    TEXT,                               -- NULL = no expiry
  last_used_at  TEXT,
  last_used_ip  TEXT,
  created_at    TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ','now')),
  revoked_at    TEXT
);
-- Dev/test DBs that ran the first 0005 (c73473f) have api_keys.client_id: rename it. On any other
-- DB this fails with "no such column" (tolerated for RENAME COLUMN only); the index follows the rename.
ALTER TABLE api_keys RENAME COLUMN client_id TO api_client_id;
CREATE INDEX IF NOT EXISTS idx_api_keys_client ON api_keys(api_client_id);

-- Append-only security audit: auth failures, key create/revoke/new IP, scope denials,
-- approvals, passkey + one-time-token events. Not touched by /api/ops/purge.
CREATE TABLE IF NOT EXISTS security_events (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  at            TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ','now')),
  type          TEXT    NOT NULL,
  client_id     TEXT,
  credential_id TEXT,                               -- api key_id / 'passkey' / 'ott'
  person_id     INTEGER,
  ip            TEXT,
  method        TEXT,
  path          TEXT,
  outcome       TEXT,
  request_id    TEXT,                               -- = http_requests.correlation_id = events.request_id
  detail_json   TEXT
);
-- request_id came after the first 0005 (c73473f): add it there (duplicate column elsewhere, tolerated).
ALTER TABLE security_events ADD COLUMN request_id TEXT;
CREATE INDEX IF NOT EXISTS idx_security_events_at   ON security_events(at);
CREATE INDEX IF NOT EXISTS idx_security_events_type ON security_events(type, at);

-- Human-in-the-loop: one row per risky agent request. Single use, bound to the exact request
-- (method + path + canonical body + client), expires 10 minutes after creation.
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
  decided_by    INTEGER,
  decided_via   TEXT,                               -- 'passkey'
  consumed_at   TEXT
);
CREATE INDEX IF NOT EXISTS idx_approvals_status ON approval_requests(status, expires_at);

-- Spring Security WebAuthn (passkeys): JdbcPublicKeyCredentialUserEntityRepository /
-- JdbcUserCredentialRepository column names; SQLite types (blob, 0/1 booleans, ISO text times).
CREATE TABLE IF NOT EXISTS user_entities (
  id            TEXT    NOT NULL PRIMARY KEY,
  name          TEXT    NOT NULL,
  display_name  TEXT
);
CREATE TABLE IF NOT EXISTS user_credentials (
  credential_id                TEXT    NOT NULL PRIMARY KEY,
  user_entity_user_id          TEXT    NOT NULL,
  public_key                   BLOB    NOT NULL,
  signature_count              INTEGER,
  uv_initialized               INTEGER,
  backup_eligible              INTEGER NOT NULL,
  authenticator_transports     TEXT,
  public_key_credential_type   TEXT,
  backup_state                 INTEGER NOT NULL,
  attestation_object           BLOB,
  attestation_client_data_json BLOB,
  created                      TIMESTAMP,
  last_used                    TIMESTAMP,
  label                        TEXT    NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_user_credentials_user ON user_credentials(user_entity_user_id);

-- Spring-owned timestamp columns are TIMESTAMP (NUMERIC affinity, like Spring's reference DDL): the
-- libSQL driver stores ISO-8601 UTC text, sqlite-jdbc stores epoch millis; both read back. TEXT
-- would turn sqlite-jdbc's millis into an unparseable string.
-- Spring Security one-time-token login (JdbcOneTimeTokenService). Only used to register the
-- first passkey (or recover); tokens are minted by the admin CLI, valid 5 minutes, single use.
CREATE TABLE IF NOT EXISTS one_time_tokens (
  token_value   TEXT    NOT NULL PRIMARY KEY,
  username      TEXT    NOT NULL,
  expires_at    TIMESTAMP NOT NULL
);

-- Risk-tier thresholds (owner-editable). Money in integer cents, like everything else.
INSERT OR IGNORE INTO settings (key, value) VALUES
  ('approval.receipt_total_cents', '5000000'),     -- confirm >= 50,000.00 needs owner approval
  ('approval.bulk_rows', '20');                    -- a confirm writing more rows than this, too

INSERT OR IGNORE INTO schema_migrations (name) VALUES ('0005_auth');
