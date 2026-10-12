# API authentication and authorization

Spring Security 7.1 (Spring Boot 4.1.1 BOM). Design and threat model: the architecture doc
(`lifeos-auth-architecture.md`, revision 2), phases 1–2 implemented here. There is **no human web
login** (no passkeys, sessions, cookies or login pages): zoomzoom approves in the Grok Bot chat, and the
lifeOS Admin bot, the only holder of the admin key, carries out admin actions and approvals after an
explicit yes. Phase 3 (OAuth 2.1 tokens) and phase 4 (MCP) slot in without changing anything below.
See [Later phases](#later-phases).

## Who can call what

| Caller | Client (kind) | Scopes | Can |
|---|---|---|---|
| Grok Bot (chat) | `grokbot-chat` (agent) | `finance:write fridge:write memos:write stocks:write ops:read ops:write` (write implies read) | tier 0–1 directly; tier 2 → `approval_required` |
| Grok Bot (routines / wake check) | `grokbot-routine` (agent) | `memos:read ops:read` | reads |
| lifeOS Admin bot | `lifeos-admin` (**admin**, exactly one) | `ops:admin` + `finance:read fridge:read memos:read stocks:read ops:read`, **no data writes** (`ops:admin` does not imply `ops:write`: `POST /api/ops/ai` and `/api/ops/proactive/run` are 403) | `/api/admin/**`, approve/deny approvals, tier 3 (purge, `PUT /api/ops/prices`, actuator) |
| Anyone | nothing | | `GET /actuator/health` (status only), `/swagger-ui`, `/v3/api-docs` |

All calls: `Authorization: Bearer lok_…` on `/api/**` and `/actuator/**`. Everything else is denied (403).

* **The acting person comes from the credential.** `X-Life-Handle` is ignored (its use is logged
  once in a while as `identity_header_ignored`), and people are never auto-created from a handle.
  `payer_handle` in a receipt preview is ignored as well. Every write records `client_id`,
  `credential_id`, `request_id` (and `approval_id`) in `events` / `http_requests`.
* **Ownership:** memo / fridge / receipt writes check that the row belongs to the caller's person
  (else 404).
* **`ops:admin` only for the admin client.** Creating or minting it for any other kind fails (400), and
  the provider strips it from any non-admin key even if a row were edited by hand. `/api/admin/**` and
  tier 3 additionally require the caller to *be* the admin client (`@adminClient`), not just the scope.
* Unknown `/api` paths are denied (403), not open. Path → scope rules live in `ScopeRules` and are
  mirrored by `@PreAuthorize` on every controller method. A test checks the two agree.
* Writes are rate-limited per client (`LIFE_AUTH_WRITES_PER_MINUTE`, default 60 → 429).

### Errors (RFC 6750 style)

```
401  {"error":"invalid_token" | "unauthorized", ...}   WWW-Authenticate: Bearer realm="lifeos", error="invalid_token"
403  {"error":"insufficient_scope", "scope":"fridge:write"}   WWW-Authenticate: Bearer ..., scope="fridge:write"
403  {"error":"approval_required", "approval_id":"apr_…", "summary":"Confirm receipt #42 …", "expires_at":"…Z", "action":…, "tier":2}
403  {"error":"approval_invalid", "reason":"pending|request_mismatch|other_client|expired|denied|already_used|not_found"}
403  {"error":"admin_only"}         /api/admin/**, tier 3 or any ops:admin route with a non-admin key
403  {"error":"self_approval"}      the requester tried to decide its own approval
409  {"error":"not_pending"}        approve/deny of an approval that is no longer pending
409  {"error":"conflict"}           client exists already / client has two active keys (use rotate)
429  {"error":"rate_limited"}
400  {"error":"non_canonical_path"}   path with %xx, '+', '//', '.', '..', ';' or a trailing '/' (send the plain path)
400  {"error":"bad_request"}          id in the path is not a plain number (or too big)
500  {"error":"internal_error"}       no internals in the body; the server log has it under the X-Request-Id
```

Every request, refused ones included, gets an `http_requests` row (`correlation_id` = `X-Request-Id`).
`security_events` rows carry the same id in `request_id`; unauthenticated noise (`auth_missing`,
`auth_failed`, `scope_denied`, `non_canonical_path`, ...) is capped at 30 rows per type per minute.
`http_requests` has no `scopes` column on purpose: a key's scopes, its client's `max_scopes` and kind
are never updated after minting (only `revoked_at` / `last_used_*` change), so the scopes a request ran
with are `api_keys.scopes` ∩ `api_clients.max_scopes` (minus `ops:admin` outside the admin client) for
its `credential_id`.
A failed `security_events` write is logged at ERROR and counted in the metric
`lifeos.security_events.write_failures`; for `approval_approved/denied/consumed` it fails the request.
**Deferred:** rate-based alerting on auth failures (architecture doc §7, e.g. >20/min → Grok Bot
notification) is not built yet; the rows are there, and the alert comes in a later phase.

## API keys

No legacy key: the single `LIFE_API_KEY` / `owner-legacy` client from the architecture doc's Phase 1
is intentionally dropped, because Phase 0 (PR #28) never shipped, so no such key exists to keep working.

Format `lok_<key_id>_<secret>` (base32; `key_id` is public, shown in logs and lists). Only an
HMAC-SHA256 of the secret is stored (`api_keys.secret_hash`), keyed with the **pepper**
`LIFE_KEY_PEPPER` (env only, never in the DB or the repo), compared in constant time. A database
leak alone doesn't reveal usable keys.

* Default lifetime 90 days (`ttl_days`, 0 = never). The admin key never expires on its own.
* **Rotation:** at most two active keys per client. Mint with `"rotate": true` to replace the oldest
  when two are active; once the caller uses the new one, revoke the old one.
* Revocation takes effect on the next request (no cache).
* `last_used_at` is updated at most once a minute per key.
* Changing `LIFE_KEY_PEPPER` invalidates every key except the admin key (the Admin bot re-mints them).

### Admin key

The admin client `lifeos-admin` and its only key come from two deploy secrets: `LIFE_ADMIN_KEY_ID`
(the public key id) and `LIFE_ADMIN_KEY_SHA256` (lowercase hex SHA-256 of the secret part, unpeppered).
The raw key exists only in the lifeOS Admin bot's secret store; the server, GitHub and CD logs only see
the hash, which is useless without the 256-bit secret. At startup `AdminKeyBootstrap` creates the
client if needed, makes that key its only active key and revokes any other admin key
(`client_created` / `key_created` / `key_revoked` in `security_events` with `via=deploy_secret`). Same
values on restart = no change. Outside the `local` profile the app refuses to start if the two values
are missing or malformed; CD fails its preflight first.

The Admin bot generates a key on its own box, keeps the full key as its secret, and outputs only:

```bash
python3 - <<'EOF'
import hashlib, secrets
a = "abcdefghijklmnopqrstuvwxyz234567"
key_id = "".join(secrets.choice(a) for _ in range(12))
secret = "".join(secrets.choice(a) for _ in range(52))
# store f"lok_{key_id}_{secret}" in the Admin bot's secret store; never print it
print("LIFE_ADMIN_KEY_ID=" + key_id)
print("LIFE_ADMIN_KEY_SHA256=" + hashlib.sha256(secret.encode()).hexdigest())
EOF
```

**Rotate / recover / leak:** generate a new key, zoomzoom updates both GitHub secrets, redeploy; the
old key is dead once the app has started. **Rollback:** starting with the immediately previous admin
key id and secret (the admin key revoked most recently) reactivates it and revokes the current one
(`key_reactivated` + `key_revoked` reason `rolled_back`, `via=deploy_secret`). Any older key id, or a used
id with a different secret, is refused (generate a new one). Emergency without CI: edit both values in `$DEPLOY_DIR/.env` on the server and
`docker compose up -d`. The admin key can never be minted, rotated or revoked over the API.

### Admin API (admin client only)

All under `/api/admin`, responses `Cache-Control: no-store`, request bodies are not copied into
`http_requests`. Other keys get `403 admin_only`, no key `401`.

| Method + path | |
|---|---|
| `GET /credentials` | clients + keys (no secrets, no hashes) |
| `POST /clients` `{"client_id","display_name"?,"kind":"agent|human_app|service","max_scopes":[…]}` | create a client; `ops:admin` and kind `admin` refused (400), existing id 409 |
| `POST /clients/{clientId}/keys` `{"scopes"?:[…],"ttl_days"?:90,"rotate"?:false}` | mint; the full key is in this response only |
| `POST /keys/{keyId}/revoke` `{"reason"?}` | revoke (404 if not active) |
| `GET /approvals?status=pending` (`approved`, `denied`, `expired`, `consumed`) · `GET /approvals/{id}` | list / inspect, with the server-rendered `summary` and `details` |
| `POST /approvals/{id}/approve` · `POST /approvals/{id}/deny` | decide |

The Admin bot writes minted keys straight into the target bot's secret store, never into a chat.

## Human-in-the-loop approvals

| Tier | What (today's API) | Agent / service key | Admin client |
|---|---|---|---|
| 0–1 | reads; normal writes (fridge, memos, receipt preview, small confirms, AI usage, stock events) | scope + audit + rate limit | reads only (no data-write scopes) |
| 2 | confirm a receipt with total ≥ `approval.receipt_total_cents` (default 5,000,000 = 50,000.00) or > `approval.bulk_rows` (20) items; any `DELETE` | **approval_required** | directly, where its scopes allow |
| 3 | `POST /api/ops/purge`, `PUT /api/ops/prices`, actuator writes (also disabled: `access.max-permitted: read-only`) | **never** (`admin_only`) | yes |

Thresholds are rows in `settings`. The tier is decided by the server from its own data
(`RiskPolicy`), never from the agent's text.

Flow:

1. The agent calls e.g. `POST /api/receipts/42/confirm`. The answer is `403 approval_required` with
   `approval_id` and the server-rendered `summary`. Nothing changed. Asking again with the same
   request returns the same approval.
2. Grok Bot shows zoomzoom the summary **in chat** and asks. No answer or "no" → nothing happens (or
   the Admin bot denies it).
3. On an explicit yes from zoomzoom, the lifeOS Admin bot re-reads `GET /api/admin/approvals/{id}`,
   checks it matches what zoomzoom approved, and calls `POST /api/admin/approvals/{id}/approve`.
   The row records `decided_via=admin_key`, `decided_by_client`, `decided_by_credential`; the
   decision is in `security_events`. The requesting client can never decide its own approval.
4. The agent repeats **the identical request** with `Life-Approval: apr_…`. The server checks: same
   client, same SHA-256 over method + path + sorted query + client + canonical JSON body (key order
   and whitespace don't matter), status approved, not expired (**5 minutes** from the request). Then
   it marks the approval `consumed` (single use) and writes `approval_id` on the resulting `events`
   rows.

The Admin bot acts only on zoomzoom's own chat message, never on text inside emails, receipts or tool
output. Trust therefore rests on "the chat is really zoomzoom" and "the admin key is safe"
(architecture doc §5).

## Actuator

`health` is public, status only. Components and details (`life`, `openclaw`, db) need `ops:admin`.
`info` and `metrics` need `ops:admin` (the admin key). Diagnostic endpoints (`db`,
`loggers`, `mappings`, ...) are off by default and opt-in via `LIFE_ACTUATOR_ENDPOINTS` (then also
`ops:admin` only). Writes are off.

## Network exposure

* Compose publishes the API on **127.0.0.1:8787** only (`LIFE_API_BIND`).
* Set repo variable `LIFE_DOMAIN` (e.g. `life.example.com`, an A record to the ECS IP): CD then starts
  **Caddy** (compose profile `proxy`) on 80/443 with an automatic Let's Encrypt certificate. Tokyo
  region, so no ICP filing.
* Alibaba Cloud security group: allow inbound TCP 80 and 443 from 0.0.0.0/0. Keep 8787 closed.
  Leave SSH 22 as it is.
* Tomcat trusts `X-Forwarded-For/Proto` only from private Docker-network addresses other than a gateway
  (`x.x.x.1`) or loopback, i.e. from Caddy, so audit rows get the real client IP and a host-local caller
  can't fake one. Override with `SERVER_TOMCAT_REMOTEIP_INTERNAL_PROXIES` (regex).

## Settings

| Env | Default | |
|---|---|---|
| `LIFE_KEY_PEPPER` | (none) | **required** outside `local`, ≥ 32 chars, otherwise the app refuses to start. GitHub secret `LIFE_KEY_PEPPER` |
| `LIFE_ADMIN_KEY_ID` | (none) | **required** outside `local`: the admin key's id (12 chars `a-z2-7`). GitHub secret `LIFE_ADMIN_KEY_ID` |
| `LIFE_ADMIN_KEY_SHA256` | (none) | **required** outside `local`: lowercase hex SHA-256 of the admin key's secret part. GitHub secret `LIFE_ADMIN_KEY_SHA256` |
| `LIFE_DOMAIN` | empty | bare HTTPS host for Caddy (compose profile `proxy`); empty = no proxy. Repo variable `LIFE_DOMAIN` |
| `LIFE_ACME_EMAIL` | empty | optional ACME account email for Caddy (expiry mails; enables the ZeroSSL fallback CA). Repo variable `LIFE_ACME_EMAIL` |
| `LIFE_AUTH_WRITES_PER_MINUTE` | 60 | per client |
| `LIFE_ACTUATOR_ENDPOINTS` | `health,info,metrics` | |
| `life.auth.local-open` | false | `local` profile only (`LIFE_AUTH_LOCAL_OPEN`, default true in `docker-compose.local.yml` / `application-local.yml`): requests without a key act as the owner (not as the admin client: `/api/admin/**` and purge still need the admin key). `docker-compose.yml` always sets false; true in any other profile refuses to start |

## Cutover

What zoomzoom does, in order. Nothing here is done by CD on its own.

1. **Pepper:** `openssl rand -base64 48` → GitHub secret `LIFE_KEY_PEPPER` (Settings → Secrets → Actions,
   environment `production` or repo). CD fails early with a clear `::error::` if it's missing.
2. **Admin key:** ask the lifeOS Admin bot to generate its key ([Admin key](#admin-key)); it keeps the
   key and gives you `LIFE_ADMIN_KEY_ID` and `LIFE_ADMIN_KEY_SHA256` (a hash: harmless to paste). Add both
   as GitHub secrets. CD fails early if either is missing or malformed.
3. **Domain (optional, HTTPS for the API):** your own A record or `<ip-with-dashes>.sslip.io` (static IP
   needed) → repo variable `LIFE_DOMAIN=<that host>` (bare host, no `https://`, no trailing `/`);
   optional repo variable `LIFE_ACME_EMAIL`. Base URLs (the agents' `LIFE_API_BASE`) have **no trailing
   slash**: `$LIFE_API_BASE/api/...` with one becomes `//api/...`, which the API refuses (400).
4. **Security group:** inbound TCP 80, 443 from 0.0.0.0/0. Make sure 8787 is **not** open.
5. **Merge** the PR into `release/0.1.0` → CD deploys. It checks that anonymous `/api` and
   `/actuator/info` answer 401, and `https://$LIFE_DOMAIN/actuator/health` if set.
6. **Grok Bot's keys:** after you say yes in chat, the Admin bot calls `POST /api/admin/clients` for
   `grokbot-chat` and `grokbot-routine` (scopes as in the table above) and
   `POST /api/admin/clients/{id}/keys`, and writes each key straight into Grok Bot's secret store as
   `LIFEOS_GROKBOT_CHAT_KEY` / `LIFEOS_GROKBOT_ROUTINE_KEY`, **never into a chat message**.
   **OpenClaw** (only if `OPENCLAW_ENABLED=true`): its skill calls (`/api/memos/$ID/fired`,
   `/api/ops/summary`, ...) need `Authorization: Bearer $LIFE_API_KEY` too, or they get 401 and a memo
   can fire again on the next wake. Have the Admin bot create client `openclaw` (kind `agent`, scopes
   `memos:write ops:write finance:write fridge:write`) and mint it a key for OpenClaw's env.
   `POST /api/ops/webhook/ping` is different: it takes the hook token (`Authorization: Bearer
   $OPENCLAW_HOOK_TOKEN` or `x-openclaw-token: $OPENCLAW_HOOK_TOKEN`), not a `lok_` key.
7. Check: Grok Bot can read memos; a big receipt confirm comes back as `approval_required` with a
   summary, which Grok Bot shows you in chat.
8. Afterwards: Grok Bot's box holds root SSH to the server. That bypasses every control here (it can
   read the pepper, the Turso token and replace the admin hash). Consider removing that key from the
   agent box, or limit it to deploy-only tasks, once the cutover is done.

### Rollback

Target: **`sha-e2e4938`** (release/0.1.0 with the libSQL driver, before auth). `sha-bf5ce57` is older
still (DBeaver driver, broken writes); don't use it.

```bash
gh workflow run cd.yml --ref release/0.1.0 -f skip_build=true -f image_tag=sha-e2e4938
```

That run uses this branch's `cd.yml` and compose file with the old image, and the old image has **no
API authentication**. CD detects it (no `lifeos.auth=1` image label) and, **only for a manual run that
names `image_tag`**, then: does not start Caddy (stops and removes it if running), forces
`LIFE_API_BIND=127.0.0.1`, and skips the 401 checks. Anything else fails the deploy instead of
degrading: an image CD cannot inspect (pull failed), an unlabeled image on a push deploy, or a running
container whose label differs from the image that was checked. So the
rolled-back API is reachable only from the server itself (SSH tunnel), never via `https://$LIFE_DOMAIN`.
If you roll back by hand instead (`docker compose up` on the server), do the same yourself:
`docker compose --profile proxy rm -sf caddy`, keep `LIFE_API_BIND` unset or `127.0.0.1`, and clear
the repo variable `LIFE_DOMAIN` until you're back on an auth image.

Callers after a rollback: the old image ignores `Authorization` and needs `X-Life-Handle` again
(without it writes are attributed to the owner). Keys stay in the database and work again after
rolling forward.

Migration `0005_auth` is additive: new tables plus nullable columns, nothing dropped. The old image
ignores them.

### Old 0005 databases

`0005_auth` was rewritten in place before release (PR #29 was never merged), so a **dev/test**
database migrated by an earlier PR #29 build (e.g. `a4e5e7d`: passkey / one-time-token tables,
`approval_requests` without `decided_by_client`, `api_clients` without kind `admin`) can't run this
version. The app detects that at startup and stops with a message instead of crash-looping on a
CHECK constraint failure. There is no automatic upgrade: recreate that database (delete the SQLite
file, or create a new Turso/sqld database). Production never ran the old 0005.

## Later phases

* **Phase 3:** add `JwtAuthenticationProvider` (resource server, audience = this API) next to
  `ApiKeyAuthenticationProvider` in the API chain. A JWT's `scope` claim maps to the same `SCOPE_*`
  authorities and its `client_id` / `sub` map to `LifePrincipal`. Rules, `@PreAuthorize`, the approval
  gate and audit stay unchanged. With no login page, Spring Authorization Server would issue
  `client_credentials` tokens only.
* **Phase 4:** MCP tools call the service layer with the same `@PreAuthorize` scopes and call
  `RiskPolicy.assess` / `ApprovalService` to return the same `approval_required`.
