# Continuous deploy

Merge **`main` → `release/x.y.z`** (current: `release/0.1.0`) to ship. CI on `main` tests and can publish the image; CD on `release/**` SSHs to the host and runs **`docker compose`** (Compose V2 plugin, not the old `docker-compose` binary).

The server is **not** a git checkout. The runner already has the repo. CD copies `docker-compose.yml` and `docker/provision-hook-token.sh`, then `docker compose pull && up`.

## Trigger

| Event | What happens |
|---|---|
| Push / merge to `release/**` | Maven verify → push GHCR → scp compose + provision script → ensure hook token → `docker compose up` |
| Actions → CD → Run workflow | Same, or **skip_build** + **image_tag**, or **rotate_hook_token** |

Workflow: [`.github/workflows/cd.yml`](../.github/workflows/cd.yml)

## Secrets

| Name | Use |
|---|---|
| `SERVER_IP` | Host |
| `SERVER_UN` | SSH user |
| `SERVER_PK` | Private key PEM / OpenSSH |

Optional: set env `DEPLOY_DIR` on the server (default `$HOME/lifeos`).

## Hook token (`OPENCLAW_HOOK_TOKEN`)

OpenClaw HTTP hook tokens **do not expire**. They are a static shared secret (`hooks.token`) until you rotate them. Not a JWT.

CD runs `provision-hook-token.sh ensure` every deploy:

1. Reuse `~/lifeos/.env` `OPENCLAW_HOOK_TOKEN` if set.
2. Else adopt a literal `hooks.token` already in `~/.openclaw/openclaw.json`.
3. Else mint `secrets.token_urlsafe(32)`, write both files (`chmod 600`), set `hooks.enabled=true`, restart Gateway.

It does **not** invent `OPENCLAW_GATEWAY_TOKEN` or WeChat credentials.

Rotate only on purpose: Actions → CD → Run workflow → `rotate_hook_token=true`. That mints a new value, saves the old one at `~/lifeos/secrets/hook-token.prev`, updates `.env` + `openclaw.json`, restarts Gateway, force-recreates the API container.

There is no automatic time-based expiry. Rotate after a leak or on your own schedule (90 days is a reasonable policy, not a protocol limit).

## Server layout (no git)

```
$HOME/lifeos/
  compose.yaml
  provision-hook-token.sh
  .env                      # CD may create/upsert OPENCLAW_HOOK_TOKEN only
  secrets/hook-token.prev   # last value after rotate
  data/life.db
  data/backups/
~/.openclaw/openclaw.json   # hooks.token kept in sync by the provision script
```

Host needs: Docker Engine + Compose V2 plugin, `python3` (for JSON edit), OpenClaw already installed.
