# life-os REST API Contract (v0.1)

Base URL: `http://localhost:8787` (or `$LIFE_API_BASE`)

**Try it in the browser:** [Swagger UI](http://localhost:8787/swagger-ui.html)  
OpenAPI JSON: `/v3/api-docs` (groups: `/v3/api-docs/api`, `/v3/api-docs/actuator`)

1. Open Swagger UI, top-right **Authorize**, put `X-Life-Handle` (WeChat peer id or `owner`).
2. Dropdown **api** vs **actuator**.
3. Expand an operation → **Try it out** → **Execute**.

This markdown is still the field-level contract (cents, fingerprints). Swagger is generated from controllers; many bodies are untyped `Map`.


All responses are JSON. Money fields are **integer cents**. Times are UTC ISO-8601 with `Z` unless noted.

Authentication (v0.1): simple header `X-Life-Handle: <people.handle>`  
(Later: JWT / OpenClaw peer token.)

Common error shape:
```json
{ "error": "code", "message": "human readable", "details": {} }
```

---

## Health & Meta

No custom `/api/health` or `/api/path`. Use Actuator.

### `GET /actuator/health`

`LifeHealthIndicator` (component `life`) plus JDBC. DOWN → HTTP 503.

```json
{
  "status": "UP",
  "components": {
    "db": { "status": "UP" },
    "life": {
      "status": "UP",
      "details": { "db": "ok", "version": "0.1.0" }
    }
  }
}
```

Also: `/actuator/health/liveness`, `/actuator/health/readiness`.

### `GET /actuator/db`

SQLite file + owner timezone (this replaced `/api/path`).

```json
{
  "jdbcUrl": "jdbc:sqlite:/data/life.db",
  "file": "/data/life.db",
  "ownerTimezone": "Asia/Tokyo",
  "ping": "ok"
}
```

### Actuator (`/actuator`)

Spring Boot Actuator. Default exposed: `health`, `info`, `metrics`, `db`, `scheduledtasks`, `loggers`, `mappings`, `threaddump`.

| Path | Notes |
|---|---|
| `GET /actuator` | index of enabled endpoints |
| `GET /actuator/health` | JDBC + component `life` (ping, version) |
| `GET /actuator/health/liveness` | process alive |
| `GET /actuator/health/readiness` | ready for traffic |
| `GET /actuator/db` | sqlite path, timezone, ping |
| `GET /actuator/info` | `info.app` name/version |
| `GET /actuator/metrics` | list; `.../metrics/jvm.memory.used` for one |
| `GET /actuator/scheduledtasks` | Java cron (proactive scan) |
| `GET /actuator/loggers` | logger levels |
| `GET /actuator/mappings` | all Spring MVC routes |

Local profile exposes `*` (`env`, `configprops`, `beans`, `heapdump`). Do not do that on a public bind.

Override: `LIFE_ACTUATOR_ENDPOINTS=health,info,db`

Docker / skill healthcheck: `GET /actuator/health`.
