package com.lifeos.receipts;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Minimal stand-in for Turso over HTTP, backed by a SQLite file:
 * <ul>
 *   <li>{@code POST /} — the legacy stateless API the DBeaver libSQL JDBC driver uses; every
 *       statement runs on a fresh connection in autocommit mode.</li>
 *   <li>{@code POST /v3/pipeline} — Hrana 3 {@code batch} + {@code close}: steps run in order on one
 *       connection, honouring {@code ok/error/not/and/or/is_autocommit} conditions.</li>
 * </ul>
 */
final class FakeTursoServer implements AutoCloseable {

    private final HttpServer server;
    private final String jdbc;
    private final JsonMapper json = JsonMapper.builder().build();
    final AtomicInteger pipelineRequests = new AtomicInteger();

    FakeTursoServer(Path dbFile) throws IOException {
        this.jdbc = "jdbc:sqlite:" + dbFile;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    String url() {
        return "jdbc:dbeaver:libsql:http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private synchronized void handle(HttpExchange ex) throws IOException {
        byte[] out;
        try {
            if (!"POST".equals(ex.getRequestMethod())) {
                out = "fake-turso".getBytes(StandardCharsets.UTF_8);
            } else {
                JsonNode body = json.readTree(ex.getRequestBody().readAllBytes());
                String path = ex.getRequestURI().getPath();
                JsonNode resp = path.equals("/v3/pipeline") ? pipeline(body) : legacy(body);
                out = json.writeValueAsBytes(resp);
            }
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, out.length);
        } catch (Exception e) {
            out = String.valueOf(e).getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(500, out.length);
        }
        ex.getResponseBody().write(out);
        ex.close();
    }

    // ---- legacy "/" (what the JDBC driver sends) ----

    private JsonNode legacy(JsonNode body) throws SQLException {
        ArrayNode out = json.createArrayNode();
        for (JsonNode st : body.path("statements")) {
            String sql = st.isString() ? st.asString() : st.path("q").asString();
            List<Object> params = new ArrayList<>();
            JsonNode p = st.path("params");
            if (p.isArray()) {
                p.forEach(v -> params.add(nativeValue(v)));
            } else if (p.isObject()) {
                List<String> keys = new ArrayList<>(p.propertyNames());
                keys.sort((a, b) -> Integer.compare(idx(a), idx(b)));
                keys.forEach(k -> params.add(nativeValue(p.get(k))));
            }
            ObjectNode item = out.addObject();
            try (Connection c = DriverManager.getConnection(jdbc); PreparedStatement ps = c.prepareStatement(sql)) {
                for (int i = 0; i < params.size(); i++) ps.setObject(i + 1, params.get(i));
                ObjectNode res = item.putObject("results");
                ArrayNode cols = res.putArray("columns");
                ArrayNode rows = res.putArray("rows");
                if (ps.execute()) {
                    try (ResultSet rs = ps.getResultSet()) {
                        ResultSetMetaData md = rs.getMetaData();
                        for (int i = 1; i <= md.getColumnCount(); i++) cols.add(md.getColumnLabel(i));
                        while (rs.next()) {
                            ArrayNode row = rows.addArray();
                            for (int i = 1; i <= md.getColumnCount(); i++) addNative(row, rs.getObject(i));
                        }
                    }
                    res.put("rows_written", 0);
                } else {
                    res.put("rows_written", ps.getUpdateCount());
                }
            } catch (SQLException e) {
                item.removeAll();
                item.put("error", e.getMessage());
            }
        }
        return out;
    }

    private static int idx(String k) {
        return Integer.parseInt(k.replaceAll("^[?:$@]", ""));
    }

    private static Object nativeValue(JsonNode v) {
        if (v == null || v.isNull()) return null;
        if (v.isBoolean()) return v.asBoolean() ? 1 : 0;
        if (v.isIntegralNumber()) return v.asLong();
        if (v.isNumber()) return v.asDouble();
        return v.asString();
    }

    private static void addNative(ArrayNode row, Object o) {
        if (o == null) row.addNull();
        else if (o instanceof Integer || o instanceof Long) row.add(((Number) o).longValue());
        else if (o instanceof Number n) row.add(n.doubleValue());
        else row.add(o.toString());
    }

    // ---- Hrana 3 "/v3/pipeline" ----

