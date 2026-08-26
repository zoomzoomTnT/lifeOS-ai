package com.lifeos.repo.jdbc;

import com.lifeos.domain.Receipt;
import com.lifeos.domain.ReceiptItem;
import com.lifeos.domain.ReceiptStatus;
import com.lifeos.repo.ReceiptRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class JdbcReceiptRepository implements ReceiptRepository {

    private final JdbcTemplate jdbc;


    @Override
    public Optional<Receipt> findByFingerprint(String fingerprint) {
        List<Receipt> rows = jdbc.query(
                "SELECT id, merchant_id, payer_id, barcode, printed_at, fingerprint, currency, total_cents, computed_cents, status, created_at FROM receipts WHERE fingerprint = ?",
                RowMappers.RECEIPT, fingerprint);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public Optional<Receipt> findById(long id) {
        List<Receipt> rows = jdbc.query(
                "SELECT id, merchant_id, payer_id, barcode, printed_at, fingerprint, currency, total_cents, computed_cents, status, created_at FROM receipts WHERE id = ?",
                RowMappers.RECEIPT, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public List<Receipt> list(ReceiptStatus status, int limit) {
        if (status != null) {
            return jdbc.query(
                    "SELECT id, merchant_id, payer_id, barcode, printed_at, fingerprint, currency, total_cents, computed_cents, status, created_at FROM receipts WHERE status = ? ORDER BY id DESC LIMIT ?",
                    RowMappers.RECEIPT, status.db(), limit);
        }
        return jdbc.query(
                "SELECT id, merchant_id, payer_id, barcode, printed_at, fingerprint, currency, total_cents, computed_cents, status, created_at FROM receipts ORDER BY id DESC LIMIT ?",
                RowMappers.RECEIPT, limit);
    }

    @Override
    public List<Receipt> stalePending(long payerId, int olderThanHours) {
        return jdbc.query("""
                SELECT id, merchant_id, payer_id, barcode, printed_at, fingerprint, currency, total_cents, computed_cents, status, created_at
                FROM receipts
                WHERE payer_id = ? AND status = ?
                  AND created_at <= strftime('%Y-%m-%dT%H:%M:%SZ','now', '-' || ? || ' hours')
                LIMIT 5
                """, RowMappers.RECEIPT, payerId, ReceiptStatus.PENDING_CONFIRM.db(), olderThanHours);
    }

    @Override
    public long insertPending(Receipt receipt, String rawOcrJson, String imagePath, int taxCents, int discountCents) {
        jdbc.update("""
                        INSERT INTO receipts (merchant_id, payer_id, barcode, printed_at, fingerprint,
                            currency, total_cents, computed_cents, tax_cents, discount_cents,
                            status, raw_ocr_json, image_path)
                        VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                        """,
                receipt.getMerchantId(), receipt.getPayerId(), receipt.getBarcode(), receipt.getPrintedAt(), receipt.getFingerprint(),
                receipt.getCurrency(), receipt.getTotalCents(), receipt.getComputedCents(), taxCents, discountCents,
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
                receiptId, item.getName(), item.getNameNorm(), item.getQty(), item.getAmountCents(),
                item.isFood() ? 1 : 0,
                item.getCategory() == null ? null : item.getCategory().db(),
                sortOrder
        );
    }

    @Override
    public List<ReceiptItem> foodItems(long receiptId) {
        return jdbc.query("SELECT * FROM receipt_items WHERE receipt_id = ? AND is_food = 1", RowMappers.RECEIPT_ITEM, receiptId);
    }

    @Override
    public void markConfirmed(long id) {
        jdbc.update("""
                UPDATE receipts SET status=?, confirmed_at=strftime('%Y-%m-%dT%H:%M:%SZ','now'),
                updated_at=strftime('%Y-%m-%dT%H:%M:%SZ','now') WHERE id=?
                """, ReceiptStatus.CONFIRMED.db(), id);
    }
}
