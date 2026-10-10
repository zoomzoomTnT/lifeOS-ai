package com.lifeos.db.libsql;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The driver against a REAL libSQL server (sqld). CI starts one as a service container and sets
 * LIBSQL_TEST_URL (e.g. jdbc:libsql:http://localhost:8080); locally:
 * {@code sqld --http-listen-addr 127.0.0.1:8080 & LIBSQL_TEST_URL=jdbc:libsql:http://127.0.0.1:8080 mvn test}.
 */
@EnabledIfEnvironmentVariable(named = "LIBSQL_TEST_URL", matches = ".+")
class LibSqlRealServerTest {

    static final String URL = System.getenv("LIBSQL_TEST_URL");
    final LibSqlHttpDriver driver = new LibSqlHttpDriver();
    String p;
    String c;

    Connection open() throws SQLException {
        Properties props = new Properties();
        String token = System.getenv("LIBSQL_TEST_TOKEN");
        if (token != null) props.setProperty("password", token);
        return driver.connect(URL, props);
    }

    long count(String table) throws SQLException {
        try (Connection conn = open(); ResultSet rs = conn.createStatement().executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @BeforeEach
    void tables() throws SQLException {
        String s = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        p = "it_p_" + s;
        c = "it_c_" + s;
        try (Connection conn = open(); Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE " + p + " (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT UNIQUE)");
            st.execute("CREATE TABLE " + c + " (id INTEGER PRIMARY KEY, pid INTEGER NOT NULL REFERENCES " + p + "(id))");
            st.execute("CREATE TRIGGER " + c + "_rb BEFORE INSERT ON " + c
                    + " WHEN NEW.pid = -1 BEGIN SELECT RAISE(ROLLBACK, 'server-side rollback'); END");
        }
    }

    @Test
    void commitSpansSeveralHttpRequests() throws SQLException {
        try (Connection conn = open()) {
            conn.setAutoCommit(false);
            conn.createStatement().executeUpdate("INSERT INTO " + p + "(name) VALUES ('a')");
            conn.createStatement().executeUpdate("INSERT INTO " + p + "(name) VALUES ('b')");
            assertEquals(0, count(p), "uncommitted rows are invisible to other connections");
            conn.commit();
        }
        assertEquals(2, count(p));
    }

    @Test
    void rollbackDiscardsEverything() throws SQLException {
        try (Connection conn = open()) {
            conn.setAutoCommit(false);
            conn.createStatement().executeUpdate("INSERT INTO " + p + "(name) VALUES ('a')");
            assertThrows(SQLException.class,
                    () -> conn.createStatement().executeUpdate("INSERT INTO " + p + "(name) VALUES ('a')"));
            conn.rollback();
        }
        assertEquals(0, count(p));
    }

    @Test
    void serverSideRollbackIsDetected() throws SQLException {
        try (Connection conn = open()) {
            conn.setAutoCommit(false);
            conn.createStatement().executeUpdate("INSERT INTO " + p + "(name) VALUES ('a')");
            assertThrows(SQLException.class,
                    () -> conn.createStatement().executeUpdate("INSERT INTO " + c + "(id, pid) VALUES (1, -1)"));
            assertThrows(SQLException.class, conn::commit);
        }
        assertEquals(0, count(p));
    }

    @Test
    void foreignKeysAreOnInAutoCommitAndInTransactions() throws SQLException {
        try (Connection conn = open()) {
            ResultSet rs = conn.createStatement().executeQuery("PRAGMA foreign_keys");
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
            assertThrows(SQLException.class,
                    () -> conn.createStatement().executeUpdate("INSERT INTO " + c + "(id, pid) VALUES (1, 999)"));
            conn.setAutoCommit(false);
            rs = conn.createStatement().executeQuery("PRAGMA foreign_keys");
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
            assertThrows(SQLException.class,
                    () -> conn.createStatement().executeUpdate("INSERT INTO " + c + "(id, pid) VALUES (2, 999)"));
            conn.rollback();
        }
        assertEquals(0, count(c));
    }

    @Test
    void generatedKeysAndReturning() throws SQLException {
        try (Connection conn = open()) {
            PreparedStatement ps = conn.prepareStatement("INSERT INTO " + p + "(name) VALUES (?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, "k");
            ps.executeUpdate();
            ResultSet keys = ps.getGeneratedKeys();
            assertTrue(keys.next());
            long id = keys.getLong(1);
            ResultSet r = conn.createStatement().executeQuery(
                    "INSERT INTO " + p + "(name) VALUES ('r') RETURNING id");
            assertTrue(r.next());
            assertEquals(id + 1, r.getLong(1));
        }
    }

    @Test
    void concurrentWriteTransactionsAreEachAllOrNothing() throws SQLException {
        // a holds the write lock; b waits. sqld/Turso may instead time a's transaction out so b can
        // proceed ("Transaction timed out"). Either way: no partial writes, and a failed commit is
        // reported, never silently lost.
        int committed = 0;
        try (Connection a = open(); Connection b = open()) {
            a.setAutoCommit(false);
            a.createStatement().executeUpdate("INSERT INTO " + p + "(name) VALUES ('a1')");
            a.createStatement().executeUpdate("INSERT INTO " + p + "(name) VALUES ('a2')");
            b.setAutoCommit(false);
            boolean bOk = true;
            try {
                b.createStatement().executeUpdate("INSERT INTO " + p + "(name) VALUES ('b1')");
            } catch (SQLException e) {
                bOk = false;
                b.rollback();
            }
            try {
                a.commit();
                committed += 2;
            } catch (SQLException e) {
                assertTrue(e.getMessage().contains("rolled back") || e.getMessage().contains("aborted"), e.getMessage());
            }
            if (bOk) {
                b.commit();
                committed += 1;
            }
        }
        assertEquals(committed, count(p));
        assertTrue(committed > 0);
    }
}
