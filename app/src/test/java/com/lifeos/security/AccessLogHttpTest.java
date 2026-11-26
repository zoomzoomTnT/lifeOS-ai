package com.lifeos.security;

import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Refused requests are visible in the DB too (http_requests + security_events, joined by request_id). */
class AccessLogHttpTest extends AuthHttpTestBase {

    Map<String, Object> httpRow(String rid) {
        return jdbc.queryForMap("SELECT status, client_id, credential_id, path FROM http_requests WHERE correlation_id = ?", rid);
    }

    @Test
    void anonymousRequestIsLoggedWithAuthMissing() throws Exception {
        String rid = "t-anon-" + uniq();
        HttpResponse<String> r = send("POST", "/api/memos", null, "{}", "X-Request-Id", rid);
        assertThat(r.statusCode()).isEqualTo(401);
        assertThat(r.headers().firstValue("X-Request-Id")).contains(rid);
        assertThat(((Number) httpRow(rid).get("status")).intValue()).isEqualTo(401);
        assertThat(count("SELECT COUNT(*) FROM security_events WHERE type = 'auth_missing' AND request_id = ?", rid)).isEqualTo(1);
    }

    @Test
    void badKeyIsLoggedWithAuthFailedAndRequestId() throws Exception {
        String rid = "t-bad-" + uniq();
        HttpResponse<String> r = send("GET", "/api/memos/due", "lok_aaaaaaaaaaaa_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", null, "X-Request-Id", rid);
        assertThat(r.statusCode()).isEqualTo(401);
        assertThat(((Number) httpRow(rid).get("status")).intValue()).isEqualTo(401);
        assertThat(count("SELECT COUNT(*) FROM security_events WHERE type = 'auth_failed' AND request_id = ?", rid)).isEqualTo(1);
    }

    @Test
    void scopeDeniedIsAttributedToTheClient() throws Exception {
        String rid = "t-scope-" + uniq();
        HttpResponse<String> r = send("POST", "/api/ops/proactive/run", routineKey(), "{}", "X-Request-Id", rid);
        assertThat(r.statusCode()).isEqualTo(403);
        Map<String, Object> row = httpRow(rid);
        assertThat(((Number) row.get("status")).intValue()).isEqualTo(403);
        assertThat(row.get("client_id")).isNotNull();
        assertThat(row.get("credential_id")).isNotNull();
        assertThat(count("SELECT COUNT(*) FROM security_events WHERE type = 'scope_denied' AND request_id = ?", rid)).isEqualTo(1);
    }

    @Test
    void nonCanonicalPathIsLoggedToo() throws Exception {
        String rid = "t-canon-" + uniq();
        HttpResponse<String> r = send("POST", "/api/memos/%31/fired", chatKey(), "{}", "X-Request-Id", rid);
        assertThat(r.statusCode()).isEqualTo(400);
        assertThat(((Number) httpRow(rid).get("status")).intValue()).isEqualTo(400);
    }

    @Test
    void errorPageIsNotAnEndpoint() throws Exception {
        assertThat(get("/error", null).statusCode()).isIn(401, 403, 404);
        assertThat(get("/error", chatKey()).statusCode()).isIn(401, 403, 404);
    }

    @Test
    void unknownApiRouteIsJson404() throws Exception {
        // an /api path that has a scope rule but no handler
        HttpResponse<String> r = get("/api/receipts/1/nothing-here", chatKey());
        assertThat(r.statusCode()).isEqualTo(404);
        assertThat(r.body()).contains("\"error\":\"not_found\"").doesNotContain("timestamp");
    }

    @Test
    void pepperNeverInToString() {
        assertThat(new AuthProperties("supersecretpepper-0123456789abcdef0123", false, 1, null, null).toString())
                .doesNotContain("supersecret").contains("pepper=***");
    }
}
