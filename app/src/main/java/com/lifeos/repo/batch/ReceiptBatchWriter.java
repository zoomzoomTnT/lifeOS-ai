package com.lifeos.repo.batch;

import com.lifeos.domain.DbEnum;
import com.lifeos.domain.FridgeItem;
import com.lifeos.domain.FridgeLocation;
import com.lifeos.domain.FridgeStatus;
import com.lifeos.domain.LocationTag;
import com.lifeos.domain.MerchantKind;
import com.lifeos.domain.Receipt;
import com.lifeos.domain.ReceiptItem;
import com.lifeos.domain.ReceiptStatus;
import com.lifeos.domain.Utc;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The two multi-row receipt writes, each as ONE {@link AtomicSqlBatch} so they are all-or-nothing
 * on Turso as well as on local SQLite. Later rows find earlier ones via unique keys
 * (merchants.name_norm, receipts.fingerprint) instead of ids held in Java.
 */
@Component
@RequiredArgsConstructor
public class ReceiptBatchWriter {

    private final AtomicSqlBatch batch;

    public record PreviewIds(long receiptId, long merchantId) {}

    /** merchant (if new) + pending receipt + its items + finance/preview event. */
    public PreviewIds writePreview(String merchantName, String merchantNorm, Receipt pending,
                                   List<ReceiptItem> items, long actorId) {
        String now = Utc.now();
        String fp = pending.getFingerprint();
        List<SqlStep> steps = new ArrayList<>();
        steps.add(SqlStep.of("""
                INSERT INTO merchants (name, name_norm, kind, location_tag, favorite_score, created_at, updated_at)
                VALUES (?,?,?,?,0.0,?,?)
                ON CONFLICT(name_norm) DO NOTHING""",
                merchantName, merchantNorm, MerchantKind.OTHER.db(), LocationTag.OTHER.db(), now, now));
        steps.add(SqlStep.of("""
                INSERT INTO receipts (merchant_id, payer_id, barcode, printed_at, fingerprint, currency,
                  total_cents, computed_cents, tax_cents, discount_cents, status, raw_ocr_json, image_path,
                  notes, created_at, updated_at)
                VALUES ((SELECT id FROM merchants WHERE name_norm = ?),?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                RETURNING id, merchant_id""",
                merchantNorm, pending.getPayerId(), pending.getBarcode(), pending.getPrintedAt(), fp,
                pending.getCurrency(), pending.getTotalCents(), pending.getComputedCents(),
                pending.getTaxCents(), pending.getDiscountCents(),
                db(pending.getStatus() == null ? ReceiptStatus.PENDING_CONFIRM : pending.getStatus()),
                pending.getRawOcrJson(), pending.getImagePath(), pending.getNotes(), now, now));
        int order = 0;
        for (ReceiptItem it : items) {
            steps.add(SqlStep.of("""
                    INSERT INTO receipt_items (receipt_id, name, name_norm, qty, unit, amount_cents, is_food,
                      category, sort_order, created_at)
                    VALUES ((SELECT id FROM receipts WHERE fingerprint = ?),?,?,?,?,?,?,?,?,?)""",
                    fp, it.getName(), it.getNameNorm(), it.getQty(), it.getUnit(), it.getAmountCents(),
                    it.isFood() ? 1 : 0, db(it.getCategory()), order++, now));
        }
        steps.add(SqlStep.of("""
                INSERT INTO events (domain, action, actor_id, entity_table, entity_id, payload_json)
                VALUES ('finance','preview',?,'receipts',(SELECT id FROM receipts WHERE fingerprint = ?),NULL)""",
                actorId, fp));

        List<List<Map<String, Object>>> out = batch.execute(steps);
        Map<String, Object> row = single(out.get(1), "receipt insert");
        return new PreviewIds(asLong(row.get("id")), asLong(row.get("merchant_id")));
    }

    /** confirm receipt + one fridge row per food item + finance/confirm event. Returns fridge ids in order. */
    public List<Long> writeConfirm(long receiptId, List<FridgeItem> fridgeItems, long actorId) {
        String now = Utc.now();
        List<SqlStep> steps = new ArrayList<>();
        steps.add(SqlStep.of("UPDATE receipts SET status = ?, confirmed_at = ?, updated_at = ? WHERE id = ?",
                ReceiptStatus.CONFIRMED.db(), now, now, receiptId));
        for (FridgeItem f : fridgeItems) {
            steps.add(SqlStep.of("""
                    INSERT INTO fridge_items (owner_id, added_by_id, name, name_norm, category, location, status,
                      qty, unit, purchased_at, expires_at, preference, source_receipt_id, source_receipt_item_id,
                      notes, created_at, updated_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    RETURNING id""",
                    f.getOwnerId(), f.getAddedById(), f.getName(), f.getNameNorm(), db(f.getCategory()),
                    db(f.getLocation() == null ? FridgeLocation.FRIDGE : f.getLocation()),
                    db(f.getStatus() == null ? FridgeStatus.IN_STOCK : f.getStatus()),
                    f.getQty(), f.getUnit(), f.getPurchasedAt() == null ? now : f.getPurchasedAt(),
                    f.getExpiresAt(), f.getPreference(), f.getSourceReceiptId(), f.getSourceReceiptItemId(),
                    f.getNotes(), now, now));
        }
        steps.add(SqlStep.of("""
                INSERT INTO events (domain, action, actor_id, entity_table, entity_id, payload_json)
                VALUES ('finance','confirm',?,'receipts',?,NULL)""", actorId, receiptId));

        List<List<Map<String, Object>>> out = batch.execute(steps);
        List<Long> ids = new ArrayList<>(fridgeItems.size());
        for (int i = 1; i <= fridgeItems.size(); i++) {
            ids.add(asLong(single(out.get(i), "fridge insert").get("id")));
        }
        return ids;
    }

    private static String db(DbEnum e) {
        return e == null ? null : e.db();
    }

    private static Map<String, Object> single(List<Map<String, Object>> rows, String what) {
        if (rows.size() != 1) throw new AtomicBatchException(what + " returned " + rows.size() + " rows");
        return rows.get(0);
    }

    private static long asLong(Object o) {
        if (o == null) throw new AtomicBatchException("missing id in batch result");
        return ((Number) o).longValue();
    }
}
