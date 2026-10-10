package com.lifeos.tools;

import com.lifeos.config.SqlScript;
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
import java.util.HashMap;
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
 * Steps, in ONE target transaction: create the app schema (schema.sql) objects the target lacks
 * — so an old file missing e.g. {@code merchants} still yields a complete database — plus any
 * source-only tables (source DDL) → clear seed rows the source replaces (or wiped rows) → copy rows
 * (multi-row INSERTs, explicit ids) → carry sqlite_sequence → create missing source indexes /
 * views / triggers → verify row counts and {@code PRAGMA foreign_key_check} → COMMIT (or ROLLBACK
 * for --dry-run / any problem). Foreign keys are checked at commit
 * ({@code PRAGMA defer_foreign_keys}), so table order does not matter. The source is read in one
 * read transaction (consistent snapshot).
 *
 * Safety: migrate BEFORE cutover into an empty database. A non-empty target is refused unless
 * {@code --wipe-target}, and wiping is only allowed by itself when the target holds nothing but the
 * app's seed rows; otherwise the exact non-empty counts must be confirmed with
 * {@code --confirm-target-counts} (re-checked under the write lock). Refuses when a source column is
 * missing in the target table. Stop the app while migrating.
 */
public final class SqliteToLibsql {

    /**
     * @param confirmTargetCounts with --wipe-target on a target holding more than seed rows: the
     *                            exact non-empty row counts the operator confirmed (table → rows), or null
     */
    public record Options(Path source, String targetUrl, String token, boolean dryRun, boolean wipeTarget,
                          Map<String, Long> confirmTargetCounts) {
        public Options(Path source, String targetUrl, String token, boolean dryRun, boolean wipeTarget) {
            this(source, targetUrl, token, dryRun, wipeTarget, null);
        }
    }

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
              --wipe-target  replace rows already in the target. Allowed by itself only when the target
                             holds nothing but the app's seed rows (owner, settings, model prices).
              --confirm-target-counts t1=N,t2=M
                             with --wipe-target: required when the target holds more than seed rows;
                             must list exactly the target's non-empty tables and row counts (the abort
                             message prints the current value). Those rows are DELETED.

            Migrate BEFORE cutover: stop the old API, migrate into the new (empty) database, then
            deploy. Never wipe a database the new app is already writing to.""";

    static Options parse(String[] args) {
        Path source = null;
        String url = System.getenv("TURSO_DATABASE_URL");
        boolean dry = false;
        boolean wipe = false;
        Map<String, Long> confirm = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--dry-run" -> dry = true;
                case "--wipe-target" -> wipe = true;
                case "--confirm-target-counts" -> {
                    if (i + 1 >= args.length) throw new IllegalArgumentException("--confirm-target-counts needs a value");
                    confirm = parseCounts(args[++i]);
                }
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
        if (confirm != null && !wipe) throw new IllegalArgumentException("--confirm-target-counts only makes sense with --wipe-target");
        return new Options(source, url, System.getenv("TURSO_AUTH_TOKEN"), dry, wipe, confirm);
    }

    static Map<String, Long> parseCounts(String v) {
        Map<String, Long> m = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (String kv : v.split(",")) {
            if (kv.isBlank()) continue;
            int eq = kv.indexOf('=');
            if (eq <= 0) throw new IllegalArgumentException("bad --confirm-target-counts entry: " + kv);
            try {
                m.put(kv.substring(0, eq).strip(), Long.parseLong(kv.substring(eq + 1).strip()));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("bad --confirm-target-counts entry: " + kv);
            }
        }
        return m;
    }

    static String formatCounts(Map<String, Long> m) {
        return String.join(",", m.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toList());
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
            // One read transaction on the source: a consistent snapshot even if something still writes it.
            src.setAutoCommit(false);
            try {
                return migrate(src, dst, o, out);
            } finally {
                src.rollback();
            }
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

    /** What the app runs on an empty database (SchemaInitializer): schema.sql, then the migrations. */
    static final String[] APP_SCRIPTS = {"/schema.sql", "/migrations/0002_ops.sql",
            "/migrations/0003_logs.sql", "/migrations/0004_session_v3.sql"};

    /**
     * The app's schema and seed rows, materialised in a private in-memory SQLite exactly the way
     * the app initialises an empty database (statement errors ignored like SchemaInitializer does,
     * e.g. duplicate columns; PRAGMAs skipped).
     */
    static Connection appReference() throws Exception {
        Connection mem = DriverManager.getConnection("jdbc:sqlite::memory:");
        try (Statement st = mem.createStatement()) {
            for (String script : APP_SCRIPTS) {
                try (var in = SqliteToLibsql.class.getResourceAsStream(script)) {
                    if (in == null) throw new java.io.IOException(script + " not on the classpath");
                    for (String x : SqlScript.split(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8))) {
                        if (x.toUpperCase(java.util.Locale.ROOT).startsWith("PRAGMA")) continue;
                        try {
                            st.execute(x);
                        } catch (SQLException e) {
                            // additive migrations repeat columns schema.sql already has
                        }
                    }
                }
            }
        } catch (Exception e) {
            mem.close();
            throw e;
        }
        return mem;
    }

    private static List<TableReport> migrate(Connection src, Connection dst, Options o, PrintStream out)
            throws Exception {
        try (Connection app = appReference()) {
            return migrate(src, dst, app, o, out);
        }
    }

    private static List<TableReport> migrate(Connection src, Connection dst, Connection app, Options o, PrintStream out)
            throws Exception {
        List<SchemaObject> srcObjects = schema(src);
        List<SchemaObject> srcTables = srcObjects.stream().filter(x -> x.type().equals("table")).toList();
        Set<String> dstNames = new LinkedHashSet<>();
        for (SchemaObject x : schema(dst)) dstNames.add(x.name().toLowerCase());

        // The app schema (as the app would create it) and the seed rows it writes.
        List<SchemaObject> appObjects = schema(app);
        Map<String, Long> seed = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        Map<String, List<String>> appColumns = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (SchemaObject t : appObjects) {
            if (!t.type().equals("table")) continue;
            appColumns.put(t.name(), columns(app, t.name()));
            seed.put(t.name(), count(app, t.name()));
        }

        // --- pre-checks (read-only) ---
        Map<String, List<String>> columns = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        for (SchemaObject t : srcTables) {
            List<String> srcCols = columns(src, t.name());
            columns.put(t.name(), srcCols);
            List<String> targetCols = dstNames.contains(t.name().toLowerCase()) ? columns(dst, t.name())
                    : appColumns.get(t.name()); // null: created from the source DDL
            if (targetCols != null) {
                Set<String> tc = new LinkedHashSet<>();
                for (String c : targetCols) tc.add(c.toLowerCase());
                List<String> missing = srcCols.stream().filter(c -> !tc.contains(c.toLowerCase())).toList();
                if (!missing.isEmpty()) problems.add(t.name() + ": target lacks columns " + missing);
            }
        }
        if (!problems.isEmpty()) {
            throw new MigrationException("schema mismatch: " + String.join("; ", problems));
        }
        Map<String, Long> before = checkTarget(dst, app, seed, o);

        // --- one transaction ---
        dst.setAutoCommit(false);
        boolean committed = false;
        try (Statement st = dst.createStatement()) {
            st.execute("PRAGMA defer_foreign_keys=ON"); // also takes the write lock (BEGIN IMMEDIATE)
            // judge again under the write lock: nothing can change between this check and the wipe
            Set<String> existedBefore = new LinkedHashSet<>(dstNames);
            before = checkTarget(dst, app, seed, o);
            // 1. the app schema first: tables the source lacks (e.g. merchants on an old file) exist afterwards
            Set<String> createdNow = new LinkedHashSet<>();
            for (SchemaObject t : appObjects) {
                if (!t.type().equals("table") || dstNames.contains(t.name().toLowerCase())) continue;
                st.execute(t.sql());
                dstNames.add(t.name().toLowerCase());
                createdNow.add(t.name().toLowerCase());
                copyRows(app, dst, t.name(), appColumns.get(t.name())); // seed rows
            }
            for (SchemaObject x : appObjects) {
                if (!x.type().equals("table") && !dstNames.contains(x.name().toLowerCase())) {
                    st.execute(x.sql());
                    dstNames.add(x.name().toLowerCase());
                }
            }
            if (!createdNow.isEmpty()) out.println("created app schema tables: " + createdNow);
            // 2. source-only tables (not in the app schema) from the source DDL
            for (SchemaObject t : srcTables) {
                if (!dstNames.contains(t.name().toLowerCase())) {
                    st.execute(t.sql());
                    dstNames.add(t.name().toLowerCase());
                    out.println("created table " + t.name() + " (source DDL)");
                }
            }
            // 3. clear what the source replaces: our own fresh seed rows, or (checked above) wiped rows
            for (SchemaObject t : srcTables) {
                if (createdNow.contains(t.name().toLowerCase()) || (o.wipeTarget() && existedBefore.contains(t.name().toLowerCase()))) {
                    st.executeUpdate("DELETE FROM " + q(t.name()));
                    if (exists(dst, "sqlite_sequence")) {
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
                    dstNames.add(x.name().toLowerCase());
                }
            }

            // --- verify inside the transaction ---
            List<TableReport> report = new ArrayList<>();
            List<String> mismatches = new ArrayList<>();
            for (SchemaObject t : srcTables) {
                long s = count(src, t.name());
                long d = count(dst, t.name());
                report.add(new TableReport(t.name(), s, before.getOrDefault(t.name(), 0L), d));
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

    /**
     * Tables whose rows are written on every HTTP request (access log). They grow continuously
     * while any app instance serves the target, so they are not counted against "seed only" and
     * are not part of --confirm-target-counts; with --wipe-target they are replaced by the source's.
     */
    static final Set<String> REQUEST_LOG_TABLES = Set.of("http_requests");

    /** Columns filled by a time default ({@code strftime(...,'now')} etc.): ignored when comparing seed rows. */
    private static boolean isTimeDefault(String dflt) {
        return dflt != null && dflt.toLowerCase(java.util.Locale.ROOT).matches(".*(now|current_|strftime|datetime|julianday|unixepoch).*");
    }

    /**
     * Refuses unless the target may be written: empty, or with --wipe-target holding nothing but
     * unchanged app seed rows (compared by CONTENT, time-default columns ignored), or exactly the
     * confirmed counts. Returns all target table counts.
     */
    private static Map<String, Long> checkTarget(Connection dst, Connection app, Map<String, Long> seed, Options o)
            throws SQLException, MigrationException {
        Map<String, Long> counts = targetCounts(dst);
        Map<String, Long> nonEmpty = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        Map<String, Long> logRows = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        counts.forEach((t, n) -> {
            if (n > 0) (REQUEST_LOG_TABLES.contains(t.toLowerCase()) ? logRows : nonEmpty).put(t, n);
        });
        if (nonEmpty.isEmpty() && logRows.isEmpty()) return counts;
        if (!o.wipeTarget()) {
            Map<String, Long> all = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            all.putAll(nonEmpty);
            all.putAll(logRows);
            throw new MigrationException("target is not empty (" + formatCounts(all)
                    + "). Migrate into an empty database before cutover. If these rows may be replaced, "
                    + "re-run with --wipe-target.");
        }
        List<String> notSeed = new ArrayList<>();
        for (Map.Entry<String, Long> e : nonEmpty.entrySet()) {
            String why = notSeedRows(dst, app, e.getKey(), seed.getOrDefault(e.getKey(), 0L));
            if (why != null) notSeed.add(why);
        }
        boolean confirmed = o.confirmTargetCounts() != null
                && new java.util.TreeMap<>(nonEmpty).equals(withoutLogTables(o.confirmTargetCounts()));
        if (!notSeed.isEmpty() && !confirmed) {
            throw new MigrationException("--wipe-target refused: the target holds more than the app's unchanged seed rows ("
                    + String.join("; ", notSeed) + "), e.g. data written after a deploy, which would be DELETED. "
                    + "If that is really intended, add: --confirm-target-counts " + formatCounts(nonEmpty));
        }
        return counts;
    }

    private static Map<String, Long> withoutLogTables(Map<String, Long> m) {
        Map<String, Long> out = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        m.forEach((t, n) -> { if (!REQUEST_LOG_TABLES.contains(t.toLowerCase())) out.put(t, n); });
        return out;
    }

    /** null when every target row of {@code table} equals a distinct app seed row; else why not. */
    private static String notSeedRows(Connection dst, Connection app, String table, long seedCount) throws SQLException {
        if (seedCount == 0) return table + ": " + count(dst, table) + " rows, app seeds none";
        Set<String> targetCols = new java.util.HashSet<>();
        for (String c : columns(dst, table)) targetCols.add(c.toLowerCase());
        List<String> cols = new ArrayList<>();
        try (Statement st = app.createStatement(); ResultSet rs = st.executeQuery("PRAGMA table_info(" + q(table) + ")")) {
            while (rs.next()) {
                String c = rs.getString("name");
                if (!isTimeDefault(rs.getString("dflt_value")) && targetCols.contains(c.toLowerCase())) cols.add(c);
            }
        }
        if (cols.isEmpty()) return table + ": no comparable columns";
        Map<List<String>, Integer> seedRows = new HashMap<>();
        for (List<String> r : rows(app, table, cols)) seedRows.merge(r, 1, Integer::sum);
        for (List<String> r : rows(dst, table, cols)) {
            Integer left = seedRows.get(r);
            if (left == null || left == 0) return table + ": row " + cols + "=" + r + " is not an app seed row";
            seedRows.put(r, left - 1);
        }
        return null;
    }

    private static List<List<String>> rows(Connection c, String table, List<String> cols) throws SQLException {
        List<List<String>> out = new ArrayList<>();
        String sql = "SELECT " + String.join(", ", cols.stream().map(SqliteToLibsql::q).toList()) + " FROM " + q(table);
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                List<String> r = new ArrayList<>(cols.size());
                for (int i = 1; i <= cols.size(); i++) r.add(rs.getString(i));
                out.add(r);
            }
        }
        return out;
    }

    /** Row counts of the target's tables (all existing user tables, not only the copied ones). */
    private static Map<String, Long> targetCounts(Connection dst) throws SQLException {
        Map<String, Long> m = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (SchemaObject t : schema(dst)) {
            if (t.type().equals("table")) m.put(t.name(), count(dst, t.name()));
        }
        return m;
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
