package com.lifeos.config;

import com.lifeos.repo.batch.AtomicSqlBatch;
import com.lifeos.repo.batch.JdbcAtomicSqlBatch;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Multi-statement writes run in a normal Spring/JDBC transaction for both backends: local SQLite
 * and Turso (the libSQL driver holds a real server-side transaction, see
 * {@link com.lifeos.db.libsql.LibSqlHttpDriver}). Joining the caller's transaction matters: a
 * second, independent channel to Turso would contend with the caller's write lock.
 */
@Configuration
public class AtomicSqlBatchConfig {

    @Bean
    AtomicSqlBatch atomicSqlBatch(JdbcTemplate jdbc) {
        return new JdbcAtomicSqlBatch(jdbc);
    }
}
