---
name: life-os
description: lifeOS-ai Life OS via REST — WeChat 记账, 小票, 冰箱, 备忘, 持仓, and proactive pings on openclaw-weixin. Use when the user mentions life-os, life.db, 生活台账, 记账, 小票, 冰箱, 过期, 提醒我, 期权, or any life-* skill.
metadata:
  type: workflow
  version: "2.1"
  repo: zoomzoomTnT/lifeOS-ai
  api: "http://localhost:8787"
---

# life-os — 生活台账

**One SQLite file. Business logic lives in the Spring Boot app. This skill only talks HTTP + domain rules.**

## Paths & API

| Item | Value |
|---|---|
| API base | `$LIFE_API_BASE` or `http://localhost:8787`, **no trailing slash** (`//api/...` is refused with 400) |
| DB (owned by app) | `$LIFE_DB` or `~/.openclaw/workspace/data/life.db` |
| Schema | `app/src/main/resources/schema.sql` (idempotent, applied by the app on every start) |
| Owner timezone | `Asia/Tokyo` |
| Options timezone | `America/New_York` |
| WeChat channel | `openclaw-weixin` |
| Auth header | `Authorization: Bearer $LIFE_API_KEY` (a lifeOS API key, `lok_...`; see docs/auth.md). Grok Bot: `LIFE_API_KEY=$LIFEOS_GROKBOT_CHAT_KEY` in chat turns, `$LIFEOS_GROKBOT_ROUTINE_KEY` (read-only) in routines; OpenClaw: the key minted for client `openclaw`. Risky calls answer `403 approval_required`: show zoomzoom the `summary` in chat and ask; nothing happens without an explicit yes. On yes the lifeOS Admin bot approves `approval_id` (within 5 minutes); then repeat the identical request with `Life-Approval: <approval_id>`. Never approve it yourself |

All mutations go through the REST API. Do not write SQLite from this skill.

## Session start

```bash
curl -s "$LIFE_API_BASE/actuator/health"   # public: {"status":"UP"} only
```

`/actuator/db` and health details need an `ops:admin` credential (the owner or a service key); agent keys get 403, so don't call them here. Members are no longer auto-created from WeChat handles: every write is attributed to the API key's person.

## Core contract

- Money = integer cents (CNY). AI spend = integer USD micros.
- Times stored as UTC ISO with `Z`. Speak in owner timezone.
- After important writes the app already inserts `events` rows.
- After **every** model call, `POST /api/ops/ai` (see `references/ops.md`).
- Cross-domain order: confirm finance → fridge intake (server can do both) → memos.

## Route by intent

| User intent | Read |
|---|---|
| init / backup / health / schema | this file + `references/conventions.md` |
| 小票, 记账, 花了, merchant | `references/finance.md` |
| 冰箱, 过期, 蔬菜水果肉 | `references/fridge.md` |
| 提醒我, cron, 到期 | `references/memos.md` |
| 持仓, 期权, ticker | `references/stocks.md` |
| 主动找我, 到期提醒 | `references/proactive.md` |
| 用量, token, 花费模型, ops, 日志 | `references/ops.md` |

## Install (OpenClaw workspace)

On the OpenClaw host (same compose file, skill is inside the image):

```bash
docker compose up -d
docker compose --profile sync run --rm skill-sync
```

That writes `$OPENCLAW_HOME/workspace/skills/life-os` and `HEARTBEAT.md`.


1. Run the Spring Boot app so the API is listening (`docker compose up -d`).
2. Merge `openclaw/openclaw.json` (`heartbeat.every=0m`, `hooks.enabled`). Set `OPENCLAW_HOOK_TOKEN`.
3. Java cron wakes this skill via `POST /hooks/agent` only when memos are due. Do not create a 30m model heartbeat.
4. Vision model required for receipt photos only, never for proactive.

## Backup

There is no backup endpoint. Production data lives in Turso (hosted backups / point-in-time restore in the Turso dashboard); a local `life.db` is backed up by copying the file while the app is stopped. Never commit `life.db`.

## Voice

WeChat-length Chinese. One fact + one question. No assistant preamble. No schema dumps in chat.
