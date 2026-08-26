---
name: life-os
description: Operate the wechat-lifeOS-ai Life OS via REST API — SQLite life.db for WeChat记账, 小票, 冰箱, 备忘, 持仓, and proactive pings via OpenClaw openclaw-weixin. Use when the user mentions life-os-skills, life.db, 生活台账, 记账, 小票, 冰箱, 过期, 提醒我, 期权, heartbeat, or any life-* skill.
metadata:
  type: workflow
  version: "2.0"
  repo: zoomzoomTnT/wechat-lifeOS-ai
  api: "http://127.0.0.1:8787"
---

# life-os — 生活台账 (API edition)

Source of truth is the GitHub package in `zoomzoomTnT/wechat-lifeOS-ai`.  
**One SQLite file. Business logic lives in the Spring Boot app. This skill only talks HTTP + domain rules.**

## Paths & API

| Item | Value |
|---|---|
| API base | `$LIFE_API_BASE` or `http://127.0.0.1:8787` |
| DB (owned by app) | `$LIFE_DB` or `~/.openclaw/workspace/data/life.db` |
| Schema | `schema/schema.sql` (applied by app on first start) |
| Owner timezone | `Asia/Tokyo` |
| Options timezone | `America/New_York` |
| WeChat channel | `openclaw-weixin` |
| Auth header | `X-Life-Handle: <people.handle>` |

Never call `life.py` or raw `sqlite3` for writes. All mutations go through the API.

## Session start

```bash
curl -s "$LIFE_API_BASE/api/health"
curl -s "$LIFE_API_BASE/api/path"
```

If `people.handle` is still `owner`, the first real WeChat peer id will auto-create a member row (or upsert via API later).

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
| heartbeat, 主动找我, HEARTBEAT_OK | `references/proactive.md` |
| 用量, token, 花费模型, ops, 日志 | `references/ops.md` |

## Install (OpenClaw workspace)

1. Run the Spring Boot app (`life-os-app`) so the API is listening.
2. Copy updated skill package to `~/.openclaw/workspace/skills/life-os-skills`.
3. Merge AGENTS / HEARTBEAT snippets.
4. Heartbeat every 30m, target `openclaw-weixin`, activeHours 08:00-22:00 Asia/Tokyo.
5. Vision model required for receipt photos.

## Backup

```bash
curl -s -X POST "$LIFE_API_BASE/api/backup" -H "Content-Type: application/json" -d '{}'
```

Tell the user to copy the returned `.db` snapshot. Never commit `life.db`.

## Voice

WeChat-length Chinese. One fact + one question. No assistant preamble. No schema dumps in chat.
