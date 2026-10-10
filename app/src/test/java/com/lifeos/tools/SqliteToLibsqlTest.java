package com.lifeos.tools;

import com.lifeos.testsupport.FakeTursoServer;
import com.lifeos.testsupport.TestDb;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteToLibsqlTest {

    Path source;
    Path targetFile;
    FakeTursoServer target;
    final PrintStream out = new PrintStream(new ByteArrayOutputStream());

    @BeforeEach
    void setUp() throws Exception {
        source = TestDb.createPlain();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + source); Statement st = c.createStatement()) {
            st.execute("PRAGMA foreign_keys=ON");
            st.execute("INSERT INTO people (id, handle, display_name, role) VALUES (2, 'wx-bob', 'Bob', 'member')");
            st.execute("INSERT INTO merchants (id, name, name_norm) VALUES (7, '全家', '全家')");
            st.execute("""
                    INSERT INTO receipts (id, merchant_id, payer_id, fingerprint, total_cents, status, raw_ocr_json)
                    VALUES (40, 7, 2, 'fp-1', 300, 'confirmed', '{"text":"it''s; ok"}')""");
            st.execute("INSERT INTO receipt_items (receipt_id, name, name_norm, qty, amount_cents, is_food) VALUES (40, '牛奶', '牛奶', 1, 300, 1)");
            st.execute("INSERT INTO events (domain, action, actor_id, entity_table, entity_id) VALUES ('finance','confirm',2,'receipts',40)");
            st.execute("INSERT INTO events (domain, action) VALUES ('system','gone')");
            st.execute("DELETE FROM events WHERE action = 'gone'"); // AUTOINCREMENT gap must survive
            for (int i = 0; i < 1200; i++) {
                st.execute("INSERT INTO http_requests (method, path, status, latency_ms) VALUES ('GET', '/p" + i + "', 200, " + i + ")");
            }
        }
        targetFile = Files.createTempFile("lifeos-target-", ".db");
        target = new FakeTursoServer(targetFile);
    }

    @AfterEach
    void tearDown() throws Exception {
        target.close();
        Files.deleteIfExists(targetFile);
    }

    SqliteToLibsql.Options opts(boolean dry, boolean wipe) {
        return new SqliteToLibsql.Options(source, target.url(), "t", dry, wipe);
    }

    static long q(Path db, String sql) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             ResultSet rs = c.createStatement().executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : -1;
        }
    }

    @Test
    void copiesEverythingIntoAnEmptyTargetPreservingIdsAndSequences() throws Exception {
        List<SqliteToLibsql.TableReport> report = SqliteToLibsql.run(opts(false, false), out);
        for (SqliteToLibsql.TableReport r : report) {
            assertEquals(r.sourceRows(), r.targetRowsAfter(), r.table());
            assertEquals(r.sourceRows(), q(targetFile, "SELECT COUNT(*) FROM \"" + r.table() + "\""), r.table());
        }
        assertEquals(40, q(targetFile, "SELECT id FROM receipts WHERE fingerprint = 'fp-1'"));
        assertEquals(1200, q(targetFile, "SELECT COUNT(*) FROM http_requests"));
        assertEquals(q(source, "SELECT seq FROM sqlite_sequence WHERE name = 'events'"),
                q(targetFile, "SELECT seq FROM sqlite_sequence WHERE name = 'events'"));
        // indexes and views came along
        assertTrue(q(targetFile, "SELECT COUNT(*) FROM sqlite_master WHERE name = 'idx_merchants_name_norm'") == 1);
        assertTrue(q(targetFile, "SELECT COUNT(*) FROM sqlite_master WHERE type = 'view' AND name = 'v_ai_daily'") == 1);
        // one transaction
        assertEquals(1, target.executed.stream().filter(s -> s.startsWith("BEGIN")).count());
        assertTrue(target.executed.contains("COMMIT"));
    }

    @Test
    void refusesANonEmptyTargetUnlessWipe() throws Exception {
        SqliteToLibsql.run(opts(false, false), out);
        SqliteToLibsql.MigrationException e = assertThrows(SqliteToLibsql.MigrationException.class,
                () -> SqliteToLibsql.run(opts(false, false), out));
        assertTrue(e.getMessage().contains("--wipe-target"), e.getMessage());

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + targetFile)) {
            c.createStatement().execute("INSERT INTO http_requests (method, path) VALUES ('GET', '/extra')");
        }
        SqliteToLibsql.run(opts(false, true), out);
        assertEquals(1200, q(targetFile, "SELECT COUNT(*) FROM http_requests"));
    }

    @Test
    void targetInitializedByTheAppNeedsWipeAndThenMatchesTheSource() throws Exception {
        Files.delete(targetFile);
        Files.copy(TestDb.createPlain(), targetFile); // schema + seed rows, like after first app start
        assertThrows(SqliteToLibsql.MigrationException.class, () -> SqliteToLibsql.run(opts(false, false), out));
        SqliteToLibsql.run(opts(false, true), out);
        assertEquals(q(source, "SELECT COUNT(*) FROM people"), q(targetFile, "SELECT COUNT(*) FROM people"));
        assertEquals(1, q(targetFile, "SELECT COUNT(*) FROM people WHERE handle = 'wx-bob'"));
    }

    @Test
    void dryRunVerifiesAndLeavesTheTargetUnchanged() throws Exception {
        SqliteToLibsql.run(opts(true, false), out);
        assertEquals(0, q(targetFile, "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'people'"));
        assertTrue(target.executed.contains("ROLLBACK"));
    }

    @Test
    void failureMidCopyLeavesTheTargetUntouched() throws Exception {
        Files.delete(targetFile);
        Files.copy(TestDb.createPlain(), targetFile);
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + targetFile)) {
            c.createStatement().execute("""
                    CREATE TRIGGER boom BEFORE INSERT ON http_requests WHEN NEW.path = '/p900'
                    BEGIN SELECT RAISE(ABORT, 'boom'); END""");
        }
        long peopleBefore = q(targetFile, "SELECT COUNT(*) FROM people");
        assertThrows(Exception.class, () -> SqliteToLibsql.run(opts(false, true), out));
        assertEquals(peopleBefore, q(targetFile, "SELECT COUNT(*) FROM people"), "wipe rolled back too");
        assertEquals(0, q(targetFile, "SELECT COUNT(*) FROM http_requests"));
    }

    @Test
    void missingSourceFileIsRejected() {
        SqliteToLibsql.Options o = new SqliteToLibsql.Options(Path.of("/nope/life.db"), target.url(), "t", false, false);
        assertThrows(SqliteToLibsql.MigrationException.class, () -> SqliteToLibsql.run(o, out));
    }

    @Test
    void parseNeedsSourceAndTarget() {
        assertThrows(IllegalArgumentException.class, () -> SqliteToLibsql.parse(new String[]{"--dry-run"}));
        SqliteToLibsql.Options o = SqliteToLibsql.parse(new String[]{"x.db", "--target-url", "jdbc:libsql:http://h:1", "--wipe-target"});
        assertEquals("jdbc:libsql:http://h:1", o.targetUrl());
        assertTrue(o.wipeTarget());
    }
}
