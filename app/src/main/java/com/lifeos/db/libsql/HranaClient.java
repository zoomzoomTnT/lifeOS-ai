package com.lifeos.db.libsql;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

/**
 * Minimal Hrana-over-HTTP client ({@code POST /v3/pipeline}, JSON encoding).
 *
 * A pipeline request runs a list of stream requests on one server-side SQLite connection
 * ("stream"). Sending the returned {@code baton} with the next request continues the SAME stream,
 * which is what makes interactive transactions (BEGIN ... COMMIT across several HTTP requests)
 * possible. Spec: https://github.com/tursodatabase/libsql/blob/main/docs/HRANA_3_SPEC.md
 */
final class HranaClient {

    static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30);

    /** Shared by all connections (one connection pool / executor per JVM, not per JDBC connection). */
    private static final HttpClient HTTPS = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    /**
     * Plain http: no h2c upgrade. sqld's h2c handling breaks the JDK client's "Upgrade: h2c" + POST
     * body (request hangs). https negotiates HTTP/2 via ALPN.
     */
    private static final HttpClient PLAIN_HTTP = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT)
            .version(HttpClient.Version.HTTP_1_1).build();

    private final String baseUrl;
    private final String authToken;
    private final HttpClient http;
    private volatile Duration requestTimeout = DEFAULT_REQUEST_TIMEOUT;

    /** The server no longer knows the stream (expired or invalid baton): nothing on it was committed. */
    static final class StreamGoneException extends SQLException {
        StreamGoneException(String msg) {
            super(msg, "08003");
        }
    }

    HranaClient(String baseUrl, String authToken) {
        this.baseUrl = baseUrl;
        this.authToken = authToken;
        this.http = baseUrl.startsWith("http://") ? PLAIN_HTTP : HTTPS;
    }

    Duration requestTimeout() {
        return requestTimeout;
    }

    String baseUrl() {
        return baseUrl;
    }

    /** null / zero / negative = back to the default. */
    void setRequestTimeout(Duration d) {
        requestTimeout = d == null || d.isZero() || d.isNegative() ? DEFAULT_REQUEST_TIMEOUT : d;
    }

    /** Result of one pipeline call. {@code baton == null} means the server closed the stream. */
    record Pipeline(String baton, String baseUrl, JsonNode results) {
        JsonNode result(int i) {
            return results.path(i);
        }

        boolean ok(int i) {
            return "ok".equals(result(i).path("type").asString());
        }

        /** The execute result of request i, or an SQLException carrying the server's error. */
        JsonNode stmtResult(int i, String sql) throws SQLException {
            JsonNode r = result(i);
            if (!"ok".equals(r.path("type").asString())) {
                throw error(r, sql);
            }
            return r.path("response").path("result");
        }

        static SQLException error(JsonNode r, String sql) {
            return errorOf(r.path("error"), sql);
        }

        static SQLException errorOf(JsonNode e, String sql) {
            String msg = e.path("message").asString("unknown libSQL error");
            String code = e.path("code").asString(null);
            return new SQLException(msg + (sql == null ? "" : " [" + abbreviate(sql) + "]"), sqlState(code));
        }
    }

    /**
     * @param streamBaseUrl base URL the server asked us to use for this stream (null = default)
     */
    Pipeline pipeline(String baton, String streamBaseUrl, List<ObjectNode> requests) throws SQLException {
        ObjectNode body = JSON.createObjectNode();
        if (baton == null) body.putNull("baton");
        else body.put("baton", baton);
        ArrayNode reqs = body.putArray("requests");
        requests.forEach(reqs::add);

        String base = streamBaseUrl != null ? streamBaseUrl : baseUrl;
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(base + "/v3/pipeline"))
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        if (authToken != null && !authToken.isBlank()) {
            req.header("Authorization", "Bearer " + authToken);
        }
        HttpResponse<String> resp;
        try {
            resp = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new SQLTransientConnectionException("libSQL HTTP request to " + base + " failed: " + e, "08006", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SQLTransientConnectionException("interrupted during libSQL HTTP request", "08006", e);
        }
        int status = resp.statusCode();
        if (status == 401 || status == 403) {
            throw new SQLException("libSQL server " + base + " rejected the auth token (HTTP " + status
                    + "). Check TURSO_AUTH_TOKEN: it must be a valid token for this database. "
                    + abbreviate(resp.body()), "28000");
        }
        if (baton != null && status == 400 && looksLikeStreamGone(resp.body())) {
            throw new StreamGoneException("libSQL stream is gone on " + base + ": " + abbreviate(resp.body()));
        }
        if (status / 100 != 2) {
            throw new SQLException("libSQL HTTP " + status + " from " + base + ": " + abbreviate(resp.body()),
                    baton != null ? "08003" : "08001");
        }
        JsonNode root = JSON.readTree(resp.body());
        JsonNode b = root.path("baton");
        JsonNode u = root.path("base_url");
        return new Pipeline(b.isString() ? b.asString() : null, u.isString() ? u.asString() : null,
                root.path("results"));
    }

    // ---- request builders ----

    static ObjectNode execute(String sql, List<Object> args) {
        ObjectNode req = JSON.createObjectNode();
        req.put("type", "execute");
        ObjectNode stmt = req.putObject("stmt");
        stmt.put("sql", sql);
        ArrayNode a = stmt.putArray("args");
        if (args != null) for (Object v : args) a.add(value(v));
        stmt.put("want_rows", true);
        return req;
    }

    static ObjectNode execute(String sql) {
        return execute(sql, List.of());
    }

    /** sqld's plain-text answers for a baton whose stream no longer exists on the server. */
    private static final List<String> STREAM_GONE_MESSAGES = List.of(
            "Received an invalid baton",
            "Received a baton for a closed stream",
            "The stream has expired due to inactivity");

    /**
     * Only answers that mean "the server has no such stream" (so its transaction is gone):
     * Hrana error code {@code STREAM_EXPIRED} (Turso, JSON body) or sqld's specific messages.
     * Other 400s (malformed request, SQL errors, ...) are not treated as a rollback.
     */
    static boolean looksLikeStreamGone(String body) {
        if (body == null) return false;
        String b = body.strip();
        if (b.startsWith("{")) {
            try {
                JsonNode n = JSON.readTree(b);
                if ("STREAM_EXPIRED".equals(n.path("code").asString(""))) return true;
                String m = n.path("message").asString(n.path("error").asString(""));
                return STREAM_GONE_MESSAGES.stream().anyMatch(m::startsWith);
            } catch (RuntimeException e) {
                return false;
            }
        }
        return STREAM_GONE_MESSAGES.stream().anyMatch(b::startsWith);
    }

    /** Hrana batch step; {@code okStep} = only run if that earlier step succeeded (null = always). */
    static ObjectNode step(ObjectNode executeRequest, Integer okStep) {
        ObjectNode st = JSON.createObjectNode();
        st.set("stmt", executeRequest.path("stmt"));
        if (okStep != null) {
            ObjectNode cond = st.putObject("condition");
            cond.put("type", "ok");
            cond.put("step", okStep);
        }
        return st;
    }

    static ObjectNode batch(List<ObjectNode> steps) {
        ObjectNode req = JSON.createObjectNode();
        req.put("type", "batch");
        ArrayNode a = req.putObject("batch").putArray("steps");
        steps.forEach(a::add);
        return req;
    }

    static ObjectNode close() {
        ObjectNode req = JSON.createObjectNode();
        req.put("type", "close");
        return req;
    }

    static ObjectNode getAutocommit() {
        ObjectNode req = JSON.createObjectNode();
        req.put("type", "get_autocommit");
        return req;
    }

    static ObjectNode value(Object v) {
        ObjectNode n = JSON.createObjectNode();
        switch (v) {
            case null -> n.put("type", "null");
            case Boolean b -> integer(n, b ? 1 : 0);
            case Byte x -> integer(n, x);
            case Short x -> integer(n, x);
            case Integer x -> integer(n, x);
            case Long x -> integer(n, x);
            case BigInteger x -> { n.put("type", "integer"); n.put("value", x.toString()); }
            case Float f -> real(n, f.doubleValue());
            case Double d -> real(n, d);
            case BigDecimal d -> text(n, d.toPlainString());
            case byte[] bytes -> { n.put("type", "blob"); n.put("base64", Base64.getEncoder().encodeToString(bytes)); }
            case Enum<?> e -> text(n, e.name());
            default -> text(n, v.toString());
        }
        return n;
    }

    private static void integer(ObjectNode n, long v) {
        n.put("type", "integer");
        n.put("value", Long.toString(v));
    }

    private static void real(ObjectNode n, double d) {
        if (Double.isNaN(d)) {
            n.put("type", "null"); // SQLite stores NaN as NULL
        } else {
            n.put("type", "float");
            n.put("value", d);
        }
    }

    private static void text(ObjectNode n, String s) {
        n.put("type", "text");
        n.put("value", s);
    }

    /** Hrana value → Long / Double / String / byte[] / null (same classes for every column). */
    static Object fromValue(JsonNode v) {
        return switch (v.path("type").asString()) {
            case "integer" -> Long.parseLong(v.path("value").asString());
            case "float" -> v.path("value").asDouble();
            case "text" -> v.path("value").asString();
            case "blob" -> Base64.getDecoder().decode(v.path("base64").asString());
            default -> null;
        };
    }

    private static String sqlState(String code) {
        if (code == null) return "HY000";
        if (code.startsWith("SQLITE_CONSTRAINT")) return "23000";
        if (code.startsWith("SQLITE_BUSY") || code.startsWith("SQLITE_LOCKED")) return "40001";
        if (code.startsWith("SQL_PARSE") || code.startsWith("SQL_INPUT") || code.contains("PARSE")) return "42000";
        return "HY000";
    }

    static String abbreviate(String s) {
        if (s == null) return "";
        String one = s.replaceAll("\\s+", " ").strip();
        return one.length() > 300 ? one.substring(0, 300) + "…" : one;
    }
}
