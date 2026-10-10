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

/** Runs the real SchemaInitializer (schema.sql + migrations 0002..0004) against a SQLite file. */
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
        init.run(null);
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
        // Base seed (preceded by a comment with ';') + 0004 seed (';' inside the notes string).
        assertThat(count(jdbc, "SELECT COUNT(*) FROM model_prices")).isEqualTo(7);
        Map<String, Object> gemini = jdbc.queryForMap(
                "SELECT input_usd_micros_per_mtok AS i, output_usd_micros_per_mtok AS o, notes FROM model_prices "
                        + "WHERE provider='google' AND model='gemini-3.1-pro-preview'");
        assertThat(gemini).containsEntry("i", 2000000).containsEntry("o", 12000000)
                .containsEntry("notes", "estimate; prefer invoice / TokenHub");
        assertThat(count(jdbc, "SELECT COUNT(*) FROM schema_migrations WHERE name='0004_session_v3'")).isEqualTo(1);
    }

    @Test
    void rerunIsIdempotentAndBackfillsMissingSeedRow() throws Exception {
        JdbcTemplate jdbc = sqlite();
        runInit(jdbc);
        // Like prod: 0004 recorded as applied but the gemini row missing (old splitter broke it).
        jdbc.update("DELETE FROM model_prices WHERE provider='google'");
        assertThat(count(jdbc, "SELECT COUNT(*) FROM model_prices")).isEqualTo(6);

        runInit(jdbc);
        runInit(jdbc);

        assertThat(count(jdbc, "SELECT COUNT(*) FROM model_prices")).isEqualTo(7);
        assertThat(count(jdbc, "SELECT COUNT(*) FROM model_prices WHERE notes='estimate; prefer invoice / TokenHub'")).isEqualTo(1);
        assertThat(count(jdbc, "SELECT COUNT(*) FROM schema_migrations WHERE name='0004_session_v3'")).isEqualTo(1);
    }
}
