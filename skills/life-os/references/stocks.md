# life-stocks — 持仓（试用, API）

No broker orders. Do not invent live prices without a quote source. Say you only remind from registered holdings.

## Holdings

```bash
# list
curl -s "$LIFE_API_BASE/api/holdings" -H "X-Life-Handle: $HANDLE"

# get one (includes events)
curl -s "$LIFE_API_BASE/api/holdings/7" -H "X-Life-Handle: $HANDLE"

# upsert by (owner, symbol, market)
curl -s -X POST "$LIFE_API_BASE/api/holdings" \
  -H "Content-Type: application/json" \
  -H "X-Life-Handle: $HANDLE" \
  -d '{"symbol":"AAPL","market":"US","qty":2,"avg_cost":180.5,"currency":"USD"}'

# attach an event (optional memo_id from /api/memos)
curl -s -X POST "$LIFE_API_BASE/api/holdings/7/events" \
  -H "Content-Type: application/json" \
  -H "X-Life-Handle: $HANDLE" \
  -d '{"kind":"options_expiry","event_date":"2026-09-18","notes":"weekly","memo_id":12}'
```

`market` — `US | HK | CN`. Missing `currency` defaults to USD / HKD / CNY. Symbol is stored uppercase. US options default tz `America/New_York`.

`kind` — `options_expiry | earnings | dividend | custom`.

## Event → memo

「每周五美东 8:25 提醒我期权到期」:

1. `POST /api/holdings` so the row exists
2. Create memo kind=`options`, `cron_expr='25 8 * * 5'`, `cron_tz='America/New_York'`, `source_domain=stocks`, `source_table=holdings`, `source_id=<holding id>` via `/api/memos`
3. `POST /api/holdings/{id}/events` with that `memo_id`
4. Create OpenClaw cron per memos rules, deliver on `openclaw-weixin`
5. PATCH automation_id back

## Ping style

> 美东周五 8:25。AAPL 还挂着 2 张到期期权（按你上次登记）。要持有到到期、提前平，还是我只做记录？

Do not pose as an investment advisor. You may restate the user's own plan.
