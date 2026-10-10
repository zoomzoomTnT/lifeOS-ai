package com.lifeos.config;

import com.lifeos.repo.batch.AtomicSqlBatch;
import com.lifeos.repo.batch.JdbcAtomicSqlBatch;
import com.lifeos.repo.batch.TursoAtomicSqlBatch;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

/** Turso URL → one-request Hrana batch; anything else (local SQLite) → JDBC transaction. */
@Configuration
@Slf4j
public class AtomicSqlBatchConfig {

    @Bean
    AtomicSqlBatch atomicSqlBatch(Environment env, JdbcTemplate jdbc) {
        String url = env.getProperty("spring.datasource.url");
        if (LibSqlCompatConfig.isLibSql(url)) {
            log.info("Atomic multi-statement writes: Turso v3/pipeline batch");
            return new TursoAtomicSqlBatch(url, env.getProperty("spring.datasource.password", ""));
        }
        return new JdbcAtomicSqlBatch(jdbc);
    }
}
