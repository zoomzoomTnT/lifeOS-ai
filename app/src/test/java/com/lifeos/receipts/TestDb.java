package com.lifeos.receipts;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

/** Creates a SQLite file with the app schema plus test-only failure triggers. */
public final class TestDb {
    private TestDb() {}

    public static Path create() throws Exception {
        Path file = Files.createTempFile("lifeos-test-", ".db");
        Files.delete(file);
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file); Statement st = c.createStatement()) {
            for (String res : new String[]{"/schema.sql", "/migrations/0002_ops.sql", "/migrations/0003_logs.sql"}) {
                try (var in = TestDb.class.getResourceAsStream(res)) {
                    for (String sql : split(new String(in.readAllBytes(), StandardCharsets.UTF_8))) {
                        st.execute(sql);
                    }
                }
            }
            // Fail a statement in the MIDDLE of a batch, to prove nothing before it is kept.
            st.execute("""
                    CREATE TRIGGER test_boom_item BEFORE INSERT ON receipt_items WHEN NEW.name = 'BOOM'
                    BEGIN SELECT RAISE(ABORT, 'boom item'); END""");
            st.execute("""
                    CREATE TRIGGER test_boom_fridge BEFORE INSERT ON fridge_items WHEN NEW.name = 'BOOMFOOD'
                    BEGIN SELECT RAISE(ABORT, 'boom fridge'); END""");
        }
        return file;
    }

    /** Same splitter the app uses (SchemaInitializer). */
    static List<String> split(String script) {
        return com.lifeos.config.SqlScriptSplitter.split(script);
    }
}
