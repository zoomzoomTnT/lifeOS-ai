# life-fridge — 冰箱 (API)

Expiry pings go through `memos.md`.

## Sources

1. Confirmed grocery lines with `is_food=1` after user agrees (`also_fridge: true` on confirm)
2. Manual — 冰水、冰茶、剩菜 → `POST /api/fridge`
3. Other people — server records `added_by_id` / `owner_id`

## Manual add

```bash
curl -s -X POST "$LIFE_API_BASE/api/fridge" \
  -H "Content-Type: application/json" \
  -H "X-Life-Handle: $HANDLE" \
  -d '{"name":"冰茶","category":"drink","location":"fridge","expires_in_days":3}'
```

## List expiring

```bash
curl -s "$LIFE_API_BASE/api/fridge?expiring_within_hours=48" -H "X-Life-Handle: $HANDLE"
```

## Expiry-day resolve

```bash
curl -s -X POST "$LIFE_API_BASE/api/fridge/$ID/resolve" \
  -H "Content-Type: application/json" \
  -H "X-Life-Handle: $HANDLE" \
  -d '{"action":"eaten","preference":4}'
```

Actions: `eaten` | `discarded` | `keep_one_more_day`.

## Talk

> 鸡胸今天该处理了。吃完了、扔了，还是我再记一天？

Server updates status + (later) food_prefs. Skill closes related memo via memos API.
