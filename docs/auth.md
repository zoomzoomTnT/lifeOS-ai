# API authentication and authorization

Spring Security 7.1 (Spring Boot 4.1.1 BOM). Design and threat model: the architecture doc
(`lifeos-auth-architecture.md`), phases 0–2 implemented here. Phase 3 (OAuth 2.1 tokens via Spring
Authorization Server) and phase 4 (MCP endpoint) slot in without changing anything below. See
[Later phases](#later-phases).

## Who can call what

| Caller | Credential | Where |
|---|---|---|
| Grok Bot (chat) | API key `grokbot-chat`: `finance:write fridge:write memos:write stocks:write ops:read ops:write` (write implies read) | `Authorization: Bearer lok_…` on `/api/**` |
| Grok Bot (routines / wake check) | API key `grokbot-routine`: `memos:read ops:read` | same |
| A deploy check / backup job | `service` client, e.g. `ops:admin` for `/actuator/**` | same |
| zoomzoom (owner) | **passkey** (WebAuthn) in the browser; session cookie | `/login`, `/admin`, `/ops`, `/approve/{id}`, and `/api/**` from those pages (CSRF on writes) |
| Anyone | nothing | `GET /actuator/health` (status only), `/swagger-ui`, `/v3/api-docs`, `/login` |

* **The acting person comes from the credential.** `X-Life-Handle` is ignored (its use is logged
  once in a while as `identity_header_ignored`), and people are never auto-created from a handle.
  `payer_handle` in a receipt preview is ignored as well. Every write records `client_id`,
  `credential_id`, `request_id` (and `approval_id`) in `events` / `http_requests`.
* **Ownership:** memo / fridge / receipt writes check that the row belongs to the caller's person
  (else 404).
* **Agents never get `ops:admin`** (`lifeos-admin` refuses to grant it to an `agent` client).
* Unknown `/api` paths are denied (403), not open. Path → scope rules live in `ScopeRules` and are
  mirrored by `@PreAuthorize` on every controller method. A test checks the two agree.
* Writes are rate-limited per non-owner client (`LIFE_AUTH_WRITES_PER_MINUTE`, default 60 → 429).

### Errors (RFC 6750 style)

```
401  {"error":"invalid_token" | "unauthorized", ...}   WWW-Authenticate: Bearer realm="lifeos", error="invalid_token"
403  {"error":"insufficient_scope", "scope":"fridge:write"}   WWW-Authenticate: Bearer ..., scope="fridge:write"
403  {"error":"approval_required", "approval_id":"apr_…", "approve_url":"https://<domain>/approve/apr_…", "expires_at":"…Z"}
403  {"error":"approval_invalid", "reason":"pending|request_mismatch|other_client|expired|denied|already_used|not_found"}
403  {"error":"owner_only"}                          tier 3 with any API key
403  {"error":"passkey_reauth_required", "login_url":…}   owner session older than 5 minutes for tier 3
429  {"error":"rate_limited"}
```

## API keys

Format `lok_<key_id>_<secret>` (base32; `key_id` is public, shown in logs and lists). Only an
HMAC-SHA256 of the secret is stored (`api_keys.secret_hash`), keyed with the **pepper**
`LIFE_KEY_PEPPER` (env only, never in the DB or the repo), compared in constant time. A database
leak alone doesn't reveal usable keys.

* Default lifetime 90 days (`--ttl-days`, 0 = never).
* **Rotation:** at most two active keys per client. `mint-key <client> --rotate` mints a new one; once
  the caller uses it, `revoke-key <old_key_id>`. (With two active keys, `--rotate` revokes the oldest.)
* Revocation takes effect on the next request (no cache).
* `last_used_at` is updated at most once a minute per key.
* Changing `LIFE_KEY_PEPPER` invalidates every key.

### `lifeos-admin` (in the image)

Runs a small separate JVM inside the api container (about 95 MB for about 1 s). It uses the
container's database settings and pepper. **Secrets are printed once to stdout and nothing else
goes there.** They are never logged.

```bash
docker exec -it lifeos-api lifeos-admin help
docker exec lifeos-api lifeos-admin bootstrap --format env     # Grok Bot's two keys (idempotent)
docker exec lifeos-api lifeos-admin list                       # clients + keys, no secrets
docker exec lifeos-api lifeos-admin mint-key grokbot-chat --rotate --format env
docker exec lifeos-api lifeos-admin revoke-key <key_id> --reason leaked
docker exec lifeos-api lifeos-admin create-client backup --kind service --scopes "ops:admin"
docker exec -it lifeos-api lifeos-admin owner-login            # YOU only: passkey registration link
docker exec -it lifeos-api lifeos-admin owner-login --recovery # lost every passkey
```

`bootstrap` never prints the owner link. That output is meant for Grok Bot, and an agent holding
the owner link could register its own passkey.

## Owner: passkeys (WebAuthn)

* RP ID = `LIFE_DOMAIN`, so passkeys need the HTTPS domain (Caddy). Without a domain they only work
  on `http://localhost`.
* **First passkey:** `lifeos-admin owner-login` prints a one-time link (5 minutes, single use,
  stored in `one_time_tokens`). Opening it signs you in **only to register a passkey**
  (`/webauthn/register`). Then sign in at `/login` with the passkey. There's no password, and no web
  form that sends tokens: tokens come only from the CLI on the server.
* An OTT-only session can't see `/ops`, `/admin`, approvals or the API. That needs the passkey
  factor (Spring Security 7 `FactorGrantedAuthority`).
* Sessions: cookie `LIFEOS_SESSION`, HttpOnly, SameSite=Strict, Secure (HTTPS), 30 minutes; CSRF on.
* Approvals and tier-3 actions need a passkey assertion from the last 5 minutes
  (`life.auth.passkey-max-age`). Otherwise the page asks you to confirm with the passkey again.
* Passkey add/remove, owner logins and their failures go to `security_events`.

## Human-in-the-loop approvals

| Tier | What (today's API) | API key (agent) | Owner passkey session |
|---|---|---|---|
| 0–1 | reads; normal writes (fridge, memos, receipt preview, small confirms, AI usage, stock events) | scope + audit + rate limit | yes |
| 2 | confirm a receipt with total ≥ `approval.receipt_total_cents` (default 5,000,000 = 50,000.00) or > `approval.bulk_rows` (20) items; `PUT /api/ops/prices`; any `DELETE` | **approval_required** | yes, directly |
| 3 | `POST /api/ops/purge`, actuator writes (also disabled: `access.max-permitted: read-only`) | **never** (`owner_only`), even with `ops:admin` | yes, passkey ≤ 5 min |

Thresholds are rows in `settings` and are owner-editable. The tier is decided by the server from
its own data (`RiskPolicy`), never from the agent's text.

Flow:

1. The agent calls e.g. `POST /api/receipts/42/confirm`. The answer is `403 approval_required` with
   `approve_url`. Nothing changed. Asking again with the same request returns the same approval.
2. Grok Bot sends you the link. `/approve/apr_…` shows what will happen (merchant, total, items,
   client, key, expiry), rendered from the database.
3. You approve or deny. That needs a fresh passkey. The decision is recorded with `decided_via=passkey`
   and written to `security_events`.
4. The agent repeats **the identical request** with `Life-Approval: apr_…`. The server checks: same
   client, same SHA-256 over method + path + sorted query + client + canonical JSON body (key order
   and whitespace don't matter), status approved, not expired (10 minutes from the request). Then it
   marks the approval `consumed` (single use) and writes `approval_id` on the resulting `events`
   rows.

Grok Bot's own "are you sure?" in chat is UX, never a control.

## Actuator

`health` is public, status only. Components and details (`life`, `openclaw`, db) need `ops:admin`.
`info`, `metrics` and `db` need `ops:admin` (the owner session has it). Other endpoints are
opt-in via `LIFE_ACTUATOR_ENDPOINTS`. Writes are off.

## Network exposure

* Compose publishes the API on **127.0.0.1:8787** only (`LIFE_API_BIND`).
* Set repo variable `LIFE_DOMAIN` (e.g. `life.example.com`, an A record to the ECS IP): CD then starts
  **Caddy** (compose profile `proxy`) on 80/443 with an automatic Let's Encrypt certificate. Tokyo
  region, so no ICP filing.
* Alibaba Cloud security group: allow inbound TCP 80 and 443 from 0.0.0.0/0. Keep 8787 closed.
  Leave SSH 22 as it is.
* Tomcat trusts `X-Forwarded-For/Proto` from the internal proxy only, so audit rows get the real client IP.

## Settings

| Env | Default | |
|---|---|---|
| `LIFE_KEY_PEPPER` | (none) | **required** outside `local`, ≥ 32 chars, otherwise the app refuses to start. GitHub secret `LIFE_KEY_PEPPER` |
| `LIFE_DOMAIN` | empty | HTTPS host, passkey RP ID, Caddy on/off. Repo variable `LIFE_DOMAIN` |
| `LIFE_PUBLIC_URL` | `https://$LIFE_DOMAIN` | base for login/approval links |
| `LIFE_AUTH_WRITES_PER_MINUTE` | 60 | per non-owner client |
| `LIFE_ACTUATOR_ENDPOINTS` | `health,info,metrics,db` | |
| `LIFE_COOKIE_SECURE` | true | `false` only for plain http://localhost |
| `life.auth.local-open` | false | `local` profile only (`LIFE_AUTH_LOCAL_OPEN`, default true there): requests without a key act as the owner. Refused in any other profile |

## Cutover

What zoomzoom does, in order. Nothing here is done by CD or by an agent.

1. **Pepper:** `openssl rand -base64 48` → GitHub secret `LIFE_KEY_PEPPER` (Settings → Secrets → Actions,
   environment `production` or repo). CD fails early with a clear `::error::` if it's missing.
2. **DNS (optional but needed for passkeys/approvals):** A record `life.<your-domain>` → ECS public IP.
   Repo variable `LIFE_DOMAIN=life.<your-domain>`.
3. **Security group:** inbound TCP 80, 443 from 0.0.0.0/0. Make sure 8787 is **not** open.
4. **Merge** the PR into `release/0.1.0` → CD deploys. It checks that anonymous `/api` and
   `/actuator/info` answer 401, and `https://$LIFE_DOMAIN/actuator/health` if set.
5. **Grok Bot's keys:** on the server, `docker exec lifeos-api lifeos-admin bootstrap --format env`
   prints `LIFEOS_GROKBOT_CHAT_KEY=…` and `LIFEOS_GROKBOT_ROUTINE_KEY=…`. Put them into Grok Bot's
   secret store (connector/env secret settings), **not into a chat message** (chats are logged).
   Alternatively let Grok Bot run that command over its SSH access and write the output straight to
   a mode-600 env file on its own box without printing it.
6. **Your passkey:** run `docker exec -it lifeos-api lifeos-admin owner-login` **yourself**, open the
   link within 5 minutes, register a passkey (phone or laptop; add a second one as a backup), then sign
   in at `https://$LIFE_DOMAIN/login`.
7. Check: `https://$LIFE_DOMAIN/ops` shows the dashboard after passkey sign-in. Grok Bot can read
   memos. A big receipt confirm comes back as an approval link.
8. Afterwards: Grok Bot's box holds root SSH to the server. That bypasses every control here (it can
   read the pepper and the Turso token). Consider removing that key from the agent box, or limit it to
   deploy-only tasks, once the cutover is done.

Rollback: redeploy the previous image tag (`workflow_dispatch`, `skip_build`, `image_tag=sha-…`).
Migration `0005_auth` is additive: new tables plus nullable columns, nothing dropped. The old image
ignores them.

## Later phases

* **Phase 3:** add `JwtAuthenticationProvider` (resource server, audience = this API) next to
  `ApiKeyAuthenticationProvider` in the API chain. A JWT's `scope` claim maps to the same `SCOPE_*`
  authorities and its `client_id` / `sub` map to `LifePrincipal`. Rules, `@PreAuthorize`, the approval
  gate and audit stay unchanged. Spring Authorization Server would reuse `/login` (passkey) for consent.
* **Phase 4:** MCP tools call the service layer with the same `@PreAuthorize` scopes and call
  `RiskPolicy.assess` / `ApprovalService` to return the same `approval_required`.
