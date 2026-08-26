package com.lifeos.web;

import com.lifeos.config.LifeHealthIndicator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class HealthController {

    private final LifeHealthIndicator lifeHealth;

    @Value("${spring.datasource.url}")
    private String dbUrl;

    public HealthController(LifeHealthIndicator lifeHealth) {
        this.lifeHealth = lifeHealth;
    }

    /** Skill / Docker contract. Same payload as actuator component `life`. */
    @GetMapping("/health")
    public Map<String, Object> health() {
        Health h = lifeHealth.health();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", Status.UP.equals(h.getStatus()) ? "ok" : "error");
        out.put("db", h.getDetails().getOrDefault("db", "unknown"));
        out.put("version", h.getDetails().getOrDefault("version", "0.1.0"));
        return out;
    }

    @GetMapping("/path")
    public Map<String, Object> path() {
        return Map.of(
                "dbUrl", dbUrl,
                "ownerTimezone", "Asia/Tokyo"
        );
    }
}
