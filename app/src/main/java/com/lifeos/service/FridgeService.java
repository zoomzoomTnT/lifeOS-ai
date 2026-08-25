package com.lifeos.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
public class FridgeService {

    private final JdbcTemplate jdbc;
    private final PersonService personService;

    public FridgeService(JdbcTemplate jdbc, PersonService personService) {
        this.jdbc = jdbc;
        this.personService = personService;
    }

    @Transactional
    public Map<String, Object> add(Map<String, Object> body, String handle) {
        long personId = personService.resolveId(handle);
        String name = String.valueOf(body.get("name"));
        String nameNorm = ReceiptService.nameNorm(name);
        Integer days = body.get("expires_in_days") instanceof Number n ? n.intValue() : null;

        jdbc.update("""
                INSERT INTO fridge_items (owner_id, added_by_id, name, name_norm, category, location, status, qty, purchased_at, expires_at)
                VALUES (?,?,?,?,?, ?, 'in_stock', ?, strftime('%Y-%m-%dT%H:%M:%SZ','now'),
                    CASE WHEN ? IS NOT NULL THEN strftime('%Y-%m-%dT%H:%M:%SZ','now', ? || ' days') ELSE NULL END)
                """,
                personId, personId, name, nameNorm,
                body.get("category"),
                body.getOrDefault("location", "fridge"),
                body.getOrDefault("qty", 1),
                days, days
        );
        long id = jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
        return Map.of("id", id, "status", "in_stock");
    }

    public List<Map<String, Object>> list(String status, Integer expiringWithinHours, String handle) {
        long ownerId = personService.resolveId(handle);
        if (expiringWithinHours != null) {
            return jdbc.queryForList("""
                    SELECT * FROM fridge_items
                    WHERE owner_id = ? AND status = 'in_stock'
                      AND expires_at IS NOT NULL
                      AND expires_at <= strftime('%Y-%m-%dT%H:%M:%SZ','now', ? || ' hours')
                    ORDER BY expires_at ASC
                    """, ownerId, expiringWithinHours);
        }
        if (status != null) {
            return jdbc.queryForList("SELECT * FROM fridge_items WHERE owner_id = ? AND status = ? ORDER BY id DESC",
                    ownerId, status);
        }
        return jdbc.queryForList("SELECT * FROM fridge_items WHERE owner_id = ? ORDER BY id DESC LIMIT 50", ownerId);
    }

    @Transactional
    public Map<String, Object> resolve(long id, Map<String, Object> body, String handle) {
        String action = String.valueOf(body.getOrDefault("action", ""));
        switch (action) {
            case "eaten" -> jdbc.update("UPDATE fridge_items SET status='eaten', updated_at=strftime('%Y-%m-%dT%H:%M:%SZ','now') WHERE id=?", id);
            case "discarded" -> jdbc.update("UPDATE fridge_items SET status='discarded', updated_at=strftime('%Y-%m-%dT%H:%M:%SZ','now') WHERE id=?", id);
            case "keep_one_more_day" -> jdbc.update("""
                    UPDATE fridge_items SET expires_at = strftime('%Y-%m-%dT%H:%M:%SZ', expires_at, '+1 day'),
                    updated_at=strftime('%Y-%m-%dT%H:%M:%SZ','now') WHERE id=?
                    """, id);
            default -> { return Map.of("error", "unknown_action"); }
        }
        // TODO: update food_prefs + close related memos
        return Map.of("id", id, "action", action);
    }
}
