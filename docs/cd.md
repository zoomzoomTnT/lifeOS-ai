# Continuous deploy

Merge **`main` → `release`** (or push to `release`) to ship. CI on `main` still tests and publishes the image; CD on `release` SSHs to the OpenClaw host and runs `docker compose up`.

## Trigger

| Event | What happens |
|---|---|
| Push / merge to `release` | Maven verify → build & push GHCR (`sha-<7>`, `release`, `latest`) → SSH deploy |
| Actions → CD → Run workflow | Same, or set **skip_build** + **image_tag** to roll to an existing tag |

Workflow: [`.github/workflows/cd.yml`](../.github/workflows/cd.yml)

## Secrets (repo Settings → Secrets and variables → Actions)

| Name | Use |
|---|---|
| `SERVER_IP` | Host |
| `SERVER_UN` | SSH user |
| `SERVER_PK` | Private key (PEM / OpenSSH). Matching public key must be in `~/.ssh/authorized_keys` on the server |

Optional repo **variable**: `DEPLOY_DIR` is not required. The script uses `$HOME/lifeOS-ai` on the server.

Do not put the private key in git. The Actions `GITHUB_TOKEN` is passed to the server only for `docker login ghcr.io` during that job.

## Server layout CD expects

```
$HOME/lifeOS-ai/          # git clone (created on first run if missing)
  docker-compose.yml
  .env                    # you created this; CD never overwrites it
  data/life.db            # volume; CD copies to data/backups/ before pull
~/.openclaw/              # already installed; skill-sync writes workspace/skills/life-os
```

Host needs: `git`, Docker Engine + Compose plugin, outbound pull of `ghcr.io/zoomzoomtnt/lifeos-ai`, OpenClaw already running. If the GHCR package is still private, either make it Public (Packages → lifeos-ai) or leave the job login as-is.

## What this pipeline does today

1. Gate on `mvn verify` so a red build never reaches the box.
2. Push an immutable `sha-<short>` plus moving `release` / `latest` tags.
3. Fast-forward the server clone to `release` (compose file + `sync-skill.sh`).
4. Copy `data/life.db` → `data/backups/` (keep 10).
5. `docker compose pull` + `up -d --no-build --wait` using that image.
6. `skill-sync` so OpenClaw picks up the skill baked into the image.
7. `curl /actuator/health` must show `UP`.

`.env` and `life.db` stay on the server.

## What else the pipeline can grow into

Cheap next steps, not wired yet:

- **GitHub Environment protection** — `production` is already referenced. Add required reviewers so a merge to `release` waits for you before SSH.
- **Rollback** — re-run CD with `skip_build=true` and `image_tag=sha-<old>`.
- **WeChat / OpenClaw notify** — POST `/hooks/agent` after health is green (or on failure).
- **Production smoke** — hit `/api/ops/should-wake` and `/api/holdings` after deploy (read-only).
- **Off-box DB backup** — scp/rclone `data/backups/` to object storage before pull.
- **Compose file drift check** — fail if server `.env` is missing required keys from `env.example`.
- **OpenClaw itself** — restart gateway only when `openclaw/` or skill files change.
- **GitHub Release notes** — tag `vX.Y.Z` from `release` and attach the image digest.
- **Package cleanup** — expire untagged GHCR images older than N days.
- **Concurrency window** — pause Spring proactive cron (`LIFE_OPENCLAW_WAKE=false`) during the few seconds of container replace.

Do not add Watchtower while this workflow owns `compose up`; they will race.

## First-time server checklist

- [ ] `ssh -i <key> $SERVER_UN@$SERVER_IP` works from your laptop (same key as `SERVER_PK`)
- [ ] That user can `docker compose version` without sudo
- [ ] Clone exists at `~/lifeOS-ai` **or** CD may `git clone` it
- [ ] `~/lifeOS-ai/.env` is already filled (tokens). CD will not create secrets
- [ ] GHCR image is pullable (`docker pull ghcr.io/zoomzoomtnt/lifeos-ai:latest`)
- [ ] Repo environment **production** exists (created on first CD run) and secrets are repo-scoped, not missing from the environment
