package com.lifeos.tools;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SqliteBackupTest {

    @Test
    void copiesALiveWalDatabaseIncludingUncheckpointedRows() throws Exception {
        Path dir = Files.createTempDirectory("lifeos-backup-");
        Path src = dir.resolve("life.db");
        try (Connection writer = DriverManager.getConnection("jdbc:sqlite:" + src); Statement st = writer.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA wal_autocheckpoint=0");
            st.execute("CREATE TABLE t (x)");
            for (int i = 0; i < 500; i++) st.execute("INSERT INTO t VALUES (" + i + ")");
            // writer still open: rows live in -wal only
            Path dst = dir.resolve("backups/life.db.pre-turso");
            SqliteBackup.backup(src, dst);
            try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dst);
                 ResultSet rs = c.createStatement().executeQuery("SELECT COUNT(*) FROM t")) {
                rs.next();
                assertEquals(500, rs.getLong(1));
            }
            try (var files = Files.list(dst.getParent())) {
                assertFalse(files.anyMatch(p -> p.toString().endsWith(".part")));
            }
        }
        assertThrows(IllegalArgumentException.class, () -> SqliteBackup.backup(dir.resolve("nope.db"), dir.resolve("x.db")));
    }
}
