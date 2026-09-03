#!/usr/bin/env bash
# Ensure OPENCLAW_HOOK_TOKEN exists on the host and matches Gateway hooks.token.
# Opaque shared secret: no TTL, no JWT, no DB. Mint only when .env has none.
# Writes config via `openclaw config set` (does not edit openclaw.json, no gateway restart).
#
# Prints MINTED=0|1 on stdout for the caller (compose recreate when first mint).
set -euo pipefail

DEPLOY_DIR="${DEPLOY_DIR:-$HOME/lifeos}"
ENVF="$DEPLOY_DIR/.env"
export PATH="$HOME/.local/bin:/usr/local/bin:$PATH"

umask 077
mkdir -p "$DEPLOY_DIR"

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
  local line
  line="$(grep -E '^OPENCLAW_HOOK_TOKEN=' "$ENVF" | tail -n1 || true)"
  [[ -n "$line" ]] || return 0
  line="${line#OPENCLAW_HOOK_TOKEN=}"
  line="${line%$'\r'}"
  if [[ "$line" == \"*\" ]]; then
    line="${line#\"}"
    line="${line%\"}"
  fi
  printf '%s\n' "$line"
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

TOKEN="$(read_env_token || true)"
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
  echo "openclaw not on PATH; wrote $ENVF only. Run: openclaw config set hooks.token <token>" >&2
  echo "MINTED=$MINTED"
  exit 0
fi

openclaw config set hooks.enabled true
openclaw config set hooks.token "$TOKEN"
echo "aligned Gateway hooks.token via config set (no json edit, no restart)" >&2
echo "MINTED=$MINTED"
