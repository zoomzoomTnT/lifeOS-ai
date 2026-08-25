package com.lifeos.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@Service
public class ReceiptService {

    private final JdbcTemplate jdbc;
    private final PersonService personService;

    public ReceiptService(JdbcTemplate jdbc, PersonService personService) {
        this.jdbc = jdbc;
        this.personService = personService;
    }

    public Map<String, Object> lookup(String barcode, String printedAt) {
        String fp = fingerprint(barcode, printedAt);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, status, total_cents, merchant_id FROM receipts WHERE fingerprint = ?", fp);
        if (rows.isEmpty()) return Map.of("found", false);
        return Map.of("found", true, "receipt", rows.get(0));
    }

    @Transactional
    public Map<String, Object> preview(Map<String, Object> body, String handle) {
        String barcode = str(body.get("barcode"));
        String printedAt = str(body.get("printed_at"));
        String fp = fingerprint(barcode, printedAt);

        // duplicate check
        List<Map<String, Object>> existing = jdbc.queryForList(
                "SELECT id, status FROM receipts WHERE fingerprint = ?", fp);
        if (!existing.isEmpty()) {
            return Map.of(
                    "action", "duplicate",
                    "existing_receipt_id", existing.get(0).get("id"),
                    "status", existing.get(0).get("status"),
                    "message", "同一张小票已经记过了"
            );
        }

        long payerId = personService.resolveId(handle);
        String merchantName = str(body.get("merchant_name"));
        long merchantId = ensureMerchant(merchantName);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) body.getOrDefault("items", List.of());
        int computed = items.stream().mapToInt(i -> toInt(i.get("amount_cents"))).sum();
        int total = toInt(body.get("total_cents"));
        boolean sumOk = Math.abs(computed - total) <= 2;

