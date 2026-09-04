#!/usr/bin/env bash
# Life OS custom webhook on the Gateway.
# Docs title = Webhooks. Config keys = hooks.*. Route = POST {hooks.path}/life-os
# via hooks.mappings — not /hooks/agent, not plugins.entries.webhooks (TaskFlow).
# Usage: openclaw-config.sh <hooks-token>
#
# Life OS .env OPENCLAW_HOME is the OpenClaw STATE dir (compose volume).
# OpenClaw CLI treats OPENCLAW_HOME as Unix $HOME and would write
#   $OPENCLAW_HOME/.openclaw/openclaw.json
# Bind STATE_DIR + CONFIG_PATH and unset OPENCLAW_HOME before any config set.
set -euo pipefail

TOKEN="${1:?usage: openclaw-config.sh <hooks-token>}"
export PATH="$HOME/.local/bin:/usr/local/bin:$PATH"

HOOKS_PATH="${OPENCLAW_HOOKS_PATH:-/hooks}"
HOOK_NAME="${OPENCLAW_HOOK_NAME:-life-os}"
CHANNEL="${LIFE_WEIXIN_CHANNEL:-openclaw-weixin}"

bind_openclaw_cli() {
  local state="${OPENCLAW_STATE_DIR:-${OPENCLAW_HOME:-}}"
  if [[ -z "$state" ]]; then
    echo "set OPENCLAW_STATE_DIR (or Life OS OPENCLAW_HOME state dir) before config set" >&2
    exit 1
  fi
  if [[ ! -d "$state" ]]; then
    echo "OpenClaw state dir does not exist: $state" >&2
    exit 1
  fi
  export OPENCLAW_STATE_DIR="$state"
  export OPENCLAW_CONFIG_PATH="${OPENCLAW_CONFIG_PATH:-$state/openclaw.json}"
  unset OPENCLAW_HOME
  local resolved
  resolved="$(openclaw config file)"
  echo "openclaw config file=$resolved"
  case "$resolved" in
    */.openclaw/.openclaw/*|*/.openclaw/.openclaw)
      echo "refusing nested OpenClaw config path: $resolved" >&2
      echo "do not export the state dir as OPENCLAW_HOME" >&2
      exit 1
      ;;
  esac
  if [[ ! -f "$OPENCLAW_CONFIG_PATH" ]]; then
    echo "missing $OPENCLAW_CONFIG_PATH" >&2
    exit 1
  fi
}

bind_openclaw_cli

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
echo "wrote $(openclaw config file)"
