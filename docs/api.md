# life-os REST API Contract (v0.1)

Base URL: `http://127.0.0.1:8787` (or `$LIFE_API_BASE`)

All responses are JSON. Money fields are **integer cents**. Times are UTC ISO-8601 with `Z` unless noted.

Authentication (v0.1): simple header `X-Life-Handle: <people.handle>`  
(Later: JWT / OpenClaw peer token.)

Common error shape:
```json
{ "error": "code", "message": "human readable", "details": {} }
```

---

## Health & Meta

### `GET /api/health`
```json
{ "status": "ok", "db": "ok", "version": "0.1.0" }
```

### `GET /api/path`
Returns current DB path and owner timezone (for skill diagnostics).

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

### `GET /api/holdings`

### `POST /api/holdings`
Upsert by (owner, symbol, market).

### `POST /api/holdings/{id}/events`
Create options_expiry / earnings event + optional linked memo.

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

**主动提醒 (heartbeat):**
1. `GET /api/memos/due?within_hours=36`
2. If empty → HEARTBEAT_OK
3. Else pick top 1-2, send WeChat, then `POST /api/memos/{id}/fired`

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

