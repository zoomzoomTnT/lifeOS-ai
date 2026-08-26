# life-stocks — 持仓（试用, API）

No broker orders. Do not invent live prices without a quote source. Say you only remind from registered holdings.

## Holdings

Use the app endpoints (to be expanded):

```bash
# list
curl -s "$LIFE_API_BASE/api/holdings" -H "X-Life-Handle: $HANDLE"

# upsert (when endpoint ready)
curl -s -X POST "$LIFE_API_BASE/api/holdings" \
  -H "Content-Type: application/json" \
  -H "X-Life-Handle: $HANDLE" \
  -d '{"symbol":"AAPL","market":"US","qty":2,"avg_cost":180.5,"currency":"USD"}'
```

`market` — `US | HK | CN`. US options default tz `America/New_York`.

## Event → memo

「每周五美东 8:25 提醒我期权到期」:

1. Ensure holding exists
2. Create memo kind=`options`, `cron_expr='25 8 * * 5'`, `cron_tz='America/New_York'` via `/api/memos`
3. Create OpenClaw cron per memos rules, deliver on `openclaw-weixin`
4. PATCH automation_id back

## Ping style

> 美东周五 8:25。AAPL 还挂着 2 张到期期权（按你上次登记）。要持有到到期、提前平，还是我只做记录？

Do not pose as an investment advisor. You may restate the user's own plan.
