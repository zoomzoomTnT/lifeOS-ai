#!/usr/bin/env bash
# All Life OS `openclaw config set` calls. Add new keys here.
# Usage: openclaw-config.sh <hooks-token>
set -euo pipefail
TOKEN="${1:?usage: openclaw-config.sh <hooks-token>}"
export PATH="$HOME/.local/bin:/usr/local/bin:$PATH"

openclaw config set hooks.enabled true
openclaw config set hooks.path "/hooks"
openclaw config set hooks.token "$TOKEN"
openclaw config set skills.load.watch true
