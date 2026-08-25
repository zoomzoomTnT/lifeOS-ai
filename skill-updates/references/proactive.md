# life-proactive — 主动开口 (API)

Schema does not speak. Heartbeat or automation must wake the model.

## Wake sources

| Source | Use |
|---|---|
| Heartbeat ~30m + activeHours | Cheap scan — due memos, fridge 48h, pending receipts |
| Automation cron | Exact minute — options 8:25 ET, daily 18:00 fridge |

Do not expect a 30m heartbeat to hit 8:25.

## Each wake

```bash
curl -s "$LIFE_API_BASE/api/memos/due?within_hours=36" -H "X-Life-Handle: $HANDLE"
```

1. All empty → reply `HEARTBEAT_OK` on heartbeat, or stay silent on cron. No small talk.
2. Something due → at most 2 WeChat messages, priority order — priority 1–2 / food expiring today, pending receipt >24h, options/earnings.
3. Skip memo if `last_fired_at` within 6 hours.
4. After send → `POST /api/memos/{id}/fired`

Night Asia/Tokyo 22:00–08:00 — only priority=1.

## Copy

Chinese, friend-like, one fact + one question. No long reports.

## Channel

`openclaw-weixin`, recipient `people.handle`.

Receipt OCR needs vision. Heartbeat can use a cheaper text model.
