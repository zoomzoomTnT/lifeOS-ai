package com.lifeos.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Runs the real SchemaInitializer (schema.sql + required columns) against a SQLite file. */
class SchemaInitializerSqliteTest {

    private static JdbcTemplate sqlite() throws Exception {
        Path file = Files.createTempFile("lifeos-schema-", ".db");
        Files.delete(file);
        file.toFile().deleteOnExit();
        return new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:" + file));
    }

    private static void runInit(JdbcTemplate jdbc) throws Exception {
        SchemaInitializer init = new SchemaInitializer(jdbc, new DefaultResourceLoader());
        ReflectionTestUtils.setField(init, "schemaPath", "classpath:schema.sql");
        init.apply();
    }

    private static int count(JdbcTemplate jdbc, String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }

    @Test
    void freshDbGetsFullSchemaAndAllSeedPrices() throws Exception {
        JdbcTemplate jdbc = sqlite();
        runInit(jdbc);

        // merchants has a ';' in a column comment; it used to be split in half and never created.
        assertThat(count(jdbc, "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='merchants'")).isEqualTo(1);
        // Seed preceded by a comment with ';' + the gemini row (';' inside the notes string).
        assertThat(count(jdbc, "SELECT COUNT(*) FROM model_prices")).isEqualTo(7);
        Map<String, Object> gemini = jdbc.queryForMap(
                "SELECT input_usd_micros_per_mtok AS i, output_usd_micros_per_mtok AS o, notes FROM model_prices "
                        + "WHERE provider='google' AND model='gemini-3.1-pro-preview'");
        assertThat(gemini).containsEntry("i", 2000000).containsEntry("o", 12000000)
                .containsEntry("notes", "estimate; prefer invoice / TokenHub");
        assertThat(count(jdbc, "SELECT COUNT(*) FROM schema_migrations WHERE name='0004_session_v3'")).isEqualTo(1);
        assertThat(count(jdbc, "SELECT COUNT(*) FROM schema_migrations WHERE name='0005_auth'")).isEqualTo(1);
        for (String t : new String[]{"user_entities", "user_credentials", "one_time_tokens"}) { // no web login
            assertThat(count(jdbc, "SELECT COUNT(*) FROM sqlite_master WHERE name='" + t + "'")).as(t).isZero();
        }
    }

    @Test
    void rerunIsIdempotentAndBackfillsMissingSeedRow() throws Exception {
        JdbcTemplate jdbc = sqlite();
        runInit(jdbc);
        // Like prod once: the gemini row missing (an old splitter broke it); schema.sql backfills it.
        jdbc.update("DELETE FROM model_prices WHERE provider='google'");
        assertThat(count(jdbc, "SELECT COUNT(*) FROM model_prices")).isEqualTo(6);

        runInit(jdbc);
        runInit(jdbc);

        assertThat(count(jdbc, "SELECT COUNT(*) FROM model_prices")).isEqualTo(7);
        assertThat(count(jdbc, "SELECT COUNT(*) FROM model_prices WHERE notes='estimate; prefer invoice / TokenHub'")).isEqualTo(1);
        assertThat(count(jdbc, "SELECT COUNT(*) FROM schema_migrations WHERE name='0004_session_v3'")).isEqualTo(1);
    }

    @Test
    void aPre0005DatabaseGetsTheAuthTablesAndColumnsAndRerunsAreIdempotent() throws Exception {
        JdbcTemplate jdbc = sqlite();
        runInit(jdbc);
        // Simulate prod before 0005: no auth tables, events/http_requests without the audit columns.
        for (String t : new String[]{"api_keys", "api_clients", "security_events", "approval_requests"}) {
            jdbc.execute("DROP TABLE " + t);
        }
        jdbc.execute("ALTER TABLE events DROP COLUMN client_id");
        jdbc.execute("ALTER TABLE events DROP COLUMN credential_id");
        jdbc.execute("ALTER TABLE events DROP COLUMN request_id");
        jdbc.execute("ALTER TABLE events DROP COLUMN approval_id");
        jdbc.execute("ALTER TABLE http_requests DROP COLUMN client_id");
        jdbc.execute("ALTER TABLE http_requests DROP COLUMN credential_id");
        jdbc.execute("DELETE FROM schema_migrations WHERE name='0005_auth'");
        jdbc.execute("DELETE FROM settings WHERE key LIKE 'approval.%'");

        runInit(jdbc);
        runInit(jdbc);

        for (String t : new String[]{"api_clients", "api_keys", "security_events", "approval_requests"}) {
            assertThat(count(jdbc, "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='" + t + "'"))
                    .as(t).isEqualTo(1);
        }
        jdbc.queryForList("SELECT client_id, credential_id, request_id, approval_id FROM events");
        jdbc.queryForList("SELECT client_id, credential_id FROM http_requests");
        assertThat(count(jdbc, "SELECT COUNT(*) FROM settings WHERE key LIKE 'approval.%'")).isEqualTo(2);
        assertThat(count(jdbc, "SELECT COUNT(*) FROM schema_migrations WHERE name='0005_auth'")).isEqualTo(1);
        for (String t : new String[]{"user_entities", "user_credentials", "one_time_tokens"}) { // no web login
            assertThat(count(jdbc, "SELECT COUNT(*) FROM sqlite_master WHERE name='" + t + "'")).as(t).isZero();
        }
    }

