package com.lifeos.tools;

import com.lifeos.db.libsql.LibSqlHttpDriver;
import com.lifeos.db.libsql.LibSqlUrl;
import org.sqlite.SQLiteConfig;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Copies a Life OS SQLite file (e.g. the old {@code data/life.db}) into a libSQL/Turso database,
 * all-or-nothing, preserving ids. Reusable against any target: the target comes from
 * {@code --target-url} or TURSO_DATABASE_URL, the token from TURSO_AUTH_TOKEN (never a flag, so it
 * does not land in shell history). No URL is hard-coded.
 *
 * <pre>
 *   java -cp life-os-app.jar -Dloader.main=com.lifeos.tools.SqliteToLibsql \
 *        org.springframework.boot.loader.launch.PropertiesLauncher /path/life.db [--dry-run] [--wipe-target]
 * </pre>
 * (docker/migrate-sqlite-to-turso.sh wraps this using the app image.)
 *
 * Steps, in ONE target transaction: create missing tables (source DDL) → optionally wipe →
 * copy rows (multi-row INSERTs, explicit ids) → carry sqlite_sequence → create missing
 * indexes/views/triggers → verify row counts and {@code PRAGMA foreign_key_check} → COMMIT
 * (or ROLLBACK for --dry-run / any problem). Foreign keys are checked at commit
 * ({@code PRAGMA defer_foreign_keys}), so table order does not matter.
 *
 * Safety: refuses a non-empty target unless {@code --wipe-target}; refuses when a source column
 * is missing in an existing target table. Stop the app (or point it elsewhere) while migrating:
 * a concurrent writer can make the server time this transaction out (it then rolls back cleanly).
 */
public final class SqliteToLibsql {

    public record Options(Path source, String targetUrl, String token, boolean dryRun, boolean wipeTarget) {}

    public record TableReport(String table, long sourceRows, long targetRowsBefore, long targetRowsAfter) {}

    public static final class MigrationException extends Exception {
        public MigrationException(String msg) {
            super(msg);
        }
    }

    private static final long MAX_BATCH_BYTES = 512 * 1024;
    private static final int MAX_BATCH_PARAMS = 30_000;

    private SqliteToLibsql() {}

    public static void main(String[] args) {
        try {
            Options o = parse(args);
            run(o, System.out);
            System.exit(0);
        } catch (MigrationException e) {
            System.err.println("MIGRATION ABORTED (nothing was written): " + e.getMessage());
            System.exit(2);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.err.println(USAGE);
            System.exit(64);
        } catch (Exception e) {
            System.err.println("MIGRATION FAILED (target transaction rolled back): " + e);
            System.exit(1);
        }
    }

    static final String USAGE = """
            usage: SqliteToLibsql <source.db> [--target-url URL] [--dry-run] [--wipe-target]
              target URL : --target-url or env TURSO_DATABASE_URL (jdbc:libsql:https://<db>-<org>.turso.io)
              auth token : env TURSO_AUTH_TOKEN
              --dry-run    do everything, verify, then ROLL BACK
              --wipe-target  delete existing rows of the copied tables first (e.g. the seed rows the app
                             wrote when it started against an empty database)""";

