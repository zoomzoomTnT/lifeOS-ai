# life-os REST API Contract (v0.1)

Base URL: `http://localhost:8787` (or `$LIFE_API_BASE`)

**Try it in the browser:** [Swagger UI](http://localhost:8787/swagger-ui.html)  
OpenAPI JSON: `/v3/api-docs` (groups: `/v3/api-docs/api`, `/v3/api-docs/actuator`)

1. Open Swagger UI, top-right **Authorize**, put `X-Life-Handle` (WeChat peer id or `owner`).
2. Dropdown **api** vs **actuator**.
3. Expand an operation → **Try it out** → **Execute**.

This markdown is still the field-level contract (cents, fingerprints). Swagger request/response bodies come from DTOs (MapStruct maps domain ↔ DTO).




All responses are JSON. Money fields are **integer cents**. Times are UTC ISO-8601 with `Z` unless noted.

Authentication (v0.1): simple header `X-Life-Handle: <people.handle>`  
(Later: JWT / OpenClaw peer token.)

Common error shape:
```json
{ "error": "code", "message": "human readable", "details": {} }
```

---

## Health & Meta

No custom `/api/health` or `/api/path`. Use Actuator.

### `GET /actuator/health`

`LifeHealthIndicator` (component `life`) plus JDBC. DOWN → HTTP 503.

```json
{
  "status": "UP",
  "components": {
    "db": { "status": "UP" },
    "life": {
      "status": "UP",
      "details": { "db": "ok", "version": "0.1.0" }
    }
  }
}
```

Also: `/actuator/health/liveness`, `/actuator/health/readiness`.

### `GET /actuator/db`

SQLite file + owner timezone (replaced `/api/path`).

```json
{
  "jdbcUrl": "jdbc:sqlite:/data/life.db",
  "file": "/data/life.db",
  "ownerTimezone": "Asia/Tokyo",
  "ping": "ok"
}
```

### Actuator (`/actuator`)

Default: `health`, `info`, `metrics`, `db`, `scheduledtasks`, `loggers`, `mappings`, `threaddump`.

| Path | Notes |
|---|---|
| `GET /actuator` | index of enabled endpoints |
| `GET /actuator/health` | JDBC + component `life` (ping, version) |
| `GET /actuator/health/liveness` | process alive |
| `GET /actuator/health/readiness` | ready for traffic |
| `GET /actuator/db` | sqlite path, timezone, ping |
| `GET /actuator/info` | `info.app` name/version |
| `GET /actuator/metrics` | list; `.../metrics/jvm.memory.used` for one |
| `GET /actuator/scheduledtasks` | Java cron (proactive scan) |
| `GET /actuator/loggers` | logger levels |
| `GET /actuator/mappings` | all Spring MVC routes |

Local profile exposes `*` (`env`, `configprops`, `beans`, `heapdump`). Do not do that on a public bind.

Override: `LIFE_ACTUATOR_ENDPOINTS=health,info,db`

Docker / skill healthcheck: `GET /actuator/health`.

---

## People

### `GET /api/people/me`
Resolve current handle → person record.

### `PUT /api/people/me`
Update display_name / timezone.

### `POST /api/people`
Create member/guest (admin only later).

---

## Merchants

### `GET /api/merchants?q=盒马`
Search by name_norm / name.

### `POST /api/merchants`
```json
{
  "name": "盒马鲜生",
  "kind": "supermarket",
  "location_tag": "home_nearby"
}
```

### `PATCH /api/merchants/{id}`
Update favorite_score (±0.5 clamp -2..2), notes, location_tag.

---

## Receipts (Finance)

### `POST /api/receipts/preview`
Skill sends OCR extract **before** write. Server computes fingerprint, checks duplicate, validates sum.

Request:
```json
{
  "merchant_name": "盒马鲜生",
  "barcode": "262508241912",
  "printed_at": "2026-08-24 19:12:03",
  "currency": "CNY",
  "total_cents": 4460,
  "tax_cents": 0,
  "discount_cents": 0,
  "items": [
    { "name": "生菜", "qty": 1, "amount_cents": 490, "is_food": true, "category": "veg" },
    { "name": "西红柿", "qty": 2, "amount_cents": 980, "is_food": true, "category": "veg" },
    { "name": "鸡胸", "qty": 1, "amount_cents": 2990, "is_food": true, "category": "meat" }
  ],
  "raw_ocr_json": { ... },
  "image_path": "/path/to/image.jpg",
  "payer_handle": null
}
```

Response (new):
```json
{
  "action": "create_pending",
  "fingerprint": "a1b2...",
  "sum_ok": true,
  "computed_cents": 4460,
  "merchant": { "id": 3, "name": "盒马鲜生", "location_tag": "home_nearby" },
  "suggested_confirm_text": "盒马鲜生 · 今天 19:12 · ...",
  "food_items": [ ... ]
}
```

Response (duplicate):
```json
{
  "action": "duplicate",
  "existing_receipt_id": 42,
  "status": "confirmed",
  "message": "同一张小票已经记过了"
}
```

### `POST /api/receipts`
Actually insert as `pending_confirm` (or use preview + confirm flow).  
Prefer: skill calls preview → shows user → on 「对」 calls confirm.

### `POST /api/receipts/{id}/confirm`
Body optional: `{ "also_fridge": true }`  
- Sets status=confirmed  
- If also_fridge and food lines exist → creates fridge_items + two expiry memos (server side)  
- Returns created fridge item ids + memo ids

### `POST /api/receipts/{id}/reject`

### `GET /api/receipts?status=pending_confirm&limit=20`

### `GET /api/receipts/{id}`

### `POST /api/receipts/{id}/claims`
Add another person (multi-user).

### `POST /api/receipts/lookup`
```json
{ "barcode": "...", "printed_at": "..." }
```
→ existing receipt or null.

---

## Fridge

### `POST /api/fridge`
Manual add:
```json
{
  "name": "冰茶",
  "category": "drink",
  "location": "fridge",
  "qty": 1,
  "expires_in_days": 3,
  "owner_handle": null
}
```

### `GET /api/fridge?status=in_stock&expiring_within_hours=48`

### `PATCH /api/fridge/{id}`
Status change (eaten / discarded / ...), preference, extend expires_at.

### `POST /api/fridge/{id}/resolve`
Expiry-day action:
```json
{ "action": "eaten", "preference": 4 }
```
or `"discarded"` / `"keep_one_more_day"`

Server updates food_prefs, marks related memo done.

---

## Memos

### `GET /api/memos/due?within_hours=36`
Returns open/snoozed memos whose due_at is within window, ordered by priority + due_at.  
Also includes fridge items expiring today if no memo yet (server can auto-create).

### `POST /api/memos`
```json
{
  "title": "期权到期提醒",
  "body": "...",
  "kind": "options",
  "priority": 1,
  "cron_expr": "25 8 * * 5",
  "cron_tz": "America/New_York",
  "source_domain": "stocks",
  "source_table": "holdings",
  "source_id": 7,
  "payload_json": {}
}
```
Server computes next due_at, returns the memo.  
**Skill** is still responsible for creating the OpenClaw automation and then PATCHing automation_id back.

### `PATCH /api/memos/{id}`
Update status, due_at, snooze, automation_id, last_fired_at.

### `POST /api/memos/{id}/fired`
Mark last_fired_at = now (after successful WeChat send).

---

## Stocks

Trial only. No live quotes. Money here is **per-share price in the holding currency**, not integer cents.

### `GET /api/holdings`
List the current handle's holdings (no events).

### `GET /api/holdings/{id}`
One holding plus `events`. 404 if missing or owned by someone else.

### `POST /api/holdings`
Upsert by `(owner, symbol, market)`. Symbol is uppercased. Default currency: US→USD, HK→HKD, CN→CNY.

```json
{ "symbol": "AAPL", "market": "US", "qty": 2, "avg_cost": 180.5, "currency": "USD" }
```

```json
{ "id": 7, "symbol": "AAPL", "market": "US", "created": true }
```

### `POST /api/holdings/{id}/events`
Create `options_expiry` / `earnings` / `dividend` / `custom`. Optional `memo_id` from `/api/memos`.

```json
{ "kind": "options_expiry", "event_date": "2026-09-18", "notes": "weekly", "memo_id": 12 }
```

---

## System

### `POST /api/backup`
Body: `{ "dest": "/path/or/null" }`  
Performs WAL checkpoint + consistent copy. Returns path of snapshot.

### `POST /api/events`
Internal audit (most endpoints already write events).

### `GET /api/query` (admin / debug only, later remove or protect)
Raw SELECT with params — not for production skill use.

---

## Skill-side flow examples

**小票:**
1. Vision → structured JSON
2. `POST /api/receipts/preview`
3. Show confirm text to user
4. User 「对」 → `POST /api/receipts/{id}/confirm` `{ "also_fridge": true }`
5. If fridge created, skill may still soft-ask, but server already did the writes + memos

**主动提醒:** Java cron is the clock. OpenClaw heartbeat is off.

### `GET /api/ops/should-wake?lead_minutes=10`

Java/SQLite gate, **no model**. Default lead is 10 minutes (not 36 hours). `{ "wake": false, "heartbeat_ok": true }`.

### `POST /api/ops/logs/ingest`

Tail OpenClaw jsonl under `$LIFE_OPENCLAW_HOME` into SQLite.

### `GET /api/ops/logs/app`

Application / gateway logs. No chat.

### `GET /api/ops/logs/sessions?include_content=false`

AI session / trajectory rows. Default **omits** `content` and `raw_json`. Pass `include_content=true` only when you need the transcript.


## Ops (logging / AI spend)

AI money is **USD micros** (`1 USD = 1_000_000`), not CNY cents. See `docs/logging.md`.

Dashboard: `GET /ops`

### `POST /api/ops/ai`

Skill reports one model call. Server prices it from `model_prices` if `cost_micros` omitted.

```json
{
  "source": "skill",
  "purpose": "receipt_ocr",
  "provider": "xai",
  "model": "grok-4",
  "prompt_tokens": 12000,
  "completion_tokens": 800,
  "latency_ms": 2400,
  "status": "ok",
  "correlation_id": "uuid",
  "meta_json": { "image_path": "..." }
}
```

Response includes `cost_usd`, `today_cost_usd`, `budget_exceeded`.

### `GET /api/ops/summary?hours=24`

HTTP counts + AI tokens/spend + per-model + last 14 daily rows.

### `GET /api/ops/ai?limit=50` / `GET /api/ops/http?limit=50`

### `GET /api/ops/prices` / `PUT /api/ops/prices`

Update the rate card so cost estimates match the invoice.

### `POST /api/ops/purge`

```json
{ "older_than_days": 90 }
```

Send the same `X-Request-Id` on the business API call and the `/api/ops/ai` report so rows correlate.
