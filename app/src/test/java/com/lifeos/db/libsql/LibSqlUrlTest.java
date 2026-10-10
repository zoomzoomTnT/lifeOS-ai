package com.lifeos.db.libsql;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LibSqlUrlTest {

    @Test
    void acceptsOnlyLibSqlUrls() {
        assertTrue(LibSqlUrl.accepts("jdbc:libsql:https://db-org.turso.io"));
        assertTrue(LibSqlUrl.accepts("jdbc:dbeaver:libsql:https://db-org.turso.io"));
        assertTrue(LibSqlUrl.accepts("libsql://db-org.turso.io"));
        assertFalse(LibSqlUrl.accepts("jdbc:sqlite:/data/life.db"));
        assertFalse(LibSqlUrl.accepts("https://db-org.turso.io"));
        assertFalse(LibSqlUrl.accepts(null));
        assertFalse(LibSqlUrl.accepts(""));
    }

    @Test
    void allFormsMapToTheHttpBase() {
        assertEquals("https://db-org.turso.io", LibSqlUrl.parse("jdbc:libsql:https://db-org.turso.io/").httpBase());
        assertEquals("https://db-org.turso.io", LibSqlUrl.parse("jdbc:dbeaver:libsql:https://db-org.turso.io").httpBase());
        assertEquals("https://db-org.turso.io", LibSqlUrl.parse("libsql://db-org.turso.io").httpBase());
        assertEquals("https://db-org.turso.io", LibSqlUrl.parse("jdbc:libsql://db-org.turso.io").httpBase());
        assertEquals("http://127.0.0.1:8080", LibSqlUrl.parse("jdbc:libsql:http://127.0.0.1:8080").httpBase());
    }

    @Test
    void tokenRequiredOnlyForTls() {
        assertTrue(LibSqlUrl.parse("libsql://db-org.turso.io").requiresAuthToken());
        assertFalse(LibSqlUrl.parse("jdbc:libsql:http://localhost:8080").requiresAuthToken());
    }

    @Test
    void authTokenQueryParamIsSeparatedAndNotDisplayed() {
        LibSqlUrl u = LibSqlUrl.parse("libsql://db-org.turso.io?authToken=abc%2Bdef");
        assertEquals("abc+def", u.authTokenFromUrl());
        assertEquals("https://db-org.turso.io", u.display());
        assertNull(LibSqlUrl.parse("libsql://db-org.turso.io").authTokenFromUrl());
    }

    @Test
    void rejectsGarbage() {
        assertThrows(IllegalArgumentException.class, () -> LibSqlUrl.parse("jdbc:libsql:ftp://x"));
        assertThrows(IllegalArgumentException.class, () -> LibSqlUrl.parse("jdbc:sqlite:x.db"));
    }
}
