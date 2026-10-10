package com.lifeos.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Turso (libSQL) profile: verify at startup that the server enforces foreign keys.
 *
 * <p>The libSQL driver ({@code com.lifeos.db.libsql}) sends {@code PRAGMA foreign_keys=ON} at
 * the start of every Hrana stream (statements only run if it succeeded), so this should always
 * read 1; it stays as a cheap guard. It only READS the pragma (no connection-init-sql write: the
 * old DBeaver driver's legacy HTTP endpoint rejected that and crash-looped the pool).
 *
 * <p>Default on failure: log an ERROR and keep running. A fail-fast here would crash-loop prod
 * (the outage this hotfix fixes) on a check that by itself does not damage data; the app ran
 * before with the same server setting. Set {@code life.db.require-foreign-keys=true} to fail
 * startup instead.
 */
@Component
@Order(0)
@Slf4j
public class ForeignKeyCheck implements ApplicationRunner {

    private final JdbcTemplate jdbc;
    private final String url;
    private final boolean require;

    public ForeignKeyCheck(JdbcTemplate jdbc,
                           @Value("${spring.datasource.url:}") String url,
                           @Value("${life.db.require-foreign-keys:false}") boolean require) {
        this.jdbc = jdbc;
        this.url = url;
        this.require = require;
    }

    static boolean isLibSql(String url) {
        return url != null && url.toLowerCase(java.util.Locale.ROOT).contains("libsql:");
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!isLibSql(url)) return;
        Integer v;
        try {
            v = jdbc.queryForObject("PRAGMA foreign_keys", Integer.class);
        } catch (Exception e) {
            problem("could not read PRAGMA foreign_keys: " + e.getMessage(), e);
            return;
        }
        if (v != null && v == 1) {
            log.info("Turso: foreign_keys=1 (enforced by the server)");
        } else {
            problem("PRAGMA foreign_keys returned " + v + ", expected 1: the database is NOT enforcing "
                    + "foreign keys (orphan rows possible)", null);
        }
    }

    private void problem(String msg, Exception cause) {
        if (require) throw new IllegalStateException(msg + " (life.db.require-foreign-keys=true)", cause);
        log.error("{}. Startup continues; set life.db.require-foreign-keys=true to fail instead.", msg);
    }
}
