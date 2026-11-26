package com.lifeos.security.approval;

import com.lifeos.security.ClientKind;
import com.lifeos.security.LifePrincipal;
import com.lifeos.security.SecurityEventLog;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * approval_requests: create (pending, 5 min), decision by the admin client (approved / denied, after
 * zoomzoom said yes in chat), and the single-use consume when the agent retries the identical request
 * with {@code Life-Approval}. The requester can never be the approver. Every step writes
 * security_events. All timestamps are ISO-8601 UTC strings written here (driver independent).
 */
public class ApprovalService {

    public static final Duration TTL = Duration.ofMinutes(5);
    public static final String HEADER = "Life-Approval";
    private static final char[] B32 = "abcdefghijklmnopqrstuvwxyz234567".toCharArray();

    public record Approval(long id, String publicId, String clientId, String credentialId, long personId,
                           String action, String method, String path, String requestHash, String headline,
                           Map<String, Object> summary, int tier, String status, Instant createdAt,
                           Instant expiresAt, Instant decidedAt, String decidedVia, String decidedByClient,
                           String decidedByCredential) {
        public boolean expired(Instant now) {
            return !now.isBefore(expiresAt);
        }
    }

    /** Why a Life-Approval header was not accepted. */
    public enum Rejection { NOT_FOUND, OTHER_CLIENT, REQUEST_MISMATCH, PENDING, DENIED, EXPIRED, ALREADY_USED }

    /** Outcome of {@link #approve} / {@link #deny}. */
    public enum Decision { DONE, NOT_FOUND, NOT_PENDING, SELF_APPROVAL }

    public record DecisionResult(Decision decision, Approval approval) {}

    public record Verdict(Approval approval, Rejection rejection) {
        public boolean ok() {
            return rejection == null;
        }
    }

    private final JdbcTemplate jdbc;
    private final SecurityEventLog events;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final JsonMapper json = JsonMapper.builder().build();

