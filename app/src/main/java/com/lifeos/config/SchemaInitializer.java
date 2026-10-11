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

import java.nio.charset.StandardCharsets;

/**
 * Fresh DB: apply schema.sql. Existing DB: apply additive migrations (IF NOT EXISTS).
 * Runs once all singletons exist and before the web server's connector starts accepting requests
 * (it used to be an ApplicationRunner, i.e. after the port was open, so the first requests after a
 * deploy could hit a table that did not exist yet).
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
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("schema init failed: " + e.getMessage(), e);
        }
    }

    /** Apply schema.sql / migrations now (also the entry point for tests and tools). */
    public void apply() throws Exception {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='people'",
                Integer.class);
        if (count == null || count == 0) {
            log.info("Initializing schema from {}", schemaPath);
            execScript(schemaPath);
            log.info("Schema init done");
        } else {
            log.info("Base schema present");
        }

        log.info("Applying ops migration 0002 (idempotent)");
        execScript("classpath:migrations/0002_ops.sql");
        log.info("Applying logs migration 0003 (idempotent)");
        execScript("classpath:migrations/0003_logs.sql");
        log.info("Applying session-v3 migration 0004 (idempotent)");
        execScript("classpath:migrations/0004_session_v3.sql");
        log.info("Applying auth migration 0005 (idempotent)");
        execScript("classpath:migrations/0005_auth.sql");
    }

    private void execScript(String location) throws Exception {
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
                if (s.strip().toUpperCase(java.util.Locale.ROOT).startsWith("PRAGMA")) {
                    log.info("Skipped (expected, {}): {}", expected, abbreviate(s));
                } else {
                    log.debug("Skipped (expected, {}): {}", expected, abbreviate(s)); // every start
                }
            }
        }
    }

    /**
     * The only tolerated failures: additive {@code ALTER TABLE ... ADD COLUMN} re-runs
     * ("duplicate column name") and PRAGMA statements the server refuses (Turso's legacy HTTP
     * endpoint rejects {@code PRAGMA foreign_keys = ON} / {@code journal_mode = WAL} from
     * schema.sql). Returns why it is expected, or null.
     */
    static boolean isPragma(String stmt) {
        return stmt.strip().toUpperCase(java.util.Locale.ROOT).startsWith("PRAGMA");
    }

    public static String expectedFailure(String stmt, Exception e) {
        String msg = String.valueOf(e.getMessage()).toLowerCase(java.util.Locale.ROOT);
        Throwable c = e.getCause();
        while (c != null) {
            msg += " " + String.valueOf(c.getMessage()).toLowerCase(java.util.Locale.ROOT);
            c = c.getCause();
        }
        if (msg.contains("duplicate column name")) return "column already exists";
        // guarded one-off renames in a migration: already renamed (or created with the new name)
        if (msg.contains("no such column") && stmt.toUpperCase(java.util.Locale.ROOT).matches("(?s)(\\s*--[^\\n]*\\n)*\\s*ALTER TABLE \\S+ RENAME COLUMN .*")) {
            return "column already renamed";
        }
        if (stmt.strip().toUpperCase(java.util.Locale.ROOT).startsWith("PRAGMA")) return "PRAGMA not supported here";
        return null;
    }

    private static String abbreviate(String s) {
        return s.substring(0, Math.min(80, s.length())).replace('\n', ' ');
    }
}
