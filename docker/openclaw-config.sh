#!/usr/bin/env bash
# ALL `openclaw config set` for Life OS CD lives here.
# Add new keys in apply_openclaw_config below. Do not add config set to cd.yml.
# Also mints OPENCLAW_HOOK_TOKEN in ~/lifeos/.env when missing.
# Requires OPENCLAW_HOME in .env to be an existing absolute directory.
# Prints MINTED=0|1 and OPENCLAW_HOME=... on stdout.
set -euo pipefail

DEPLOY_DIR="${DEPLOY_DIR:-$HOME/lifeos}"
ENVF="$DEPLOY_DIR/.env"
export PATH="$HOME/.local/bin:/usr/local/bin:$PATH"

umask 077
mkdir -p "$DEPLOY_DIR"

if [[ ! -f "$ENVF" ]]; then
  echo "missing $ENVF" >&2
  exit 1
fi

gen_token() {
  if command -v python3 >/dev/null; then
    python3 -c 'import secrets; print(secrets.token_urlsafe(32))'
  else
    openssl rand -base64 32 | tr -d '=+/\n' | head -c 43
    echo
  fi
}

read_env_key() {
  local key="$1"
  python3 - "$ENVF" "$key" <<'PY'
import sys
path, key = sys.argv[1], sys.argv[2]
val = ""
prefix = key + "="
for raw in open(path, encoding="utf-8").read().splitlines():
    line = raw.strip()
    if not line or line.startswith("#") or not line.startswith(prefix):
        continue
    val = line.split("=", 1)[1].strip().strip('"').strip("'")
print(val)
PY
}

upsert_env() {
  local key="$1" val="$2"
  python3 - "$ENVF" "$key" "$val" <<'PY'
import os, sys
path, key, val = sys.argv[1], sys.argv[2], sys.argv[3]
lines = open(path, encoding="utf-8").read().splitlines() if os.path.exists(path) else []
found = False
out = []
prefix = key + "="
for line in lines:
    if line.startswith(prefix) or line.startswith(key + " ="):
        out.append(f"{key}={val}")
        found = True
    else:
        out.append(line)
if not found:
    out.append(f"{key}={val}")
tmp = path + ".tmp"
with open(tmp, "w", encoding="utf-8") as f:
    f.write("\n".join(out) + "\n")
os.replace(tmp, path)
os.chmod(path, 0o600)
PY
}

# Add every Life OS `openclaw config set` here.
apply_openclaw_config() {
  local token="$1"
  openclaw config set hooks.enabled true
  openclaw config set hooks.path "/hooks"
  openclaw config set hooks.token "$token"
  openclaw config set skills.load.watch true
}

HOME_PATH="$(read_env_key OPENCLAW_HOME || true)"
if [[ -z "$HOME_PATH" ]]; then
  echo "OPENCLAW_HOME is empty in $ENVF — set an absolute existing directory" >&2
  exit 1
fi
if [[ ! -d "$HOME_PATH" ]]; then
  echo "OPENCLAW_HOME=$HOME_PATH does not exist on the host (CD will not create it)" >&2
  exit 1
fi
export OPENCLAW_HOME="$HOME_PATH"
echo "OPENCLAW_HOME=$OPENCLAW_HOME" >&2

TOKEN="$(read_env_key OPENCLAW_HOOK_TOKEN || true)"
MINTED=0
if [[ -z "$TOKEN" ]]; then
  TOKEN="$(gen_token)"
  MINTED=1
  echo "minted OPENCLAW_HOOK_TOKEN (first run; opaque, no expiry)" >&2
else
  echo "reusing OPENCLAW_HOOK_TOKEN from $ENVF" >&2
fi

upsert_env OPENCLAW_HOOK_TOKEN "$TOKEN"

if ! command -v openclaw >/dev/null; then
  echo "openclaw not on PATH; wrote $ENVF only" >&2
  echo "MINTED=$MINTED"
  echo "OPENCLAW_HOME=$OPENCLAW_HOME"
  exit 0
fi

apply_openclaw_config "$TOKEN"
echo "set hooks.enabled=true hooks.path=/hooks hooks.token=<redacted> skills.load.watch=true" >&2
openclaw config get hooks.path >&2 || true
echo "MINTED=$MINTED"
echo "OPENCLAW_HOME=$OPENCLAW_HOME"
