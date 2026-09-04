#!/usr/bin/env bash
# Life OS custom webhook on the Gateway.
# Docs title = Webhooks. Config keys = hooks.*. Route = POST {hooks.path}/life-os
# via hooks.mappings — not /hooks/agent, not plugins.entries.webhooks (TaskFlow).
# Usage: openclaw-config.sh <hooks-token>
set -euo pipefail

TOKEN="${1:?usage: openclaw-config.sh <hooks-token>}"
export PATH="$HOME/.local/bin:/usr/local/bin:$PATH"

HOOKS_PATH="${OPENCLAW_HOOKS_PATH:-/hooks}"
HOOK_NAME="${OPENCLAW_HOOK_NAME:-life-os}"
CHANNEL="${LIFE_WEIXIN_CHANNEL:-openclaw-weixin}"

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

oc_set() {
  local key="$1"
  shift
  if [[ "$key" == "hooks.token" ]]; then
    echo "+ openclaw config set hooks.token <redacted>"
  else
    echo "+ openclaw config set $key $*"
  fi
  openclaw config set "$key" "$@"
}

MAPPING="$(HOOK_NAME="$HOOK_NAME" CHANNEL="$CHANNEL" python3 - <<'PY'
import json, os
print(json.dumps([{
    "id": "life-os",
    "match": {"path": os.environ["HOOK_NAME"]},
    "action": "agent",
    "name": "life-os-proactive",
    "sessionMode": "isolated",
    "deliver": True,
    "channel": os.environ["CHANNEL"],
    "messageTemplate": "{{message}}",
    "to": "{{to}}",
}], separators=(",", ":")))
PY
)"

oc_set hooks.enabled true
oc_set hooks.path "$HOOKS_PATH"
oc_set hooks.token "$TOKEN"
oc_set hooks.mappings "$MAPPING" --strict-json
oc_set skills.load.watch true

echo "hooks.enabled=$(openclaw config get hooks.enabled)"
echo "hooks.path=$(openclaw config get hooks.path)"
echo "hooks.mappings=$(openclaw config get hooks.mappings)"
echo "skills.load.watch=$(openclaw config get skills.load.watch)"
echo "life-os webhook URL path=${HOOKS_PATH}/${HOOK_NAME}"
