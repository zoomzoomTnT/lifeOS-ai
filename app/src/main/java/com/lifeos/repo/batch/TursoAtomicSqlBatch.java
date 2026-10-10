package com.lifeos.repo.batch;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turso: sends all steps in ONE {@code POST /v3/pipeline} request as a Hrana {@code batch}:
 *
 * <pre>
 *   0      BEGIN IMMEDIATE
 *   1..n   step i            if ok(i-1) and not is_autocommit
 *   n+1    COMMIT            if ok(n)   and not is_autocommit
 *   n+2    ROLLBACK          if not ok(n+1)
 * </pre>
 *
 * The batch runs sequentially on one server-side stream, so a failure skips every later step and
 * rolls back. This is the same construction libsql-client uses for {@code client.batch(..., "write")}.
 * Spec: https://github.com/tursodatabase/libsql/blob/main/docs/HRANA_3_SPEC.md (Batches, v3/pipeline).
 */
public class TursoAtomicSqlBatch implements AtomicSqlBatch {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final URI pipelineUri;
    private final String authToken;
    private final HttpClient http;
    private final JsonMapper json = JsonMapper.builder().build();

    public TursoAtomicSqlBatch(String jdbcUrl, String authToken) {
        this.pipelineUri = URI.create(httpBase(jdbcUrl) + "/v3/pipeline");
        this.authToken = authToken;
        this.http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    }

    /** {@code jdbc:dbeaver:libsql:https://host} or {@code libsql://host} → {@code https://host}. */
    static String httpBase(String url) {
        String u = url.strip();
        if (u.startsWith("jdbc:dbeaver:libsql:")) u = u.substring("jdbc:dbeaver:libsql:".length());
        if (u.startsWith("libsql://")) u = "https://" + u.substring("libsql://".length());
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u;
    }

    @Override
    public List<List<Map<String, Object>>> execute(List<SqlStep> steps) {
        if (steps.isEmpty()) return List.of();
        String body = json.writeValueAsString(requestBody(steps));
        HttpRequest.Builder req = HttpRequest.newBuilder(pipelineUri)
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (authToken != null && !authToken.isBlank()) {
            req.header("Authorization", "Bearer " + authToken);
        }
        HttpResponse<String> resp;
        try {
            resp = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            // The request may or may not have reached the server; the batch is still all-or-nothing.
            throw new AtomicBatchException("Turso batch request failed (outcome unknown): " + e.getMessage(), e);
        }
        if (resp.statusCode() / 100 != 2) {
            throw new AtomicBatchException("Turso batch HTTP " + resp.statusCode() + ": " + abbreviate(resp.body()));
        }
        return parse(json.readTree(resp.body()), steps.size());
    }

    ObjectNode requestBody(List<SqlStep> steps) {
        int n = steps.size();
        ObjectNode root = json.createObjectNode();
        root.putNull("baton");
        ArrayNode requests = root.putArray("requests");
        ObjectNode batchReq = requests.addObject();
        batchReq.put("type", "batch");
        ArrayNode bsteps = batchReq.putObject("batch").putArray("steps");

        addStep(bsteps, "BEGIN IMMEDIATE", List.of(), null);
        for (int i = 0; i < n; i++) {
            addStep(bsteps, steps.get(i).sql(), steps.get(i).args(), okAndInTx(i));
        }
        addStep(bsteps, "COMMIT", List.of(), okAndInTx(n));
        ObjectNode notCommitted = json.createObjectNode();
        notCommitted.put("type", "not");
        notCommitted.set("cond", ok(n + 1));
        addStep(bsteps, "ROLLBACK", List.of(), notCommitted);

        requests.addObject().put("type", "close");
        return root;
    }

    private ObjectNode ok(int step) {
        ObjectNode c = json.createObjectNode();
        c.put("type", "ok");
        c.put("step", step);
        return c;
    }

