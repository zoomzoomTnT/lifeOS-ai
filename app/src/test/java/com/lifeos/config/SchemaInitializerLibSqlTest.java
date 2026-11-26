package com.lifeos.config;

import com.lifeos.testsupport.FakeTursoServer;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/** SchemaInitializer on libSQL: script PRAGMAs (journal_mode, foreign_keys) are not sent. */
class SchemaInitializerLibSqlTest {

    @Test
    void freshLibSqlStartSendsNoScriptPragmas() throws Exception {
        Path file = Files.createTempFile("lifeos-schema-libsql-", ".db");
        Files.delete(file);
        file.toFile().deleteOnExit();
        try (FakeTursoServer server = new FakeTursoServer(file)) {
            DriverManagerDataSource ds = new DriverManagerDataSource(server.url());
            ds.setDriverClassName("com.lifeos.db.libsql.LibSqlHttpDriver");
            JdbcTemplate jdbc = new JdbcTemplate(ds);
            SchemaInitializer init = new SchemaInitializer(jdbc, new DefaultResourceLoader());
            ReflectionTestUtils.setField(init, "schemaPath", "classpath:schema.sql");
            ReflectionTestUtils.setField(init, "datasourceUrl", server.url());
            init.apply();

            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name IN ('people','merchants')",
                    Integer.class)).isEqualTo(2);
            assertThat(server.executed).noneMatch(s -> s.toLowerCase(Locale.ROOT).contains("journal_mode"));
            // only the driver's own per-stream PRAGMA, never the script's "PRAGMA foreign_keys = ON"
            assertThat(server.executed).filteredOn(s -> s.toUpperCase(Locale.ROOT).startsWith("PRAGMA"))
                    .allMatch(s -> s.equals("PRAGMA foreign_keys=ON"));
        }
    }

    @Test
    void isPragma() {
        assertThat(SchemaInitializer.isPragma("  pragma journal_mode = WAL")).isTrue();
        assertThat(SchemaInitializer.isPragma("CREATE TABLE t(x)")).isFalse();
    }
}