        // insert pending
        jdbc.update("""
                INSERT INTO receipts (merchant_id, payer_id, barcode, printed_at, fingerprint,
                    currency, total_cents, computed_cents, tax_cents, discount_cents,
                    status, raw_ocr_json, image_path)
                VALUES (?,?,?,?,?,?,?,?,?,?, 'pending_confirm', ?, ?)
                """,
                merchantId, payerId, barcode, printedAt, fp,
                str(body.getOrDefault("currency", "CNY")),
                total, computed,
                toInt(body.getOrDefault("tax_cents", 0)),
                toInt(body.getOrDefault("discount_cents", 0)),
                body.get("raw_ocr_json") != null ? body.get("raw_ocr_json").toString() : null,
                str(body.get("image_path"))
        );
        long receiptId = jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);

        int order = 0;
        List<Map<String, Object>> foodItems = new ArrayList<>();
        for (Map<String, Object> it : items) {
            boolean isFood = Boolean.TRUE.equals(it.get("is_food")) || "1".equals(String.valueOf(it.get("is_food")));
            String name = str(it.get("name"));
            String nameNorm = nameNorm(name);
            jdbc.update("""
                    INSERT INTO receipt_items (receipt_id, name, name_norm, qty, amount_cents, is_food, category, sort_order)
                    VALUES (?,?,?,?,?,?,?,?)
                    """,
                    receiptId, name, nameNorm,
                    toDouble(it.getOrDefault("qty", 1)),
                    toInt(it.get("amount_cents")),
                    isFood ? 1 : 0,
                    str(it.get("category")),
                    order++
            );
            if (isFood) {
                foodItems.add(Map.of("name", name, "name_norm", nameNorm, "category", str(it.get("category"))));
            }
        }

        // audit
        jdbc.update("INSERT INTO events (domain, action, actor_id, entity_table, entity_id) VALUES ('finance','preview',?, 'receipts',?)",
                payerId, receiptId);

        return Map.of(
                "action", "create_pending",
                "receipt_id", receiptId,
                "fingerprint", fp,
                "sum_ok", sumOk,
                "computed_cents", computed,
                "total_cents", total,
                "merchant_id", merchantId,
                "food_items", foodItems
        );
    }

    @Transactional
    public Map<String, Object> confirm(long id, boolean alsoFridge, String handle) {
        Map<String, Object> r = jdbc.queryForMap("SELECT * FROM receipts WHERE id = ?", id);
        if (!"pending_confirm".equals(r.get("status"))) {
            return Map.of("error", "not_pending", "status", r.get("status"));
        }
        // optional: re-check sum
        jdbc.update("UPDATE receipts SET status='confirmed', confirmed_at=strftime('%Y-%m-%dT%H:%M:%SZ','now'), updated_at=strftime('%Y-%m-%dT%H:%M:%SZ','now') WHERE id=?", id);

        List<Long> fridgeIds = List.of();
        if (alsoFridge) {
            fridgeIds = createFridgeFromReceipt(id, handle);
        }

        long actor = personService.resolveId(handle);
        jdbc.update("INSERT INTO events (domain, action, actor_id, entity_table, entity_id) VALUES ('finance','confirm',?, 'receipts',?)",
                actor, id);

        return Map.of("status", "confirmed", "receipt_id", id, "fridge_item_ids", fridgeIds);
    }

    public List<Map<String, Object>> list(String status, int limit) {
        if (status != null) {
            return jdbc.queryForList("SELECT * FROM receipts WHERE status = ? ORDER BY id DESC LIMIT ?", status, limit);
        }
        return jdbc.queryForList("SELECT * FROM receipts ORDER BY id DESC LIMIT ?", limit);
    }

    private List<Long> createFridgeFromReceipt(long receiptId, String handle) {
        // minimal: create in_stock items for is_food lines; real shelf-life logic later
        long ownerId = personService.resolveId(handle);
        List<Map<String, Object>> items = jdbc.queryForList(
                "SELECT * FROM receipt_items WHERE receipt_id = ? AND is_food = 1", receiptId);
        List<Long> ids = new ArrayList<>();
        for (Map<String, Object> it : items) {
            jdbc.update("""
                    INSERT INTO fridge_items (owner_id, added_by_id, name, name_norm, category, location, status,
                        qty, purchased_at, source_receipt_id, source_receipt_item_id)
                    VALUES (?,?,?,?,?, 'fridge', 'in_stock', ?, strftime('%Y-%m-%dT%H:%M:%SZ','now'), ?, ?)
                    """,
                    ownerId, ownerId,
                    it.get("name"), it.get("name_norm"), it.get("category"),
                    it.get("qty"), receiptId, it.get("id")
            );
            ids.add(jdbc.queryForObject("SELECT last_insert_rowid()", Long.class));
        }
        return ids;
    }

    private long ensureMerchant(String name) {
        if (name == null || name.isBlank()) name = "未知商户";
        String norm = nameNorm(name);
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id FROM merchants WHERE name_norm = ?", norm);
        if (!rows.isEmpty()) return ((Number) rows.get(0).get("id")).longValue();
        jdbc.update("INSERT INTO merchants (name, name_norm) VALUES (?,?)", name, norm);
        return jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
    }

    static String fingerprint(String barcode, String printedAt) {
        String raw = (barcode == null ? "" : barcode.trim()) + "|" + (printedAt == null ? "" : printedAt.trim());
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 16; i++) sb.append(String.format("%02x", dig[i]));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(raw.hashCode());
        }
    }

    static String nameNorm(String s) {
        if (s == null) return "";
        return s.toLowerCase().replaceAll("[\\s\\p{Punct}]+", "");
    }

    private static String str(Object o) { return o == null ? null : o.toString(); }
    private static int toInt(Object o) {
        if (o == null) return 0;
        if (o instanceof Number n) return n.intValue();
        return Integer.parseInt(o.toString());
    }
    private static double toDouble(Object o) {
        if (o == null) return 1.0;
        if (o instanceof Number n) return n.doubleValue();
        return Double.parseDouble(o.toString());
    }
}
