package com.lifeos.ops;

import com.lifeos.service.PersonService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Decides whether an LLM needs to wake. This query is free — Java + SQLite, no model.
 */
@Service
public class WakeService {

    private final JdbcTemplate jdbc;
    private final PersonService personService;

    public WakeService(JdbcTemplate jdbc, PersonService personService) {
        this.jdbc = jdbc;
        this.personService = personService;
    }

    public Map<String, Object> shouldWake(String handle, int withinHours) {
        int hours = Math.max(1, Math.min(withinHours, 72));
        long ownerId = personService.resolveId(handle);
        boolean night = isTokyoNight();

        List<Map<String, Object>> due = jdbc.queryForList("""
                SELECT id, title, kind, priority, due_at
                FROM memos
                WHERE owner_id = ?
                  AND status IN ('open','snoozed')
                  AND due_at IS NOT NULL
                  AND due_at <= strftime('%Y-%m-%dT%H:%M:%SZ','now', ? || ' hours')
                  AND (last_fired_at IS NULL
                       OR last_fired_at <= strftime('%Y-%m-%dT%H:%M:%SZ','now','-6 hours'))
                  AND (? = 0 OR priority = 1)
                ORDER BY priority ASC, due_at ASC
                LIMIT 10
                """, ownerId, hours, night ? 1 : 0);

        List<Map<String, Object>> staleReceipts = night ? List.of() : jdbc.queryForList("""
                SELECT id, status, created_at FROM receipts
                WHERE payer_id = ?
                  AND status = 'pending_confirm'
                  AND created_at <= strftime('%Y-%m-%dT%H:%M:%SZ','now','-24 hours')
                LIMIT 5
                """, ownerId);

        List<String> reasons = new ArrayList<>();
        if (!due.isEmpty()) reasons.add("due_memos");
        if (!staleReceipts.isEmpty()) reasons.add("pending_receipts");
        boolean wake = !reasons.isEmpty();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("wake", wake);
        out.put("heartbeat_ok", !wake);
        out.put("night", night);
        out.put("timezone", "Asia/Tokyo");
        out.put("reasons", reasons);
        out.put("due_memos", due);
        out.put("pending_receipts", staleReceipts);
        out.put("instruction", wake
                ? "Speak at most 2 short WeChat messages, then POST /api/memos/{id}/fired. Do not use a vision model."
                : "Reply HEARTBEAT_OK. No other tools. No prose.");
        return out;
    }

    static boolean isTokyoNight() {
        int hour = ZonedDateTime.now(ZoneId.of("Asia/Tokyo")).getHour();
        return hour >= 22 || hour < 8;
    }
}
