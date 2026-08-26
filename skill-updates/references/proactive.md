# life-proactive — 主动开口 (API)

**Do not run an OpenClaw model heartbeat.** That path burns tokens every 30 minutes even when nothing is due.

The app already scans due rows in Java every 15 minutes (`DueScanScheduler`) — cost $0. WeChat delivery is **OpenClaw automations created when a memo is inserted** (exact minute: options 8:25 ET, fridge 18:00).

## Wake sources (allowed)

| Source | Cost | Use |
|---|---|---|
| OpenClaw automation on a memo | one model call when it actually fires | options, expiry, user reminders |
| User message | normal | 记账 / 小票 |
| App `DueScanScheduler` | zero | SQL only; optional `LIFE_WAKE_WEBHOOK` |
| OpenClaw heartbeat | **forbidden by default** | see gate below if you cannot turn it off |

Do not expect a 30m heartbeat to hit 8:25. That is what cron automations are for.

## If OpenClaw heartbeat cannot be disabled

It must be a **gate only**. Cheapest text model. No vision. One HTTP call:

```bash
curl -s "$LIFE_API_BASE/api/ops/should-wake?within_hours=36" -H "X-Life-Handle: $HANDLE"
```

- `"heartbeat_ok": true` / `"wake": false` → reply exactly `HEARTBEAT_OK`. **No other tools. No prose. Do not POST /api/ops/ai** (this turn is wasted money; do not add a usage row unless the runtime already billed you).
- `"wake": true` → follow `instruction` in the JSON. At most 2 WeChat messages. Then `POST /api/memos/{id}/fired`. Still no vision.

Do not `GET /api/memos/due` on a quiet heartbeat — `should-wake` already did the SQL.

## After a real send

```bash
curl -s -X POST "$LIFE_API_BASE/api/memos/$ID/fired"
```

Night Asia/Tokyo 22:00–08:00 — server only returns priority=1 memos.

## Copy

Chinese, friend-like, one fact + one question. No long reports.

## Channel

`openclaw-weixin`, recipient `people.handle`.
