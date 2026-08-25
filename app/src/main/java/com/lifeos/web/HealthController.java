package com.lifeos.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class HealthController {

    private final JdbcTemplate jdbc;

    @Value("${spring.datasource.url}")
    private String dbUrl;

    public HealthController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        String dbStatus = "ok";
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
        } catch (Exception e) {
            dbStatus = "error: " + e.getMessage();
        }
        return Map.of(
                "status", "ok",
                "db", dbStatus,
                "version", "0.1.0"
        );
    }

    @GetMapping("/path")
    public Map<String, Object> path() {
        return Map.of(
                "dbUrl", dbUrl,
                "ownerTimezone", "Asia/Tokyo"
        );
    }
}
