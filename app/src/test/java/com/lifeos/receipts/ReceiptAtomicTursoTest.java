package com.lifeos.receipts;

import com.lifeos.service.ReceiptService;
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

/**
 * Default (Turso) profile against {@link FakeTursoServer}: the app talks HTTP through the real
 * libSQL JDBC driver, and receipt writes go through one {@code /v3/pipeline} batch each.
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
    void eachMultiStepWriteIsOneHttpBatch() {
        int before = TURSO.pipelineRequests.get();
        ReceiptPreviewResponse p = receipts.preview(
                request("One-" + uniq(), uniq(), List.of(food("egg", 100), food("rice", 200))), "owner");
        assertEquals(before + 1, TURSO.pipelineRequests.get(), "preview = one batch request");
        receipts.confirm(p.receiptId(), new ReceiptConfirmRequest(true), "owner");
        assertEquals(before + 2, TURSO.pipelineRequests.get(), "confirm = one batch request");
    }
}
