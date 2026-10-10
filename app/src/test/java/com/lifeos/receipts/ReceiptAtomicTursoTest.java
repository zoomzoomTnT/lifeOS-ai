package com.lifeos.receipts;

import com.lifeos.testsupport.FakeTursoServer;
import com.lifeos.testsupport.TestDb;
import com.lifeos.web.dto.ReceiptConfirmRequest;
import com.lifeos.web.dto.ReceiptPreviewResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Default (Turso) profile against {@link FakeTursoServer}: the app talks Hrana HTTP through the
 * libSQL driver, and each @Transactional receipt write is ONE server-side transaction.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ReceiptAtomicTursoTest extends AbstractReceiptAtomicTest {

    static final Path DB;
    static final FakeTursoServer TURSO;

    static {
        try {
            DB = TestDb.create();
            TURSO = new FakeTursoServer(DB);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", TURSO::url);
        r.add("spring.datasource.password", () -> "test-token");
        r.add("life.openclaw.enabled", () -> "false");
        r.add("life.proactive.cron", () -> "-");
        r.add("life.logs.ingest-ms", () -> "86400000");
    }

    @AfterAll
    static void stop() {
        TURSO.close();
    }

    @Override
    Path dbFile() {
        return DB;
    }

    @Test
    void eachReceiptWriteIsOneServerTransaction() {
        int before = TURSO.executed.size();
        ReceiptPreviewResponse p = receipts.preview(
                request("One-" + uniq(), uniq(), List.of(food("egg", 100), food("rice", 200))), "owner");
        List<String> sql = TURSO.executed.subList(before, TURSO.executed.size());
        assertEquals(1, sql.stream().filter(s -> s.startsWith("BEGIN")).count(), "one BEGIN: " + sql);
        assertEquals("BEGIN IMMEDIATE", sql.stream().filter(s -> s.startsWith("BEGIN")).findFirst().orElseThrow());
        assertEquals("COMMIT", sql.get(sql.size() - 1));
        assertTrue(sql.stream().anyMatch(s -> s.contains("INSERT INTO receipt_items")));

        before = TURSO.executed.size();
        receipts.confirm(p.receiptId(), new ReceiptConfirmRequest(true), "owner");
        sql = TURSO.executed.subList(before, TURSO.executed.size());
        assertEquals(1, sql.stream().filter(s -> s.startsWith("BEGIN")).count(), "one BEGIN: " + sql);
        assertEquals("COMMIT", sql.get(sql.size() - 1));
        assertEquals(0, TURSO.openStreams(), "no stream left open after commit");
    }
}
