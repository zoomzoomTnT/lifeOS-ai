#!/usr/bin/env bash
# Manual helper on the host. CD does the same over SSH (no git).
# Usage, from $HOME/lifeos (compose.yaml + .env + data/):
#   LIFE_IMAGE=ghcr.io/zoomzoomtnt/lifeos-ai:latest ./deploy.sh
set -euo pipefail

ROOT="${DEPLOY_DIR:-$HOME/lifeos}"
cd "$ROOT"

if ! command -v docker >/dev/null 2>&1; then
  echo "docker not found (PATH=$PATH). Install Docker Engine + Compose V2 plugin (docs/cd.md#server-prerequisites)." >&2
  exit 1
fi
if ! docker compose version; then
  echo "'docker compose' (Compose V2) not available. Install the docker-compose-plugin." >&2
  exit 1
fi
# compose mounts this empty dir when OPENCLAW_HOME is unset (OpenClaw off)
mkdir -p openclaw-disabled

FILE=compose.yaml
if [ ! -f "$FILE" ] && [ -f docker-compose.yml ]; then
  FILE=docker-compose.yml
fi

# OpenClaw master switch: env, else .env, else true.
if [ -z "${OPENCLAW_ENABLED:-}" ] && [ -f .env ]; then
  OPENCLAW_ENABLED="$(sed -n 's/^OPENCLAW_ENABLED=//p' .env | tail -n 1)"
fi
case "$(printf '%s' "${OPENCLAW_ENABLED:-true}" | tr '[:upper:]' '[:lower:]')" in
  false|0|no|off) OPENCLAW_ENABLED=false ;;
  *) OPENCLAW_ENABLED=true ;;
esac
export OPENCLAW_ENABLED

IMAGE="${LIFE_IMAGE:-ghcr.io/zoomzoomtnt/lifeos-ai:latest}"
export LIFE_IMAGE="$IMAGE"

mkdir -p data/backups
if [ -f data/life.db ]; then
  STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
  cp -a data/life.db "data/backups/life.db.${STAMP}"
  echo "backed up data/life.db -> data/backups/life.db.${STAMP}"
  ls -1t data/backups/life.db.* 2>/dev/null | tail -n +11 | xargs -r rm -f
fi

docker compose -f "$FILE" pull api
docker compose -f "$FILE" up -d --no-build --remove-orphans --wait --wait-timeout 120
if [ "$OPENCLAW_ENABLED" = "true" ]; then
  docker compose -f "$FILE" --profile sync run --rm skill-sync
else
  echo "OPENCLAW_ENABLED=false: skipping skill sync"
fi
curl -fsS http://127.0.0.1:${LIFE_API_PORT:-8787}/actuator/health
echo "deployed $IMAGE"
