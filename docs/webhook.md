# Life OS custom webhook

Gateway route: `POST /hooks/life-os` (`hooks.mappings`, applied by `docker/openclaw-config.sh` via `openclaw config set`).

Not `/hooks/agent`. Not `plugins.entries.webhooks` (TaskFlow, does not start an agent).

## Manual WeChat test

```bash
curl -sS -X POST http://127.0.0.1:8787/api/ops/webhook/ping \
  -H 'Content-Type: application/json' -d '{}'
```

Optional body: `{ "to": "<weixin peer id>", "message": "…" }`.

Skips the due-memo gate and the proactive lock. Expect 「钩子 OK」 on WeChat.

Cron still uses the same mapping when a memo is due.