    private ObjectNode okAndInTx(int prevStep) {
        ObjectNode autocommit = json.createObjectNode();
        autocommit.put("type", "is_autocommit");
        ObjectNode notAutocommit = json.createObjectNode();
        notAutocommit.put("type", "not");
        notAutocommit.set("cond", autocommit);
        ObjectNode and = json.createObjectNode();
        and.put("type", "and");
        ArrayNode conds = and.putArray("conds");
        conds.add(ok(prevStep));
        conds.add(notAutocommit);
        return and;
    }

    private void addStep(ArrayNode bsteps, String sql, List<Object> args, ObjectNode condition) {
        ObjectNode step = bsteps.addObject();
        if (condition != null) step.set("condition", condition);
        ObjectNode stmt = step.putObject("stmt");
        stmt.put("sql", sql);
        ArrayNode a = stmt.putArray("args");
        for (Object v : args) a.add(value(v));
    }

    private ObjectNode value(Object v) {
        ObjectNode n = json.createObjectNode();
        switch (v) {
            case null -> n.put("type", "null");
            case Boolean b -> { n.put("type", "integer"); n.put("value", b ? "1" : "0"); }
            case Integer i -> { n.put("type", "integer"); n.put("value", Long.toString(i)); }
            case Long l -> { n.put("type", "integer"); n.put("value", Long.toString(l)); }
            case Short s -> { n.put("type", "integer"); n.put("value", Long.toString(s)); }
            case Double d -> { n.put("type", "float"); n.put("value", d); }
            case Float f -> { n.put("type", "float"); n.put("value", f.doubleValue()); }
            case byte[] bytes -> { n.put("type", "blob"); n.put("base64", Base64.getEncoder().encodeToString(bytes)); }
            default -> { n.put("type", "text"); n.put("value", v.toString()); }
        }
        return n;
    }

    List<List<Map<String, Object>>> parse(JsonNode root, int n) {
        JsonNode first = root.path("results").path(0);
        if (!"ok".equals(first.path("type").asString())) {
            throw new AtomicBatchException("Turso batch rejected: " + abbreviate(first.path("error").toString()));
        }
        JsonNode result = first.path("response").path("result");
        JsonNode stepResults = result.path("step_results");
        JsonNode stepErrors = result.path("step_errors");
        int commitIdx = n + 1;
        if (stepResults.path(commitIdx).isMissingNode() || stepResults.path(commitIdx).isNull()) {
            String why = "transaction not committed";
            for (int i = 0; i <= commitIdx; i++) {
                JsonNode err = stepErrors.path(i);
                if (!err.isMissingNode() && !err.isNull()) {
                    why = (i == 0 ? "BEGIN" : i == commitIdx ? "COMMIT" : "step " + (i - 1))
                            + " failed: " + err.path("message").asString();
                    break;
                }
            }
            throw new AtomicBatchException("Turso batch rolled back, nothing written (" + why + ")");
        }
        List<List<Map<String, Object>>> out = new ArrayList<>(n);
        for (int i = 1; i <= n; i++) {
            out.add(rows(stepResults.path(i)));
        }
        return out;
    }

    private List<Map<String, Object>> rows(JsonNode stmtResult) {
        JsonNode cols = stmtResult.path("cols");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode row : stmtResult.path("rows")) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (int c = 0; c < row.size(); c++) {
                String name = cols.path(c).path("name").asString();
                m.put(name, fromValue(row.get(c)));
            }
            rows.add(m);
        }
        return rows;
    }

    static Object fromValue(JsonNode v) {
        return switch (v.path("type").asString()) {
            case "integer" -> Long.parseLong(v.path("value").asString());
            case "float" -> v.path("value").asDouble();
            case "text" -> v.path("value").asString();
            case "blob" -> Base64.getDecoder().decode(v.path("base64").asString());
            default -> null;
        };
    }

    private static String abbreviate(String s) {
        if (s == null) return "";
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }
}
