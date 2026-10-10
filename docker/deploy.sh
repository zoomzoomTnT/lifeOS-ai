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

# API key: env, else .env. The app refuses to start without one (>= 32 chars).
if [ -z "${LIFE_API_KEY:-}" ] && [ -f .env ]; then
  LIFE_API_KEY="$(sed -n 's/^LIFE_API_KEY=//p' .env | tail -n 1)"
fi
if [ -z "${LIFE_API_KEY:-}" ]; then
  echo "LIFE_API_KEY is not set (env or $ROOT/.env). Generate one: openssl rand -hex 32" >&2
  exit 1
fi
if [ "${#LIFE_API_KEY}" -lt 32 ]; then
  echo "LIFE_API_KEY is too short (${#LIFE_API_KEY} chars, need >= 32). Regenerate: openssl rand -hex 32" >&2
  exit 1
fi
export LIFE_API_KEY

# Optional HTTPS proxy (Caddy) when LIFE_DOMAIN is set (env or .env).
if [ -z "${LIFE_DOMAIN:-}" ] && [ -f .env ]; then
  LIFE_DOMAIN="$(sed -n 's/^LIFE_DOMAIN=//p' .env | tail -n 1)"
fi
PROFILES=()
if [ -n "${LIFE_DOMAIN:-}" ]; then
  [ -f docker/Caddyfile ] || { echo "LIFE_DOMAIN is set but $ROOT/docker/Caddyfile is missing" >&2; exit 1; }
  export LIFE_DOMAIN
  PROFILES=(--profile proxy)
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

docker compose -f "$FILE" "${PROFILES[@]}" pull
docker compose -f "$FILE" "${PROFILES[@]}" up -d --no-build --remove-orphans --wait --wait-timeout 120
if [ "$OPENCLAW_ENABLED" = "true" ]; then
  docker compose -f "$FILE" --profile sync run --rm skill-sync
else
  echo "OPENCLAW_ENABLED=false: skipping skill sync"
fi
curl -fsS http://127.0.0.1:${LIFE_API_PORT:-8787}/actuator/health
echo
code="$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:${LIFE_API_PORT:-8787}/api/ops/summary?hours=1")"
[ "$code" = "401" ] || { echo "API auth not enforced (unauthenticated request returned $code)" >&2; exit 1; }
curl -fsS -o /dev/null -H "Authorization: Bearer $LIFE_API_KEY" "http://127.0.0.1:${LIFE_API_PORT:-8787}/api/ops/summary?hours=1"
echo "auth ok: 401 without key, 200 with LIFE_API_KEY"
echo "deployed $IMAGE"
