#!/usr/bin/env bash
# Copy a Life OS SQLite file (e.g. ~/lifeos/data/life.db) into a Turso / libSQL database.
# Manual, one-off. Reusable for any target: nothing is hard-coded.
#
#   export TURSO_DATABASE_URL='jdbc:libsql:https://<db>-<org>.turso.io'   # target
#   export TURSO_AUTH_TOKEN='...'                                          # turso db tokens create <db>
#   ./migrate-sqlite-to-turso.sh ~/lifeos/data/life.db --dry-run           # verify, then roll back
#   ./migrate-sqlite-to-turso.sh ~/lifeos/data/life.db                     # copy for real
#
# Options (passed through): --dry-run, --wipe-target (replace rows already in the target, e.g. the
# seed rows the app writes when it first starts against an empty database), --target-url URL.
#
# Runs the migrator from the app image, so the host needs only Docker. Stop the API first
# (docker compose -f compose.yaml stop api) so nothing writes to either database meanwhile.
# The copy is ONE transaction: it either fully commits or leaves the target unchanged.
set -euo pipefail

if [ $# -lt 1 ] || [ "${1#-}" != "$1" ]; then
  sed -n '2,17p' "$0"
  exit 64
fi
SRC="$1"; shift
[ -f "$SRC" ] || { echo "source not found: $SRC" >&2; exit 66; }
: "${TURSO_DATABASE_URL:?set TURSO_DATABASE_URL to the target (jdbc:libsql:https://<db>-<org>.turso.io)}"
case "$TURSO_DATABASE_URL" in
  *https://*|libsql://*) : "${TURSO_AUTH_TOKEN:?set TURSO_AUTH_TOKEN for the target database}" ;;
esac
IMAGE="${LIFE_IMAGE:-ghcr.io/zoomzoomtnt/lifeos-ai:latest}"

# Work on a copy (plus any -wal/-shm) so the source is never modified and WAL content is included.
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
cp -a "$SRC" "$WORK/life.db"
for ext in -wal -shm; do [ -f "${SRC}${ext}" ] && cp -a "${SRC}${ext}" "$WORK/life.db${ext}"; done

docker run --rm \
  -e TURSO_DATABASE_URL -e TURSO_AUTH_TOKEN \
  -v "$WORK:/src" \
  --entrypoint java "$IMAGE" \
  -cp /app/app.jar -Dloader.main=com.lifeos.tools.SqliteToLibsql \
  org.springframework.boot.loader.launch.PropertiesLauncher /src/life.db "$@"
