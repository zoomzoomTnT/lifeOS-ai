-- life-os schema.sql
-- Single SQLite database for WeChat Life OS
-- Money: integer cents. Times: UTC ISO-8601 with Z (TEXT). Enums enforced by CHECK.
-- Source of truth. Keep in sync with references/conventions.md enums.

PRAGMA foreign_keys = ON;
PRAGMA journal_mode = WAL;

-- ---------------------------------------------------------------------------
-- Core identity
-- ---------------------------------------------------------------------------

CREATE TABLE people (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  handle        TEXT    NOT NULL UNIQUE,          -- OpenClaw WeChat peer id (stable)
  display_name  TEXT,                             -- may change
  role          TEXT    NOT NULL DEFAULT 'member'
                CHECK (role IN ('owner','member','guest')),
  timezone      TEXT    NOT NULL DEFAULT 'Asia/Tokyo',
  created_at    TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ','now')),
  updated_at    TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ','now'))
);

CREATE INDEX idx_people_handle ON people(handle);

-- ---------------------------------------------------------------------------
-- Merchants (shops near home / office, favorites)
-- ---------------------------------------------------------------------------

CREATE TABLE merchants (
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

CREATE INDEX idx_merchants_name_norm ON merchants(name_norm);
CREATE INDEX idx_merchants_location  ON merchants(location_tag);

-- ---------------------------------------------------------------------------
-- Finance: receipts + line items + multi-user claims
-- ---------------------------------------------------------------------------

CREATE TABLE receipts (
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

CREATE INDEX idx_receipts_fingerprint ON receipts(fingerprint);
CREATE INDEX idx_receipts_payer       ON receipts(payer_id);
CREATE INDEX idx_receipts_status      ON receipts(status);
CREATE INDEX idx_receipts_printed     ON receipts(printed_at);

CREATE TABLE receipt_items (
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

CREATE INDEX idx_receipt_items_receipt ON receipt_items(receipt_id);
CREATE INDEX idx_receipt_items_norm    ON receipt_items(name_norm);

-- Multi-user / household claims (who can see / split)
CREATE TABLE receipt_claims (
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

CREATE TABLE food_knowledge (
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

CREATE TABLE food_prefs (
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

CREATE TABLE fridge_items (
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

CREATE INDEX idx_fridge_owner_status ON fridge_items(owner_id, status);
CREATE INDEX idx_fridge_expires      ON fridge_items(expires_at) WHERE status = 'in_stock';
CREATE INDEX idx_fridge_name_norm    ON fridge_items(name_norm);

-- ---------------------------------------------------------------------------
-- Memos (the only outbound / proactive channel)
-- ---------------------------------------------------------------------------

CREATE TABLE memos (
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

CREATE INDEX idx_memos_owner_status ON memos(owner_id, status);
CREATE INDEX idx_memos_due          ON memos(due_at) WHERE status IN ('open','snoozed');
CREATE INDEX idx_memos_source       ON memos(source_domain, source_table, source_id);

-- ---------------------------------------------------------------------------
-- Stocks (trial)
-- ---------------------------------------------------------------------------

CREATE TABLE holdings (
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

CREATE TABLE stock_events (
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

CREATE TABLE events (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  domain      TEXT    NOT NULL,                           -- finance / fridge / memos / stocks / system
  action      TEXT    NOT NULL,                           -- create / confirm / update / delete / fire ...
  actor_id    INTEGER REFERENCES people(id),
  entity_table TEXT,
  entity_id   INTEGER,
  payload_json TEXT,
  created_at  TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ','now'))
);

CREATE INDEX idx_events_domain ON events(domain, created_at);
CREATE INDEX idx_events_entity ON events(entity_table, entity_id);

-- ---------------------------------------------------------------------------
-- Bootstrap: single owner row (handle updated later by skill / API)
-- ---------------------------------------------------------------------------

INSERT INTO people (id, handle, display_name, role, timezone)
VALUES (1, 'owner', '主人', 'owner', 'Asia/Tokyo');
