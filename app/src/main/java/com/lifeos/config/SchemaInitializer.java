package com.lifeos.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.nio.charset.StandardCharsets;

/**
 * Fresh DB: apply schema.sql. Existing DB: apply additive migrations (IF NOT EXISTS).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SchemaInitializer implements ApplicationRunner {

    private final JdbcTemplate jdbc;
    private final ResourceLoader resourceLoader;

    @Value("${life.schema-path:classpath:schema.sql}")
    private String schemaPath;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='people'",
                Integer.class);
        if (count == null || count == 0) {
            log.info("Initializing schema from {}", schemaPath);
            execScript(schemaPath);
            log.info("Schema init done");
        } else {
            log.info("Base schema present");
        }

        log.info("Applying ops migration 0002 (idempotent)");
        execScript("classpath:migrations/0002_ops.sql");
        log.info("Applying logs migration 0003 (idempotent)");
        execScript("classpath:migrations/0003_logs.sql");
    }

    private void execScript(String location) throws Exception {
        Resource resource = resourceLoader.getResource(location);
        String sql = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
        for (String stmt : sql.split(";")) {
            String s = stmt.trim();
            if (s.isEmpty()) continue;
            if (s.lines().allMatch(l -> {
                String t = l.trim();
                return t.isEmpty() || t.startsWith("--");
            })) continue;
            try {
                jdbc.execute(s);
            } catch (Exception e) {
                log.warn("Statement failed (may be idempotent): {} → {}",
                        s.substring(0, Math.min(80, s.length())).replace('\n', ' '),
                        e.getMessage());
            }
        }
    }
}