    /** Captures this class's log output during one run. */
    private static java.util.List<ch.qos.logback.classic.spi.ILoggingEvent> logsOf(ThrowingRunnable r) throws Exception {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(SchemaInitializer.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> app =
                new ch.qos.logback.core.read.ListAppender<>();
        app.start();
        logger.addAppender(app);
        // other tests in the same JVM may have left com.lifeos at WARN (Spring's LoggingSystem)
        ch.qos.logback.classic.Level level = logger.getLevel();
        logger.setLevel(ch.qos.logback.classic.Level.DEBUG);
        try {
            r.run();
        } finally {
            logger.setLevel(level);
            logger.detachAppender(app);
        }
        return app.list;
    }

    interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static java.util.List<String> warnings(java.util.List<ch.qos.logback.classic.spi.ILoggingEvent> events) {
        return events.stream().filter(e -> e.getLevel().isGreaterOrEqual(ch.qos.logback.classic.Level.WARN))
                .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage).toList();
    }

    @Test
    void freshStartLogsNoWarnings() throws Exception {
        JdbcTemplate jdbc = sqlite();
        assertThat(warnings(logsOf(() -> runInit(jdbc)))).isEmpty();
        assertThat(warnings(logsOf(() -> runInit(jdbc)))).as("re-run").isEmpty();
    }

    /** Like Turso's legacy HTTP endpoint: PRAGMA writes are refused. */
    static final class NoPragmaWrites extends JdbcTemplate {
        NoPragmaWrites(JdbcTemplate real) {
            super(real.getDataSource());
        }

        @Override
        public void execute(String sql) {
            if (sql.strip().toUpperCase().startsWith("PRAGMA") && sql.contains("=")) {
                throw new org.springframework.jdbc.UncategorizedSQLException("execute", sql, new java.sql.SQLException(
                        "Protocol error: interactive transactions are not supported for legacy HTTP protocol"));
            }
            super.execute(sql);
        }
    }

    @Test
    void freshStartOnAServerThatRefusesPragmaWritesStillBuildsTheSchemaWithoutWarnings() throws Exception {
        JdbcTemplate jdbc = new NoPragmaWrites(sqlite());
        java.util.List<ch.qos.logback.classic.spi.ILoggingEvent> events = logsOf(() -> runInit(jdbc));
        assertThat(warnings(events)).isEmpty();
        assertThat(events).extracting(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                .anyMatch(m -> m.contains("PRAGMA not supported") && m.contains("foreign_keys"))
                .anyMatch(m -> m.contains("PRAGMA not supported") && m.contains("journal_mode"));
        assertThat(count(jdbc, "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='merchants'")).isEqualTo(1);
        assertThat(count(jdbc, "SELECT COUNT(*) FROM model_prices")).isEqualTo(7);
    }

    @Test
    void unexpectedStatementFailureFailsStartup() throws Exception {
        Path script = Files.createTempFile("lifeos-bad-", ".sql");
        Files.writeString(script, "CREATE TABLE people (id INTEGER PRIMARY KEY);\nINSERT INTO no_such_table VALUES (1);\n");
        JdbcTemplate jdbc = sqlite();
        SchemaInitializer init = new SchemaInitializer(jdbc, new DefaultResourceLoader());
        ReflectionTestUtils.setField(init, "schemaPath", script.toUri().toString());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> init.apply())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("INSERT INTO no_such_table")
                .hasMessageContaining("no such table");
    }

    /** A dev/test DB migrated by an earlier PR #29 build of 0005 (e.g. a4e5e7d): refuse to start, never upgrade. */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "CREATE TABLE user_entities (id TEXT PRIMARY KEY, name TEXT)",
            "ALTER TABLE approval_requests DROP COLUMN decided_by_client",
            "DROP TABLE api_keys; DROP TABLE api_clients; CREATE TABLE api_clients (id INTEGER PRIMARY KEY, "
                    + "client_id TEXT, kind TEXT CHECK (kind IN ('agent','human_app','service')))"})
    void anOld0005DatabaseFailsFastWithARecreateMessage(String makeOld) throws Exception {
        JdbcTemplate jdbc = sqlite();
        runInit(jdbc);
        for (String stmt : makeOld.split(";")) jdbc.execute(stmt);
        int before = count(jdbc, "SELECT COUNT(*) FROM sqlite_master");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> runInit(jdbc))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("earlier PR #29 build of migration 0005")
                .hasMessageContaining("recreate this database");
        assertThat(count(jdbc, "SELECT COUNT(*) FROM sqlite_master")).isEqualTo(before); // nothing touched
    }
}
