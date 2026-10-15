package com.lifeos.config;

import com.lifeos.LifeOsApplication;
import com.lifeos.db.libsql.LibSqlHttpDriver;
import com.lifeos.testsupport.FakeTursoServer;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Boots the real application with the real application*.yml, per profile — the profile/YAML wiring
 * QA asked for, including startup on an EMPTY database (schema created, incl. merchants).
 */
class ContextWiringTest {

    static final String[] QUIET = {
            "--life.openclaw.enabled=false", "--life.proactive.cron=-", "--life.logs.ingest-ms=86400000",
            "--server.port=0", "--logging.level.com.lifeos=WARN"};

    static ConfigurableApplicationContext boot(String... args) {
        List<String> all = new ArrayList<>(List.of(QUIET));
        all.addAll(List.of(args));
        return new SpringApplicationBuilder(LifeOsApplication.class)
                .web(WebApplicationType.NONE)
                .run(all.toArray(String[]::new));
    }

    static Throwable rootCause(Throwable t) {
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        return t;
    }

    static List<String> tables(ConfigurableApplicationContext ctx) {
        return ctx.getBean(JdbcTemplate.class).queryForList(
                "SELECT name FROM sqlite_master WHERE type='table'", String.class);
    }

    @Test
    void localProfileUsesTheSqliteFileAndCreatesTheSchema() throws Exception {
        Path dir = Files.createTempDirectory("lifeos-local-");
        Path db = dir.resolve("life.db");
        try (ConfigurableApplicationContext ctx = boot("--spring.profiles.active=local", "--LIFE_DB=" + db)) {
            HikariDataSource ds = (HikariDataSource) ctx.getBean(DataSource.class);
            assertEquals("jdbc:sqlite:" + db, ds.getJdbcUrl());
            assertEquals("org.sqlite.JDBC", ds.getDriverClassName());
            assertTrue(tables(ctx).containsAll(List.of("people", "merchants", "receipts", "ai_calls")), tables(ctx).toString());
        }
        assertTrue(Files.exists(db));
    }

    @Test
    void defaultProfileUsesTheLibSqlDriverAndCreatesTheSchemaOnAnEmptyDatabase() throws Exception {
        Path empty = Files.createTempFile("lifeos-empty-", ".db");
        try (FakeTursoServer turso = new FakeTursoServer(empty);
             ConfigurableApplicationContext ctx = boot(
                     "--spring.datasource.url=" + turso.url(), "--spring.datasource.password=t")) {
            HikariDataSource ds = (HikariDataSource) ctx.getBean(DataSource.class);
            assertEquals(LibSqlHttpDriver.class.getName(), ds.getDriverClassName());
            try (Connection c = ds.getConnection()) {
                assertTrue(c.isWrapperFor(Class.forName("com.lifeos.db.libsql.LibSqlConnection")));
            }
            List<String> t = tables(ctx);
            assertTrue(t.containsAll(List.of("people", "merchants", "receipts", "ai_calls", "app_logs")), t.toString());
            assertTrue(turso.executed.stream().anyMatch(s -> s.startsWith("CREATE TABLE IF NOT EXISTS merchants")));
        }
    }

    @Test
    void defaultProfileWithoutUrlFailsWithAClearMessage() {
        Throwable e = assertThrows(Throwable.class, () -> boot("--spring.datasource.url="));
        String msg = rootCause(e).getMessage();
        assertTrue(msg.contains("TURSO_DATABASE_URL is not set"), msg);
    }

    @Test
    void remoteUrlWithoutTokenFailsWithAClearMessage() {
        Throwable e = assertThrows(Throwable.class, () -> boot(
                "--spring.datasource.url=jdbc:libsql:https://nope-org.turso.io", "--spring.datasource.password="));
        String msg = rootCause(e).getMessage();
        assertTrue(msg.contains("TURSO_AUTH_TOKEN"), msg);
    }
}
