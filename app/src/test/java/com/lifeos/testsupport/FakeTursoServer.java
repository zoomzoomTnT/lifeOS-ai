package com.lifeos.testsupport;

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
import java.sql.Statement;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-process stand-in for Turso's Hrana-over-HTTP API ({@code POST /v3/pipeline}), backed by a
 * SQLite file. Each stream is its own SQLite connection, kept alive across HTTP requests by a
 * baton, so interactive transactions behave like on the real server. Like Turso, foreign keys
 * are OFF on a new connection unless the client sends {@code PRAGMA foreign_keys=ON}.
 *
 * Test hooks: request/SQL log, HTTP failure injection, stream expiry, required auth token.
 * The real-server counterpart is {@code LibSqlServerIT} (sqld, run in CI).
 */
public final class FakeTursoServer implements AutoCloseable {

    static {
        // JDK HttpServer + Nagle/delayed-ACK adds ~40 ms per request otherwise.
        System.setProperty("sun.net.httpserver.nodelay", "true");
    }

    private final HttpServer server;
    private final String jdbc;
    private final JsonMapper json = JsonMapper.builder().build();
    private final Map<String, Stream> streams = new ConcurrentHashMap<>();

    public final AtomicInteger pipelineRequests = new AtomicInteger();
    public final AtomicInteger streamsOpened = new AtomicInteger();
    /** Every SQL text executed, in order. */
    public final List<String> executed = new CopyOnWriteArrayList<>();
    /** Next N requests fail with HTTP 500 before touching the database. */
    public final AtomicInteger failNextRequests = new AtomicInteger();
    private volatile String requiredToken;
    /** Statements matching this fail with an SQLite error instead of running (e.g. BEGIN). */
    public volatile java.util.function.Predicate<String> failSql = sql -> false;
    /** Statements matching this sleep {@link #slowMs} before running. */
    public volatile java.util.function.Predicate<String> slowSql = sql -> false;
    public volatile long slowMs = 0;
    /**
     * Non-null: reject every batch request whose SQL contains {@link #rejectBatchesContaining} as a whole
     * (request-level error with this message), like sqld when one step does not parse.
     */
    public volatile String rejectBatchMessage = null;
    public volatile String rejectBatchesContaining = "";

    private static final class Stream {
        final Connection c;

        Stream(Connection c) {
            this.c = c;
        }
    }

