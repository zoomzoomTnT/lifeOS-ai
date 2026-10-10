package com.lifeos.tools;

import com.lifeos.db.libsql.LibSqlHttpDriver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Migration against a real sqld. Needs a DEDICATED server (it is emptied first):
 * {@code LIBSQL_MIGRATION_TEST_URL=jdbc:libsql:http://localhost:8081} (CI: the sqld-migrate service).
 */
@EnabledIfEnvironmentVariable(named = "LIBSQL_MIGRATION_TEST_URL", matches = ".+")
class SqliteToLibsqlRealServerTest {

    static final String URL = System.getenv("LIBSQL_MIGRATION_TEST_URL");
    final PrintStream out = new PrintStream(new ByteArrayOutputStream());

    static {
        new LibSqlHttpDriver(); // registers
    }

    static Connection open() throws SQLException {
        Properties p = new Properties();
        p.setProperty("password", "");
        return DriverManager.getConnection(URL, p);
    }

    static long q(String sql) throws SQLException {
        try (Connection c = open(); ResultSet rs = c.createStatement().executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : -1;
        }
    }

    @BeforeEach
    void emptyTheServer() throws SQLException {
        try (Connection c = open(); Statement st = c.createStatement()) {
            for (String type : List.of("view", "trigger", "table")) {
                List<String> names = new ArrayList<>();
                try (ResultSet rs = st.executeQuery("SELECT name FROM sqlite_master WHERE type = '" + type
                        + "' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'libsql_%' ORDER BY rowid DESC")) {
                    while (rs.next()) names.add(rs.getString(1));
                }
                c.setAutoCommit(false);
                st.execute("PRAGMA defer_foreign_keys=ON");
                for (String n : names) st.execute("DROP " + type + " IF EXISTS \"" + n + "\"");
                c.commit();
                c.setAutoCommit(true);
            }
        }
        assertEquals(0, q("SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'libsql_%'"));
    }

    @Test
    void migratesALifeDbIntoRealSqld() throws Exception {
        Path source = SqliteToLibsqlTest.richSource();
        SqliteToLibsql.Options o = new SqliteToLibsql.Options(source, URL, "", false, false);

        SqliteToLibsql.run(new SqliteToLibsql.Options(source, URL, "", true, false), out); // dry run
        assertEquals(0, q("SELECT COUNT(*) FROM sqlite_master WHERE name = 'people'"));

        List<SqliteToLibsql.TableReport> report = SqliteToLibsql.run(o, out);
        for (SqliteToLibsql.TableReport r : report) {
            assertEquals(r.sourceRows(), q("SELECT COUNT(*) FROM \"" + r.table() + "\""), r.table());
        }
        assertEquals(1200, q("SELECT COUNT(*) FROM http_requests"));
        assertEquals(40, q("SELECT id FROM receipts WHERE fingerprint = 'fp-1'"));
        assertEquals(SqliteToLibsqlTest.q(source, "SELECT seq FROM sqlite_sequence WHERE name = 'events'"),
                q("SELECT seq FROM sqlite_sequence WHERE name = 'events'"));
        assertEquals(1, q("SELECT COUNT(*) FROM sqlite_master WHERE type = 'view' AND name = 'v_ai_daily'"));
        assertEquals(0, q("SELECT COUNT(*) FROM pragma_foreign_key_check"));

        // second run: real data in the target -> refused, wipe without confirmation refused too
        assertThrows(SqliteToLibsql.MigrationException.class, () -> SqliteToLibsql.run(o, out));
        SqliteToLibsql.MigrationException e = assertThrows(SqliteToLibsql.MigrationException.class,
                () -> SqliteToLibsql.run(new SqliteToLibsql.Options(source, URL, "", false, true), out));
        assertTrue(e.getMessage().contains("--confirm-target-counts"), e.getMessage());
        String flag = e.getMessage().substring(e.getMessage().indexOf("--confirm-target-counts ") + 24).strip();
        SqliteToLibsql.run(new SqliteToLibsql.Options(source, URL, "", false, true, SqliteToLibsql.parseCounts(flag)), out);
        assertEquals(1200, q("SELECT COUNT(*) FROM http_requests"));
    }
}
