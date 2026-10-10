package com.lifeos.config;

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
        assertTrue(LibSqlCompatConfig.isLibSql("jdbc:dbeaver:libsql:https://example.turso.io"));
        assertTrue(LibSqlCompatConfig.isLibSql("libsql://example.turso.io"));
        assertFalse(LibSqlCompatConfig.isLibSql("jdbc:sqlite:/data/life.db"));
    }
}
