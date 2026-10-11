package com.lifeos.config;

import com.lifeos.db.libsql.LibSqlUrl;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DbEndpointTest {

    @Test
    void fileFromSqliteUrl() {
        assertEquals("/data/life.db", DbEndpoint.fileFrom("jdbc:sqlite:/data/life.db"));
    }

    @Test
    void fileFromRemoteUrlIsEmpty() {
        assertEquals("", DbEndpoint.fileFrom("jdbc:dbeaver:libsql:https://example.turso.io"));
        assertEquals("", DbEndpoint.fileFrom("libsql://example.turso.io"));
        assertEquals("", DbEndpoint.fileFrom(null));
    }

    @Test
    void libSqlUrlDetection() {
        assertTrue(LibSqlUrl.accepts("jdbc:dbeaver:libsql:https://example.turso.io"));
        assertTrue(LibSqlUrl.accepts("jdbc:libsql:https://example.turso.io"));
        assertTrue(LibSqlUrl.accepts("libsql://example.turso.io"));
        assertFalse(LibSqlUrl.accepts("jdbc:sqlite:/data/life.db"));
    }

    @Test
    void displayedUrlNeverContainsAToken() {
        assertEquals("https://example.turso.io",
                DbEndpoint.displayUrl("jdbc:libsql:https://example.turso.io?authToken=secret"));
        assertEquals("jdbc:sqlite:/data/life.db", DbEndpoint.displayUrl("jdbc:sqlite:/data/life.db"));
    }
}
