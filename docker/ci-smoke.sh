#!/usr/bin/env bash
# CI compose smoke test. Brings the stack up with whatever COMPOSE_FILE / TURSO_* the caller set,
# exercises the API over HTTP (incl. a multi-write receipt preview + confirm), then tears it down.
#   SMOKE_EXPECT_DB  optional substring that /actuator/db must contain (which database is in use)
#   SMOKE_BASE       default http://localhost:8787
set -euo pipefail
BASE="${SMOKE_BASE:-http://localhost:8787}"
export LIFE_OPENCLAW_WAKE=false
export OPENCLAW_HOME="${OPENCLAW_HOME:-$HOME/.openclaw}"
mkdir -p "$OPENCLAW_HOME/agents/main/sessions"
printf '%s\n' \
  '{"type":"session","timestamp":"2026-08-26T00:00:00Z","id":"ci-sess"}' \
  '{"type":"message","timestamp":"2026-08-26T00:00:01Z","message":{"role":"user","content":[{"type":"text","text":"秘密对话"}]}}' \
  > "$OPENCLAW_HOME/agents/main/sessions/ci.jsonl"

cleanup() { docker compose logs api | tail -n 80 || true; docker compose down || true; }
trap cleanup EXIT

docker compose up -d --wait --wait-timeout 90
curl -fsS "$BASE/actuator/health" | grep -q '"status":"UP"'
curl -fsS "$BASE/actuator/health" | grep -q '"life"'
DB=$(curl -fsS "$BASE/actuator/db")
echo "$DB"
echo "$DB" | grep -q ownerTimezone
echo "$DB" | grep -q '"ping":"ok"'
if [ -n "${SMOKE_EXPECT_DB:-}" ]; then echo "$DB" | grep -qF "$SMOKE_EXPECT_DB"; fi
curl -fsS "$BASE/actuator/info" | grep -q life-os-app
curl -fsS "$BASE/v3/api-docs/api" | grep -q '"openapi"'
curl -fsS "$BASE/swagger-ui/index.html" | grep -qi swagger
curl -fsS "$BASE/ops/index.html" | grep -q "Life OS ops"
AI=$(curl -fsS -X POST "$BASE/api/ops/ai" \
  -H 'Content-Type: application/json' \
  -H 'X-Life-Handle: owner' \
  -H 'X-Request-Id: ci-smoke-1' \
  -d '{"provider":"xai","model":"grok-4","purpose":"heartbeat","source":"app","prompt_tokens":1000,"completion_tokens":0}')
echo "$AI"
echo "$AI" | grep -q '0.003000'
echo "$AI" | grep -q '"id":[0-9]'
SW=$(curl -fsS "$BASE/api/ops/should-wake?lead_minutes=10")
echo "$SW"
echo "$SW" | grep -qi 'heartbeat_ok'
RUN=$(curl -fsS -X POST "$BASE/api/ops/proactive/run" -H 'Content-Type: application/json' -d '{}')
echo "$RUN"
echo "$RUN" | grep -qi 'skipped\|wake'
curl -fsS -X POST "$BASE/api/receipts/lookup" \
  -H 'Content-Type: application/json' \
  -H 'X-Request-Id: ci-smoke-1' \
  -d '{"barcode":"nope","printed_at":"x"}' >/dev/null
# multi-write transaction: merchant + receipt + items + event, then status + fridge rows + event
PRE=$(curl -fsS -X POST "$BASE/api/receipts/preview" \
  -H 'Content-Type: application/json' -H 'X-Life-Handle: owner' \
  -d "{\"merchant_name\":\"CI Mart\",\"barcode\":\"ci-$(date +%s%N)\",\"printed_at\":\"2026-10-10 12:00\",\"currency\":\"JPY\",\"total_cents\":300,\"items\":[{\"name\":\"milk\",\"qty\":1,\"amount_cents\":200,\"is_food\":true,\"category\":\"dairy\"},{\"name\":\"soap\",\"qty\":1,\"amount_cents\":100,\"is_food\":false}]}")
echo "$PRE"
echo "$PRE" | grep -q '"action":"create_pending"'
RID=$(echo "$PRE" | sed -n 's/.*"receipt_id":\([0-9]*\).*/\1/p')
test -n "$RID"
CONF=$(curl -fsS -X POST "$BASE/api/receipts/$RID/confirm" \
  -H 'Content-Type: application/json' -H 'X-Life-Handle: owner' -d '{"also_fridge":true}')
echo "$CONF"
echo "$CONF" | grep -q '"fridge_item_ids":\[[0-9]'
curl -fsS "$BASE/api/ops/http?limit=10" | grep -q '/api/receipts/lookup'
ING=$(curl -fsS -X POST "$BASE/api/ops/logs/ingest")
echo "$ING"
echo "$ING" | grep -q 'session_rows'
META=$(curl -fsS "$BASE/api/ops/logs/sessions?limit=5")
echo "$META"
echo "$META" | grep -q 'openclaw_session'
echo "$META" | grep -qv '秘密对话'
if [ "${SMOKE_SKILL_SYNC:-1}" = "1" ]; then
  docker compose --profile sync run --rm skill-sync
  grep -q 'name: life-os' "$OPENCLAW_HOME/workspace/skills/life-os/SKILL.md"
  test -f "$OPENCLAW_HOME/workspace/HEARTBEAT.md"
fi
echo "smoke OK"
