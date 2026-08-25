# lifeOS-ai

[![CI](https://github.com/zoomzoomTnT/lifeOS-ai/actions/workflows/ci.yml/badge.svg)](https://github.com/zoomzoomTnT/lifeOS-ai/actions/workflows/ci.yml)

WeChat / OpenClaw Life OS: one SQLite file, a Spring Boot REST API, and a thin AI skill.

Repo: [zoomzoomTnT/lifeOS-ai](https://github.com/zoomzoomTnT/lifeOS-ai)

Covers 记账/小票, 冰箱, 备忘 (proactive pings), 持仓 (trial).

## Layout

```
.
├── schema/schema.sql       # single source of truth for tables
├── docs/api.md             # REST contract
├── app/                    # Spring Boot 3 + Java 21
├── skill-updates/          # drop-in OpenClaw skill (HTTP, not life.py)
└── .github/workflows/ci.yml
```

## CI

GitHub Actions on `main` and pull requests:

1. `schema/schema.sql` and `app/src/main/resources/schema.sql` must be identical
2. Apply schema to a fresh SQLite database
3. Maven `verify` (Java 21) + unit tests
4. On `main` push, upload the packaged jar as a workflow artifact

## Quick start

1. Schema is applied by the app on first boot (or `sqlite3 life.db < schema/schema.sql`).

2. Start the API:
   ```bash
   export LIFE_DB=~/.openclaw/workspace/data/life.db
   cd app && mvn spring-boot:run
   ```

3. Point the skill at the API:
   - Copy `skill-updates/` into `~/.openclaw/workspace/skills/life-os-skills`
   - Set `LIFE_API_BASE=http://127.0.0.1:8787`

4. OpenClaw heartbeat / cron call `GET /api/memos/due` (no Python exec).

## Design (2026-08-26)

- `schema.sql` is the only executable schema. Domain rules stay in markdown.
- Python `life.py` is retired. All writes go through Java REST.
- AI skill: vision + intent + OpenClaw automations + Chinese copy.
- App layer: fingerprint, sum validation, fridge intake, due queries, backup.

## Next

- Expiry-memo auto-creation + food_knowledge defaults
- `POST /api/backup`
- Holdings / stock_events controllers
- Flyway instead of naive SchemaInitializer
- Auth beyond `X-Life-Handle`
