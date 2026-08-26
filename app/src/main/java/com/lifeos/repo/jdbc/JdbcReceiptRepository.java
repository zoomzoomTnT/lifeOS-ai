package com.lifeos.repo.jdbc;

import com.lifeos.domain.FoodCategory;
import com.lifeos.domain.Receipt;
import com.lifeos.domain.ReceiptItem;
import com.lifeos.domain.ReceiptStatus;
import com.lifeos.repo.ReceiptRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class JdbcReceiptRepository implements ReceiptRepository {

    private final JdbcTemplate jdbc;

    private static final RowMapper<Receipt> ROW = (rs, n) -> new Receipt(
            rs.getLong("id"),
            SqliteIds.longOrNull(rs.getObject("merchant_id")),
            SqliteIds.longOrNull(rs.getObject("payer_id")),
            rs.getString("barcode"),
            rs.getString("printed_at"),
            rs.getString("fingerprint"),
            rs.getString("currency"),
            SqliteIds.intOrNull(rs.getObject("total_cents")),
            SqliteIds.intOrNull(rs.getObject("computed_cents")),
            ReceiptStatus.from(rs.getString("status")),
            rs.getString("created_at")
    );

    private static final RowMapper<ReceiptItem> ITEM = (rs, n) -> new ReceiptItem(
            rs.getLong("id"),
            rs.getLong("receipt_id"),
            rs.getString("name"),
            rs.getString("name_norm"),
            rs.getDouble("qty"),
            rs.getInt("amount_cents"),
            rs.getInt("is_food") == 1,
            FoodCategory.from(rs.getString("category"))
    );

    @Override
    public Optional<Receipt> findByFingerprint(String fingerprint) {
        List<Receipt> rows = jdbc.query(
                "SELECT id, merchant_id, payer_id, barcode, printed_at, fingerprint, currency, total_cents, computed_cents, status, created_at FROM receipts WHERE fingerprint = ?",
                ROW, fingerprint);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public Optional<Receipt> findById(long id) {
        List<Receipt> rows = jdbc.query(
                "SELECT id, merchant_id, payer_id, barcode, printed_at, fingerprint, currency, total_cents, computed_cents, status, created_at FROM receipts WHERE id = ?",
                ROW, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public List<Receipt> list(ReceiptStatus status, int limit) {
        if (status != null) {
            return jdbc.query(
                    "SELECT id, merchant_id, payer_id, barcode, printed_at, fingerprint, currency, total_cents, computed_cents, status, created_at FROM receipts WHERE status = ? ORDER BY id DESC LIMIT ?",
                    ROW, status.db(), limit);
        }
        return jdbc.query(
                "SELECT id, merchant_id, payer_id, barcode, printed_at, fingerprint, currency, total_cents, computed_cents, status, created_at FROM receipts ORDER BY id DESC LIMIT ?",
                ROW, limit);
    }

    @Override
    public List<Receipt> stalePending(long payerId, int olderThanHours) {
        return jdbc.query("""
                SELECT id, merchant_id, payer_id, barcode, printed_at, fingerprint, currency, total_cents, computed_cents, status, created_at
                FROM receipts
                WHERE payer_id = ? AND status = ?
                  AND created_at <= strftime('%Y-%m-%dT%H:%M:%SZ','now', '-' || ? || ' hours')
                LIMIT 5
                """, ROW, payerId, ReceiptStatus.PENDING_CONFIRM.db(), olderThanHours);
    }

    @Override
    public long insertPending(Receipt receipt, String rawOcrJson, String imagePath, int taxCents, int discountCents) {
        jdbc.update("""
                        INSERT INTO receipts (merchant_id, payer_id, barcode, printed_at, fingerprint,
                            currency, total_cents, computed_cents, tax_cents, discount_cents,
                            status, raw_ocr_json, image_path)
                        VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                        """,
                receipt.merchantId(), receipt.payerId(), receipt.barcode(), receipt.printedAt(), receipt.fingerprint(),
                receipt.currency(), receipt.totalCents(), receipt.computedCents(), taxCents, discountCents,
                ReceiptStatus.PENDING_CONFIRM.db(), rawOcrJson, imagePath
        );
        return SqliteIds.lastInsertId(jdbc);
    }

    @Override
    public void insertItem(long receiptId, ReceiptItem item, int sortOrder) {
        jdbc.update("""
                        INSERT INTO receipt_items (receipt_id, name, name_norm, qty, amount_cents, is_food, category, sort_order)
                        VALUES (?,?,?,?,?,?,?,?)
                        """,
                receiptId, item.name(), item.nameNorm(), item.qty(), item.amountCents(),
                item.food() ? 1 : 0,
                item.category() == null ? null : item.category().db(),
                sortOrder
        );
    }

    @Override
    public List<ReceiptItem> foodItems(long receiptId) {
        return jdbc.query("SELECT * FROM receipt_items WHERE receipt_id = ? AND is_food = 1", ITEM, receiptId);
    }

    @Override
    public void markConfirmed(long id) {
        jdbc.update("""
                UPDATE receipts SET status=?, confirmed_at=strftime('%Y-%m-%dT%H:%M:%SZ','now'),
                updated_at=strftime('%Y-%m-%dT%H:%M:%SZ','now') WHERE id=?
                """, ReceiptStatus.CONFIRMED.db(), id);
    }
}
