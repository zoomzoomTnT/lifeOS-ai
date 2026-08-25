package com.lifeos.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class PersonService {

    private final JdbcTemplate jdbc;

    public PersonService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Resolve handle → people.id. Falls back to owner (id=1) if handle missing/unknown. */
    public long resolveId(String handle) {
        if (handle == null || handle.isBlank() || "owner".equals(handle)) {
            return 1L;
        }
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id FROM people WHERE handle = ?", handle);
        if (!rows.isEmpty()) {
            return ((Number) rows.get(0).get("id")).longValue();
        }
        // auto-create member
        jdbc.update("INSERT INTO people (handle, display_name, role) VALUES (?,?, 'member')", handle, handle);
        return jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
    }
}
