package com.lifeos.repo.batch;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TursoAtomicSqlBatchTest {

    @Test
    void httpBaseFromJdbcOrLibsqlUrl() {
        assertEquals("https://db-org.turso.io",
                TursoAtomicSqlBatch.httpBase("jdbc:dbeaver:libsql:https://db-org.turso.io/"));
        assertEquals("https://db-org.turso.io", TursoAtomicSqlBatch.httpBase("libsql://db-org.turso.io"));
        assertEquals("http://127.0.0.1:8080", TursoAtomicSqlBatch.httpBase("jdbc:dbeaver:libsql:http://127.0.0.1:8080"));
    }

    @Test
    void batchIsBeginStepsCommitRollbackThenClose() {
        var b = new TursoAtomicSqlBatch("libsql://x.turso.io", "t");
        String body = b.requestBody(List.of(SqlStep.of("INSERT INTO t VALUES (?)", 1),
                SqlStep.of("INSERT INTO t VALUES (?)", "a"))).toString();
        assertTrue(body.contains("\"sql\":\"BEGIN IMMEDIATE\""), body);
        assertTrue(body.contains("\"sql\":\"COMMIT\""), body);
        assertTrue(body.contains("\"sql\":\"ROLLBACK\""), body);
        assertTrue(body.contains("{\"type\":\"close\"}"), body);
        assertTrue(body.contains("\"type\":\"is_autocommit\""), body);
    }
}
