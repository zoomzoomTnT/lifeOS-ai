package com.lifeos.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** GET /actuator/db — SQLite path + timezone (replaces /api/path). */
@Component
@Endpoint(id = "db")
public class DbEndpoint {

    private final JdbcTemplate jdbc;

    @Value("${spring.datasource.url}")
    private String jdbcUrl;

    @Value("${life.owner-timezone:Asia/Tokyo}")
    private String ownerTimezone;

    public DbEndpoint(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @ReadOperation
    public Map<String, Object> db() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("jdbcUrl", jdbcUrl);
        out.put("file", fileFrom(jdbcUrl));
        out.put("ownerTimezone", ownerTimezone);
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
            out.put("ping", "ok");
        } catch (Exception e) {
            out.put("ping", "error: " + e.getMessage());
        }
        return out;
    }

    static String fileFrom(String url) {
        if (url == null) return "";
        String prefix = "jdbc:sqlite:";
        return url.startsWith(prefix) ? url.substring(prefix.length()) : url;
    }
}
