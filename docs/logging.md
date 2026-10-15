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
# owner (signed in on /ops) or an agent key; an agent gets 403 approval_required first (tier 2)
curl -X PUT "$LIFE_API_BASE/api/ops/prices" -H "Authorization: Bearer $LIFE_API_KEY" -H 'Content-Type: application/json' -d '{
  "provider":"xai","model":"grok-4",
  "input_usd_micros_per_mtok":3000000,
  "output_usd_micros_per_mtok":15000000,
  "notes":"invoice"
}'
```

## What is logged

**HTTP** (`http_requests`) — `OncePerRequestFilter`

- method, path, query, status, latency_ms, byte sizes, truncated JSON excerpt
- the API key's person → `actor_id`, plus `client_id` / `credential_id` (also for refused requests: 400/401/403/429 are logged too)
- `X-Request-Id` (generated if missing), echoed on the response
- **Not logged:** `GET /actuator*`, `GET /swagger-ui*`, `GET /v3/api-docs*`, `GET /api/ops/*`, `GET /ops*`
- Never stores image bytes. Redacts `password` / `token` / `authorization` / `secret` keys

**AI** (`ai_calls`) — skill **must** POST after every model call, including failed ones

```bash
curl -s -X POST "$LIFE_API_BASE/api/ops/ai" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $LIFE_API_KEY" \
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

Periodic OpenClaw heartbeats are **disabled** (`heartbeat.every=0m`). Due scanning is Spring cron every minute (`ProactiveCronService`); it reverse-calls `/hooks/agent` only when `should-wake` is true. Record `purpose=heartbeat` only if a model was actually invoked.

## Two log tables (privacy)

| Table | `source` examples | Has conversation? |
|---|---|---|
| `app_logs` | `app`, `scheduler`, `openclaw_gateway` | no |
| `ai_session_logs` | `openclaw_session`, `trajectory`, `transcript` | **yes** — isolated on purpose |

Both have `occurred_at` (from the jsonl timestamp) and `ingested_at`.

OpenClaw **session JSONL v3** (the file under `agents/*/sessions/*.jsonl`) is not a flat transcript. Ingest maps it like this:

```
{"type":"session","id":"<uuid>"}                  → session_id for every later row in that file
{"type":"model_change","provider","modelId"}      → provider / model
{"type":"thinking_level_change"}                  → event_type only
{"type":"custom","customType":"model-snapshot"}   → custom_type
{"type":"message","id","parentId","message":{...}}
{"type":"leaf"}                                   → tree marker, no tokens
```

A `message` object has `role` (`user` | `assistant` | `toolResult`) and `content[]`:

| part `type` | stored in `content` | dropped |
|---|---|---|
| `text` | text (heartbeat poll, WeChat copy, media path caption) | — |
| `thinking` | `[thinking] …` | `thoughtSignature` |
| `toolCall` | `[tool exec] {command:…}` | `thoughtSignature` |
| `image` | `[image mime=image/jpeg bytes=N]` | **JPEG base64 `data`** |

`session_id` is **only** the UUID from `type=session`. Short ids like `da6f557c` go to `event_id` / `parent_id`. The cursor remembers `last_session_id` so a file tailed mid-session still tags later lines.

`message.usage` (`input` / `output` / `cacheRead` / `cacheWrite` / `totalTokens` / `cost`) is copied onto the session row **and** inserted into `ai_calls` (`correlation_id=oc:<event_id>`, `purpose=chat` or `heartbeat`) so heartbeat token burn shows up on `/ops` even when the skill never POSTed.

`GET /api/ops/logs/sessions` returns metadata + token columns by default. `include_content=true` adds `content` and sanitized `raw_json` (still no image bytes).

```bash
curl -s -X POST "$LIFE_API_BASE/api/ops/logs/ingest" -H "Authorization: Bearer $LIFE_API_KEY"
curl -s "$LIFE_API_BASE/api/ops/logs/app" -H "Authorization: Bearer $LIFE_API_KEY"
curl -s "$LIFE_API_BASE/api/ops/logs/sessions" -H "Authorization: Bearer $LIFE_API_KEY"                  # no content
curl -s "$LIFE_API_BASE/api/ops/logs/sessions?include_content=true" -H "Authorization: Bearer $LIFE_API_KEY"  # private
```

`/ops` dashboard shows **counts only**, never transcript text.




## Dashboard

`http://localhost:8787/ops` — today spend vs budget, per-model, recent AI + HTTP.

JSON:

- `GET /api/ops/summary?hours=24`
- `GET /api/ops/ai?limit=50`
- `GET /api/ops/http?limit=50`
- `POST /api/ops/purge` `{"older_than_days":90}`

## Existing DBs

On boot the app always applies `schema.sql` (idempotent: `CREATE ... IF NOT EXISTS`, `INSERT OR IGNORE`). Old `life.db` files pick up new tables without a rebuild. A changed existing table is dropped and recreated before 1.0.0; see docs/auth.md ("Schema upgrades").

Logs live **in the same** `life.db` — backup the file, you backup spend history.
