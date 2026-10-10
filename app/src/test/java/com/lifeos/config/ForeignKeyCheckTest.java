package com.lifeos.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ForeignKeyCheckTest {

    static final String TURSO = "jdbc:dbeaver:libsql:https://db-org.turso.io";

    final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    final Logger logger = (Logger) LoggerFactory.getLogger(ForeignKeyCheck.class);

    @BeforeEach
    void attach() {
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(logs);
    }

    /** Answers PRAGMA foreign_keys reads; records every statement; any PRAGMA write would be visible. */
    static final class StubJdbc extends JdbcTemplate {
        final List<String> sql = new ArrayList<>();
        final Object answer;

        StubJdbc(Object answer) {
            this.answer = answer;
        }

        @Override
        public <T> T queryForObject(String s, Class<T> type) {
            sql.add(s);
            if (answer instanceof RuntimeException e) throw e;
            return type.cast(answer);
        }
    }

    List<ILoggingEvent> errors() {
        return logs.list.stream().filter(e -> e.getLevel() == Level.ERROR).toList();
    }

    @Test
    void foreignKeysOnPassesQuietly() {
        StubJdbc jdbc = new StubJdbc(1);
        new ForeignKeyCheck(jdbc, TURSO, false).run(null);
        assertThat(jdbc.sql).containsExactly("PRAGMA foreign_keys"); // a read, never a write
        assertThat(errors()).isEmpty();
    }

    @Test
    void foreignKeysOffLogsAnErrorByDefault() {
        new ForeignKeyCheck(new StubJdbc(0), TURSO, false).run(null);
        assertThat(errors()).singleElement().extracting(ILoggingEvent::getFormattedMessage)
                .asString().contains("returned 0").contains("NOT enforcing");
    }

    @Test
    void foreignKeysOffFailsStartupWhenRequired() {
        assertThatThrownBy(() -> new ForeignKeyCheck(new StubJdbc(0), TURSO, true).run(null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("expected 1");
    }

    @Test
    void unreadablePragmaLogsAnError() {
        new ForeignKeyCheck(new StubJdbc(new DataAccessResourceFailureException("boom")), TURSO, false).run(null);
        assertThat(errors()).singleElement().extracting(ILoggingEvent::getFormattedMessage)
                .asString().contains("could not read PRAGMA foreign_keys").contains("boom");
    }

    @Test
    void localSqliteIsNotChecked() {
        StubJdbc jdbc = new StubJdbc(0);
        new ForeignKeyCheck(jdbc, "jdbc:sqlite:/tmp/life.db", true).run(null);
        assertThat(jdbc.sql).isEmpty();
    }
}
