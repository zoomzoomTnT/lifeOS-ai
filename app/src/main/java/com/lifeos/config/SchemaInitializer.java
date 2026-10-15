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
 * Applies schema.sql (idempotent) on every start. It never alters existing tables: a column added
 * to an existing table needs a one-time manual ALTER (docs/auth.md, "Schema upgrades"). Refuses
 * databases from an earlier PR #29 build of the auth tables. Runs once all singletons exist and before the web server's connector starts
 * accepting requests.
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
        String stale = staleAuthSchema();
        if (stale != null) {
            String msg = "This database has the auth tables of an earlier PR #29 build of migration 0005 (" + stale
                    + "). 0005 was rewritten in place before release and is not upgraded automatically. It only "
                    + "exists in dev/test databases: recreate this database (delete the SQLite file, or create a "
                    + "new Turso/sqld database) and start again. See docs/auth.md (\"Old 0005 databases\").";
            log.error(msg);
            throw new IllegalStateException(msg);
        }
        log.info("Applying {} (idempotent)", schemaPath);
        execScript(schemaPath);
        log.info("Schema up to date");
    }

    /**
     * Null if 0005 has not run yet or ran in its final form, else what gives away the earlier build
     * (passkey / one-time-token tables, approval_requests without decided_by_client, or api_clients
     * without the admin kind).
     */
    String staleAuthSchema() {
        var legacy = jdbc.queryForList("SELECT name FROM sqlite_master WHERE type='table' AND name IN "
                + "('user_entities','user_credentials','one_time_tokens') ORDER BY name", String.class);
        if (!legacy.isEmpty()) return "tables " + String.join(", ", legacy);
        String approvals = tableSql("approval_requests");
        if (approvals != null && !approvals.contains("decided_by_client")) return "approval_requests has no decided_by_client";
        String clients = tableSql("api_clients");
        if (clients != null && !clients.contains("'admin'")) return "api_clients does not allow kind 'admin'";
        return null;
    }

    private String tableSql(String table) {
        var sql = jdbc.queryForList("SELECT sql FROM sqlite_master WHERE type='table' AND name = ?", String.class, table);
        return sql.isEmpty() ? null : sql.get(0);
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