    private JsonNode pipeline(JsonNode body) throws SQLException {
        pipelineRequests.incrementAndGet();
        ObjectNode resp = json.createObjectNode();
        resp.putNull("baton");
        resp.putNull("base_url");
        ArrayNode results = resp.putArray("results");
        try (Connection c = DriverManager.getConnection(jdbc)) {
            for (JsonNode req : body.path("requests")) {
                ObjectNode r = results.addObject();
                r.put("type", "ok");
                ObjectNode response = r.putObject("response");
                String type = req.path("type").asString();
                response.put("type", type);
                if (type.equals("batch")) {
                    response.set("result", batch(c, req.path("batch").path("steps")));
                }
            }
        }
        return resp;
    }

    private ObjectNode batch(Connection c, JsonNode steps) throws SQLException {
        int n = steps.size();
        Boolean[] ok = new Boolean[n]; // null = skipped
        ObjectNode result = json.createObjectNode();
        ArrayNode stepResults = result.putArray("step_results");
        ArrayNode stepErrors = result.putArray("step_errors");
        boolean[] inTx = {false};
        for (int i = 0; i < n; i++) {
            JsonNode step = steps.get(i);
            JsonNode cond = step.path("condition");
            if (!cond.isMissingNode() && !cond.isNull() && !eval(cond, ok, inTx[0])) {
                stepResults.addNull();
                stepErrors.addNull();
                continue;
            }
            JsonNode stmt = step.path("stmt");
            String sql = stmt.path("sql").asString();
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                int k = 1;
                for (JsonNode a : stmt.path("args")) ps.setObject(k++, hranaValue(a));
                ObjectNode sr = json.createObjectNode();
                ArrayNode cols = sr.putArray("cols");
                ArrayNode rows = sr.putArray("rows");
                if (ps.execute()) {
                    try (ResultSet rs = ps.getResultSet()) {
                        ResultSetMetaData md = rs.getMetaData();
                        for (int j = 1; j <= md.getColumnCount(); j++) cols.addObject().put("name", md.getColumnLabel(j));
                        while (rs.next()) {
                            ArrayNode row = rows.addArray();
                            for (int j = 1; j <= md.getColumnCount(); j++) row.add(toHrana(rs.getObject(j)));
                        }
                    }
                    sr.put("affected_row_count", 0);
                } else {
                    sr.put("affected_row_count", ps.getUpdateCount());
                }
                String up = sql.strip().toUpperCase();
                if (up.startsWith("BEGIN")) inTx[0] = true;
                if (up.startsWith("COMMIT") || up.startsWith("ROLLBACK")) inTx[0] = false;
                ok[i] = true;
                stepResults.add(sr);
                stepErrors.addNull();
            } catch (SQLException e) {
                ok[i] = false;
                stepResults.addNull();
                stepErrors.addObject().put("message", e.getMessage());
            }
        }
        return result;
    }

    private boolean eval(JsonNode cond, Boolean[] ok, boolean inTx) {
        return switch (cond.path("type").asString()) {
            case "ok" -> Boolean.TRUE.equals(ok[cond.path("step").asInt()]);
            case "error" -> Boolean.FALSE.equals(ok[cond.path("step").asInt()]);
            case "not" -> !eval(cond.path("cond"), ok, inTx);
            case "and" -> {
                for (JsonNode c : cond.path("conds")) if (!eval(c, ok, inTx)) yield false;
                yield true;
            }
            case "or" -> {
                for (JsonNode c : cond.path("conds")) if (eval(c, ok, inTx)) yield true;
                yield false;
            }
            case "is_autocommit" -> !inTx;
            default -> throw new IllegalArgumentException("cond " + cond);
        };
    }

    private static Object hranaValue(JsonNode v) {
        return switch (v.path("type").asString()) {
            case "integer" -> Long.parseLong(v.path("value").asString());
            case "float" -> v.path("value").asDouble();
            case "text" -> v.path("value").asString();
            default -> null;
        };
    }

    private ObjectNode toHrana(Object o) {
        ObjectNode v = json.createObjectNode();
        if (o == null) v.put("type", "null");
        else if (o instanceof Integer || o instanceof Long) { v.put("type", "integer"); v.put("value", o.toString()); }
        else if (o instanceof Number n) { v.put("type", "float"); v.put("value", n.doubleValue()); }
        else { v.put("type", "text"); v.put("value", o.toString()); }
        return v;
    }
}
