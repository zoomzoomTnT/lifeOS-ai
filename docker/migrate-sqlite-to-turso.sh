#!/usr/bin/env bash
# OPTIONAL tool: copy a Life OS SQLite file into a NEW, EMPTY Turso / libSQL database.
# Not part of any planned cutover (prod already runs on Turso; data/life.db is not imported).
# NEVER point it at the live database and never use --confirm-target-counts there (it deletes rows).
#
#   export LIFE_IMAGE=ghcr.io/zoomzoomtnt/lifeos-ai:sha-<commit>   # pinned image that has the tool
#   export TURSO_DATABASE_URL='jdbc:libsql:https://<new-db>-<org>.turso.io'  # NEW, EMPTY target
#   export TURSO_AUTH_TOKEN='...'                                          # turso db tokens create <db>
#   ./migrate-sqlite-to-turso.sh ~/lifeos/data/life.db --dry-run           # verify, then roll back
#   ./migrate-sqlite-to-turso.sh ~/lifeos/data/life.db                     # copy for real
#
# Options (passed through): --dry-run, --target-url URL, --wipe-target [--confirm-target-counts t=N,...]
# (wiping is refused if the target holds more than the app's seed rows unless counts are confirmed).
# The copy is ONE transaction: it either fully commits or leaves the target unchanged. The source
# file is copied to a temp dir first (needs the old API stopped); the original is never mounted.
set -euo pipefail

if [ $# -lt 1 ] || [ "${1#-}" != "$1" ]; then
  sed -n '2,15p' "$0"
  exit 64
fi
SRC="$1"; shift
[ -f "$SRC" ] || { echo "source not found: $SRC" >&2; exit 66; }
: "${TURSO_DATABASE_URL:?set TURSO_DATABASE_URL to the target (jdbc:libsql:https://<db>-<org>.turso.io)}"
case "$TURSO_DATABASE_URL" in
  *authToken=*) : ;;                                   # token embedded in the URL
  *https://*|*libsql://*) : "${TURSO_AUTH_TOKEN:?set TURSO_AUTH_TOKEN for the target database}" ;;
esac
# Pin the image: :latest may be an older build without the migrator, or a newer one.
IMAGE="${LIFE_IMAGE:-}"
case "$IMAGE" in
  ""|*:latest|*/lifeos-ai)
    echo "set LIFE_IMAGE to a pinned image tag, e.g. ghcr.io/zoomzoomtnt/lifeos-ai:sha-<commit> or :<version>" >&2
    exit 64 ;;
esac

# The API must be stopped so the source file is final.
if command -v docker >/dev/null 2>&1 && [ -n "$(docker ps -q --filter name=^lifeos-api$ 2>/dev/null)" ]; then
  echo "lifeos-api is running: stop it first (docker compose -f compose.yaml stop api), then migrate" >&2
  exit 75
fi

# Work on a private copy; the original file is never mounted into the container.
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
NAME="$(basename "$SRC")"
for ext in "" -wal; do
  if [ -f "$SRC$ext" ]; then
    cp "$SRC$ext" "$WORK/$NAME$ext" || { echo "cannot read $SRC$ext (root-owned? re-run with sudo -E)" >&2; exit 77; }
  fi
done
chmod -R a+rwX "$WORK"

docker run --rm \
  -e TURSO_DATABASE_URL -e TURSO_AUTH_TOKEN \
  -v "$WORK:/src" \
  --entrypoint java "$IMAGE" \
  -cp /app/app.jar -Dloader.main=com.lifeos.tools.SqliteToLibsql \
  org.springframework.boot.loader.launch.PropertiesLauncher "/src/$NAME" "$@"
