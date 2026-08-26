# life-ops — 用量与日志

AI 很贵。每一次模型调用（看图、分类、写提醒、heartbeat 扫描）都必须记账。

HTTP 访问由服务器过滤器自动写入 `http_requests`。  
**模型用量不会自动出现** — 必须 `POST /api/ops/ai`。

## After every model call

```bash
CORR="${CORR:-$(uuidgen)}"
curl -s -X POST "$LIFE_API_BASE/api/ops/ai" \
  -H "Content-Type: application/json" \
  -H "X-Life-Handle: $HANDLE" \
  -H "X-Request-Id: $CORR" \
  -d '{
    "source":"skill",
    "purpose":"receipt_ocr",
    "provider":"xai",
    "model":"grok-4",
    "prompt_tokens":0,
    "completion_tokens":0,
    "latency_ms":0,
    "status":"ok",
    "correlation_id":"'"$CORR"'",
    "meta_json":{}
  }'
```

Use the **same** `X-Request-Id` on `/api/receipts/preview` (or whatever API you call next).

If you do not know token counts, still POST (`prompt_tokens: 0`) with model + purpose + latency — a zero-token row is better than a missing row. Fill tokens when the runtime exposes them.

Do **not** POST `/api/ops/ai` for a quiet heartbeat that only hit `should-wake` and returned `HEARTBEAT_OK` — that turn should not exist. If the harness billed you anyway, you may record it with `purpose=heartbeat` so the waste is visible.

## purpose

| Situation | purpose |
|---|---|
| 小票 / 看图 OCR | `receipt_ocr` |
| 这是小票还是食物照片 | `classify` |
| heartbeat（仅当模型真的跑了） | `heartbeat` |
| 写提醒文案 | `memo` |
| 普通对话 | `chat` |

## When user asks 花了多少 / token / 用量

```bash
curl -s "$LIFE_API_BASE/api/ops/summary?hours=24"
```

Speak today's USD and whether the $5 default budget is blown. Point them at `/ops` if they have a browser.

Session transcripts live in `ai_session_logs` (private). Do not dump `include_content=true` into WeChat. Application logs are `app_logs`. OpenClaw session JSONL v3: `type=session` UUID is `session_id`; later short ids are `event_id`. Image bytes and `thoughtSignature` are stripped. Heartbeat polls are tagged `heartbeat=1` and their token usage is copied into `ai_calls`.

Do not dump schema. Do not paste full prompts into `meta_json`.
