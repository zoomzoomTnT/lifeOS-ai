package com.lifeos.config;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlScriptTest {

    @Test
    void semicolonInLineCommentDoesNotSplit() {
        List<String> s = SqlScript.split("""
                CREATE TABLE merchants (
                  name_norm TEXT NOT NULL UNIQUE,  -- lowercase, strip spaces/punct; Chinese stays
                  kind TEXT
                );
                CREATE TABLE x (id INTEGER);
                """);
        assertEquals(2, s.size(), s.toString());
        assertTrue(s.get(0).startsWith("CREATE TABLE merchants"));
        assertTrue(s.get(0).contains("kind TEXT"));
    }

    @Test
    void blockCommentsAndStrings() {
        List<String> s = SqlScript.split("""
                /* header; with semicolon */
                INSERT INTO t VALUES ('a;b', 'it''s; fine', "q;id");
                INSERT INTO t VALUES ([we;ird], `x;y`);
                -- trailing comment;
                """);
        assertEquals(2, s.size(), s.toString());
        assertTrue(s.get(0).contains("'a;b'"));
        assertTrue(s.get(0).contains("'it''s; fine'"));
        assertTrue(s.get(1).contains("[we;ird]"));
    }

    @Test
    void triggerBodyStaysOneStatement() {
        List<String> s = SqlScript.split("""
                CREATE TRIGGER trg AFTER INSERT ON t BEGIN
                  UPDATE t SET a = 1;
                  UPDATE t SET b = 2;
                END;
                SELECT 1;
                """);
        assertEquals(2, s.size(), s.toString());
        assertTrue(s.get(0).endsWith("END"));
    }

    @Test
    void commentOnlyChunksAreDropped() {
        assertEquals(List.of(), SqlScript.split("-- just a comment;\n/* and ; another */\n;;"));
    }

    @Test
    void appSchemaContainsEveryCreateTable() throws Exception {
        String schema;
        try (var in = SqlScriptTest.class.getResourceAsStream("/schema.sql")) {
            schema = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        List<String> stmts = SqlScript.split(schema);
        long creates = stmts.stream().filter(x -> x.toUpperCase().startsWith("CREATE TABLE")).count();
        long expected = schema.lines().filter(l -> l.toUpperCase().startsWith("CREATE TABLE")).count();
        assertEquals(expected, creates);
        assertTrue(stmts.stream().anyMatch(x -> x.startsWith("CREATE TABLE merchants")));
    }
}
