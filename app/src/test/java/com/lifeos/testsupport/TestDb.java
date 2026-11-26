package com.lifeos.testsupport;

import com.lifeos.config.SqlScriptSplitter;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

/** Creates a SQLite file with the app schema plus test-only failure triggers. */
public final class TestDb {
    private TestDb() {}

    public static final String[] SCRIPTS = {"/schema.sql"};

    /** The app schema only. */
    public static Path createPlain() throws Exception {
        Path file = Files.createTempFile("lifeos-test-", ".db");
        Files.delete(file);
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file); Statement st = c.createStatement()) {
            for (String res : SCRIPTS) {
                try (var in = TestDb.class.getResourceAsStream(res)) {
                    for (String sql : SqlScriptSplitter.split(new String(in.readAllBytes(), StandardCharsets.UTF_8))) {
                        st.execute(sql);
                    }
                }
            }
        }
        file.toFile().deleteOnExit();
        return file;
    }

    /** Schema + triggers that fail a statement in the MIDDLE of a multi-write, to prove nothing is kept. */
    public static Path create() throws Exception {
        Path file = createPlain();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file); Statement st = c.createStatement()) {
            st.execute("""
                    CREATE TRIGGER test_boom_item BEFORE INSERT ON receipt_items WHEN NEW.name = 'BOOM'
                    BEGIN SELECT RAISE(ABORT, 'boom item'); END""");
            st.execute("""
                    CREATE TRIGGER test_boom_fridge BEFORE INSERT ON fridge_items WHEN NEW.name = 'BOOMFOOD'
                    BEGIN SELECT RAISE(ABORT, 'boom fridge'); END""");
        }
        return file;
    }
}
