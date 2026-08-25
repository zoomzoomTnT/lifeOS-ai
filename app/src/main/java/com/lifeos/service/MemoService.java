package com.lifeos.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
public class MemoService {

    private final JdbcTemplate jdbc;
    private final PersonService personService;

    public MemoService(JdbcTemplate jdbc, PersonService personService) {
        this.jdbc = jdbc;
        this.personService = personService;
    }

    public List<Map<String, Object>> due(int withinHours, String handle) {
        long ownerId = personService.resolveId(handle);
        // due_at within now + withinHours, status open/snoozed
        return jdbc.queryForList("""
                SELECT * FROM memos
                WHERE owner_id = ?
                  AND status IN ('open','snoozed')
                  AND due_at IS NOT NULL
                  AND due_at <= strftime('%Y-%m-%dT%H:%M:%SZ', 'now', ? || ' hours')
                ORDER BY priority ASC, due_at ASC
                LIMIT 20
                """, ownerId, withinHours);
    }

    @Transactional
    public Map<String, Object> create(Map<String, Object> body, String handle) {
        long ownerId = personService.resolveId(handle);
        jdbc.update("""
                INSERT INTO memos (owner_id, title, body, kind, status, priority,
                    due_at, timezone, cron_expr, cron_tz,
                    source_domain, source_table, source_id, payload_json)
                VALUES (?,?,?,?, 'open', ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                ownerId,
                body.get("title"),
                body.get("body"),
                body.getOrDefault("kind", "reminder"),
                body.getOrDefault("priority", 3),
                body.get("due_at"),
                body.getOrDefault("timezone", "Asia/Tokyo"),
                body.get("cron_expr"),
                body.get("cron_tz"),
                body.get("source_domain"),
                body.get("source_table"),
                body.get("source_id"),
                body.get("payload_json") != null ? body.get("payload_json").toString() : null
        );
        long id = jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
        return Map.of("id", id, "status", "open");
    }

    public Map<String, Object> patch(long id, Map<String, Object> body) {
        // simple dynamic update for the fields we care about
        if (body.containsKey("status")) {
            jdbc.update("UPDATE memos SET status=?, updated_at=strftime('%Y-%m-%dT%H:%M:%SZ','now') WHERE id=?",
                    body.get("status"), id);
        }
        if (body.containsKey("due_at")) {
            jdbc.update("UPDATE memos SET due_at=?, updated_at=strftime('%Y-%m-%dT%H:%M:%SZ','now') WHERE id=?",
                    body.get("due_at"), id);
        }
        if (body.containsKey("automation_id")) {
            jdbc.update("UPDATE memos SET automation_id=? WHERE id=?", body.get("automation_id"), id);
        }
        if (body.containsKey("last_fired_at")) {
            jdbc.update("UPDATE memos SET last_fired_at=? WHERE id=?", body.get("last_fired_at"), id);
        }
        return Map.of("id", id, "updated", true);
    }

    public Map<String, Object> markFired(long id) {
        jdbc.update("UPDATE memos SET last_fired_at=strftime('%Y-%m-%dT%H:%M:%SZ','now') WHERE id=?", id);
        return Map.of("id", id, "fired", true);
    }
}
