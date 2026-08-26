package com.lifeos.ops;

import com.lifeos.service.PersonService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class OpsService {

    private final JdbcTemplate jdbc;
    private final PersonService personService;

    public OpsService(JdbcTemplate jdbc, PersonService personService) {
        this.jdbc = jdbc;
        this.personService = personService;
    }

    public void recordHttp(String correlationId, String handle, String method, String path,
                           String query, Integer status, long latencyMs,
                           Integer requestBytes, Integer responseBytes,
                           String bodyExcerpt, String error) {
        Long actorId = resolveOptional(handle);
        jdbc.update("""
                INSERT INTO http_requests (
                  correlation_id, actor_id, method, path, query, status, latency_ms,
                  request_bytes, response_bytes, body_excerpt, error)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)
                """,
                correlationId, actorId, method, path, query, status, latencyMs,
                requestBytes, responseBytes, bodyExcerpt, error);
    }

    @Transactional
    public Map<String, Object> recordAi(Map<String, Object> body, String handle) {
        String provider = str(body.get("provider"));
        String model = str(body.get("model"));
        if (provider == null || provider.isBlank() || model == null || model.isBlank()) {
            return Map.of("error", "provider_and_model_required");
        }
        long prompt = toLong(body.get("prompt_tokens"));
        long completion = toLong(body.get("completion_tokens"));
        long total = body.get("total_tokens") != null ? toLong(body.get("total_tokens")) : prompt + completion;

        Long cost = body.get("cost_micros") != null ? toLong(body.get("cost_micros")) : null;
        Map<String, Object> price = lookupPrice(provider, model);
        if (cost == null) {
            if (price != null) {
                cost = CostCalculator.costMicros(
                        prompt, completion,
                        toLong(price.get("input_usd_micros_per_mtok")),
                        toLong(price.get("output_usd_micros_per_mtok")));
            } else {
                cost = 0L;
            }
        }

        Long actorId = resolveOptional(handle);
        String source = str(body.getOrDefault("source", "skill"));
        String purpose = str(body.getOrDefault("purpose", "other"));
        String status = str(body.getOrDefault("status", "ok"));
        String currency = str(body.getOrDefault("currency", "USD"));

        jdbc.update("""
                INSERT INTO ai_calls (
                  correlation_id, actor_id, source, purpose, provider, model,
                  prompt_tokens, completion_tokens, total_tokens, cost_micros, currency,
                  latency_ms, status, error, meta_json)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                str(body.get("correlation_id")),
                actorId,
                source, purpose, provider, model,
                prompt, completion, total, cost, currency,
                body.get("latency_ms") != null ? toLong(body.get("latency_ms")) : null,
                status,
                str(body.get("error")),
                body.get("meta_json") != null ? body.get("meta_json").toString() : null
        );
        long id = jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);

        long today = todayCostMicros();
        long budget = dailyBudgetMicros();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("cost_micros", cost);
        out.put("cost_usd", CostCalculator.usdDisplay(cost));
        out.put("priced", price != null);
        out.put("today_cost_micros", today);
        out.put("today_cost_usd", CostCalculator.usdDisplay(today));
        out.put("daily_budget_micros", budget);
        out.put("budget_exceeded", today > budget);
        return out;
    }

    public Map<String, Object> summary(int hours) {
        int h = Math.max(1, Math.min(hours, 24 * 90));
        String window = h + " hours";
        Map<String, Object> http = jdbc.queryForMap("""
                SELECT COUNT(*) AS calls,
                       AVG(latency_ms) AS avg_latency_ms,
                       MAX(latency_ms) AS max_latency_ms
                FROM http_requests
                WHERE created_at >= strftime('%Y-%m-%dT%H:%M:%SZ','now', ?)
                """, "-" + window);
        Map<String, Object> ai = jdbc.queryForMap("""
                SELECT COUNT(*) AS calls,
                       COALESCE(SUM(prompt_tokens),0) AS prompt_tokens,
                       COALESCE(SUM(completion_tokens),0) AS completion_tokens,
                       COALESCE(SUM(total_tokens),0) AS total_tokens,
                       COALESCE(SUM(cost_micros),0) AS cost_micros
                FROM ai_calls
                WHERE created_at >= strftime('%Y-%m-%dT%H:%M:%SZ','now', ?)
                """, "-" + window);
        long today = todayCostMicros();
        long budget = dailyBudgetMicros();
        List<Map<String, Object>> byModel = jdbc.queryForList("""
                SELECT provider, model, COUNT(*) AS calls, SUM(cost_micros) AS cost_micros
                FROM ai_calls
                WHERE created_at >= strftime('%Y-%m-%dT%H:%M:%SZ','now', ?)
                GROUP BY provider, model
                ORDER BY cost_micros DESC
                """, "-" + window);
        List<Map<String, Object>> daily = jdbc.queryForList("""
                SELECT * FROM v_ai_daily ORDER BY day DESC LIMIT 14
                """);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("window_hours", h);
        out.put("http", http);
        out.put("ai", ai);
        out.put("ai_cost_usd", CostCalculator.usdDisplay(toLong(ai.get("cost_micros"))));
        out.put("today_cost_micros", today);
        out.put("today_cost_usd", CostCalculator.usdDisplay(today));
        out.put("daily_budget_micros", budget);
        out.put("daily_budget_usd", CostCalculator.usdDisplay(budget));
        out.put("budget_used_pct", budget == 0 ? 0 : Math.round(today * 1000.0 / budget) / 10.0);
        out.put("budget_exceeded", today > budget);
        out.put("by_model", byModel);
        out.put("daily", daily);
        out.put("app_logs", jdbc.queryForMap(
                "SELECT COUNT(*) AS rows FROM app_logs WHERE occurred_at >= strftime('%Y-%m-%dT%H:%M:%SZ','now', ?)",
                "-" + window));
        out.put("ai_session_logs", jdbc.queryForMap(
                "SELECT COUNT(*) AS rows FROM ai_session_logs WHERE occurred_at >= strftime('%Y-%m-%dT%H:%M:%SZ','now', ?)",
                "-" + window));
        return out;
    }

    public List<Map<String, Object>> listHttp(int limit) {
        return jdbc.queryForList(
                "SELECT * FROM http_requests ORDER BY id DESC LIMIT ?",
                Math.min(Math.max(limit, 1), 200));
    }

    public List<Map<String, Object>> listAi(int limit) {
        return jdbc.queryForList(
                "SELECT * FROM ai_calls ORDER BY id DESC LIMIT ?",
                Math.min(Math.max(limit, 1), 200));
    }

    public List<Map<String, Object>> prices() {
        return jdbc.queryForList("SELECT * FROM model_prices ORDER BY provider, model");
    }

    @Transactional
    public Map<String, Object> upsertPrice(Map<String, Object> body) {
        jdbc.update("""
                INSERT INTO model_prices (provider, model, input_usd_micros_per_mtok, output_usd_micros_per_mtok, notes, updated_at)
                VALUES (?,?,?,?,?, strftime('%Y-%m-%dT%H:%M:%SZ','now'))
                ON CONFLICT(provider, model) DO UPDATE SET
                  input_usd_micros_per_mtok=excluded.input_usd_micros_per_mtok,
                  output_usd_micros_per_mtok=excluded.output_usd_micros_per_mtok,
                  notes=excluded.notes,
                  updated_at=excluded.updated_at
                """,
                body.get("provider"), body.get("model"),
                toLong(body.get("input_usd_micros_per_mtok")),
                toLong(body.get("output_usd_micros_per_mtok")),
                body.get("notes"));
        return Map.of("ok", true);
    }

    public Map<String, Object> purge(int olderThanDays) {
        int days = Math.max(1, olderThanDays);
        int http = jdbc.update(
                "DELETE FROM http_requests WHERE created_at < strftime('%Y-%m-%dT%H:%M:%SZ','now', ?)",
                "-" + days + " days");
        int ai = jdbc.update(
                "DELETE FROM ai_calls WHERE created_at < strftime('%Y-%m-%dT%H:%M:%SZ','now', ?)",
                "-" + days + " days");
        int appLogs = jdbc.update(
                "DELETE FROM app_logs WHERE occurred_at < strftime('%Y-%m-%dT%H:%M:%SZ','now', ?)",
                "-" + days + " days");
        int sessions = jdbc.update(
                "DELETE FROM ai_session_logs WHERE occurred_at < strftime('%Y-%m-%dT%H:%M:%SZ','now', ?)",
                "-" + days + " days");
        return Map.of(
                "deleted_http", http,
                "deleted_ai", ai,
                "deleted_app_logs", appLogs,
                "deleted_session_logs", sessions,
                "older_than_days", days);
    }

    private Map<String, Object> lookupPrice(String provider, String model) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM model_prices WHERE provider = ? AND model = ?", provider, model);
        if (!rows.isEmpty()) return rows.get(0);
        rows = jdbc.queryForList("SELECT * FROM model_prices WHERE model = ?", model);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private long todayCostMicros() {
        Long v = jdbc.queryForObject("""
                SELECT COALESCE(SUM(cost_micros),0) FROM ai_calls
                WHERE substr(created_at,1,10) = substr(strftime('%Y-%m-%dT%H:%M:%SZ','now'),1,10)
                """, Long.class);
        return v == null ? 0 : v;
    }

    private long dailyBudgetMicros() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT value FROM settings WHERE key = 'ai_daily_budget_usd_micros'");
        if (rows.isEmpty()) return 5_000_000L;
        try {
            return Long.parseLong(String.valueOf(rows.get(0).get("value")));
        } catch (NumberFormatException e) {
            return 5_000_000L;
        }
    }

    private Long resolveOptional(String handle) {
        if (handle == null || handle.isBlank()) return null;
        return personService.resolveId(handle);
    }

    private static String str(Object o) { return o == null ? null : o.toString(); }

    private static long toLong(Object o) {
        if (o == null) return 0;
        if (o instanceof Number n) return n.longValue();
        return Long.parseLong(o.toString());
    }
}
