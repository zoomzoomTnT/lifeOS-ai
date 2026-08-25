# Conventions (API edition)

## Money

Store integer cents. `128.50 CNY` → `12850`. Display divide by 100, 2 dp (`¥128.50`).

## Time

- `*_at` columns are UTC ISO-8601 with `Z`.
- `timezone` / `cron_tz` are IANA (`Asia/Tokyo`, `America/New_York`).
- Owner wall clock defaults to Asia/Tokyo.
- Friday 8:25 ET options → `cron_expr` `25 8 * * 5` + `cron_tz=America/New_York`. Convert to Tokyo only when speaking.

## Identity

`people.handle` = OpenClaw WeChat peer id. Display names change; handles should not.

Expense owner = `receipts.payer_id`. Household split / viewing = `receipt_claims`.

Pass current handle with every mutating call:

```
-H "X-Life-Handle: <handle>"
```

## Dedup

Receipts dedupe on printed barcode + printed timestamp (server computes `fingerprint`).

- `barcode` — barcode digits, order id, ticket no. Strip spaces.
- `printed_at` — time printed on the ticket, keep as-is.
- `fingerprint` = sha256(`barcode|printed_at`)[:32] (done by app)

Always call `/api/receipts/preview` (or `/lookup`) before treating a ticket as new.

## Enums (keep in sync with schema CHECKs)

- people.role — `owner | member | guest`
- merchants.kind — `supermarket | restaurant | cafe | market | other`
- merchants.location_tag — `home_nearby | office_nearby | other`
- receipts.status — `pending_confirm | confirmed | rejected | duplicate`
- fridge status — `in_stock | eaten | discarded | expired | gifted`
- fridge location — `fridge | freezer | pantry | counter`
- memo kind — `reminder | followup | expiry | options | restock | brief | custom`
- memo status — `open | snoozed | done | cancelled`
- food category — `veg | fruit | meat | seafood | dairy | drink | leftover | staple | other`

## name_norm

Lowercase, strip spaces and punctuation. Chinese stays Chinese (`生菜` → `生菜`).

## Don't

- Second `.db` per domain
- Raw card numbers or payment QR payloads
- Auto-confirm a receipt whose lines do not sum to `total_cents` (±2 cents)
- Mark fridge `eaten` without user confirmation on expiry day
- Call `life.py` or raw sqlite for writes — use the REST API only
