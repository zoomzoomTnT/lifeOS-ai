# API auth and exposure

## API key

Every request to `/api/**` and `/actuator/**` needs

```
Authorization: Bearer <LIFE_API_KEY>
```

`X-Life-Handle` is still required on mutations, but it only says *who* is acting. It is not authentication.

| Path | Auth |
|---|---|
| `/actuator/health`, `/actuator/health/**` | open (CD, Docker healthcheck, uptime probes) |
| `/ops/**` (static page), `/swagger-ui/**`, `/v3/api-docs/**` | open (no data; the ops page asks for the key and keeps it in `sessionStorage`) |
| `/api/ops/webhook/ping` | its own `OPENCLAW_HOOK_TOKEN` check (unchanged) |
| everything else | `Authorization: Bearer <LIFE_API_KEY>` |

Missing or wrong key: `401`, `WWW-Authenticate: Bearer realm="lifeos"` (plus `error="invalid_token"` for a wrong key), and a JSON body `{"error":"unauthorized",...}`. The comparison is constant-time (SHA-256 of both sides, then `MessageDigest.isEqual`). The key is never logged. Rejected requests are not written to `http_requests`.

### Fail closed

| Profile | `LIFE_API_KEY` | Result |
|---|---|---|
| default (prod, Turso) | unset / blank | **app refuses to start**: `LIFE_API_KEY is not set ...` |
| default | < 32 chars | refuses to start |
| default | >= 32 chars | enforced |
| `local` | unset | auth **off**, with a startup warning (laptop, CI SQLite overlay) |
| `local` | set | enforced |

### Generate and install the key

```bash
openssl rand -hex 32
```

GitHub → repo → Settings → Secrets and variables → Actions → **New repository secret** → name `LIFE_API_KEY`, value = that output. CD then:

1. fails in the first step with `::error title=LIFE_API_KEY secret missing::` if the secret is absent;
2. passes it over SSH and writes `LIFE_API_KEY=` into `~/lifeos/.env` (mode 600);
3. after the health check, asserts `/api/ops/summary` is `401` without the key and `200` with it.

Give the same value to API clients (e.g. Grok Bot) through their own secret store. To rotate: update the secret, re-run CD, update clients.

## Exposure

The API port is published on **127.0.0.1 only** (`LIFE_API_BIND`, default `127.0.0.1`). Internet traffic goes through Caddy on 80/443 with an automatic Let's Encrypt certificate.

### With a domain (recommended)

1. DNS: `A` record, e.g. `lifeos.<your-domain>` → the server's public IP. (Add `AAAA` only if the ECS has public IPv6.)
2. Alibaba Cloud ECS → Security groups → inbound rules:

   | Action | Protocol | Port | Source | Why |
   |---|---|---|---|---|
   | Allow | TCP | 80 | 0.0.0.0/0 | ACME HTTP-01 challenge + redirect to HTTPS |
   | Allow | TCP | 443 | 0.0.0.0/0 | HTTPS API |
   | Allow | UDP | 443 | 0.0.0.0/0 | optional, HTTP/3 |
   | (keep) | TCP | 22 | as today | SSH / CD |
   | **Remove** | TCP | 8787 | any | the app port must not be public |

3. GitHub → repo → Settings → Secrets and variables → Actions → **Variables** → `LIFE_DOMAIN` = `lifeos.<your-domain>`.
4. Re-run CD. It starts `caddy` (compose profile `proxy`) next to `api`. First certificate takes a few seconds.
5. Check: `curl https://lifeos.<your-domain>/actuator/health` → UP. Without a key, `curl -i https://lifeos.<your-domain>/api/ops/summary` → 401.

Caddy config: [`docker/Caddyfile`](../docker/Caddyfile). Certificates persist in the `caddy_data` volume.

### Without a domain (fallbacks)

| Option | How | Tradeoffs |
|---|---|---|
| Free wildcard DNS (`sslip.io` / `nip.io`) | `LIFE_DOMAIN=<ip-with-dashes>.sslip.io`, same steps 2–5 | Real Let's Encrypt cert, no DNS setup. Depends on a third-party DNS service; shared-domain rate limits; name breaks if the IP changes. |
| Caddy `tls internal` (self-signed) | change the site to `https://<ip>` with `tls internal` | No DNS. Clients must pin/trust Caddy's root CA or skip verification (skipping is unsafe). |
| Tunnel (Cloudflare Tunnel, Tailscale Funnel) | run the tunnel agent on the host to `127.0.0.1:8787` | No inbound ports at all (close 80/443 too). Adds an account and an agent; traffic goes through the provider. |
| Plain HTTP (`LIFE_API_BIND=0.0.0.0`, open 8787) | not recommended | The bearer key and all data travel unencrypted and can be sniffed and replayed. |
