#!/usr/bin/env bash
# Manual / server-side deploy helper. CD runs the same steps over SSH.
# Usage (on the OpenClaw host, from the clone):
#   LIFE_IMAGE=ghcr.io/zoomzoomtnt/lifeos-ai:latest ./docker/deploy.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

IMAGE="${LIFE_IMAGE:-ghcr.io/zoomzoomtnt/lifeos-ai:latest}"
export LIFE_IMAGE="$IMAGE"

mkdir -p data/backups
if [ -f data/life.db ]; then
  STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
  cp -a data/life.db "data/backups/life.db.${STAMP}"
  echo "backed up data/life.db -> data/backups/life.db.${STAMP}"
  ls -1t data/backups/life.db.* 2>/dev/null | tail -n +11 | xargs -r rm -f
fi

docker compose pull api
docker compose up -d --no-build --remove-orphans --wait --wait-timeout 120
docker compose --profile sync run --rm skill-sync
curl -fsS http://127.0.0.1:${LIFE_API_PORT:-8787}/actuator/health
echo "deployed $IMAGE"
