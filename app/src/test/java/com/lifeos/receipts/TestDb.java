package com.lifeos.receipts;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** Creates a SQLite file with the app schema plus test-only failure triggers. */
final class TestDb {
    private TestDb() {}

    static Path create() throws Exception {
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

    /** Split a SQL script on ';', ignoring '--' comments and quoted strings. */
    static List<String> split(String script) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < script.length(); i++) {
            char ch = script.charAt(i);
            if (quote != 0) {
                cur.append(ch);
                if (ch == quote) quote = 0;
            } else if (ch == '\'' || ch == '"') {
                quote = ch;
                cur.append(ch);
            } else if (ch == '-' && i + 1 < script.length() && script.charAt(i + 1) == '-') {
                while (i < script.length() && script.charAt(i) != '\n') i++;
                cur.append('\n');
            } else if (ch == ';') {
                if (!cur.toString().isBlank()) out.add(cur.toString().strip());
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        if (!cur.toString().isBlank()) out.add(cur.toString().strip());
        return out;
    }
}
