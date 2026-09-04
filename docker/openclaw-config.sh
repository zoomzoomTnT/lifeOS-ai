#!/usr/bin/env bash
# OpenClaw *webhook* ingress for Life OS.
#
# Docs call the feature "Webhooks". The config namespace is still hooks.*
# There is no webhooks.enabled / webhooks.path — those keys are invalid.
#
# HTTP routes live under hooks.path (must be a dedicated subpath, not "/"):
#   POST {path}/agent  ← life-os proactive (isolated turn + WeChat deliver)
#   POST {path}/wake   ← main-session nudge; unused (heartbeat.every = 0m)
#   POST {path}/<name> ← hooks.mappings only
#
# `openclaw hooks` is a different subsystem (internal HOOK.md handlers).
# `openclaw webhooks` is Gmail/Pub-Sub helpers that still write hooks.*.
#
# Usage: openclaw-config.sh <hooks-token>
set -euo pipefail
TOKEN="${1:?usage: openclaw-config.sh <hooks-token>}"
export PATH="$HOME/.local/bin:/usr/local/bin:$PATH"

HOOKS_PATH="${OPENCLAW_HOOKS_PATH:-/hooks}"
case "$HOOKS_PATH" in
  ""|"/")
    echo "OPENCLAW_HOOKS_PATH cannot be empty or /" >&2
    exit 1
    ;;
esac
case "$HOOKS_PATH" in
  /*) ;;
  *) HOOKS_PATH="/$HOOKS_PATH" ;;
esac
HOOKS_PATH="${HOOKS_PATH%/}"

openclaw config set hooks.enabled true
openclaw config set hooks.path "$HOOKS_PATH"
openclaw config set hooks.token "$TOKEN"
openclaw config set hooks.defaultSessionKey "hook:life-os"
openclaw config set hooks.allowRequestSessionKey false --strict-json
openclaw config set skills.load.watch true

# Confirm the prefix the API will POST to. Never print hooks.token.
echo "hooks.path=$(openclaw config get hooks.path)"
echo "hooks.enabled=$(openclaw config get hooks.enabled)"
echo "hooks.defaultSessionKey=$(openclaw config get hooks.defaultSessionKey)"
echo "hooks.allowRequestSessionKey=$(openclaw config get hooks.allowRequestSessionKey)"