    public ApprovalService(JdbcTemplate jdbc, SecurityEventLog events, Clock clock) {
        this.jdbc = jdbc;
        this.events = events;
        this.clock = clock;
    }

    Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.SECONDS);
    }

    static String iso(Instant i) {
        return i == null ? null : i.toString();
    }

    String newPublicId() {
        byte[] b = new byte[20];
        random.nextBytes(b);
        StringBuilder sb = new StringBuilder("apr_");
        int buf = 0, bits = 0;
        for (byte x : b) {
            buf = (buf << 8) | (x & 0xff);
            bits += 8;
            while (bits >= 5) {
                sb.append(B32[(buf >> (bits - 5)) & 31]);
                bits -= 5;
            }
        }
        return sb.toString();
    }

    /** A pending request for this exact fingerprint (reused instead of piling up rows), or a new one. */
    public Approval request(LifePrincipal p, RiskAssessment risk, String method, String path, String hash) {
        Instant now = now();
        Optional<Approval> same = jdbc.query("SELECT * FROM approval_requests WHERE client_id = ? AND request_hash = ? "
                        + "AND status = 'pending' AND expires_at > ? ORDER BY id DESC LIMIT 1", this::map,
                p.clientId(), hash, iso(now)).stream().findFirst();
        if (same.isPresent()) return same.get();
        String publicId = newPublicId();
        Map<String, Object> summary = new java.util.LinkedHashMap<>(risk.summary());
        summary.put("_headline", risk.headline());
        jdbc.update("""
                INSERT INTO approval_requests (public_id, client_id, credential_id, person_id, action, method, path,
                  request_hash, summary_json, risk_tier, status, created_at, expires_at)
                VALUES (?,?,?,?,?,?,?,?,?,?, 'pending', ?, ?)""",
                publicId, p.clientId(), p.credentialId(), p.personId(), risk.action(), method, path, hash,
                json.writeValueAsString(summary), risk.tier(), iso(now), iso(now.plus(TTL)));
        Approval a = find(publicId).orElseThrow();
        events.record(SecurityEventLog.Event.of("approval_requested").principal(p).outcome(risk.action())
                .detail(Map.of("approval", publicId, "tier", risk.tier(), "path", path)));
        return a;
    }

    public Optional<Approval> find(String publicId) {
        if (publicId == null || publicId.length() > 64) return Optional.empty();
        return jdbc.query("SELECT * FROM approval_requests WHERE public_id = ?", this::map, publicId).stream().findFirst();
    }

    /** Pending rows past their deadline become 'expired' (lazily, when looked at). */
    Approval refresh(Approval a) {
        if (a.expired(now()) && (a.status().equals("pending") || a.status().equals("approved"))) {
            jdbc.update("UPDATE approval_requests SET status = 'expired' WHERE id = ? AND status IN ('pending','approved')", a.id());
            return find(a.publicId()).orElse(a);
        }
        return a;
    }

    public Optional<Approval> view(String publicId) {
        return find(publicId).map(this::refresh);
    }

    /** Approvals with this status, newest first; overdue ones are marked expired first. */
    public List<Approval> list(String status, int limit) {
        jdbc.update("UPDATE approval_requests SET status = 'expired' WHERE status IN ('pending','approved') AND expires_at <= ?",
                iso(now()));
        return jdbc.query("SELECT * FROM approval_requests WHERE status = ? ORDER BY id DESC LIMIT ?", this::map,
                status, limit);
    }

    /** The admin client approves (zoomzoom said yes in chat). */
    public DecisionResult approve(String publicId, LifePrincipal approver) {
        return decide(publicId, true, approver);
    }

    public DecisionResult deny(String publicId, LifePrincipal approver) {
        return decide(publicId, false, approver);
    }

    private DecisionResult decide(String publicId, boolean approve, LifePrincipal approver) {
        if (approver.kind() != ClientKind.ADMIN) {
            throw new IllegalArgumentException("only the admin client decides approvals, not kind " + approver.kind());
        }
        Optional<Approval> cur = view(publicId);
        if (cur.isEmpty()) return new DecisionResult(Decision.NOT_FOUND, null);
        Approval a = cur.get();
        if (a.clientId().equals(approver.clientId())) {
            events.record(SecurityEventLog.Event.of("approval_self_denied").principal(approver).outcome(a.action())
                    .detail(Map.of("approval", publicId)));
            return new DecisionResult(Decision.SELF_APPROVAL, a);
        }
        if (!a.status().equals("pending")) return new DecisionResult(Decision.NOT_PENDING, a);
        int n = jdbc.update("""
                        UPDATE approval_requests SET status = ?, decided_at = ?, decided_by = ?, decided_via = 'admin_key',
                               decided_by_client = ?, decided_by_credential = ?
                         WHERE id = ? AND status = 'pending' AND expires_at > ?""",
                approve ? "approved" : "denied", iso(now()), approver.personId(), approver.clientId(),
                approver.credentialId(), a.id(), iso(now()));
        Approval after = refresh(find(publicId).orElseThrow());
        if (n != 1) return new DecisionResult(Decision.NOT_PENDING, after);
        events.record(SecurityEventLog.Event.of(approve ? "approval_approved" : "approval_denied").principal(approver)
                .outcome(after.action()).detail(Map.of("approval", publicId, "requested_by", after.clientId(),
                        "decided_by_client", approver.clientId(), "decided_via", "admin_key")));
        return new DecisionResult(Decision.DONE, after);
    }

    /** Checks a Life-Approval header against the retried request and consumes it (single use). */
    public Verdict consume(String publicId, LifePrincipal p, String hash) {
        Optional<Approval> found = find(publicId);
        Verdict v;
        if (found.isEmpty()) v = new Verdict(null, Rejection.NOT_FOUND);
        else {
            Approval a = refresh(found.get());
            if (!a.clientId().equals(p.clientId())) v = new Verdict(a, Rejection.OTHER_CLIENT);
            else if (!a.requestHash().equals(hash)) v = new Verdict(a, Rejection.REQUEST_MISMATCH);
            else v = switch (a.status()) {
                case "pending" -> new Verdict(a, Rejection.PENDING);
                case "denied" -> new Verdict(a, Rejection.DENIED);
                case "expired" -> new Verdict(a, Rejection.EXPIRED);
                case "consumed" -> new Verdict(a, Rejection.ALREADY_USED);
                default -> {
                    int n = jdbc.update("UPDATE approval_requests SET status = 'consumed', consumed_at = ? "
                                    + "WHERE id = ? AND status = 'approved' AND client_id = ? AND request_hash = ? AND expires_at > ?",
                            iso(now()), a.id(), p.clientId(), hash, iso(now()));
                    yield n == 1 ? new Verdict(a, null) : new Verdict(a, Rejection.ALREADY_USED);
                }
            };
        }
        events.record(SecurityEventLog.Event.of(v.ok() ? "approval_consumed" : "approval_rejected").principal(p)
                .outcome(v.ok() ? v.approval().action() : v.rejection().name().toLowerCase())
                .detail(Map.of("approval", String.valueOf(publicId))));
        return v;
    }

    @SuppressWarnings("unchecked")
    private Approval map(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        Map<String, Object> summary = json.readValue(rs.getString("summary_json"), Map.class);
        String headline = String.valueOf(summary.remove("_headline"));
        return new Approval(rs.getLong("id"), rs.getString("public_id"), rs.getString("client_id"),
                rs.getString("credential_id"), rs.getLong("person_id"), rs.getString("action"),
                rs.getString("method"), rs.getString("path"), rs.getString("request_hash"), headline, summary,
                rs.getInt("risk_tier"), rs.getString("status"), parse(rs.getString("created_at")),
                parse(rs.getString("expires_at")), parse(rs.getString("decided_at")), rs.getString("decided_via"),
                rs.getString("decided_by_client"), rs.getString("decided_by_credential"));
    }

    static Instant parse(String s) {
        return s == null ? null : Instant.parse(s);
    }
}
