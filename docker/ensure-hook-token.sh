#!/usr/bin/env bash
# Back-compat wrapper. All `openclaw config set` lives in openclaw-config.sh.
set -euo pipefail
HERE="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
exec "$HERE/openclaw-config.sh" "$@"
