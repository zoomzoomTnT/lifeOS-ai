# lifeOS-ai

[![CI](https://github.com/zoomzoomTnT/lifeOS-ai/actions/workflows/ci.yml/badge.svg)](https://github.com/zoomzoomTnT/lifeOS-ai/actions/workflows/ci.yml)

WeChat / OpenClaw Life OS: one SQLite file, a Spring Boot REST API, and a thin AI skill.

Repo: [zoomzoomTnT/lifeOS-ai](https://github.com/zoomzoomTnT/lifeOS-ai)

Image: `ghcr.io/zoomzoomtnt/lifeos-ai:latest`

Covers 记账/小票, 冰箱, 备忘 (proactive pings), 持仓 (trial).

## One-click: Docker Compose

```bash
git clone https://github.com/zoomzoomTnT/lifeOS-ai.git
cd lifeOS-ai
docker compose up -d --build
```

API: `http://127.0.0.1:8787/api/health`  
SQLite file (backup this): `./data/life.db`

```bash
docker compose logs -f api
docker compose down
```

Use a published image instead of building:

```bash
LIFE_IMAGE=ghcr.io/zoomzoomtnt/lifeos-ai:latest docker compose up -d
```

Env overrides: `LIFE_API_PORT` (host port), `LIFE_DATA` (host folder for the db).

## Layout

```
.
├── Dockerfile
├── docker-compose.yml
├── schema/schema.sql
├── docs/api.md
├── app/                      # Spring Boot 3 + Java 21
├── skill-updates/            # OpenClaw skill (HTTP)
└── .github/workflows/ci.yml
```

## CI

On `main` and pull requests:

1. Schema copies must match; apply to a fresh SQLite db
2. Maven `verify` (Java 21) + unit tests; upload jar on `main`
3. Docker build + `docker compose` smoke (`GET /api/health`)
4. On `main` push, publish `ghcr.io/zoomzoomtnt/lifeos-ai` (`latest` + `sha-*`)

If the GHCR package is private on first publish: GitHub → Packages → `lifeos-ai` → Package settings → Change visibility → Public.

## Maven (without Docker)

```bash
export LIFE_DB=~/.openclaw/workspace/data/life.db
cd app && mvn spring-boot:run
```

Point the OpenClaw skill at `LIFE_API_BASE=http://127.0.0.1:8787` and copy `skill-updates/` into `~/.openclaw/workspace/skills/life-os-skills`.

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
