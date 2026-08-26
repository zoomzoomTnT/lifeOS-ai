package com.lifeos.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Same checks as GET /api/health, folded into GET /actuator/health as component "life". */
@Component("life")
public class LifeHealthIndicator implements HealthIndicator {

    private final JdbcTemplate jdbc;

    @Value("${info.app.version:0.1.0}")
    private String version;

    public LifeHealthIndicator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Health health() {
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
            return Health.up()
                    .withDetail("db", "ok")
                    .withDetail("version", version)
                    .build();
        } catch (Exception e) {
            return Health.down()
                    .withDetail("db", "error: " + e.getMessage())
                    .withDetail("version", version)
                    .build();
        }
    }
}
