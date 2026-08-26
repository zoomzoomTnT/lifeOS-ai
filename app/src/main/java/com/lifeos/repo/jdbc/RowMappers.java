package com.lifeos.repo.jdbc;

import com.lifeos.domain.FoodCategory;
import com.lifeos.domain.FridgeItem;
import com.lifeos.domain.FridgeLocation;
import com.lifeos.domain.FridgeStatus;
import com.lifeos.domain.Memo;
import com.lifeos.domain.MemoKind;
import com.lifeos.domain.MemoStatus;
import com.lifeos.domain.Receipt;
import com.lifeos.domain.ReceiptItem;
import com.lifeos.domain.ReceiptStatus;
import org.springframework.jdbc.core.RowMapper;

final class RowMappers {

    private RowMappers() {}

    static final RowMapper<FridgeItem> FRIDGE = (rs, n) -> {
        FridgeItem row = new FridgeItem();
        row.setId(rs.getLong("id"));
        row.setOwnerId(rs.getLong("owner_id"));
        row.setAddedById(rs.getLong("added_by_id"));
        row.setName(rs.getString("name"));
        row.setNameNorm(rs.getString("name_norm"));
        row.setCategory(FoodCategory.from(rs.getString("category")));
        row.setLocation(FridgeLocation.from(rs.getString("location")));
        row.setStatus(FridgeStatus.from(rs.getString("status")));
        row.setQty(rs.getDouble("qty"));
        row.setUnit(rs.getString("unit"));
        row.setPurchasedAt(rs.getString("purchased_at"));
        row.setExpiresAt(rs.getString("expires_at"));
        row.setPreference(SqliteIds.intOrNull(rs.getObject("preference")));
        row.setSourceReceiptId(SqliteIds.longOrNull(rs.getObject("source_receipt_id")));
        row.setSourceReceiptItemId(SqliteIds.longOrNull(rs.getObject("source_receipt_item_id")));
        row.setNotes(rs.getString("notes"));
        row.setCreatedAt(rs.getString("created_at"));
        row.setUpdatedAt(rs.getString("updated_at"));
        return row;
    };

    static final RowMapper<Memo> MEMO = (rs, n) -> {
        Memo row = new Memo();
        row.setId(rs.getLong("id"));
        row.setOwnerId(rs.getLong("owner_id"));
        row.setTitle(rs.getString("title"));
        row.setBody(rs.getString("body"));
        row.setKind(MemoKind.from(rs.getString("kind")));
        row.setStatus(MemoStatus.from(rs.getString("status")));
        row.setPriority(rs.getInt("priority"));
        row.setDueAt(rs.getString("due_at"));
        row.setTimezone(rs.getString("timezone"));
        row.setCronExpr(rs.getString("cron_expr"));
        row.setCronTz(rs.getString("cron_tz"));
        row.setSourceDomain(rs.getString("source_domain"));
        row.setSourceTable(rs.getString("source_table"));
        row.setSourceId(SqliteIds.longOrNull(rs.getObject("source_id")));
        row.setPayloadJson(rs.getString("payload_json"));
        row.setAutomationId(rs.getString("automation_id"));
        row.setLastFiredAt(rs.getString("last_fired_at"));
        row.setCreatedAt(rs.getString("created_at"));
        row.setUpdatedAt(rs.getString("updated_at"));
        return row;
    };

    static final RowMapper<Receipt> RECEIPT = (rs, n) -> {
        Receipt row = new Receipt();
        row.setId(rs.getLong("id"));
        row.setMerchantId(SqliteIds.longOrNull(rs.getObject("merchant_id")));
        row.setPayerId(SqliteIds.longOrNull(rs.getObject("payer_id")));
        row.setBarcode(rs.getString("barcode"));
        row.setPrintedAt(rs.getString("printed_at"));
        row.setFingerprint(rs.getString("fingerprint"));
        row.setCurrency(rs.getString("currency"));
        row.setTotalCents(SqliteIds.intOrNull(rs.getObject("total_cents")));
        row.setComputedCents(SqliteIds.intOrNull(rs.getObject("computed_cents")));
        row.setTaxCents(SqliteIds.intOrNull(rs.getObject("tax_cents")));
        row.setDiscountCents(SqliteIds.intOrNull(rs.getObject("discount_cents")));
        row.setStatus(ReceiptStatus.from(rs.getString("status")));
        row.setRawOcrJson(has(rs, "raw_ocr_json") ? rs.getString("raw_ocr_json") : null);
        row.setImagePath(has(rs, "image_path") ? rs.getString("image_path") : null);
        row.setNotes(has(rs, "notes") ? rs.getString("notes") : null);
        row.setCreatedAt(rs.getString("created_at"));
        row.setUpdatedAt(has(rs, "updated_at") ? rs.getString("updated_at") : null);
        row.setConfirmedAt(has(rs, "confirmed_at") ? rs.getString("confirmed_at") : null);
        return row;
    };

    static final RowMapper<ReceiptItem> RECEIPT_ITEM = (rs, n) -> {
        ReceiptItem row = new ReceiptItem();
        row.setId(rs.getLong("id"));
        row.setReceiptId(rs.getLong("receipt_id"));
        row.setName(rs.getString("name"));
        row.setNameNorm(rs.getString("name_norm"));
        row.setQty(rs.getDouble("qty"));
        row.setUnit(has(rs, "unit") ? rs.getString("unit") : null);
        row.setAmountCents(rs.getInt("amount_cents"));
        row.setFood(rs.getInt("is_food") == 1);
        row.setCategory(FoodCategory.from(rs.getString("category")));
        row.setSortOrder(has(rs, "sort_order") ? rs.getInt("sort_order") : 0);
        row.setCreatedAt(has(rs, "created_at") ? rs.getString("created_at") : null);
        return row;
    };

    private static boolean has(java.sql.ResultSet rs, String col) {
        try {
            rs.findColumn(col);
            return true;
        } catch (java.sql.SQLException e) {
            return false;
        }
    }
}
