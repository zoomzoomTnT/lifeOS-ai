package com.lifeos.security;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Append-only writer for security_events (there is no update/delete path, and purge skips it).
 * Noisy types from unauthenticated traffic are capped per minute so a scanner can't fill the
 * database; the cap is logged. A failed write is logged at ERROR and counted; for owner decisions
 * and consumed approvals ({@link #REQUIRED}) it is rethrown, so those never pass unaudited.
 */
public class SecurityEventLog {

    private static final Logger log = LoggerFactory.getLogger(SecurityEventLog.class);
    /** Types an outsider can trigger at will. */
    static final Set<String> THROTTLED = Set.of("auth_failed", "scope_denied", "approval_rejected",
            "identity_header_ignored", "non_canonical_path", "auth_missing");
    static final int PER_TYPE_PER_MINUTE = 30;
    /** Types whose audit row must exist: a failed write fails the operation. */
    static final Set<String> REQUIRED = Set.of("approval_approved", "approval_denied", "approval_consumed");

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final JsonMapper json = JsonMapper.builder().build();
    private final Map<String, AtomicInteger> window = new ConcurrentHashMap<>();
    private volatile long windowMinute = -1;
    private final AtomicLong failures = new AtomicLong();

    public SecurityEventLog(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public static final class Event {
        final String type;
        String clientId, credentialId, ip, method, path, outcome, requestId;
        Long personId;
        Map<String, ?> detail;

        private Event(String type) {
            this.type = type;
        }

        public static Event of(String type) {
            return new Event(type);
        }

        public Event client(String v) { clientId = v; return this; }
        public Event credential(String v) { credentialId = v; return this; }
        public Event person(Long v) { personId = v; return this; }
        public Event outcome(String v) { outcome = v; return this; }
        public Event detail(Map<String, ?> v) { detail = v; return this; }

        public Event request(HttpServletRequest r) {
            if (r != null) {
                ip = r.getRemoteAddr();
                method = r.getMethod();
                path = r.getRequestURI();
                Object rid = r.getAttribute(AuditContext.REQUEST_ID_ATTR);
                requestId = rid instanceof String s ? s : r.getHeader("X-Request-Id");
                if (requestId != null && requestId.length() > 100) requestId = requestId.substring(0, 100);
            }
            return this;
        }

        public Event principal(LifePrincipal p) {
            if (p != null) {
                clientId = p.clientId();
                credentialId = p.credentialId();
                personId = p.personId();
            }
            return this;
        }
    }

    public void record(Event e) {
        if (THROTTLED.contains(e.type) && !allow(e.type)) return;
        try {
            jdbc.update("""
                    INSERT INTO security_events (type, client_id, credential_id, person_id, ip, method, path, outcome, request_id, detail_json)
                    VALUES (?,?,?,?,?,?,?,?,?,?)""", e.type, e.clientId, e.credentialId, e.personId, e.ip, e.method,
                    e.path == null ? null : e.path.substring(0, Math.min(500, e.path.length())), e.outcome, e.requestId,
                    e.detail == null ? null : json.writeValueAsString(e.detail));
        } catch (DataAccessException | JacksonException ex) {
            failures.incrementAndGet();
            log.error("security event {} not recorded", e.type, ex);
            if (REQUIRED.contains(e.type)) throw ex;
        }
    }

    /** Security events that could not be written since start (exposed as a metric). */
    public long failures() {
        return failures.get();
    }

    private boolean allow(String type) {
        long minute = clock.millis() / 60_000;
        if (minute != windowMinute) {
            window.clear();
            windowMinute = minute;
        }
        int n = window.computeIfAbsent(type, k -> new AtomicInteger()).incrementAndGet();
        if (n == PER_TYPE_PER_MINUTE + 1) {
            log.warn("security_events: more than {} '{}' events this minute; not recording the rest", PER_TYPE_PER_MINUTE, type);
        }
        return n <= PER_TYPE_PER_MINUTE;
    }
}
