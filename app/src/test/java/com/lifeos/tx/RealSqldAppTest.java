package com.lifeos.tx;

import com.lifeos.domain.FoodCategory;
import com.lifeos.domain.Market;
import com.lifeos.ops.OpsService;
import com.lifeos.service.HoldingService;
import com.lifeos.service.ReceiptService;
import com.lifeos.web.dto.HoldingUpsertRequest;
import com.lifeos.web.dto.ReceiptConfirmRequest;
import com.lifeos.web.dto.ReceiptConfirmResponse;
import com.lifeos.web.dto.ReceiptPreviewRequest;
import com.lifeos.web.dto.ReceiptPreviewResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The whole app (default profile, libSQL driver) against a REAL sqld — CI runs it with a
 * libsql-server service container (LIBSQL_TEST_URL). Starts on whatever the server has; on a
 * fresh server this also proves schema creation (incl. merchants) over libSQL.
 */
@EnabledIfEnvironmentVariable(named = "LIBSQL_TEST_URL", matches = ".+")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class RealSqldAppTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> System.getenv("LIBSQL_TEST_URL"));
        r.add("spring.datasource.password", () -> System.getenv().getOrDefault("LIBSQL_TEST_TOKEN", ""));
        r.add("life.openclaw.enabled", () -> "false");
        r.add("life.proactive.cron", () -> "-");
        r.add("life.logs.ingest-ms", () -> "86400000");
    }

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ReceiptService receipts;
    @Autowired
    HoldingService holdings;
    @Autowired
    OpsService ops;

    @BeforeEach
    void triggers() {
        jdbc.execute("""
                CREATE TRIGGER IF NOT EXISTS it_boom_item BEFORE INSERT ON receipt_items WHEN NEW.name = 'BOOM'
                BEGIN SELECT RAISE(ABORT, 'boom item'); END""");
        jdbc.execute("""
                CREATE TRIGGER IF NOT EXISTS it_boom_event BEFORE INSERT ON events WHEN NEW.payload_json = 'BOOMSYM'
                BEGIN SELECT RAISE(ABORT, 'boom event'); END""");
    }

    long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    static String uniq() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    static ReceiptPreviewRequest request(String merchant, List<ReceiptPreviewRequest.Line> lines) {
        int total = lines.stream().mapToInt(ReceiptPreviewRequest.Line::amountCents).sum();
        return new ReceiptPreviewRequest(merchant, uniq(), "2026-10-10 12:00", "JPY", total, 0, 0, lines, null, null, null);
    }

    static ReceiptPreviewRequest.Line food(String name, int cents) {
        return new ReceiptPreviewRequest.Line(name, 1.0, cents, true, FoodCategory.DAIRY);
    }

    @Test
    void schemaExists() {
        List<String> t = jdbc.queryForList("SELECT name FROM sqlite_master WHERE type='table'", String.class);
        assertTrue(t.containsAll(List.of("people", "merchants", "receipts", "receipt_items", "ai_calls")), t.toString());
    }

    @Test
    void receiptPreviewAndConfirm() {
        ReceiptPreviewResponse p = receipts.preview(request("Real-" + uniq(), List.of(food("milk", 100), food("egg", 50))), 1L);
        assertEquals(2, count("SELECT COUNT(*) FROM receipt_items WHERE receipt_id = ?", p.receiptId()));
        ReceiptConfirmResponse c = receipts.confirm(p.receiptId(), new ReceiptConfirmRequest(true), 1L);
        assertEquals(2, c.fridgeItemIds().size());
        assertEquals(c.fridgeItemIds(), jdbc.queryForList(
                "SELECT id FROM fridge_items WHERE source_receipt_id = ? ORDER BY id", Long.class, p.receiptId()));
    }

    @Test
    void receiptFailureMidWayLeavesNothing() {
        String m = "RealBoom-" + uniq();
        assertThrows(RuntimeException.class, () -> receipts.preview(
                request(m, List.of(food("milk", 100), food("BOOM", 100))), 1L));
        assertEquals(0, count("SELECT COUNT(*) FROM merchants WHERE name = ?", m));
    }

    @Test
    void holdingFailureAtEventLeavesNoHolding() {
        assertThrows(RuntimeException.class, () -> holdings.upsert(
                new HoldingUpsertRequest("BOOMSYM", Market.US, "Boom", 1.0, 1.0, "USD", null), 1L));
        assertEquals(0, count("SELECT COUNT(*) FROM holdings WHERE symbol = 'BOOMSYM'"));
    }

    @Test
    void recordAiReturnsTheRowId() {
        String corr = "it-" + uniq();
        Map<String, Object> out = ops.recordAi(Map.of("provider", "xai", "model", "grok-4",
                "prompt_tokens", 1, "correlation_id", corr), 1L);
        assertEquals(((Number) out.get("id")).longValue(),
                count("SELECT id FROM ai_calls WHERE correlation_id = ?", corr));
    }
}
