package com.lifeos.receipts;

import com.lifeos.domain.FoodCategory;
import com.lifeos.domain.ReceiptStatus;
import com.lifeos.service.ReceiptService;
import com.lifeos.web.dto.ReceiptConfirmRequest;
import com.lifeos.web.dto.ReceiptConfirmResponse;
import com.lifeos.web.dto.ReceiptPreviewRequest;
import com.lifeos.web.dto.ReceiptPreviewResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Same receipt scenarios for local SQLite and for the (fake) Turso HTTP path. */
abstract class AbstractReceiptAtomicTest {

    @Autowired
    ReceiptService receipts;

    abstract Path dbFile();

    /** Reads the database file directly, bypassing the app. */
    JdbcTemplate db() {
        SingleConnectionDataSource ds = new SingleConnectionDataSource("jdbc:sqlite:" + dbFile(), true);
        return new JdbcTemplate(ds);
    }

    long count(String sql, Object... args) {
        Long n = db().queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    static ReceiptPreviewRequest request(String merchant, String barcode, List<ReceiptPreviewRequest.Line> lines) {
        int total = lines.stream().mapToInt(ReceiptPreviewRequest.Line::amountCents).sum();
        return new ReceiptPreviewRequest(merchant, barcode, "2026-10-10 12:00", "JPY", total, 0, 0,
                lines, null, null, null);
    }

    static ReceiptPreviewRequest.Line food(String name, int cents) {
        return new ReceiptPreviewRequest.Line(name, 1.0, cents, true, FoodCategory.DAIRY);
    }

    static ReceiptPreviewRequest.Line other(String name, int cents) {
        return new ReceiptPreviewRequest.Line(name, 2.0, cents, false, null);
    }

    static String uniq() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    void previewWritesEverythingAndReturnsIds() {
        String m = "Shop-" + uniq();
        ReceiptPreviewResponse r = receipts.preview(
                request(m, uniq(), List.of(food("milk", 200), other("soap", 300))), 1L);
        assertEquals("create_pending", r.action());
        assertNotNull(r.receiptId());
        assertEquals(r.receiptId(), count("SELECT id FROM receipts WHERE fingerprint = ?", r.fingerprint()));
        assertEquals(r.merchantId(), count("SELECT merchant_id FROM receipts WHERE id = ?", r.receiptId()));
        assertEquals(r.merchantId(), count("SELECT id FROM merchants WHERE name = ?", m));
        assertEquals(2, count("SELECT COUNT(*) FROM receipt_items WHERE receipt_id = ?", r.receiptId()));
        assertEquals(1, count("SELECT COUNT(*) FROM events WHERE action = 'preview' AND entity_id = ?", r.receiptId()));
        assertEquals(1, r.foodItems().size());
        assertEquals("milk", r.foodItems().get(0).name());
        assertEquals(true, r.sumOk());

        // same merchant again: reused, not duplicated
        ReceiptPreviewResponse r2 = receipts.preview(request(m, uniq(), List.of(other("tea", 100))), 1L);
        assertEquals(r.merchantId(), r2.merchantId());
        assertEquals(1, count("SELECT COUNT(*) FROM merchants WHERE name = ?", m));

        // same receipt again: duplicate, nothing written
        ReceiptPreviewResponse dup = receipts.preview(
                request(m, extractBarcode(r), List.of(food("milk", 200), other("soap", 300))), 1L);
        assertEquals("duplicate", dup.action());
        assertEquals(r.receiptId(), dup.existingReceiptId());
    }

    private String extractBarcode(ReceiptPreviewResponse r) {
        return db().queryForObject("SELECT barcode FROM receipts WHERE id = ?", String.class, r.receiptId());
    }

    @Test
    void previewFailureMidBatchLeavesNothing() {
        String m = "Boom-" + uniq();
        String barcode = uniq();
        long receiptsBefore = count("SELECT COUNT(*) FROM receipts");
        long itemsBefore = count("SELECT COUNT(*) FROM receipt_items");
        long eventsBefore = count("SELECT COUNT(*) FROM events");
        assertThrows(RuntimeException.class, () -> receipts.preview(
                request(m, barcode, List.of(food("milk", 100), other("BOOM", 100), other("after", 100))), 1L));
        assertEquals(0, count("SELECT COUNT(*) FROM merchants WHERE name = ?", m), "merchant rolled back");
        assertEquals(receiptsBefore, count("SELECT COUNT(*) FROM receipts"), "receipt rolled back");
        assertEquals(itemsBefore, count("SELECT COUNT(*) FROM receipt_items"), "items rolled back");
        assertEquals(eventsBefore, count("SELECT COUNT(*) FROM events"), "event rolled back");
    }

    @Test
    void confirmWithFridgeIsAtomicAndReturnsFridgeIds() {
        ReceiptPreviewResponse p = receipts.preview(
                request("Mart-" + uniq(), uniq(), List.of(food("yogurt", 150), other("bag", 5), food("cheese", 400))), 1L);
        ReceiptConfirmResponse c = receipts.confirm(p.receiptId(), new ReceiptConfirmRequest(true), 1L);
        assertEquals(ReceiptStatus.CONFIRMED, c.status());
        assertNull(c.error());
        assertEquals(2, c.fridgeItemIds().size());
        List<Long> fromDb = db().queryForList(
                "SELECT id FROM fridge_items WHERE source_receipt_id = ? ORDER BY id", Long.class, p.receiptId());
        assertEquals(fromDb, c.fridgeItemIds());
        assertEquals(List.of("yogurt", "cheese"), db().queryForList(
                "SELECT name FROM fridge_items WHERE source_receipt_id = ? ORDER BY id", String.class, p.receiptId()));
        assertEquals("confirmed", db().queryForObject(
                "SELECT status FROM receipts WHERE id = ?", String.class, p.receiptId()));
        assertEquals(1, count("SELECT COUNT(*) FROM events WHERE action = 'confirm' AND entity_id = ?", p.receiptId()));

        ReceiptConfirmResponse again = receipts.confirm(p.receiptId(), new ReceiptConfirmRequest(true), 1L);
        assertEquals("not_pending", again.error());
    }

    @Test
    void confirmWithoutFridge() {
        ReceiptPreviewResponse p = receipts.preview(
                request("Kiosk-" + uniq(), uniq(), List.of(food("juice", 120))), 1L);
        ReceiptConfirmResponse c = receipts.confirm(p.receiptId(), new ReceiptConfirmRequest(false), 1L);
        assertEquals(List.of(), c.fridgeItemIds());
        assertEquals(0, count("SELECT COUNT(*) FROM fridge_items WHERE source_receipt_id = ?", p.receiptId()));
        assertEquals("confirmed", db().queryForObject(
                "SELECT status FROM receipts WHERE id = ?", String.class, p.receiptId()));
    }

    @Test
    void confirmFailureMidBatchLeavesReceiptPending() {
        ReceiptPreviewResponse p = receipts.preview(
                request("Deli-" + uniq(), uniq(), List.of(food("ham", 300), food("BOOMFOOD", 100), food("bread", 200))), 1L);
        assertThrows(RuntimeException.class,
                () -> receipts.confirm(p.receiptId(), new ReceiptConfirmRequest(true), 1L));
        assertEquals("pending_confirm", db().queryForObject(
                "SELECT status FROM receipts WHERE id = ?", String.class, p.receiptId()));
        assertEquals(0, count("SELECT COUNT(*) FROM fridge_items WHERE source_receipt_id = ?", p.receiptId()));
        assertEquals(0, count("SELECT COUNT(*) FROM events WHERE action = 'confirm' AND entity_id = ?", p.receiptId()));
    }
}
