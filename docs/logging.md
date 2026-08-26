# Logging & monitoring

AI calls are the expensive path. They happen in the **skill / OpenClaw** layer (vision + copy). The **app** records them when the skill POSTs usage, and it records every mutating / business HTTP call automatically.

```
WeChat → OpenClaw skill (vision / chat)
              │  POST /api/ops/ai   (tokens, model, purpose)
              │  X-Request-Id: <same uuid>
              ▼
         Spring Boot  ──►  http_requests   (filter)
                      ──►  ai_calls        (skill-reported)
                      ──►  model_prices    (rate card)
              ▼
         ./data/life.db   dashboard GET /ops
```

## Money

| Domain | Unit | Example |
|---|---|---|
| 记账 | CNY **cents** | ¥12.80 → `1280` |
| AI | USD **micros** | $0.003 → `3000` (`1 USD = 1_000_000`) |

`cost_micros = prompt_tokens * input_usd_micros_per_mtok / 1e6 + completion * output / 1e6`

Default daily budget: **$5** (`settings.ai_daily_budget_usd_micros = 5000000`). Over-budget is **flagged, not blocked** — the model already ran.

Seeded prices are **estimates**. Set the real card:

```bash
curl -X PUT "$LIFE_API_BASE/api/ops/prices" -H 'Content-Type: application/json' -d '{
  "provider":"xai","model":"grok-4",
  "input_usd_micros_per_mtok":3000000,
  "output_usd_micros_per_mtok":15000000,
  "notes":"invoice"
}'
```

## What is logged

**HTTP** (`http_requests`) — `OncePerRequestFilter`

- method, path, query, status, latency_ms, byte sizes, truncated JSON excerpt
- `X-Life-Handle` → `actor_id`
- `X-Request-Id` (generated if missing), echoed on the response
- **Not logged:** `GET /api/health` (Docker healthcheck), `GET /api/ops/*` (dashboard poll), `GET /ops*`
- Never stores image bytes. Redacts `password` / `token` / `authorization` / `secret` keys

**AI** (`ai_calls`) — skill **must** POST after every model call, including failed ones

```bash
curl -s -X POST "$LIFE_API_BASE/api/ops/ai" \
  -H "Content-Type: application/json" \
  -H "X-Life-Handle: $HANDLE" \
  -H "X-Request-Id: $CORR" \
  -d '{
    "source":"skill",
    "purpose":"receipt_ocr",
    "provider":"xai",
    "model":"grok-4",
    "prompt_tokens":12000,
    "completion_tokens":800,
    "latency_ms":2400,
    "status":"ok",
    "correlation_id":"'"$CORR"'",
    "meta_json":{"image_path":"..."}
  }'
```

Do **not** send full prompts. `meta_json` may hold image_path, receipt_id, memo_id.

`purpose`: `receipt_ocr | classify | heartbeat | memo | chat | embedding | other`  
`source`: `skill | heartbeat | automation | app | other`

## Dashboard

`http://127.0.0.1:8787/ops` — today spend vs budget, per-model, recent AI + HTTP.

JSON:

- `GET /api/ops/summary?hours=24`
- `GET /api/ops/ai?limit=50`
- `GET /api/ops/http?limit=50`
- `POST /api/ops/purge` `{"older_than_days":90}`

## Existing DBs

On boot the app always applies `migrations/0002_ops.sql` (`CREATE IF NOT EXISTS`). Old `life.db` files pick up the new tables without rebuild.

Logs live **in the same** `life.db` — backup the file, you backup spend history.
