#!/usr/bin/env bash
# Copy a Life OS SQLite file (e.g. ~/lifeos/data/life.db) into a Turso / libSQL database.
# Manual, one-off, BEFORE cutover. Reusable for any target: nothing is hard-coded.
#
#   export LIFE_IMAGE=ghcr.io/zoomzoomtnt/lifeos-ai:sha-<commit>   # pinned image that has the tool
#   export TURSO_DATABASE_URL='jdbc:libsql:https://<db>-<org>.turso.io'   # target (new, empty)
#   export TURSO_AUTH_TOKEN='...'                                          # turso db tokens create <db>
#   ./migrate-sqlite-to-turso.sh ~/lifeos/data/life.db --dry-run           # verify, then roll back
#   ./migrate-sqlite-to-turso.sh ~/lifeos/data/life.db                     # copy for real
#
# Order: stop the old API -> migrate into the new empty database -> deploy the Turso release.
# Options (passed through): --dry-run, --target-url URL, --wipe-target [--confirm-target-counts t=N,...]
# (wiping is refused if the target holds more than the app's seed rows unless counts are confirmed).
# The copy is ONE transaction: it either fully commits or leaves the target unchanged. The source
# is read in one read transaction (consistent even if something still writes it) and not modified.
set -euo pipefail

if [ $# -lt 1 ] || [ "${1#-}" != "$1" ]; then
  sed -n '2,16p' "$0"
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

SRC_DIR="$(cd "$(dirname "$SRC")" && pwd)"
docker run --rm \
  -e TURSO_DATABASE_URL -e TURSO_AUTH_TOKEN \
  -v "$SRC_DIR:/src" \
  --entrypoint java "$IMAGE" \
  -cp /app/app.jar -Dloader.main=com.lifeos.tools.SqliteToLibsql \
  org.springframework.boot.loader.launch.PropertiesLauncher "/src/$(basename "$SRC")" "$@"
