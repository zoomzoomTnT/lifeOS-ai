package com.lifeos.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Applies schema.sql (idempotent) on every start. It never changes an existing table: before 1.0.0 a
 * schema change on an existing table is a drop and recreate (docs/auth.md, "Schema upgrades"). Runs
 * once all singletons exist and before the web server's connector starts accepting requests.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SchemaInitializer implements SmartInitializingSingleton {

    private final JdbcTemplate jdbc;
    private final ResourceLoader resourceLoader;

    @Value("${life.schema-path:classpath:schema.sql}")
    private String schemaPath;

    @Value("${spring.datasource.url:}")
    private String datasourceUrl;

    @Override
    public void afterSingletonsInstantiated() {
        try {
            apply();
        } catch (IOException e) {
            throw new UncheckedIOException("schema init: cannot read script: " + e.getMessage(), e);
        }
    }

    /** Bring the database to the current schema now (also the entry point for tests and tools). */
    public void apply() throws IOException {
        log.info("Applying {} (idempotent)", schemaPath);
        execScript(schemaPath);
        log.info("Schema up to date");
    }

    private void execScript(String location) throws IOException {
        Resource resource = resourceLoader.getResource(location);
        String sql = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
        // Not sql.split(";"): that broke statements with ';' in a comment or string literal.
        boolean libSql = ForeignKeyCheck.isLibSql(datasourceUrl);
        for (String s : SqlScriptSplitter.split(sql)) {
            if (libSql && isPragma(s)) {
                // libSQL: the driver enables foreign_keys on every stream; journal_mode is the
                // server's (sqld rejects it as an unsupported statement). Don't send script PRAGMAs.
                log.debug("Not sent on libSQL (handled by driver/server): {}", abbreviate(s));
                continue;
            }
            try {
                jdbc.execute(s);
            } catch (Exception e) {
                String expected = expectedFailure(s, e);
                if (expected == null) {
                    throw new IllegalStateException("Schema script " + location + " failed at: "
                            + abbreviate(s) + " → " + e.getMessage(), e);
                }
                log.info("Skipped (expected, {}): {}", expected, abbreviate(s));
            }
        }
    }

    /**
     * The only tolerated failures: PRAGMA statements the server refuses (Turso's legacy HTTP
     * endpoint rejects {@code PRAGMA foreign_keys = ON} / {@code journal_mode = WAL} from
     * schema.sql). Returns why it is expected, or null.
     */
    static boolean isPragma(String stmt) {
        return stmt.strip().toUpperCase(java.util.Locale.ROOT).startsWith("PRAGMA");
    }

    static String expectedFailure(String stmt, Exception e) {
        String msg = String.valueOf(e.getMessage()).toLowerCase(java.util.Locale.ROOT);
        Throwable c = e.getCause();
        while (c != null) {
            msg += " " + String.valueOf(c.getMessage()).toLowerCase(java.util.Locale.ROOT);
            c = c.getCause();
        }
        if (stmt.strip().toUpperCase(java.util.Locale.ROOT).startsWith("PRAGMA")) return "PRAGMA not supported here";
        return null;
    }

    private static String abbreviate(String s) {
        return s.substring(0, Math.min(80, s.length())).replace('\n', ' ');
    }
}
