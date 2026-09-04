# Continuous deploy

Merge **`main` → `release/x.y.z`** (current: `release/0.1.0`) to ship. CI on `main` tests and can publish the image; CD on `release/**` SSHs to the host and runs **`docker compose`** (Compose V2 plugin).

The server is **not** a git checkout. CD copies `docker-compose.yml`, `docker/ensure-hook-token.sh`, and `docker/openclaw-config.sh`, then `docker compose pull && up`. No token is baked into the GHCR image.

## Trigger

| Event | What happens |
|---|---|
| Push / merge to `release/**` | Maven verify → push GHCR → scp compose + OpenClaw scripts → ensure hook token → `docker compose up` |
| Actions → CD → Run workflow | Same, or **skip_build** + **image_tag** to roll to an existing tag |

`workflow_dispatch` is a manual run of the same workflow (no git commit). Token logic is identical to a merge.

## Webhook token (`OPENCLAW_HOOK_TOKEN`)

OpenClaw docs name: **Webhooks**. Config namespace: **`hooks.*`** (not `webhooks.*`). See [webhooks.md](webhooks.md).

Opaque shared secret. OpenClaw does not expire it. CD **does not rotate** on deploy.

Every deploy, on the host (`docker/openclaw-config.sh`):

1. If `~/lifeos/.env` already has a non-empty `OPENCLAW_HOOK_TOKEN`, reuse it.
2. Else mint `secrets.token_urlsafe(32)`, upsert `.env` (`chmod 600`).
3. `openclaw config set hooks.enabled true`
4. `openclaw config set hooks.path "/hooks"` (or `$OPENCLAW_HOOKS_PATH`; `/` is rejected)
5. `openclaw config set hooks.token "<same value>"`
6. `openclaw config set hooks.defaultSessionKey "hook:life-os"`
7. `openclaw config set hooks.allowRequestSessionKey false`
8. `openclaw config set skills.load.watch true`
9. Log `openclaw config get hooks.path` (token is never printed)

No edits to `openclaw.json`. No `gateway restart`. Recreate the API container only on **first mint**.

Does not invent `OPENCLAW_GATEWAY_TOKEN`, WeChat credentials, or `OPENCLAW_HOME`.

## `OPENCLAW_HOME`

Required in `~/lifeos/.env`. Absolute path only. Compose binds `${OPENCLAW_HOME}` with **no default**. Set it once on the server; CD will not write it.

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
  openclaw-config.sh
  .env                 # you set OPENCLAW_HOME; CD may upsert OPENCLAW_HOOK_TOKEN
  data/life.db
  data/backups/
```

Host needs: Docker Engine + Compose V2 plugin, `openclaw` on PATH for the SSH user.
