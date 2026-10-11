package com.lifeos.config;

import com.lifeos.testsupport.FakeTursoServer;
import com.lifeos.security.ApiKeyStore;
import com.lifeos.security.SecurityEventLog;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A DB set up by PR #29's first head (c73473f: security_events without request_id, api_keys.client_id)
 * must keep working after the current SchemaInitializer runs: security events insert, keys resolve.
 * The c73473f scripts are copied under test resources legacy-c73473f/.
 */
class UpgradeFromC73473fTest {

    static SchemaInitializer init(JdbcTemplate jdbc, String schema, String url) {
        SchemaInitializer init = new SchemaInitializer(jdbc, new DefaultResourceLoader());
        ReflectionTestUtils.setField(init, "schemaPath", schema);
        if (url != null) ReflectionTestUtils.setField(init, "datasourceUrl", url);
        return init;
    }

    /** What c73473f's SchemaInitializer did on a fresh DB: its schema.sql, then its 0002..0005. */
    static void legacySetup(JdbcTemplate jdbc, String url) throws Exception {
        SchemaInitializer legacy = new SchemaInitializer(jdbc, new DefaultResourceLoader());
        ReflectionTestUtils.setField(legacy, "schemaPath", "classpath:legacy-c73473f/schema.sql");
        if (url != null) ReflectionTestUtils.setField(legacy, "datasourceUrl", url);
        // run the legacy scripts through the same splitter/tolerance as the app
        var exec = SchemaInitializer.class.getDeclaredMethod("execScript", String.class);
        exec.setAccessible(true);
        exec.invoke(legacy, "classpath:legacy-c73473f/schema.sql");
        for (String m : List.of("0002_ops", "0003_logs", "0004_session_v3", "0005_auth")) {
            exec.invoke(legacy, "classpath:legacy-c73473f/" + m + ".sql");
        }
    }

    static List<String> columns(JdbcTemplate jdbc, String table) {
        return jdbc.queryForList("SELECT name FROM pragma_table_info('" + table + "')", String.class);
    }

    void checkUpgrade(JdbcTemplate jdbc, String url) throws Exception {
        legacySetup(jdbc, url);
        assertThat(columns(jdbc, "security_events")).doesNotContain("request_id");
        assertThat(columns(jdbc, "api_keys")).contains("client_id").doesNotContain("api_client_id");
        // a key minted by the old code
        jdbc.update("INSERT INTO api_clients (client_id, display_name, kind, person_id, max_scopes) VALUES ('old-agent','Old','agent',1,'memos:read')");
        jdbc.update("INSERT INTO api_keys (key_id, client_id, secret_hash, scopes) VALUES ('oldkey000001',(SELECT id FROM api_clients WHERE client_id='old-agent'),'00','memos:read')");

        init(jdbc, "classpath:schema.sql", url).apply();
        init(jdbc, "classpath:schema.sql", url).apply(); // and again: idempotent

        assertThat(columns(jdbc, "security_events")).contains("request_id");
        assertThat(columns(jdbc, "api_keys")).contains("api_client_id").doesNotContain("client_id");
        new SecurityEventLog(jdbc, Clock.systemUTC()).record(SecurityEventLog.Event.of("upgrade_check").outcome("ok"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM security_events WHERE type='upgrade_check'", Integer.class)).isEqualTo(1);
        var store = new ApiKeyStore(jdbc);
        assertThat(store.findByKeyId("oldkey000001")).isPresent()
                .get().extracting(k -> k.clientId()).isEqualTo("old-agent");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND name='idx_api_keys_client' AND sql LIKE '%api_client_id%'", Integer.class)).isEqualTo(1);
    }

    @Test
    void sqliteDbFromC73473fUpgrades() throws Exception {
        Path f = Files.createTempFile("lifeos-c73473f-", ".db");
        Files.delete(f);
        f.toFile().deleteOnExit();
        checkUpgrade(new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:" + f)), null);
    }

    @Test
    void libSqlDbFromC73473fUpgrades() throws Exception {
        Path f = Files.createTempFile("lifeos-c73473f-libsql-", ".db");
        Files.delete(f);
        f.toFile().deleteOnExit();
        try (FakeTursoServer server = new FakeTursoServer(f)) {
            DriverManagerDataSource ds = new DriverManagerDataSource(server.url());
            ds.setDriverClassName("com.lifeos.db.libsql.LibSqlHttpDriver");
            checkUpgrade(new JdbcTemplate(ds), server.url());
        }
    }

    @Test
    void renameToleranceIsNarrow() {
        var e = new RuntimeException("SQLITE_ERROR: no such column: \"client_id\"");
        assertThat(SchemaInitializer.expectedFailure("ALTER TABLE api_keys RENAME COLUMN client_id TO api_client_id", e)).isNotNull();
        assertThat(SchemaInitializer.expectedFailure("-- comment\n-- more\nALTER TABLE api_keys RENAME COLUMN client_id TO api_client_id", e)).isNotNull();
        assertThat(SchemaInitializer.expectedFailure("INSERT INTO api_keys (client_id) VALUES (1)", e)).isNull();
        assertThat(SchemaInitializer.expectedFailure("ALTER TABLE api_keys ADD COLUMN x TEXT", e)).isNull();
    }
}
