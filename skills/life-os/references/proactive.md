# life-proactive — 主动开口 (API)

**Do not run an OpenClaw model heartbeat.** `heartbeat.every = 0m`.

Java is the clock. The custom webhook lives on the Gateway as
`POST /hooks/life-os` (`hooks.mappings`, not `/hooks/agent`, not the
TaskFlow Webhooks plugin).

```
Spring @Scheduled every minute
  GET should-wake (SQL, $0)
  if wake → POST $OPENCLAW_GATEWAY/hooks/life-os
```

## Wake sources (allowed)

| Source | Cost | Use |
|---|---|---|
| Spring `ProactiveCronService` → `/hooks/life-os` | one model call when due | options, expiry, stale receipt |
| `POST /api/ops/webhook/ping` | one model call | manual WeChat connectivity test |
| User message | normal | 记账 / 小票 |
| OpenClaw heartbeat | **off** (`every: 0m`) | do not re-enable |
| `plugins.entries.webhooks` | n/a | TaskFlow only — does not speak on WeChat |

Exact minute (美东周五 8:25) works because Java polls every minute and only fires when `due_at` is within 10 minutes.

## When this skill is woken by Spring cron

The prompt will say `life-os proactive (woken by Spring cron`. Then:

1. Trust it — do not re-scan with vision.
2. Speak ≤2 WeChat messages (Chinese, one fact + one question).
3. `POST /api/memos/{id}/fired`
4. Do **not** reply `HEARTBEAT_OK`.

## When this skill is woken by webhook ping

The prompt will say `life-os webhook ping`. Then:

1. Reply exactly `钩子 OK` on WeChat (one short message).
2. Do not create or fire memos. No vision. No `HEARTBEAT_OK`.

## Manual / debug

```bash
curl -s "$LIFE_API_BASE/api/ops/should-wake?lead_minutes=10"
# due-memo path (no-op if nothing is due)
curl -s -X POST "$LIFE_API_BASE/api/ops/proactive/run" -H 'Content-Type: application/json' -d '{"force":true}'
# always fires WeChat — same token as hooks.token
curl -s -X POST "$LIFE_API_BASE/api/ops/webhook/ping" \
  -H "Authorization: Bearer $OPENCLAW_HOOK_TOKEN" \
  -H 'Content-Type: application/json' -d '{}'
```

Optional body: `{ "to": "<weixin peer id>", "message": "…" }`.

## If a heartbeat still exists

Gate only: `GET /api/ops/should-wake`. `wake=false` → `HEARTBEAT_OK` and stop.

Night Asia/Tokyo 22:00–08:00 — server only returns priority=1 memos.

## Channel

`openclaw-weixin`, recipient `people.handle`.
