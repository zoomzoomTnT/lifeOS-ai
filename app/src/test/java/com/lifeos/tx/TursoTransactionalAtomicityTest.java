package com.lifeos.tx;

import com.lifeos.domain.Market;
import com.lifeos.service.HoldingService;
import com.lifeos.testsupport.TursoSpringTestBase;
import com.lifeos.web.dto.HoldingUpsertRequest;
import com.lifeos.web.dto.HoldingWriteResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * @Transactional on the Turso path is atomic (QA blocker 1): a failure after earlier writes in the
 * same transaction leaves no rows behind — through Spring's JPA transaction manager, Hikari and
 * the libSQL driver.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class TursoTransactionalAtomicityTest extends TursoSpringTestBase {

    @Autowired
    HoldingService holdings;
    @Autowired
    TransactionTemplate tx;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void holdingUpsertFailingAtTheEventInsertLeavesNoHolding() throws Exception {
        // holding INSERT (JPA) succeeds, then the events INSERT (JDBC) fails via test trigger
        assertThrows(RuntimeException.class, () -> holdings.upsert(
                new HoldingUpsertRequest("BOOMSYM", Market.US, "Boom Inc", 1.0, 10.0, "USD", null), 1L));
        assertEquals(0, count("SELECT COUNT(*) FROM holdings WHERE symbol = 'BOOMSYM'"));
        assertEquals(0, count("SELECT COUNT(*) FROM events WHERE payload_json = 'BOOMSYM'"));
    }

    @Test
    void holdingUpsertCommitsHoldingAndEventTogether() throws Exception {
        String sym = "S" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        HoldingWriteResponse w = holdings.upsert(new HoldingUpsertRequest(sym, Market.US, "X", 2.0, 5.0, "USD", null), 1L);
        assertEquals(1, count("SELECT COUNT(*) FROM holdings WHERE id = ? AND symbol = ?", w.id(), sym));
        assertEquals(1, count("SELECT COUNT(*) FROM events WHERE entity_table = 'holdings' AND entity_id = ?", w.id()));
    }

    @Test
    void exceptionAfterWritesRollsBackEverything() throws Exception {
        String tag = "rb-" + UUID.randomUUID();
        assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(s -> {
            jdbc.update("INSERT INTO events (domain, action, payload_json) VALUES ('system','test',?)", tag);
            jdbc.update("INSERT INTO events (domain, action, payload_json) VALUES ('system','test',?)", tag);
            throw new IllegalStateException("fail after two writes");
        }));
        assertEquals(0, count("SELECT COUNT(*) FROM events WHERE payload_json = ?", tag));
    }
}
