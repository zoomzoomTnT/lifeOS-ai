package com.lifeos.config;

import com.lifeos.db.libsql.LibSqlHttpDriver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prod's database was built by e2e4938 (no auth). Starting the current app on such a database, with
 * rows in it, must end at exactly the schema of a fresh database and keep every row.
 */
class UpgradeFromE2e4938Test {

    @Test
    void sqlite() throws Exception {
        upgradeKeepsDataAndMatchesAFreshSchema(sqliteDb(), "jdbc:sqlite:");
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "LIBSQL_TEST_URL", matches = ".+")
    void realSqld() throws Exception {
        String url = System.getenv("LIBSQL_TEST_URL");
        Properties props = new Properties();
        String token = System.getenv("LIBSQL_TEST_TOKEN");
        if (token != null) props.setProperty("password", token);
        JdbcTemplate jdbc = new JdbcTemplate(new SimpleDriverDataSource(new LibSqlHttpDriver(), url, props));
        dropEverything(jdbc); // the shared test server: start from an empty database
        upgradeKeepsDataAndMatchesAFreshSchema(jdbc, url);
    }

    static JdbcTemplate sqliteDb() throws Exception {
        Path file = Files.createTempFile("lifeos-e2e4938-", ".db");
        Files.delete(file);
        file.toFile().deleteOnExit();
        return new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:" + file));
    }

    static void upgradeKeepsDataAndMatchesAFreshSchema(JdbcTemplate jdbc, String url) throws Exception {
        boolean libSql = ForeignKeyCheck.isLibSql(url);
        for (String s : SqlScriptSplitter.split(fixture())) {
            if (libSql && SchemaInitializer.isPragma(s)) continue; // as the app does on libSQL
            if (s.startsWith("ALTER TABLE") && s.contains("ADD COLUMN")) {
                // e2e4938 re-ran its migrations on every start; their columns are already in its schema.sql
                String[] w = s.split("\\s+");
                if (columns(jdbc, w[2]).contains(w[5])) continue;
            }
            jdbc.execute(s);
        }
        assertThat(columns(jdbc, "events")).doesNotContain("client_id");
        assertThat(columns(jdbc, "http_requests")).doesNotContain("credential_id");

        jdbc.update("INSERT INTO people (handle, display_name, role) VALUES ('alice', 'Alice', 'member')");
        jdbc.update("INSERT INTO events (domain, action, entity_table, entity_id, payload_json) "
                + "VALUES ('memos', 'create', 'memos', 1, '{\"t\":\"keep me\"}')");
        jdbc.update("INSERT INTO http_requests (correlation_id, method, path, status) VALUES ('req-1', 'GET', '/api/memos', 200)");
        jdbc.update("UPDATE settings SET value = '123' WHERE key = 'ai_daily_budget_usd_micros'");
        jdbc.update("UPDATE model_prices SET input_usd_micros_per_mtok = 42 WHERE provider = 'xai' AND model = 'grok-4'");
        Map<String, List<Map<String, Object>>> before = new TreeMap<>();
        for (String t : tables(jdbc)) before.put(t, rows(jdbc, t));

        init(jdbc, url);
        init(jdbc, url); // and again: nothing changes

        JdbcTemplate fresh = sqliteDb();
        init(fresh, "jdbc:sqlite:");
        assertThat(shape(jdbc)).isEqualTo(shape(fresh));

        for (Map.Entry<String, List<Map<String, Object>>> e : before.entrySet()) {
            List<Map<String, Object>> after = rows(jdbc, e.getKey());
            for (Map<String, Object> r : after) { // the new columns are NULL on old rows
                for (String c : List.of("client_id", "credential_id", "request_id", "approval_id")) {
                    if (e.getKey().equals("events") || e.getKey().equals("http_requests")) {
                        assertThat(r.remove(c)).as(e.getKey() + "." + c).isNull();
                    }
                }
            }
            if (List.of("schema_migrations", "settings").contains(e.getKey())) { // gain 0005 seed rows, keep the rest
                assertThat(after).as(e.getKey()).containsAll(e.getValue());
            } else {
                assertThat(after).as(e.getKey()).isEqualTo(e.getValue());
            }
        }
        assertThat(jdbc.queryForObject("SELECT value FROM settings WHERE key='ai_daily_budget_usd_micros'", String.class)).isEqualTo("123");
        assertThat(jdbc.queryForObject("SELECT input_usd_micros_per_mtok FROM model_prices WHERE model='grok-4'", Integer.class)).isEqualTo(42);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM settings WHERE key LIKE 'approval.%'", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT payload_json FROM events WHERE entity_table='memos'", String.class)).contains("keep me");
    }

    static void init(JdbcTemplate jdbc, String url) throws Exception {
        SchemaInitializer init = new SchemaInitializer(jdbc, new DefaultResourceLoader());
        ReflectionTestUtils.setField(init, "schemaPath", "classpath:schema.sql");
        ReflectionTestUtils.setField(init, "datasourceUrl", url);
        init.apply();
    }

    static String fixture() throws Exception {
        try (InputStream in = UpgradeFromE2e4938Test.class.getResourceAsStream("/fixtures/e2e4938-schema.sql")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static List<String> columns(JdbcTemplate jdbc, String table) {
        return jdbc.queryForList("SELECT name FROM pragma_table_info(?)", String.class, table);
    }

    static List<String> tables(JdbcTemplate jdbc) {
        return jdbc.queryForList("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' "
                + "AND name NOT LIKE 'libsql_%' ORDER BY name", String.class);
    }

    static List<Map<String, Object>> rows(JdbcTemplate jdbc, String table) {
        return new ArrayList<>(jdbc.queryForList("SELECT * FROM " + table + " ORDER BY rowid"));
    }

    /** Every app object with its columns (in order) or indexed columns. */
    static Map<String, String> shape(JdbcTemplate jdbc) {
        Map<String, String> out = new TreeMap<>();
        for (Map<String, Object> o : jdbc.queryForList("SELECT type, name, tbl_name FROM sqlite_master WHERE "
                + "name NOT LIKE 'sqlite_%' AND name NOT LIKE 'libsql_%'")) {
            String name = (String) o.get("name");
            String desc = o.get("type") + " on " + o.get("tbl_name");
            if ("table".equals(o.get("type"))) {
                desc += " " + jdbc.queryForList("SELECT name || ' ' || type || ' ' || \"notnull\" || ' ' || "
                        + "COALESCE(dflt_value, '-') || ' ' || pk FROM pragma_table_info(?) ORDER BY cid", String.class, name);
            } else if ("index".equals(o.get("type"))) {
                desc += " " + jdbc.queryForList("SELECT COALESCE(name, '<expr>') FROM pragma_index_info(?) ORDER BY seqno",
                        String.class, name);
            }
            out.put(name, desc);
        }
        return out;
    }

    static void dropEverything(JdbcTemplate jdbc) {
        for (String v : jdbc.queryForList("SELECT name FROM sqlite_master WHERE type='view'", String.class)) {
            jdbc.execute("DROP VIEW \"" + v + "\"");
        }
        // children before parents (foreign keys are on): reverse creation order
        for (String t : jdbc.queryForList("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE "
                + "'sqlite_%' AND name NOT LIKE 'libsql_%' ORDER BY rowid DESC", String.class)) {
            jdbc.execute("DROP TABLE \"" + t + "\"");
        }
    }
}
