package com.lifeos.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Applies schema.sql once if the people table is missing.
 * For production later: switch to Flyway.
 */
@Component
public class SchemaInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SchemaInitializer.class);

    private final JdbcTemplate jdbc;
    private final ResourceLoader resourceLoader;

    @Value("${life.schema-path:classpath:schema.sql}")
    private String schemaPath;

    public SchemaInitializer(JdbcTemplate jdbc, ResourceLoader resourceLoader) {
        this.jdbc = jdbc;
        this.resourceLoader = resourceLoader;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='people'",
                Integer.class);
        if (count != null && count > 0) {
            log.info("Schema already present, skip init");
            return;
        }

        log.info("Initializing schema from {}", schemaPath);
        Resource resource = resourceLoader.getResource(schemaPath);
        String sql = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);

        // naive split — good enough for our schema (no complex triggers yet)
        for (String stmt : sql.split(";")) {
            String s = stmt.trim();
            if (s.isEmpty() || s.startsWith("--")) continue;
            // keep multi-line comments out
            if (s.lines().allMatch(l -> l.trim().startsWith("--") || l.trim().isEmpty())) continue;
            try {
                jdbc.execute(s);
            } catch (Exception e) {
                log.warn("Statement failed (may be idempotent): {} → {}", s.substring(0, Math.min(60, s.length())), e.getMessage());
            }
        }
        log.info("Schema init done");
    }
}
