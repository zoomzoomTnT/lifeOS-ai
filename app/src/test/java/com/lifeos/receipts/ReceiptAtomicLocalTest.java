package com.lifeos.receipts;

import com.lifeos.testsupport.TestDb;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;

/** Local profile: SQLite file + normal Spring/JDBC transaction. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
class ReceiptAtomicLocalTest extends AbstractReceiptAtomicTest {

    static final Path DB;

    static {
        try {
            DB = TestDb.create();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> "jdbc:sqlite:" + DB);
        r.add("life.proactive.cron", () -> "-");
        r.add("life.logs.ingest-ms", () -> "86400000");
    }

    @Override
    Path dbFile() {
        return DB;
    }
}
