package com.lifeos.testsupport;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Default (production/Turso) profile wired to one shared {@link FakeTursoServer}: the full app
 * stack (Hikari → libSQL driver → Hrana HTTP) with a SQLite file behind it for direct checks.
 * Subclasses share one Spring context.
 */
public abstract class TursoSpringTestBase {

    protected static final Path DB;
    protected static final FakeTursoServer TURSO;

    static {
        try {
            DB = TestDb.create();
            try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + DB)) {
                c.createStatement().execute("""
                        CREATE TRIGGER test_boom_event BEFORE INSERT ON events WHEN NEW.payload_json = 'BOOMSYM'
                        BEGIN SELECT RAISE(ABORT, 'boom event'); END""");
            }
            TURSO = new FakeTursoServer(DB);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void tursoProps(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", TURSO::url);
        r.add("spring.datasource.password", () -> "test-token");
        r.add("spring.jpa.properties.hibernate.session_factory.statement_inspector", SqlCapture.class::getName);
        r.add("life.openclaw.enabled", () -> "false");
        r.add("life.proactive.cron", () -> "-");
        r.add("life.logs.ingest-ms", () -> "86400000");
    }

    /** Reads the database file directly, bypassing the app. */
    protected static long count(String sql, Object... args) throws SQLException {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + DB);
             var ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }
}
