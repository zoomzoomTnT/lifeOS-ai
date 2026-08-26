#!/bin/sh
# Copy the skill baked into this image onto the host OpenClaw workspace.
set -eu
SRC="${LIFE_SKILL_SRC:-/opt/life-os-skill}"
ROOT="${OPENCLAW_SYNC_ROOT:-/openclaw}"
DEST="$ROOT/workspace/skills/life-os"

if [ ! -d "$SRC" ]; then
  echo "missing $SRC" >&2
  exit 1
fi

mkdir -p "$DEST" "$ROOT/workspace"
cp -a "$SRC"/. "$DEST/"
if [ -f "$SRC/HEARTBEAT.md" ]; then
  cp "$SRC/HEARTBEAT.md" "$ROOT/workspace/HEARTBEAT.md"
fi

echo "synced $SRC -> $DEST"
test -f "$DEST/SKILL.md"
grep -q '^name: life-os' "$DEST/SKILL.md"
