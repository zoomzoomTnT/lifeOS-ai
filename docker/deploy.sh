#!/usr/bin/env bash
# Manual helper on the host. CD does the same over SSH (no git).
# Usage, from $HOME/lifeos (compose.yaml + .env + data/):
#   LIFE_IMAGE=ghcr.io/zoomzoomtnt/lifeos-ai:latest ./deploy.sh
set -euo pipefail

ROOT="${DEPLOY_DIR:-$HOME/lifeos}"
cd "$ROOT"

command -v docker >/dev/null
docker compose version

FILE=compose.yaml
if [ ! -f "$FILE" ] && [ -f docker-compose.yml ]; then
  FILE=docker-compose.yml
fi

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
docker compose -f "$FILE" --profile sync run --rm skill-sync
curl -fsS http://127.0.0.1:${LIFE_API_PORT:-8787}/actuator/health
echo "deployed $IMAGE"
