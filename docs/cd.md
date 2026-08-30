# Continuous deploy

Merge **`main` → `release/x.y.z`** (current: `release/0.1.0`) to ship. CI on `main` tests and can publish the image; CD on `release/**` SSHs to the host and runs **`docker compose`** (Compose V2 plugin, not the old `docker-compose` binary).

The server is **not** a git checkout. The runner already has the repo. CD only copies `docker-compose.yml` → `$HOME/lifeos/compose.yaml`, then `docker compose -f compose.yaml pull && up`. No `git clone` / `git pull` on the box.

## Trigger

| Event | What happens |
|---|---|
| Push / merge to `release/**` (e.g. `release/0.1.0`) | Maven verify → push GHCR (`sha-<7>`, `0.1.0`, `latest`) → scp compose → SSH `docker compose up` |
| Actions → CD → Run workflow (from that branch) | Same, or **skip_build** + **image_tag** to roll to an existing tag |

Workflow: [`.github/workflows/cd.yml`](../.github/workflows/cd.yml)

## Secrets

| Name | Use |
|---|---|
| `SERVER_IP` | Host |
| `SERVER_UN` | SSH user |
| `SERVER_PK` | Private key PEM / OpenSSH |

Optional: set env `DEPLOY_DIR` on the server (default `$HOME/lifeos`).

## Server layout (no git)

```
$HOME/lifeos/              # not a clone
  compose.yaml             # overwritten each CD from the repo compose file
  .env                     # you create once; CD never writes this
  data/life.db             # SQLite volume
  data/backups/            # last 10 copies before a replace
~/.openclaw/               # already installed; skill-sync writes workspace/skills/life-os
```

Host needs: Docker Engine + **Compose V2 plugin** (`docker compose version`), outbound pull of `ghcr.io/zoomzoomtnt/lifeos-ai`, OpenClaw already running. Not required: `git`, a copy of this repository.

If the GHCR package is private, make it Public (Packages → lifeos-ai) or keep the job `docker login`.

## What this pipeline does today

1. `mvn verify` on the runner.
2. Push `sha-<short>` + `<version>` + `latest`.
3. scp `docker-compose.yml` to `/tmp/lifeos-cd` (Actions runner checkout only).
4. On the host: copy that file to `$HOME/lifeos/compose.yaml`.
5. Backup `data/life.db` if present.
6. `docker compose -f compose.yaml pull api`
7. `docker compose -f compose.yaml up -d --no-build --wait`
8. `docker compose -f compose.yaml --profile sync run --rm skill-sync`
9. `curl /actuator/health` must be `UP`.

`--no-build` means the host never compiles the Dockerfile (that would need a source tree).

## First-time server checklist

- [ ] `ssh -i <key> $SERVER_UN@$SERVER_IP` works
- [ ] `docker compose version` works without sudo (plugin, not `docker-compose`)
- [ ] `mkdir -p ~/lifeos && cp env.example ~/lifeos/.env` and fill tokens **once**
- [ ] GHCR image is pullable
- [ ] OpenClaw is already installed; CD does not install it
