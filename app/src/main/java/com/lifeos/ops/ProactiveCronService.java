package com.lifeos.ops;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Spring cron: cheap SQLite poll, then reverse-call OpenClaw's life-os skill.
 */
@Service
public class ProactiveCronService {

    private static final Logger log = LoggerFactory.getLogger(ProactiveCronService.class);

    private final WakeService wakeService;
    private final OpenClawClient openClawClient;
    private final JdbcTemplate jdbc;

    @Value("${life.openclaw.enabled:true}")
    private boolean enabled;

    @Value("${life.proactive.lead-minutes:10}")
    private int leadMinutes;

    @Value("${life.proactive.lock-minutes:15}")
    private int lockMinutes;

    public ProactiveCronService(WakeService wakeService, OpenClawClient openClawClient, JdbcTemplate jdbc) {
        this.wakeService = wakeService;
        this.openClawClient = openClawClient;
        this.jdbc = jdbc;
    }

    @Scheduled(cron = "${life.proactive.cron:0 * * * * *}")
    public void tick() {
        run(false);
    }

    public Map<String, Object> run(boolean force) {
        if (!enabled && !force) {
            return Map.of("ok", true, "wake", false, "skipped", "LIFE_OPENCLAW_WAKE=false");
        }
        Map<String, Object> gate = wakeService.shouldWake("owner", leadMinutes);
        boolean wake = Boolean.TRUE.equals(gate.get("wake"));
        if (!wake) {
            log.debug("proactive tick: nothing due");
            return Map.of("ok", true, "wake", false, "heartbeat_ok", true);
        }
        if (!force && locked()) {
            log.info("proactive tick: due but lock held");
            return Map.of("ok", true, "wake", true, "skipped", "locked");
        }

        String to = ownerHandle();
        String message = prompt(gate);
        Map<String, Object> hook = openClawClient.wakeProactive(message, to);
        boolean ok = Boolean.TRUE.equals(hook.get("ok"));
        if (ok || force) {
            lock();
        }
        jdbc.update("""
                INSERT INTO events (domain, action, actor_id, entity_table, payload_json)
                VALUES ('ops', 'wake_skill', 1, 'memos', ?)
                """, String.valueOf(gate.get("reasons")));
        log.info("proactive wake ok={} reasons={}", ok, gate.get("reasons"));
        return Map.of("ok", ok, "wake", true, "openclaw", hook, "to", to == null ? "" : to);
    }

    private String prompt(Map<String, Object> gate) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> due = (List<Map<String, Object>>) gate.get("due_memos");
        String ids = due == null ? "" : due.stream()
                .map(m -> String.valueOf(m.get("id")))
                .collect(Collectors.joining(","));
        return """
                life-os proactive (woken by Spring cron, not heartbeat).
                should-wake is true. reasons=%s. memo ids=[%s].
                Read references/proactive.md. Speak Chinese on openclaw-weixin, at most 2 messages.
                Then POST /api/memos/{id}/fired. Do not reply HEARTBEAT_OK. Do not use vision.
                """.formatted(gate.get("reasons"), ids);
    }

    private boolean locked() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT value FROM settings WHERE key = 'proactive_lock_until'");
        if (rows.isEmpty()) return false;
        String until = String.valueOf(rows.get(0).get("value"));
        String now = jdbc.queryForObject("SELECT strftime('%Y-%m-%dT%H:%M:%SZ','now')", String.class);
        return now != null && until.compareTo(now) > 0;
    }

    private void lock() {
        jdbc.update("""
                INSERT INTO settings (key, value, updated_at)
                VALUES ('proactive_lock_until', strftime('%Y-%m-%dT%H:%M:%SZ','now', ? || ' minutes'),
                        strftime('%Y-%m-%dT%H:%M:%SZ','now'))
                ON CONFLICT(key) DO UPDATE SET
                  value=excluded.value,
                  updated_at=excluded.updated_at
                """, lockMinutes);
    }

    private String ownerHandle() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT handle FROM people WHERE role='owner' LIMIT 1");
        if (rows.isEmpty()) return null;
        return String.valueOf(rows.get(0).get("handle"));
    }
}
