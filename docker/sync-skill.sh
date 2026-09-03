#!/bin/sh
# Replace the skill baked into this image onto the host OpenClaw workspace.
# Delete then copy so the skills watcher sees unlink + new SKILL.md (no gateway restart).
set -eu
SRC="${LIFE_SKILL_SRC:-/opt/life-os-skill}"
ROOT="${OPENCLAW_SYNC_ROOT:-/openclaw}"
DEST="$ROOT/workspace/skills/life-os"

if [ ! -d "$SRC" ]; then
  echo "missing $SRC" >&2
  exit 1
fi

mkdir -p "$ROOT/workspace/skills"
if [ -e "$DEST" ]; then
  rm -rf "$DEST"
fi
mkdir -p "$DEST"
cp -a "$SRC"/. "$DEST/"
if [ -f "$SRC/HEARTBEAT.md" ]; then
  cp "$SRC/HEARTBEAT.md" "$ROOT/workspace/HEARTBEAT.md"
fi

echo "replaced $DEST from $SRC"
test -f "$DEST/SKILL.md"
grep -q '^name: life-os' "$DEST/SKILL.md"
