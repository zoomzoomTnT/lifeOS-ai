# life-memos — 提醒总线 (API)

`memos` is the only outbound channel. Other domains insert memos via the app; this layer attaches OpenClaw automations.

## When to write a memo

- User says 提醒我
- Fridge expiry (server may auto-create on confirm; skill still creates OpenClaw job)
- Options / earnings (`options` / `brief`)
- Receipt follow-up (`followup`)

## Create then automate

```bash
curl -s -X POST "$LIFE_API_BASE/api/memos" \
  -H "Content-Type: application/json" \
  -H "X-Life-Handle: $HANDLE" \
  -d '{
    "title": "期权到期",
    "body": "...",
    "kind": "options",
    "priority": 1,
    "cron_expr": "25 8 * * 5",
    "cron_tz": "America/New_York",
    "source_domain": "stocks",
    "source_table": "holdings",
    "source_id": 7
  }'
```

- One-shot — supply `due_at` (UTC), leave `cron_expr` empty
- Recurring — `cron_expr` + `cron_tz`; server stores next `due_at`

Create the OpenClaw automation, then:

Quiet 30m model heartbeats are **off**. This automation is the send path.

```bash
curl -s -X PATCH "$LIFE_API_BASE/api/memos/$ID" \
  -H "Content-Type: application/json" \
  -d '{"automation_id": "<openclaw-job-id>"}'
```

## OpenClaw jobs

Recurring Friday 8:25 ET:

```bash
openclaw automations create "25 8 * * 5" \
  --name "options-expiry-et" \
  --tz "America/New_York" \
  --session isolated \
  --announce \
  --channel openclaw-weixin \
  --to "<owner-weixin-id>" \
  --message "Read life-os proactive rules. Memo id=<ID>. 现在是美东周五 8:25，检查 memos 与 holdings，用中文主动提醒主人期权到期。不要回复 HEARTBEAT_OK。"
```

One-shot uses `openclaw automations add --at ...`.

Channel must be `openclaw-weixin`. `--to` is `people.handle`.

## Status verbs

| User | API action |
|---|---|
| 完成了 / 吃完了 / 丢掉了 | `PATCH` status=`done` (+ disable automation) |
| 推迟 N | `PATCH` status=`snoozed`, new `due_at` |
| 取消 | `PATCH` status=`cancelled` |
| 改时间 | `PATCH` due_at / cron_* |

After successful send:

```bash
curl -s -X POST "$LIFE_API_BASE/api/memos/$ID/fired"
```

Do not re-fire within 6 hours (`last_fired_at`).

## Query due

```bash
curl -s "$LIFE_API_BASE/api/memos/due?within_hours=36" -H "X-Life-Handle: $HANDLE"
```
