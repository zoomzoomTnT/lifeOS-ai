package com.lifeos.db.libsql;

import com.lifeos.testsupport.FakeTursoServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Driver behaviour against the in-process Hrana server. Covers what the old no-op wrapper got
 * wrong: auto-commit, real commit/rollback, every prepareStatement overload, unwrap.
 */
class LibSqlConnectionTest {

    Path file;
    FakeTursoServer server;
    final LibSqlHttpDriver driver = new LibSqlHttpDriver();

    @BeforeEach
    void setUp() throws Exception {
        file = Files.createTempFile("libsql-conn-", ".db");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE p (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT UNIQUE, score REAL, data BLOB)");
            st.execute("CREATE TABLE c (id INTEGER PRIMARY KEY, pid INTEGER NOT NULL REFERENCES p(id))");
            st.execute("""
                    CREATE TRIGGER c_rollback BEFORE INSERT ON c WHEN NEW.pid = -1
                    BEGIN SELECT RAISE(ROLLBACK, 'server-side rollback'); END""");
        }
        server = new FakeTursoServer(file);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.close();
        Files.deleteIfExists(file);
    }

    Connection open() throws SQLException {
        Properties p = new Properties();
        p.setProperty("password", "t");
        return driver.connect(server.url(), p);
    }

    long count(String table) throws SQLException {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file);
             ResultSet rs = c.createStatement().executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    void driverAcceptsOnlyLibSqlUrls() throws SQLException {
        assertTrue(driver.acceptsURL(server.url()));
        assertTrue(driver.acceptsURL("jdbc:dbeaver:libsql:https://x.turso.io"));
        assertFalse(driver.acceptsURL("jdbc:sqlite:/tmp/x.db"));
        assertNull(driver.connect("jdbc:sqlite:/tmp/x.db", new Properties()));
    }

    @Test
    void remoteUrlWithoutTokenFailsWithClearMessage() {
        SQLException e = assertThrows(SQLException.class,
                () -> driver.connect("libsql://nope.turso.io", new Properties()));
        assertTrue(e.getMessage().contains("TURSO_AUTH_TOKEN"), e.getMessage());
    }

    @Test
    void wrongTokenFailsWithClearMessage() throws SQLException {
        server.requireToken("right");
        Properties p = new Properties();
        p.setProperty("password", "wrong");
        try (Connection c = driver.connect(server.url(), p)) {
            SQLException e = assertThrows(SQLException.class, () -> c.createStatement().execute("SELECT 1"));
            assertTrue(e.getMessage().contains("TURSO_AUTH_TOKEN"), e.getMessage());
        }
    }

    @Test
    void autoCommitIsOnByDefaultAndEachStatementCommits() throws SQLException {
        try (Connection c = open()) {
            assertTrue(c.getAutoCommit());
            c.createStatement().executeUpdate("INSERT INTO p(name) VALUES ('a')");
            assertEquals(1, count("p"));
            c.commit();   // no-op in auto-commit mode
            c.rollback(); // no-op, does not undo
            assertEquals(1, count("p"));
            assertEquals(0, server.openStreams());
        }
    }

    @Test
    void setAutoCommitFalseIsReportedAndOpensARealTransaction() throws SQLException {
        try (Connection c = open()) {
            c.setAutoCommit(false);
            assertFalse(c.getAutoCommit());
            c.createStatement().executeUpdate("INSERT INTO p(name) VALUES ('a')");
            c.createStatement().executeUpdate("INSERT INTO p(name) VALUES ('b')");
            assertEquals(0, count("p"), "not visible before commit");
            // the transaction lives on ONE server stream across requests
            assertEquals(1, server.openStreams());
            c.commit();
            assertEquals(2, count("p"));
            assertEquals(0, server.openStreams());
            assertTrue(server.executed.contains("BEGIN IMMEDIATE"));
            assertTrue(server.executed.contains("COMMIT"));
        }
    }

    @Test
    void rollbackUndoesEveryStatementOfTheTransaction() throws SQLException {
        try (Connection c = open()) {
            c.setAutoCommit(false);
            c.createStatement().executeUpdate("INSERT INTO p(name) VALUES ('a')");
            c.createStatement().executeUpdate("INSERT INTO p(name) VALUES ('b')");
            c.rollback();
            assertEquals(0, count("p"));
            assertTrue(server.executed.contains("ROLLBACK"));
            // the connection is usable for the next transaction
            c.createStatement().executeUpdate("INSERT INTO p(name) VALUES ('c')");
            c.commit();
            assertEquals(1, count("p"));
        }
    }

    @Test
    void failedStatementMidTransactionThenRollbackLeavesNothing() throws SQLException {
        try (Connection c = open()) {
            c.setAutoCommit(false);
            c.createStatement().executeUpdate("INSERT INTO p(name) VALUES ('a')");
            assertThrows(SQLException.class, () -> c.createStatement().executeUpdate("INSERT INTO p(name) VALUES ('a')"));
            c.rollback();
            assertEquals(0, count("p"));
        }
    }

    @Test
    void serverSideRollbackMakesCommitFail() throws SQLException {
        try (Connection c = open()) {
            c.setAutoCommit(false);
            c.createStatement().executeUpdate("INSERT INTO p(name) VALUES ('a')");
            assertThrows(SQLException.class, () -> c.createStatement().executeUpdate("INSERT INTO c(id, pid) VALUES (1, -1)"));
            SQLException next = assertThrows(SQLException.class,
                    () -> c.createStatement().executeUpdate("INSERT INTO p(name) VALUES ('b')"));
            assertTrue(next.getMessage().contains("aborted"), next.getMessage());
            SQLException e = assertThrows(SQLException.class, c::commit);
            assertTrue(e.getMessage().contains("nothing was committed"), e.getMessage());
            assertEquals(0, count("p"));
        }
    }

    @Test
    void httpFailureMidTransactionNeverHalfCommits() throws SQLException {
        try (Connection c = open()) {
            c.setAutoCommit(false);
            c.createStatement().executeUpdate("INSERT INTO p(name) VALUES ('a')");
            server.failNextRequests.set(1);
            assertThrows(SQLException.class, () -> c.createStatement().executeUpdate("INSERT INTO p(name) VALUES ('b')"));
            assertThrows(SQLException.class, c::commit);
            server.expireAllStreams(); // what the server does with the abandoned stream
            assertEquals(0, count("p"));
        }
    }

    @Test
    void expiredStreamMakesTheTransactionFail() throws SQLException {
        try (Connection c = open()) {
            c.setAutoCommit(false);
            c.createStatement().executeUpdate("INSERT INTO p(name) VALUES ('a')");
            server.expireAllStreams();
            assertThrows(SQLException.class, () -> c.createStatement().executeUpdate("INSERT INTO p(name) VALUES ('b')"));
            assertThrows(SQLException.class, c::commit);
            assertEquals(0, count("p"));
        }
    }

    @Test
    void readOnlyTransactionUsesDeferredBegin() throws SQLException {
        try (Connection c = open()) {
            c.setReadOnly(true);
            c.setAutoCommit(false);
            c.createStatement().executeQuery("SELECT COUNT(*) FROM p").close();
            c.commit();
            assertTrue(server.executed.contains("BEGIN DEFERRED"));
            assertFalse(server.executed.contains("BEGIN IMMEDIATE"));
        }
    }

    @Test
    void transactionWithoutStatementsSendsNothing() throws SQLException {
        try (Connection c = open()) {
            c.setAutoCommit(false);
            c.commit();
            c.rollback();
            assertEquals(0, server.pipelineRequests.get());
        }
    }

    @Test
    void switchingAutoCommitBackOnCommits() throws SQLException {
        try (Connection c = open()) {
            c.setAutoCommit(false);
            c.createStatement().executeUpdate("INSERT INTO p(name) VALUES ('a')");
            c.setAutoCommit(true);
            assertEquals(1, count("p"));
        }
    }

    @Test
    void closeRollsBackAnOpenTransaction() throws SQLException {
        Connection c = open();
        c.setAutoCommit(false);
        c.createStatement().executeUpdate("INSERT INTO p(name) VALUES ('a')");
        c.close();
        assertTrue(c.isClosed());
        assertEquals(0, count("p"));
        assertEquals(0, server.openStreams());
    }

    @Test
    void foreignKeysAreEnforcedOnEveryStream() throws SQLException {
        // The fake (like Turso) starts each stream with foreign_keys OFF.
        try (Connection c = open()) {
            assertThrows(SQLException.class, () -> c.createStatement().executeUpdate("INSERT INTO c(id, pid) VALUES (1, 999)"));
            c.setAutoCommit(false);
            assertThrows(SQLException.class, () -> c.createStatement().executeUpdate("INSERT INTO c(id, pid) VALUES (2, 999)"));
            c.rollback();
        }
        assertEquals(0, count("c"));
    }

    @Test
    void returnGeneratedKeysReturnsTheRowid() throws SQLException {
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement("INSERT INTO p(name) VALUES (?)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, "x");
            assertEquals(1, ps.executeUpdate());
            try (ResultSet keys = ps.getGeneratedKeys()) {
                assertTrue(keys.next());
                assertEquals(1L, keys.getLong(1));
                assertEquals(1L, keys.getLong("id"));
            }
            ps.setString(1, "y");
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                assertTrue(keys.next());
                assertEquals(2L, keys.getLong(1));
            }
        }
    }

    @Test
    void columnIndexAndNameKeyOverloadsReturnTheRowid() throws SQLException {
        try (Connection c = open()) {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO p(name) VALUES ('a')", new int[]{1})) {
                ps.executeUpdate();
                ResultSet keys = ps.getGeneratedKeys();
                assertTrue(keys.next());
                assertEquals(1L, keys.getLong(1));
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO p(name) VALUES ('b')", new String[]{"ID"})) {
                ps.executeUpdate();
                ResultSet keys = ps.getGeneratedKeys();
                assertTrue(keys.next());
                assertEquals(2L, keys.getLong("ID"));
            }
            assertThrows(SQLFeatureNotSupportedException.class,
                    () -> c.prepareStatement("INSERT INTO p(name) VALUES ('c')", new String[]{"id", "name"}));
        }
    }

    @Test
    void noKeysUnlessRequested() throws SQLException {
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement("INSERT INTO p(name) VALUES ('a')")) {
            ps.executeUpdate();
            assertFalse(ps.getGeneratedKeys().next());
        }
    }

    @Test
    void threeAndFourArgPrepareStatementWorkForReadOnlyAndThrowForUpdatable() throws SQLException {
        try (Connection c = open()) {
            c.createStatement().executeUpdate("INSERT INTO p(name) VALUES ('a')");
            try (PreparedStatement ps = c.prepareStatement("SELECT name FROM p WHERE id = ?",
                    ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
                ps.setLong(1, 1);
                ResultSet rs = ps.executeQuery();
                assertTrue(rs.next());
                assertEquals("a", rs.getString("name"));
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT name FROM p", ResultSet.TYPE_SCROLL_INSENSITIVE,
                    ResultSet.CONCUR_READ_ONLY, ResultSet.CLOSE_CURSORS_AT_COMMIT)) {
                ResultSet rs = ps.executeQuery();
                assertTrue(rs.last());
                assertEquals(1, rs.getRow());
            }
            assertThrows(SQLFeatureNotSupportedException.class, () -> c.prepareStatement("SELECT 1",
                    ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_UPDATABLE));
            assertThrows(SQLFeatureNotSupportedException.class, () -> c.prepareStatement("SELECT 1",
                    ResultSet.TYPE_SCROLL_SENSITIVE, ResultSet.CONCUR_READ_ONLY, ResultSet.HOLD_CURSORS_OVER_COMMIT));
            assertThrows(SQLFeatureNotSupportedException.class, () -> c.prepareCall("SELECT 1"));
        }
    }

    @Test
    void returningRowsComeBackFromExecuteQuery() throws SQLException {
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement("INSERT INTO p(name, score) VALUES (?, ?) RETURNING id, score")) {
            ps.setString(1, "r");
            ps.setDouble(2, 1.5);
            ResultSet rs = ps.executeQuery();
            assertTrue(rs.next());
            assertEquals(1L, rs.getLong("id"));
            assertEquals(1.5, rs.getDouble(2));
            assertEquals(1, count("p"));
        }
    }

    @Test
    void valueTypesRoundTrip() throws SQLException {
        try (Connection c = open()) {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO p(name, score, data) VALUES (?, ?, ?)")) {
                ps.setString(1, "日本語 'quote'");
                ps.setNull(2, java.sql.Types.DOUBLE);
                ps.setBytes(3, new byte[]{1, 2, 3});
                ps.executeUpdate();
            }
            ResultSet rs = c.createStatement().executeQuery("SELECT id, name, score, data FROM p");
            assertTrue(rs.next());
            assertEquals(1L, rs.getObject(1));
            assertEquals(Integer.valueOf(1), rs.getObject("id", Integer.class));
            assertEquals("日本語 'quote'", rs.getString(2));
            assertEquals(0.0, rs.getDouble(3));
            assertTrue(rs.wasNull());
            assertEquals(List.of((byte) 1, (byte) 2, (byte) 3), toList(rs.getBytes(4)));
            assertEquals("id", rs.getMetaData().getColumnLabel(1));
            assertEquals(4, rs.getMetaData().getColumnCount());
            assertFalse(rs.next());
        }
    }

    private static List<Byte> toList(byte[] b) {
        Byte[] out = new Byte[b.length];
        for (int i = 0; i < b.length; i++) out[i] = b[i];
        return List.of(out);
    }

    @Test
    void batchExecutesEveryRowInTheTransaction() throws SQLException {
        try (Connection c = open()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO p(name) VALUES (?)")) {
                for (String n : List.of("a", "b", "c")) {
                    ps.setString(1, n);
                    ps.addBatch();
                }
                int[] counts = ps.executeBatch();
                assertEquals(3, counts.length);
            }
            c.commit();
            assertEquals(3, count("p"));
        }
    }

    @Test
    void unwrapAndIsWrapperFor() throws SQLException {
        try (Connection c = open()) {
            assertTrue(c.isWrapperFor(Connection.class));
            assertSame(c, c.unwrap(Connection.class));
            assertTrue(c.isWrapperFor(LibSqlConnection.class));
            assertTrue(c.unwrap(LibSqlConnection.class) instanceof LibSqlConnection);
            assertFalse(c.isWrapperFor(java.sql.Array.class));
            assertThrows(SQLException.class, () -> c.unwrap(java.sql.Array.class));
            Statement st = c.createStatement();
            assertSame(st, st.unwrap(Statement.class));
            assertSame(c, st.getConnection());
        }
    }

    @Test
    void savepointsAreRejectedLoudly() throws SQLException {
        try (Connection c = open()) {
            c.setAutoCommit(false);
            assertThrows(SQLFeatureNotSupportedException.class, c::setSavepoint);
        }
    }

    @Test
    void metadataIdentifiesSqlite() throws SQLException {
        try (Connection c = open()) {
            assertEquals("SQLite", c.getMetaData().getDatabaseProductName());
            assertTrue(c.getMetaData().supportsTransactions());
            assertTrue(c.isValid(1));
            assertEquals(Connection.TRANSACTION_SERIALIZABLE, c.getTransactionIsolation());
        }
    }

    // ---------------------------------------------------------------- QA round 2

    @Test
    void networkTimeoutZeroRestoresTheDefault() throws SQLException {
        server.slowSql = sql -> sql.equals("SELECT 'slow'");
        server.slowMs = 1500;
        try (Connection c = open()) {
            c.setNetworkTimeout(Runnable::run, 1000);
            assertThrows(SQLException.class, () -> c.createStatement().executeQuery("SELECT 'slow'"));
            c.setNetworkTimeout(Runnable::run, 0);
            assertEquals(0, c.getNetworkTimeout());
            ResultSet rs = c.createStatement().executeQuery("SELECT 'slow'");
            assertTrue(rs.next());
        }
    }

    @Test
    void pooledStatementLongerThanHikariValidationTimeoutSucceeds() throws Exception {
        server.slowSql = sql -> sql.equals("SELECT 'slow'");
        server.slowMs = 6500; // > Hikari's 5 s validationTimeout
        com.zaxxer.hikari.HikariConfig cfg = new com.zaxxer.hikari.HikariConfig();
        cfg.setDriverClassName(LibSqlHttpDriver.class.getName());
        cfg.setJdbcUrl(server.url());
        cfg.setPassword("t");
        cfg.setMaximumPoolSize(1);
        cfg.setValidationTimeout(5000);
        try (com.zaxxer.hikari.HikariDataSource ds = new com.zaxxer.hikari.HikariDataSource(cfg)) {
            try (Connection c = ds.getConnection()) {
                c.createStatement().execute("SELECT 1");
            }
            Thread.sleep(700); // past Hikari's 500 ms bypass window: next borrow runs isValid()
            try (Connection c = ds.getConnection()) {
                long t0 = System.nanoTime();
                ResultSet rs = c.createStatement().executeQuery("SELECT 'slow'");
                assertTrue(rs.next());
                assertTrue(System.nanoTime() - t0 >= 6_000_000_000L);
            }
            try (Connection c = ds.getConnection()) { // still healthy, not evicted
                assertTrue(c.createStatement().executeQuery("SELECT 1").next());
            }
        }
    }

    @Test
    void failingBeginNeverLetsTheStatementAutoCommit() throws SQLException {
        server.failSql = sql -> sql.startsWith("BEGIN");
        try (Connection c = open()) {
            c.setAutoCommit(false);
            SQLException e = assertThrows(SQLException.class,
                    () -> c.createStatement().executeUpdate("INSERT INTO p(name) VALUES ('a')"));
            assertTrue(e.getMessage().contains("BEGIN"), e.getMessage());
            assertEquals(0, count("p"), "the INSERT must not run outside the transaction");
            assertFalse(server.executed.contains("INSERT INTO p(name) VALUES ('a')"));
            assertEquals(0, server.openStreams());
            server.failSql = sql -> false;
            c.createStatement().executeUpdate("INSERT INTO p(name) VALUES ('b')");
            c.commit();
            assertEquals(1, count("p"));
        }
    }

    @Test
    void streamExpiredOnCommitIsADefiniteRollback() throws SQLException {
        try (Connection c = open()) {
            c.setAutoCommit(false);
            c.createStatement().executeUpdate("INSERT INTO p(name) VALUES ('a')");
            server.expireAllStreams();
            SQLException e = assertThrows(SQLException.class, c::commit);
            assertEquals("40000", e.getSQLState(), e.getMessage());
            assertTrue(e.getMessage().contains("rolled back"), e.getMessage());
            assertEquals(0, count("p"));
        }
    }

    @Test
    void plainStatementGeneratedKeys() throws SQLException {
        try (Connection c = open(); Statement st = c.createStatement()) {
            assertEquals(1, st.executeUpdate("INSERT INTO p(name) VALUES ('a')", Statement.RETURN_GENERATED_KEYS));
            ResultSet k = st.getGeneratedKeys();
            assertTrue(k.next());
            assertEquals(1L, k.getLong(1));
            st.execute("INSERT INTO p(name) VALUES ('b')", new String[]{"ID"});
            k = st.getGeneratedKeys();
            assertTrue(k.next());
            assertEquals(2L, k.getLong("ID"));
            st.executeUpdate("INSERT INTO p(name) VALUES ('c')");
            assertFalse(st.getGeneratedKeys().next(), "no keys unless requested");
        }
    }

    @Test
    void narrowingGettersThrowInsteadOfOverflowing() throws SQLException {
        try (Connection c = open()) {
            ResultSet rs = c.createStatement().executeQuery("SELECT 1099511627776, 70000, 1e300");
            assertTrue(rs.next());
            assertEquals(1099511627776L, rs.getLong(1));
            SQLException e = assertThrows(SQLException.class, () -> rs.getInt(1));
            assertEquals("22003", e.getSQLState());
            assertEquals(70000, rs.getInt(2));
            assertThrows(SQLException.class, () -> rs.getShort(2));
            assertThrows(SQLException.class, () -> rs.getLong(3));
        }
    }

    @Test
    void dateTimesAreZoneCorrectAndKeepSecondsOnANonUtcJvm() throws SQLException {
        java.util.TimeZone old = java.util.TimeZone.getDefault();
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Tokyo"));
        try (Connection c = open()) {
            c.createStatement().execute("CREATE TABLE t (a TEXT, b TEXT, d TEXT, tm TEXT, o TEXT)");
            java.time.Instant at = java.time.Instant.parse("2026-10-10T03:00:00Z");
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO t VALUES (?, ?, ?, ?, ?)")) {
                ps.setTimestamp(1, java.sql.Timestamp.from(at));
                ps.setObject(2, java.time.LocalDateTime.of(2026, 10, 10, 12, 0, 0));
                ps.setObject(3, java.time.LocalDate.of(2026, 10, 10));
                ps.setTime(4, java.sql.Time.valueOf(java.time.LocalTime.of(12, 0, 0)));
                ps.setObject(5, java.time.OffsetDateTime.of(2026, 10, 10, 12, 0, 0, 0, java.time.ZoneOffset.ofHours(9)));
                ps.executeUpdate();
            }
            ResultSet rs = c.createStatement().executeQuery("SELECT a, b, d, tm, o FROM t");
            assertTrue(rs.next());
            assertEquals("2026-10-10T03:00:00Z", rs.getString(1));
            assertEquals("2026-10-10T12:00:00", rs.getString(2));
            assertEquals("12:00:00", rs.getString(4));
            assertEquals("2026-10-10T12:00:00+09:00", rs.getString(5));
            assertEquals(at, rs.getTimestamp(1).toInstant(), "…Z read as an instant, not in the JVM zone");
            assertEquals(at, rs.getTimestamp(5).toInstant());
            assertEquals(at, rs.getObject(1, java.time.Instant.class));
            assertEquals(java.time.LocalDateTime.of(2026, 10, 10, 12, 0), rs.getObject(2, java.time.LocalDateTime.class));
            assertEquals(java.time.LocalDate.of(2026, 10, 10), rs.getDate(3).toLocalDate());
            assertEquals(java.time.LocalTime.of(12, 0), rs.getTime(4).toLocalTime());
            // zoneless text: UTC by default, the Calendar's zone when given
            assertEquals(java.time.Instant.parse("2026-10-10T12:00:00Z"), rs.getTimestamp(2).toInstant());
            java.util.Calendar tokyo = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Tokyo"));
            assertEquals(at, rs.getTimestamp(2, tokyo).toInstant());
        } finally {
            java.util.TimeZone.setDefault(old);
        }
    }
}
