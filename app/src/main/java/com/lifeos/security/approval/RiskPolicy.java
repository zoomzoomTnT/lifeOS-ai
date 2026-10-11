package com.lifeos.security.approval;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which requests are tier 2 / 3. Tier 0-1 (reads, normal reversible writes) return empty and are
 * governed by scopes + rate limit only. Thresholds come from {@code settings}
 * ({@code approval.receipt_total_cents}, {@code approval.bulk_rows}); changing settings is itself
 * an owner-only operation. Phase 4 MCP tools call {@link #assess} with the same inputs.
 */
@Component
public class RiskPolicy {

    static final Pattern RECEIPT_CONFIRM = Pattern.compile("^/api/receipts/(\\d+)/confirm$");
    static final long DEFAULT_RECEIPT_TOTAL_CENTS = 5_000_000L; // 50,000.00
    static final int DEFAULT_BULK_ROWS = 20;

    private final JdbcTemplate jdbc;
    private final JsonMapper json = JsonMapper.builder().build();

    public RiskPolicy(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Only unsafe methods can be risky; reads are never gated. */
    public static boolean candidate(String method) {
        return !List.of("GET", "HEAD", "OPTIONS", "TRACE").contains(method);
    }

    public Optional<RiskAssessment> assess(String method, String path, byte[] body) {
        if (!candidate(method)) return Optional.empty();
        if (path.startsWith("/actuator")) {
            return Optional.of(new RiskAssessment(RiskAssessment.ADMIN, "actuator.write",
                    "Actuator write " + method + " " + path, Map.of("method", method, "path", path)));
        }
        if (path.equals("/api/ops/purge")) {
            Map<String, Object> b = parse(body);
            Object days = b.getOrDefault("older_than_days", 90);
            return Optional.of(new RiskAssessment(RiskAssessment.ADMIN, "ops.purge",
                    "Delete http/ai/app logs older than " + days + " days",
                    Map.of("older_than_days", days)));
        }
        if (method.equals("PUT") && path.equals("/api/ops/prices")) return Optional.of(prices(parse(body)));
        Matcher m = RECEIPT_CONFIRM.matcher(path);
        if (method.equals("POST") && m.matches()) return receiptConfirm(Long.parseLong(m.group(1)), parse(body));
        if (method.equals("DELETE") && path.startsWith("/api/")) {
            return Optional.of(new RiskAssessment(RiskAssessment.IMPACTFUL, "delete", "Delete " + path,
                    Map.of("path", path)));
        }
        return Optional.empty();
    }

    private RiskAssessment prices(Map<String, Object> b) {
        Object provider = b.get("provider"), model = b.get("model");
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("provider", provider);
        s.put("model", model);
        List<Map<String, Object>> cur = jdbc.queryForList(
                "SELECT input_usd_micros_per_mtok AS input, output_usd_micros_per_mtok AS output FROM model_prices "
                        + "WHERE provider = ? AND model = ?", String.valueOf(provider), String.valueOf(model));
        s.put("current", cur.isEmpty() ? "(new model)" : cur.get(0));
        s.put("new", Map.of("input", String.valueOf(b.get("input_usd_micros_per_mtok")),
                "output", String.valueOf(b.get("output_usd_micros_per_mtok"))));
        return new RiskAssessment(RiskAssessment.IMPACTFUL, "ops.prices.update",
                "Change model price for " + provider + "/" + model + " (affects all AI cost figures)", s);
    }

    private Optional<RiskAssessment> receiptConfirm(long id, Map<String, Object> b) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT r.total_cents AS total_cents, r.currency AS currency, r.printed_at AS printed_at,
                       r.status AS status, m.name AS merchant,
                       (SELECT COUNT(*) FROM receipt_items i WHERE i.receipt_id = r.id) AS items
                FROM receipts r LEFT JOIN merchants m ON m.id = r.merchant_id WHERE r.id = ?""", id);
        if (rows.isEmpty()) return Optional.empty(); // 404 from the service, nothing to approve
        Map<String, Object> r = rows.get(0);
        long total = ((Number) r.get("total_cents")).longValue();
        long items = ((Number) r.get("items")).longValue();
        long limit = setting("approval.receipt_total_cents", DEFAULT_RECEIPT_TOTAL_CENTS);
        long bulk = setting("approval.bulk_rows", DEFAULT_BULK_ROWS);
        if (total < limit && items <= bulk) return Optional.empty();
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("receipt_id", id);
        s.put("merchant", r.get("merchant"));
        s.put("printed_at", r.get("printed_at"));
        s.put("total", String.format("%,d.%02d %s", total / 100, Math.abs(total % 100), r.get("currency")));
        s.put("items", items);
        s.put("also_fridge", Boolean.TRUE.equals(b.get("also_fridge")));
        s.put("why", total >= limit ? "total >= " + String.format("%,d.%02d", limit / 100, limit % 100)
                : "more than " + bulk + " rows");
        return Optional.of(new RiskAssessment(RiskAssessment.IMPACTFUL, "receipt.confirm",
                "Confirm receipt #" + id + " from " + (r.get("merchant") == null ? "unknown merchant" : r.get("merchant"))
                        + ", total " + s.get("total") + ", " + items + " items", s));
    }

    long setting(String key, long dflt) {
        List<String> v = jdbc.queryForList("SELECT value FROM settings WHERE key = ?", String.class, key);
        try {
            return v.isEmpty() ? dflt : Long.parseLong(v.get(0).trim());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> parse(byte[] body) {
        if (body == null || body.length == 0) return Map.of();
        try {
            Object o = json.readValue(body, Object.class);
            return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        } catch (Exception e) {
            return Map.of();
        }
    }
}
