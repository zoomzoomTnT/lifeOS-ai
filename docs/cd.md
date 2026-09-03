# Continuous deploy

Merge **`main` → `release/x.y.z`** (current: `release/0.1.0`) to ship. CI on `main` tests and can publish the image; CD on `release/**` SSHs to the host and runs **`docker compose`** (Compose V2 plugin).

The server is **not** a git checkout. CD copies `docker-compose.yml` and `docker/ensure-hook-token.sh`, then `docker compose pull && up`. No token is baked into the GHCR image.

## Trigger

| Event | What happens |
|---|---|
| Push / merge to `release/**` | Maven verify → push GHCR → scp compose + ensure script → ensure hook token → `docker compose up` |
| Actions → CD → Run workflow | Same, or **skip_build** + **image_tag** to roll to an existing tag |

`workflow_dispatch` is a manual run of the same workflow (no git commit). Token logic is identical to a merge.

## Hook token (`OPENCLAW_HOOK_TOKEN`)

Opaque shared secret. OpenClaw does not expire it. CD **does not rotate** on deploy.

Every deploy, on the host:

1. If `~/lifeos/.env` already has a non-empty `OPENCLAW_HOOK_TOKEN`, reuse it.
2. Else mint `secrets.token_urlsafe(32)`, create/upsert `.env` (`chmod 600`).
3. Write `OPENCLAW_HOME` as an **absolute** path (`$HOME/.openclaw` resolved). Compose cannot nest `${HOME}` in a default — that produced `volume [${HOME/.openclaw}] not defined`.
4. `openclaw config set hooks.enabled true`
5. `openclaw config set hooks.token "<same value>"`

No edits to `openclaw.json`. No `gateway restart` (`hooks.*` hot-applies). Recreate the API container only on **first mint** so compose injects the new env.

Does not invent `OPENCLAW_GATEWAY_TOKEN` or WeChat credentials.

## Secrets

| Name | Use |
|---|---|
| `SERVER_IP` | Host |
| `SERVER_UN` | SSH user |
| `SERVER_PK` | Private key PEM / OpenSSH |

Optional host env `DEPLOY_DIR` (default `$HOME/lifeos`).

## Server layout (no git)

```
$HOME/lifeos/
  compose.yaml
  ensure-hook-token.sh
  .env                 # OPENCLAW_HOOK_TOKEN + absolute OPENCLAW_HOME
  data/life.db
  data/backups/
```

Host needs: Docker Engine + Compose V2 plugin, `openclaw` on PATH for the SSH user.