    static Options parse(String[] args) {
        Path source = null;
        String url = System.getenv("TURSO_DATABASE_URL");
        boolean dry = false;
        boolean wipe = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--dry-run" -> dry = true;
                case "--wipe-target" -> wipe = true;
                case "--target-url" -> {
                    if (i + 1 >= args.length) throw new IllegalArgumentException("--target-url needs a value");
                    url = args[++i];
                }
                default -> {
                    if (args[i].startsWith("--")) throw new IllegalArgumentException("unknown option " + args[i]);
                    if (source != null) throw new IllegalArgumentException("only one source file");
                    source = Path.of(args[i]);
                }
            }
        }
        if (source == null) throw new IllegalArgumentException("source SQLite file is required");
        if (url == null || url.isBlank()) throw new IllegalArgumentException("no target: set TURSO_DATABASE_URL or --target-url");
        return new Options(source, url, System.getenv("TURSO_AUTH_TOKEN"), dry, wipe);
    }

    // ------------------------------------------------------------------------------------------

    public static List<TableReport> run(Options o, PrintStream out) throws Exception {
        if (!Files.isRegularFile(o.source())) {
            throw new MigrationException("source file not found: " + o.source());
        }
        SQLiteConfig ro = new SQLiteConfig();
        ro.setReadOnly(true);
        try (Connection src = DriverManager.getConnection("jdbc:sqlite:" + o.source(), ro.toProperties());
             Connection dst = openTarget(o.targetUrl(), o.token())) {
            out.println("source: " + o.source());
            out.println("target: " + displayTarget(o.targetUrl()) + (o.dryRun() ? "  (DRY RUN)" : ""));
            return migrate(src, dst, o, out);
        }
    }

    static Connection openTarget(String url, String token) throws SQLException {
        Properties p = new Properties();
        if (token != null) p.setProperty("password", token);
        if (LibSqlUrl.accepts(url)) {
            return new LibSqlHttpDriver().connect(url, p);
        }
        return DriverManager.getConnection(url, p); // e.g. jdbc:sqlite: for tests / local copies
    }

    static String displayTarget(String url) {
        return LibSqlUrl.accepts(url) ? LibSqlUrl.parse(url).display() : url;
    }

    private record SchemaObject(String type, String name, String table, String sql) {}

    private static List<TableReport> migrate(Connection src, Connection dst, Options o, PrintStream out)
            throws Exception {
        List<SchemaObject> srcObjects = schema(src);
        List<SchemaObject> srcTables = srcObjects.stream().filter(x -> x.type().equals("table")).toList();
        Set<String> dstNames = new LinkedHashSet<>();
        for (SchemaObject x : schema(dst)) dstNames.add(x.name().toLowerCase());

        // --- pre-checks (read-only) ---
        Map<String, List<String>> columns = new LinkedHashMap<>();
        Map<String, Long> before = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        for (SchemaObject t : srcTables) {
            List<String> srcCols = columns(src, t.name());
            columns.put(t.name(), srcCols);
            if (dstNames.contains(t.name().toLowerCase())) {
                Set<String> dstCols = new LinkedHashSet<>();
                for (String c : columns(dst, t.name())) dstCols.add(c.toLowerCase());
                List<String> missing = srcCols.stream().filter(c -> !dstCols.contains(c.toLowerCase())).toList();
                if (!missing.isEmpty()) problems.add(t.name() + ": target lacks columns " + missing);
                before.put(t.name(), count(dst, t.name()));
            } else {
                before.put(t.name(), 0L);
            }
        }
        if (!problems.isEmpty()) {
            throw new MigrationException("schema mismatch: " + String.join("; ", problems));
        }
        List<String> nonEmpty = before.entrySet().stream().filter(e -> e.getValue() > 0)
                .map(e -> e.getKey() + "=" + e.getValue()).toList();
        if (!nonEmpty.isEmpty() && !o.wipeTarget()) {
            throw new MigrationException("target is not empty (" + String.join(", ", nonEmpty)
                    + "). Re-run with --wipe-target to replace those rows with the source data.");
        }

        // --- one transaction ---
        dst.setAutoCommit(false);
        boolean committed = false;
        try (Statement st = dst.createStatement()) {
            st.execute("PRAGMA defer_foreign_keys=ON");
            for (SchemaObject t : srcTables) {
                if (!dstNames.contains(t.name().toLowerCase())) {
                    st.execute(t.sql());
                    out.println("created table " + t.name());
                }
            }
            if (o.wipeTarget()) {
                for (SchemaObject t : srcTables) st.executeUpdate("DELETE FROM " + q(t.name()));
                if (exists(dst, "sqlite_sequence")) {
                    for (SchemaObject t : srcTables) {
                        try (PreparedStatement ps = dst.prepareStatement("DELETE FROM sqlite_sequence WHERE name = ?")) {
                            ps.setString(1, t.name());
                            ps.executeUpdate();
                        }
                    }
                }
            }
            for (SchemaObject t : srcTables) {
                long n = copyRows(src, dst, t.name(), columns.get(t.name()));
                out.printf("copied %-28s %8d rows%n", t.name(), n);
            }
            copySequences(src, dst, srcTables);
            for (SchemaObject x : srcObjects) {
                if (!x.type().equals("table") && !dstNames.contains(x.name().toLowerCase())) {
                    st.execute(x.sql());
                }
            }

            // --- verify inside the transaction ---
            List<TableReport> report = new ArrayList<>();
            List<String> mismatches = new ArrayList<>();
            for (SchemaObject t : srcTables) {
                long s = count(src, t.name());
                long d = count(dst, t.name());
                report.add(new TableReport(t.name(), s, before.get(t.name()), d));
                if (s != d) mismatches.add(t.name() + " source=" + s + " target=" + d);
            }
            if (!mismatches.isEmpty()) throw new MigrationException("row counts differ: " + mismatches);
            List<String> fk = new ArrayList<>();
            try (ResultSet rs = st.executeQuery("PRAGMA foreign_key_check")) {
                while (rs.next() && fk.size() < 20) {
                    fk.add(rs.getString(1) + " rowid=" + rs.getObject(2) + " -> " + rs.getString(3));
                }
            }
            if (!fk.isEmpty()) throw new MigrationException("foreign key violations in the copied data: " + fk);

            if (o.dryRun()) {
                dst.rollback();
                out.println("dry run OK: verified, then rolled back (target unchanged)");
            } else {
                dst.commit();
                committed = true;
                out.println("COMMITTED");
            }
            return report;
        } finally {
            if (!committed) {
                try {
                    dst.rollback();
                } catch (SQLException ignored) {
                    // already rolled back / connection gone: the server discards the transaction
                }
            }
        }
    }

    private static List<SchemaObject> schema(Connection c) throws SQLException {
        List<SchemaObject> out = new ArrayList<>();
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("""
                     SELECT type, name, tbl_name, sql FROM sqlite_master
                     WHERE sql IS NOT NULL AND name NOT LIKE 'sqlite_%'
                     ORDER BY CASE type WHEN 'table' THEN 0 WHEN 'index' THEN 1 WHEN 'view' THEN 2 ELSE 3 END, rowid""")) {
            while (rs.next()) out.add(new SchemaObject(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)));
        }
        return out;
    }

    private static List<String> columns(Connection c, String table) throws SQLException {
        List<String> cols = new ArrayList<>();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("PRAGMA table_info(" + q(table) + ")")) {
            while (rs.next()) cols.add(rs.getString("name"));
        }
        return cols;
    }

    private static boolean exists(Connection c, String name) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM sqlite_master WHERE name = ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getLong(1) > 0;
            }
        }
    }

    private static long count(Connection c, String table) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + q(table))) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static long copyRows(Connection src, Connection dst, String table, List<String> cols) throws SQLException {
        String colList = String.join(", ", cols.stream().map(SqliteToLibsql::q).toList());
        String rowPlaceholders = "(" + String.join(",", java.util.Collections.nCopies(cols.size(), "?")) + ")";
        int maxRows = Math.max(1, MAX_BATCH_PARAMS / Math.max(1, cols.size()));
        long copied = 0;
        List<Object[]> batch = new ArrayList<>();
        long batchBytes = 0;
        try (Statement st = src.createStatement();
             ResultSet rs = st.executeQuery("SELECT " + colList + " FROM " + q(table) + " ORDER BY rowid")) {
            while (rs.next()) {
                Object[] row = new Object[cols.size()];
                for (int i = 0; i < row.length; i++) {
                    row[i] = rs.getObject(i + 1);
                    batchBytes += row[i] == null ? 4 : row[i] instanceof byte[] b ? b.length * 4L / 3 : row[i].toString().length() + 8;
                }
                batch.add(row);
                if (batch.size() >= maxRows || batchBytes >= MAX_BATCH_BYTES) {
                    copied += flush(dst, table, colList, rowPlaceholders, batch);
                    batch.clear();
                    batchBytes = 0;
                }
            }
        }
        copied += flush(dst, table, colList, rowPlaceholders, batch);
        return copied;
    }

    private static int flush(Connection dst, String table, String colList, String rowPlaceholders, List<Object[]> rows)
            throws SQLException {
        if (rows.isEmpty()) return 0;
        String sql = "INSERT INTO " + q(table) + " (" + colList + ") VALUES "
                + String.join(",", java.util.Collections.nCopies(rows.size(), rowPlaceholders));
        try (PreparedStatement ps = dst.prepareStatement(sql)) {
            int k = 1;
            for (Object[] r : rows) for (Object v : r) ps.setObject(k++, v);
            return ps.executeUpdate();
        }
    }

    /** Keeps AUTOINCREMENT counters at least as high as in the source, so deleted ids are not reused. */
    private static void copySequences(Connection src, Connection dst, List<SchemaObject> tables) throws SQLException {
        if (!exists(src, "sqlite_sequence")) return;
        Set<String> names = new LinkedHashSet<>();
        for (SchemaObject t : tables) names.add(t.name());
        try (Statement st = src.createStatement(); ResultSet rs = st.executeQuery("SELECT name, seq FROM sqlite_sequence")) {
            while (rs.next()) {
                String name = rs.getString(1);
                long seq = rs.getLong(2);
                if (!names.contains(name) || !exists(dst, "sqlite_sequence")) continue;
                int updated;
                try (PreparedStatement ps = dst.prepareStatement(
                        "UPDATE sqlite_sequence SET seq = MAX(seq, ?) WHERE name = ?")) {
                    ps.setLong(1, seq);
                    ps.setString(2, name);
                    updated = ps.executeUpdate();
                }
                if (updated == 0) {
                    try (PreparedStatement ps = dst.prepareStatement("INSERT INTO sqlite_sequence (name, seq) VALUES (?, ?)")) {
                        ps.setString(1, name);
                        ps.setLong(2, seq);
                        ps.executeUpdate();
                    }
                }
            }
        }
    }

    private static String q(String ident) {
        return "\"" + ident.replace("\"", "\"\"") + "\"";
    }
}
