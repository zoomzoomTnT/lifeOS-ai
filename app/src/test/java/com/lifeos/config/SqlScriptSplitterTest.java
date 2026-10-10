package com.lifeos.config;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SqlScriptSplitterTest {

    @Test
    void splitsOnTopLevelSemicolons() {
        assertThat(SqlScriptSplitter.split("SELECT 1; SELECT 2;\nSELECT 3"))
                .containsExactly("SELECT 1", "SELECT 2", "SELECT 3");
    }

    @Test
    void semicolonInSingleQuotedString() {
        assertThat(SqlScriptSplitter.split("INSERT INTO t VALUES ('a; b'); SELECT 2;"))
                .containsExactly("INSERT INTO t VALUES ('a; b')", "SELECT 2");
    }

    @Test
    void doubledSingleQuoteIsAnEscape() {
        assertThat(SqlScriptSplitter.split("SELECT 'it''s; fine', 'x'; SELECT 2"))
                .containsExactly("SELECT 'it''s; fine', 'x'", "SELECT 2");
        assertThat(SqlScriptSplitter.split("SELECT ''; SELECT ''''; SELECT 3"))
                .containsExactly("SELECT ''", "SELECT ''''", "SELECT 3");
    }

    @Test
    void semicolonInQuotedIdentifiers() {
        assertThat(SqlScriptSplitter.split("CREATE TABLE \"we;ird\" (\"c;\"\"ol\" TEXT, `b;t` INT, [s;q] INT); SELECT 2"))
                .containsExactly("CREATE TABLE \"we;ird\" (\"c;\"\"ol\" TEXT, `b;t` INT, [s;q] INT)", "SELECT 2");
    }

    @Test
    void semicolonInLineCommentIsIgnoredAndCommentDropped() {
        List<String> out = SqlScriptSplitter.split("""
                -- header; with a semicolon
                CREATE TABLE m (
                  a TEXT, -- note; still the same statement
                  b TEXT
                );
                SELECT 1;""");
        assertThat(out).hasSize(2);
        assertThat(out.get(0)).startsWith("CREATE TABLE m (").endsWith(")").contains("b TEXT").doesNotContain("note");
        assertThat(out.get(1)).isEqualTo("SELECT 1");
    }

    @Test
    void semicolonInBlockComment() {
        assertThat(SqlScriptSplitter.split("SELECT /* a; b\n c; */ 1; /* only; a comment */; SELECT 2"))
                .containsExactly("SELECT   1", "SELECT 2");
    }

    @Test
    void commentMarkersInsideStringsAreText() {
        assertThat(SqlScriptSplitter.split("SELECT '-- not; a comment', '/* nor; this */'; SELECT 2"))
                .containsExactly("SELECT '-- not; a comment', '/* nor; this */'", "SELECT 2");
    }

    @Test
    void commentOnlyAndBlankScriptsYieldNothing() {
        assertThat(SqlScriptSplitter.split("-- a; b\n\n/* c; */\n ;; \n")).isEmpty();
        assertThat(SqlScriptSplitter.split("")).isEmpty();
    }

    @Test
    void triggerBodyIsOneStatement() {
        List<String> out = SqlScriptSplitter.split("""
                CREATE TRIGGER trg AFTER INSERT ON t BEGIN
                  UPDATE t SET a = 1;
                  INSERT INTO log VALUES ('x; y');
                END;
                SELECT 1;""");
        assertThat(out).hasSize(2);
        assertThat(out.get(0)).startsWith("CREATE TRIGGER trg").endsWith("END")
                .contains("UPDATE t SET a = 1;").contains("INSERT INTO log VALUES ('x; y');");
        assertThat(out.get(1)).isEqualTo("SELECT 1");
    }

    @Test
    void migration0004KeepsSeedRowWithSemicolonInString() throws Exception {
        List<String> out = SqlScriptSplitter.split(resource("/migrations/0004_session_v3.sql"));
        assertThat(out).noneMatch(s -> s.contains("SchemaInitializer ignores"));
        assertThat(out).filteredOn(s -> s.startsWith("INSERT OR IGNORE INTO model_prices"))
                .singleElement().asString()
                .contains("'estimate; prefer invoice / TokenHub')");
        assertThat(out).allMatch(s -> !s.startsWith("prefer invoice"));
        assertThat(out.get(0)).isEqualTo("ALTER TABLE ai_session_logs ADD COLUMN event_id TEXT");
    }

    @Test
    void schemaSqlKeepsStatementsWithSemicolonsInComments() throws Exception {
        List<String> out = SqlScriptSplitter.split(resource("/schema.sql"));
        assertThat(out).filteredOn(s -> s.startsWith("CREATE TABLE merchants"))
                .singleElement().asString().contains("name_norm").contains("favorite_score").endsWith(")");
        assertThat(out).filteredOn(s -> s.startsWith("INSERT INTO model_prices")).hasSize(1);
        assertThat(out).noneMatch(s -> s.startsWith("Chinese stays") || s.startsWith("PATCH /api/ops/prices"));
    }

    private static String resource(String path) throws Exception {
        try (InputStream in = SqlScriptSplitterTest.class.getResourceAsStream(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
