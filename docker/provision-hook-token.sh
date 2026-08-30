#!/usr/bin/env bash
# Ensure or rotate OPENCLAW_HOOK_TOKEN on the OpenClaw host.
# The Gateway HTTP hook token has no TTL; OpenClaw treats it as a static shared secret.
#
#   ./provision-hook-token.sh ensure   # default: create only if missing
#   ./provision-hook-token.sh rotate   # mint a new value, write both sides, restart gateway
#
# Writes:
#   $DEPLOY_DIR/.env                 OPENCLAW_HOOK_TOKEN=...
#   $OPENCLAW_HOME/openclaw.json     hooks.enabled/token/path (other keys untouched)
#   $DEPLOY_DIR/secrets/hook-token.prev  previous value after rotate (mode 600)
set -euo pipefail

MODE="${1:-ensure}"
DEPLOY_DIR="${DEPLOY_DIR:-$HOME/lifeos}"
OPENCLAW_HOME="${OPENCLAW_HOME:-$HOME/.openclaw}"
CFG="$OPENCLAW_HOME/openclaw.json"
ENVF="$DEPLOY_DIR/.env"
SECRET_DIR="$DEPLOY_DIR/secrets"
PREV="$SECRET_DIR/hook-token.prev"

umask 077
mkdir -p "$DEPLOY_DIR" "$SECRET_DIR" "$OPENCLAW_HOME"

gen_token() {
  if command -v python3 >/dev/null; then
    python3 -c 'import secrets; print(secrets.token_urlsafe(32))'
  else
    openssl rand -base64 32 | tr -d '=+/\n' | head -c 43
    echo
  fi
}

read_env_token() {
  [[ -f "$ENVF" ]] || return 0
  awk -F= '
    $1 == "OPENCLAW_HOOK_TOKEN" {
      v=$2
      for (i=3; i<=NF; i++) v=v "=" $i
      gsub(/\r$/, "", v)
      gsub(/^"|"$/, "", v)
      gsub(/^['\''']|['\''']$/, "", v)
      print v
      exit
    }
  ' "$ENVF"
}

read_cfg_token() {
  [[ -f "$CFG" ]] || return 0
  python3 - "$CFG" <<'PY'
import json, sys
p = sys.argv[1]
try:
    with open(p, encoding="utf-8") as f:
        cfg = json.load(f)
except Exception:
    sys.exit(0)
token = (cfg.get("hooks") or {}).get("token") or ""
token = str(token).strip()
if token.startswith("${") and token.endswith("}"):
    sys.exit(0)
if token:
    print(token)
PY
}

upsert_env() {
  local key="$1" val="$2" tmp
  tmp="$(mktemp)"
  if [[ -f "$ENVF" ]]; then
    awk -F= -v k="$key" -v val="$val" '
      BEGIN { done=0 }
      $1 == k { print k "=" val; done=1; next }
      { print }
      END { if (!done) print k "=" val }
    ' "$ENVF" > "$tmp"
  else
    cat > "$tmp" <<EOF
LIFE_API_PORT=8787
LIFE_DATA=./data
OPENCLAW_GATEWAY=http://host.docker.internal:18789
LIFE_OPENCLAW_WAKE=true
LIFE_WEIXIN_CHANNEL=openclaw-weixin
${key}=${val}
EOF
  fi
  mv "$tmp" "$ENVF"
  chmod 600 "$ENVF"
}

write_cfg_token() {
  local val="$1"
  python3 - "$CFG" "$val" <<'PY'
import json, os, sys
path, token = sys.argv[1], sys.argv[2]
cfg = {}
if os.path.exists(path):
    with open(path, encoding="utf-8") as f:
        try:
            cfg = json.load(f)
        except json.JSONDecodeError as e:
            print(f"openclaw.json is not strict JSON ({e}); leave hooks.token to the operator", file=sys.stderr)
            sys.exit(2)
hooks = cfg.get("hooks") or {}
hooks["enabled"] = True
hooks["path"] = hooks.get("path") or "/hooks"
hooks["token"] = token
cfg["hooks"] = hooks
tmp = path + ".tmp"
with open(tmp, "w", encoding="utf-8") as f:
    json.dump(cfg, f, indent=2)
    f.write("\n")
os.replace(tmp, path)
PY
}

restart_gateway() {
  if command -v openclaw >/dev/null; then
    openclaw gateway restart || openclaw gateway start || true
  fi
}

ENV_TOKEN="$(read_env_token || true)"
CFG_TOKEN="$(read_cfg_token || true)"
CHANGED=0

case "$MODE" in
  ensure)
    TOKEN="${ENV_TOKEN:-$CFG_TOKEN}"
    if [[ -z "$TOKEN" ]]; then
      TOKEN="$(gen_token)"
      echo "minted OPENCLAW_HOOK_TOKEN (first run; no expiry — rotate explicitly)"
      CHANGED=1
    else
      echo "reusing existing OPENCLAW_HOOK_TOKEN (suffix ${TOKEN: -4})"
    fi
    ;;
  rotate)
    if [[ -n "$ENV_TOKEN" ]]; then
      printf '%s\n' "$ENV_TOKEN" > "$PREV"
      chmod 600 "$PREV"
    elif [[ -n "$CFG_TOKEN" ]]; then
      printf '%s\n' "$CFG_TOKEN" > "$PREV"
      chmod 600 "$PREV"
    fi
    TOKEN="$(gen_token)"
    echo "rotated OPENCLAW_HOOK_TOKEN (previous saved at $PREV if any)"
    CHANGED=1
    ;;
  *)
    echo "usage: $0 [ensure|rotate]" >&2
    exit 2
    ;;
esac

upsert_env OPENCLAW_HOOK_TOKEN "$TOKEN"

if write_cfg_token "$TOKEN"; then
  :
else
  rc=$?
  if [[ "$rc" -eq 2 ]]; then
    echo "wrote .env only; set hooks.token in $CFG by hand so it matches" >&2
  else
    exit "$rc"
  fi
fi

if [[ "$CHANGED" -eq 1 ]]; then
  restart_gateway
fi

echo "hook token provisioned mode=$MODE changed=$CHANGED"