    public FakeTursoServer(Path dbFile) throws IOException {
        this.jdbc = "jdbc:sqlite:" + dbFile;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "fake-turso");
            t.setDaemon(true);
            return t;
        }));
        server.createContext("/", this::handle);
        server.start();
    }

    public String httpUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** JDBC URL in the canonical form. */
    public String url() {
        return "jdbc:libsql:" + httpUrl();
    }

    public void requireToken(String token) {
        this.requiredToken = token;
    }

    /** Drops every open stream (like the server's idle-stream expiry); open transactions roll back. */
    public void expireAllStreams() {
        for (String b : List.copyOf(streams.keySet())) {
            Stream s = streams.remove(b);
            if (s != null) closeQuietly(s.c);
        }
    }

    public int openStreams() {
        return streams.size();
    }

    @Override
    public void close() {
        server.stop(0);
        expireAllStreams();
    }

    private void handle(HttpExchange ex) throws IOException {
        int status = 200;
        byte[] out;
        try {
            if (!"POST".equals(ex.getRequestMethod()) || !ex.getRequestURI().getPath().equals("/v3/pipeline")) {
                status = 404;
                out = "not found".getBytes(StandardCharsets.UTF_8);
            } else if (requiredToken != null
                    && !("Bearer " + requiredToken).equals(ex.getRequestHeaders().getFirst("Authorization"))) {
                status = 401;
                out = "{\"error\":\"Unauthorized\"}".getBytes(StandardCharsets.UTF_8);
            } else if (failNextRequests.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                status = 500;
                out = "injected failure".getBytes(StandardCharsets.UTF_8);
            } else {
                JsonNode body = json.readTree(ex.getRequestBody().readAllBytes());
                JsonNode resp = pipeline(body);
                if (resp == null) {
                    status = 400; // what Turso answers for a stream that idled out
                    out = "{\"message\":\"The stream has expired due to inactivity\",\"code\":\"STREAM_EXPIRED\"}"
                            .getBytes(StandardCharsets.UTF_8);
                } else {
                    out = json.writeValueAsBytes(resp);
                }
            }
        } catch (Exception e) {
            status = 500;
            out = String.valueOf(e).getBytes(StandardCharsets.UTF_8);
        }
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, out.length);
        ex.getResponseBody().write(out);
        ex.close();
    }

    private JsonNode pipeline(JsonNode body) throws SQLException {
        pipelineRequests.incrementAndGet();
        JsonNode b = body.path("baton");
        Stream s;
        if (b.isString()) {
            s = streams.remove(b.asString());
            if (s == null) return null;
        } else {
            Connection c = DriverManager.getConnection(jdbc);
            try (Statement st = c.createStatement()) {
                st.execute("PRAGMA busy_timeout=5000");
                st.execute("PRAGMA foreign_keys=OFF"); // Turso default
            }
            s = new Stream(c);
            streamsOpened.incrementAndGet();
        }
        boolean closed = false;
        ObjectNode resp = json.createObjectNode();
        ArrayNode results = json.createArrayNode();
        synchronized (s) {
            for (JsonNode req : body.path("requests")) {
                String type = req.path("type").asString();
                ObjectNode r = results.addObject();
                switch (type) {
                    case "execute" -> {
                        try {
                            ObjectNode result = execute(s.c, req.path("stmt"));
                            r.put("type", "ok");
                            ObjectNode response = r.putObject("response");
                            response.put("type", "execute");
                            response.set("result", result);
                        } catch (SQLException e) {
                            r.put("type", "error");
                            ObjectNode err = r.putObject("error");
                            err.put("message", "SQLite error: " + e.getMessage());
                            err.put("code", e.getMessage() != null && e.getMessage().contains("CONSTRAINT")
                                    ? "SQLITE_CONSTRAINT" : "SQLITE_ERROR");
                        }
                    }
                    case "batch" -> {
                        if (rejectBatchMessage != null && req.toString().contains(rejectBatchesContaining)) {
                            r.put("type", "error");
                            ObjectNode err = r.putObject("error");
                            err.put("message", rejectBatchMessage);
                            err.put("code", "HRANA_PROTO_ERROR");
                            break;
                        }
                        ArrayNode stepResults = json.createArrayNode();
                        ArrayNode stepErrors = json.createArrayNode();
                        List<Boolean> ok = new java.util.ArrayList<>();
                        for (JsonNode step : req.path("batch").path("steps")) {
                            JsonNode cond = step.path("condition");
                            boolean run = !cond.isObject() || (cond.path("type").asString().equals("ok")
                                    && ok.size() > cond.path("step").asInt() && ok.get(cond.path("step").asInt()));
                            if (!run) {
                                stepResults.addNull();
                                stepErrors.addNull();
                                ok.add(false);
                                continue;
                            }
                            try {
                                stepResults.add(execute(s.c, step.path("stmt")));
                                stepErrors.addNull();
                                ok.add(true);
                            } catch (SQLException e) {
                                stepResults.addNull();
                                ObjectNode err = stepErrors.addObject();
                                err.put("message", "SQLite error: " + e.getMessage());
                                err.put("code", "SQLITE_ERROR");
                                ok.add(false);
                            }
                        }
                        r.put("type", "ok");
                        ObjectNode response = r.putObject("response");
                        response.put("type", "batch");
                        ObjectNode result = response.putObject("result");
                        result.set("step_results", stepResults);
                        result.set("step_errors", stepErrors);
                    }
                    case "get_autocommit" -> {
                        r.put("type", "ok");
                        ObjectNode response = r.putObject("response");
                        response.put("type", "get_autocommit");
                        response.put("is_autocommit", isAutocommit(s.c));
                    }
                    case "close" -> {
                        closed = true;
                        r.put("type", "ok");
                        r.putObject("response").put("type", "close");
                    }
                    default -> {
                        r.put("type", "error");
                        r.putObject("error").put("message", "unsupported request " + type);
                    }
                }
            }
        }
        if (closed) {
            closeQuietly(s.c);
            resp.putNull("baton");
        } else {
            String next = UUID.randomUUID().toString();
            streams.put(next, s);
            resp.put("baton", next);
        }
        resp.putNull("base_url");
        resp.set("results", results);
        return resp;
    }

    private ObjectNode execute(Connection c, JsonNode stmt) throws SQLException {
        String sql = stmt.path("sql").asString();
        executed.add(sql);
        if (slowSql.test(sql) && slowMs > 0) {
            try {
                Thread.sleep(slowMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (failSql.test(sql)) {
            throw new SQLException("injected failure for: " + sql);
        }
        ObjectNode sr = json.createObjectNode();
        ArrayNode cols = sr.putArray("cols");
        ArrayNode rows = sr.putArray("rows");
        boolean query;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            int k = 1;
            for (JsonNode a : stmt.path("args")) ps.setObject(k++, fromHrana(a));
            query = ps.execute();
            if (query) {
                try (ResultSet rs = ps.getResultSet()) {
                    ResultSetMetaData md = rs.getMetaData();
                    for (int j = 1; j <= md.getColumnCount(); j++) {
                        ObjectNode col = cols.addObject();
                        col.put("name", md.getColumnLabel(j));
                        col.putNull("decltype");
                    }
                    while (rs.next()) {
                        ArrayNode row = rows.addArray();
                        for (int j = 1; j <= md.getColumnCount(); j++) row.add(toHrana(rs.getObject(j)));
                    }
                }
            }
        }
        String up = sql.strip().toUpperCase(Locale.ROOT);
        boolean writes = !query || up.contains("RETURNING");
        long changes = 0;
        Long rowid = null;
        if (writes && !up.startsWith("BEGIN") && !up.startsWith("COMMIT") && !up.startsWith("ROLLBACK")
                && !up.startsWith("PRAGMA")) {
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT changes(), last_insert_rowid()")) {
                rs.next();
                changes = rs.getLong(1);
                rowid = up.startsWith("INSERT") || up.startsWith("REPLACE") ? rs.getLong(2) : null;
            }
        }
        sr.put("affected_row_count", changes);
        if (rowid == null) sr.putNull("last_insert_rowid");
        else sr.put("last_insert_rowid", rowid.toString());
        return sr;
    }

    /** Probe: BEGIN only succeeds outside a transaction. */
    private static boolean isAutocommit(Connection c) {
        try (Statement st = c.createStatement()) {
            st.execute("BEGIN");
            st.execute("ROLLBACK");
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    private static Object fromHrana(JsonNode v) {
        return switch (v.path("type").asString()) {
            case "integer" -> Long.parseLong(v.path("value").asString());
            case "float" -> v.path("value").asDouble();
            case "text" -> v.path("value").asString();
            case "blob" -> Base64.getDecoder().decode(v.path("base64").asString());
            default -> null;
        };
    }

    private ObjectNode toHrana(Object o) {
        ObjectNode v = json.createObjectNode();
        if (o == null) v.put("type", "null");
        else if (o instanceof Integer || o instanceof Long) { v.put("type", "integer"); v.put("value", o.toString()); }
        else if (o instanceof Number n) { v.put("type", "float"); v.put("value", n.doubleValue()); }
        else if (o instanceof byte[] b) { v.put("type", "blob"); v.put("base64", Base64.getEncoder().encodeToString(b)); }
        else { v.put("type", "text"); v.put("value", o.toString()); }
        return v;
    }

    private static void closeQuietly(Connection c) {
        try {
            c.close();
        } catch (SQLException ignored) {
            // test helper
        }
    }
}
