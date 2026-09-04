# life-os REST API Contract (v0.1)

Base URL: `http://localhost:8787` (or `$LIFE_API_BASE`)

**Try it in the browser:** [Swagger UI](http://localhost:8787/swagger-ui.html)  
OpenAPI JSON: `/v3/api-docs` (groups: `/v3/api-docs/api`, `/v3/api-docs/actuator`)

1. Open Swagger UI, top-right **Authorize**, put `X-Life-Handle` (WeChat peer id or `owner`).
2. Dropdown **api** vs **actuator**.
3. Expand an operation → **Try it out** → **Execute**.

This markdown is still the field-level contract (cents, fingerprints). Swagger request/response bodies come from DTOs (MapStruct maps domain ↔ DTO).

All responses are JSON. Money fields are **integer cents**. Times are UTC ISO-8601 with `Z` unless noted.

Authentication (v0.1): simple header `X-Life-Handle: <people.handle>`

Common error shape:
```json
{ "error": "code", "message": "human readable", "details": {} }
```

See Swagger for domain routes. Ops additions in this change:

### `POST /api/ops/webhook/ping`

Always invoke the Gateway custom webhook (`POST /hooks/life-os`) and deliver to WeChat.
Skips the due-memo gate and the proactive lock.

```json
{ "to": "optional-weixin-id", "message": "optional override" }
```

Empty body is fine. Default `to` is `people.handle` of the owner.

### `POST /api/ops/proactive/run`

`{ "force": true }` skips the lock only. Still no-ops when nothing is due.

### `GET /api/ops/should-wake?lead_minutes=10`

Java/SQLite gate, **no model**. Default lead is 10 minutes.
