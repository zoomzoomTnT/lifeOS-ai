package com.lifeos.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class HealthController {

    @Value("${spring.datasource.url}")
    private String dbUrl;

    @GetMapping("/path")
    public Map<String, Object> path() {
        return Map.of(
                "dbUrl", dbUrl,
                "ownerTimezone", "Asia/Tokyo"
        );
    }
}
