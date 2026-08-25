# life-finance — 记账与小票 (API)

Read conventions first. Food line items → after confirm, follow `fridge.md`.

## WeChat images

The current model must see the image. If it cannot, say so and stop. Do not invent amounts.

Classify first — receipt / food photo / other. Only receipts use this flow.

## Receipt workflow (in order)

1. Extract merchant, printed timestamp, barcode/order id, currency, line items, tax, discount, footer total.
2. Call API preview (server computes fingerprint + sum check + pending insert):

```bash
curl -s -X POST "$LIFE_API_BASE/api/receipts/preview" \
  -H "Content-Type: application/json" \
  -H "X-Life-Handle: $HANDLE" \
  -d '{
    "merchant_name": "盒马鲜生",
    "barcode": "262508241912",
    "printed_at": "2026-08-24 19:12:03",
    "currency": "CNY",
    "total_cents": 4460,
    "items": [
      {"name":"生菜","qty":1,"amount_cents":490,"is_food":true,"category":"veg"},
      {"name":"西红柿","qty":2,"amount_cents":980,"is_food":true,"category":"veg"},
      {"name":"鸡胸","qty":1,"amount_cents":2990,"is_food":true,"category":"meat"}
    ],
    "raw_ocr_json": { ... },
    "image_path": "..."
  }'
```

3. If `action=duplicate` → tell user already logged; optionally add claim. Stop.
4. If `sum_ok=false` → tell user lines do not match footer; do **not** confirm.
5. Show checklist (use server fields + your own formatting). Default payer = current handle.
6. User says 对 → 

```bash
curl -s -X POST "$LIFE_API_BASE/api/receipts/$ID/confirm" \
  -H "Content-Type: application/json" \
  -H "X-Life-Handle: $HANDLE" \
  -d '{"also_fridge": true}'
```

7. Never confirm when totals disagree. Never silent-write fridge without user OK (the `also_fridge` flag is the explicit OK).

## Spoken one-liner

「午饭 38」 → one receipt + one item, still go through preview → pending_confirm.

## Merchants

「这家不错 / 别去了」 → later `PATCH /api/merchants/{id}` favorite_score ±0.5.

## Confirm copy example

```
盒马鲜生 · 今天 19:12 · 票号 262508241912 · 付款人：你
生菜 1     ¥4.90
西红柿 2   ¥9.80
鸡胸 1     ¥29.90
合计行项目 ¥44.60  小票底部 ¥44.60  ✓ 一致
这张小票我先记成待确认。回「对」我就入账。
生菜/西红柿/鸡胸 要不要一并进冰箱？我按常识写保质期。
```
