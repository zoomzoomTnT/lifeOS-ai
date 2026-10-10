package com.lifeos.config;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Turso's legacy HTTP endpoint rejects PRAGMA assignments ("interactive transactions are not
 * supported for legacy HTTP protocol"). A write-PRAGMA in Hikari connection-init-sql makes the
 * pool fail fast and the app crash-loop on start (CD run 38049239405).
 */
class TursoDatasourceConfigTest {

    @Test
    @SuppressWarnings("unchecked")
    void defaultProfileHasNoPragmaAssignmentInConnectionInitSql() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/application.yml")) {
            assertNotNull(in);
            Map<String, Object> root = new Yaml().load(in);
            Map<String, Object> ds = (Map<String, Object>) ((Map<String, Object>) root.get("spring")).get("datasource");
            Map<String, Object> hikari = (Map<String, Object>) ds.get("hikari");
            Object init = hikari == null ? null : hikari.get("connection-init-sql");
            String sql = init == null ? "" : init.toString();
            assertFalse(sql.matches("(?is).*PRAGMA\\s+\\w+\\s*=.*"),
                    "Turso legacy HTTP rejects PRAGMA assignments in connection-init-sql: " + sql);
        }
    }
}
