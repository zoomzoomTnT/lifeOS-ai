package com.lifeos.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;
import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A failed security_events write is counted; owner decisions / consumed approvals fail loudly. */
class SecurityEventLogTest {

    @TempDir
    Path dir;

    @Test
    void failedWritesAreCountedAndRequiredOnesRethrown() {
        // no security_events table: every insert fails
        var jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("empty.db")));
        var log = new SecurityEventLog(jdbc, Clock.systemUTC());

        log.record(SecurityEventLog.Event.of("key_first_use"));
        assertThat(log.failures()).isEqualTo(1);

        for (String t : SecurityEventLog.REQUIRED) {
            assertThatThrownBy(() -> log.record(SecurityEventLog.Event.of(t))).isInstanceOf(DataAccessException.class);
        }
        assertThat(log.failures()).isEqualTo(1 + SecurityEventLog.REQUIRED.size());
    }
}
