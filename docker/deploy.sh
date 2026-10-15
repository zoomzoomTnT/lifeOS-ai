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

# Auth: the app refuses to start without LIFE_KEY_PEPPER (env or .env, >= 32 chars).
envval() { sed -n "s/^$1=//p" .env 2>/dev/null | tail -n 1; }
PEPPER="${LIFE_KEY_PEPPER:-$(envval LIFE_KEY_PEPPER)}"
if [ "${#PEPPER}" -lt 32 ]; then
  echo "LIFE_KEY_PEPPER missing or < 32 chars: add LIFE_KEY_PEPPER=\$(openssl rand -base64 48) to $ROOT/.env (docs/auth.md)." >&2
  exit 1
fi
unset PEPPER
# The admin key (lifeOS Admin bot): key id + SHA-256 of its secret (env or .env). The app refuses to
# start without them (docs/auth.md#admin-key).
ADMIN_ID="${LIFE_ADMIN_KEY_ID:-$(envval LIFE_ADMIN_KEY_ID)}"
ADMIN_SHA="${LIFE_ADMIN_KEY_SHA256:-$(envval LIFE_ADMIN_KEY_SHA256)}"
if ! printf '%s' "$ADMIN_ID" | grep -Eqx '[a-z2-7]{12}' || ! printf '%s' "$ADMIN_SHA" | grep -Eqx '[0-9a-f]{64}'; then
  echo "LIFE_ADMIN_KEY_ID / LIFE_ADMIN_KEY_SHA256 missing or malformed: add both to $ROOT/.env (the lifeOS Admin bot generates them; docs/auth.md#admin-key)." >&2
  exit 1
fi
unset ADMIN_ID ADMIN_SHA
# Optional HTTPS proxy (Caddy) when LIFE_DOMAIN is set (env or .env).
LIFE_DOMAIN="${LIFE_DOMAIN:-$(envval LIFE_DOMAIN)}"
PROFILES=()
if [ -n "$LIFE_DOMAIN" ]; then
  [ -f docker/Caddyfile ] || { echo "docker/Caddyfile missing next to compose.yaml" >&2; exit 1; }
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
test "$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:${LIFE_API_PORT:-8787}/api/ops/summary)" = 401 \
  || { echo "expected 401 from /api without credentials" >&2; exit 1; }
echo "deployed $IMAGE"
