package com.lifeos.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseSettingsCheckTest {

    @Test
    void missingUrlIsExplained() {
        String p = DatabaseSettingsCheck.problem("", "tok");
        assertTrue(p.contains("TURSO_DATABASE_URL") && p.contains("local"), p);
        assertTrue(DatabaseSettingsCheck.problem(null, null).contains("TURSO_DATABASE_URL"));
    }

    @Test
    void remoteUrlWithoutTokenIsExplained() {
        String p = DatabaseSettingsCheck.problem("libsql://db-org.turso.io", "");
        assertTrue(p.contains("TURSO_AUTH_TOKEN"), p);
        assertTrue(DatabaseSettingsCheck.problem("jdbc:libsql:https://db-org.turso.io", null).contains("TURSO_AUTH_TOKEN"));
    }

    @Test
    void validSettingsPass() {
        assertNull(DatabaseSettingsCheck.problem("jdbc:libsql:https://db-org.turso.io", "tok"));
        assertNull(DatabaseSettingsCheck.problem("libsql://db-org.turso.io?authToken=tok", null));
        assertNull(DatabaseSettingsCheck.problem("jdbc:libsql:http://localhost:8080", null)); // local sqld
        assertNull(DatabaseSettingsCheck.problem("jdbc:sqlite:/tmp/x.db", ""));
    }

    @Test
    void validateThrows() {
        assertThrows(IllegalStateException.class, () -> DatabaseSettingsCheck.validate("libsql://x.turso.io", ""));
    }
}
