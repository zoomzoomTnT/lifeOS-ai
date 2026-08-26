# life-os-app (Spring Boot)

Minimal REST backend for WeChat Life OS.

## Docker (preferred)

From the repo root:

```bash
docker compose up -d --build
curl http://localhost:8787/actuator/health
```

DB file: `../data/life.db` (compose volume `./data`).

## Maven

```bash
export LIFE_DB=~/.openclaw/workspace/data/life.db
export LIFE_API_PORT=8787
mvn spring-boot:run
```

Health: `curl http://localhost:8787/actuator/health`  
Swagger UI: http://localhost:8787/swagger-ui.html


## Key endpoints (see ../docs/api.md)

- `POST /api/receipts/preview` — OCR result → pending receipt + fingerprint
- `POST /api/receipts/{id}/confirm` — confirm + optional fridge intake
- `GET  /api/memos/due?within_hours=36`
- `POST /api/fridge`
- `POST /api/backup` (todo)

## Notes

- Schema auto-applied on first start if `people` table missing.
- Auth is currently just `X-Life-Handle` header (OpenClaw peer id).
- This is a skeleton: expiry memo auto-creation, food_knowledge defaults, stock events still thin.
