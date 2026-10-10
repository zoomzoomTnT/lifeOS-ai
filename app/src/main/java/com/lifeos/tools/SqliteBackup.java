package com.lifeos.tools;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * Consistent copy of a (possibly live, WAL-mode) SQLite file via SQLite's online backup API,
 * then {@code PRAGMA integrity_check} on the copy. Writes to a temp file and renames it into place,
 * so a failed run never leaves a half-written backup.
 *
 * <pre>java -cp app.jar -Dloader.main=com.lifeos.tools.SqliteBackup \
 *   org.springframework.boot.loader.launch.PropertiesLauncher &lt;source.db&gt; &lt;backup.db&gt;</pre>
 * CD runs it inside the app image as root, so root-owned {@code life.db -wal -shm} files written by
 * the container are readable (reading WAL needs write access to -shm).
 */
public final class SqliteBackup {

    private SqliteBackup() {
    }

    public static void main(String[] args) {
        if (args.length != 2) {
            System.err.println("usage: SqliteBackup <source.db> <backup.db>");
            System.exit(64);
        }
        try {
            backup(Path.of(args[0]), Path.of(args[1]));
            System.out.println("backup OK: " + args[0] + " -> " + args[1]);
        } catch (Exception e) {
            System.err.println("BACKUP FAILED: " + e.getMessage());
            System.exit(1);
        }
    }

    public static void backup(Path source, Path target) throws Exception {
        if (!Files.isRegularFile(source)) throw new IllegalArgumentException("source not found: " + source);
        Path dir = target.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, target.getFileName().toString(), ".part");
        try {
            Files.delete(tmp); // the backup API creates it
            try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + source.toAbsolutePath());
                 Statement st = c.createStatement()) {
                st.executeUpdate("backup to " + tmp.toAbsolutePath());
            }
            try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + tmp.toAbsolutePath());
                 Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("PRAGMA integrity_check")) {
                String r = rs.next() ? rs.getString(1) : "no result";
                if (!"ok".equals(r)) throw new IllegalStateException("integrity_check on the copy: " + r);
            }
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
