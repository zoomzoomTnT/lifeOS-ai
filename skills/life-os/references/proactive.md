# life-proactive — 主动开口 (API)

**Do not run an OpenClaw model heartbeat.** `heartbeat.every = 0m` in versioned `openclaw/openclaw.json`.

Java is the clock. When a memo is actually due, Spring cron reverse-calls OpenClaw:

```
Spring @Scheduled every minute
  GET should-wake (SQL, $0)
  if wake → POST $OPENCLAW_GATEWAY/hooks/agent  (one isolated skill turn)
```

## Wake sources (allowed)

| Source | Cost | Use |
|---|---|---|
| Spring `ProactiveCronService` → `/hooks/agent` | one model call when due | options, expiry, stale receipt |
| User message | normal | 记账 / 小票 |
| OpenClaw heartbeat | **off** (`every: 0m`) | do not re-enable |

Exact minute (美东周五 8:25) works because Java polls every minute and only fires when `due_at` is within 10 minutes.

## When this skill is woken by Spring

The prompt will say `life-os proactive (woken by Spring cron`. Then:

1. Trust it — do not re-scan with vision.
2. Speak ≤2 WeChat messages (Chinese, one fact + one question).
3. `POST /api/memos/{id}/fired`
4. Do **not** reply `HEARTBEAT_OK`.

## Manual / debug

```bash
curl -s "$LIFE_API_BASE/api/ops/should-wake?lead_minutes=10"
curl -s -X POST "$LIFE_API_BASE/api/ops/proactive/run" -H 'Content-Type: application/json' -d '{"force":true}'
```

## If a heartbeat still exists

Gate only: `GET /api/ops/should-wake`. `wake=false` → `HEARTBEAT_OK` and stop.

Night Asia/Tokyo 22:00–08:00 — server only returns priority=1 memos.

## Channel

`openclaw-weixin`, recipient `people.handle`.
