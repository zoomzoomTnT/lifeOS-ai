package com.lifeos.security;

import org.junit.jupiter.api.Test;

import java.net.Socket;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiKeyAuthHttpTest extends AuthHttpTestBase {

    // ---- unauthenticated / invalid credentials ----

    @Test
    void noCredentialIs401WithBearerChallenge() throws Exception {
        for (String path : List.of("/api/memos/due", "/api/ops/summary", "/api/receipts", "/actuator/metrics")) {
            HttpResponse<String> r = get(path, null);
            assertThat(r.statusCode()).as(path).isEqualTo(401);
            assertThat(r.headers().firstValue("WWW-Authenticate")).hasValue("Bearer realm=\"lifeos\"");
            assertThat(r.body()).contains("\"error\":\"unauthorized\"");
        }
        assertThat(post("/api/memos", null, "{\"title\":\"x\"}").statusCode()).isEqualTo(401);
    }

    @Test
    void malformedUnknownAndWrongSecretAre401InvalidTokenAndAudited() throws Exception {
        String good = chatKey();
        ApiKeyFormat.Parsed p = ApiKeyFormat.parse(good);
        String wrongSecret = ApiKeyFormat.PREFIX + p.keyId() + "_" + ApiKeyFormat.random(52);
        String unknown = ApiKeyFormat.generate().fullKey();
        long before = count("SELECT COUNT(*) FROM security_events WHERE type='auth_failed'");
        for (String bad : List.of("nope", "lok_short_x", unknown, wrongSecret)) {
            HttpResponse<String> r = get("/api/memos/due", bad);
            assertThat(r.statusCode()).as(bad).isEqualTo(401);
            assertThat(r.headers().firstValue("WWW-Authenticate").orElse("")).contains("error=\"invalid_token\"");
            assertThat(r.body()).contains("invalid_token").doesNotContain(bad);
        }
        assertThat(count("SELECT COUNT(*) FROM security_events WHERE type='auth_failed'")).isEqualTo(before + 4);
        assertThat(count("SELECT COUNT(*) FROM security_events WHERE type='auth_failed' AND outcome='bad_secret' AND credential_id=?",
                p.keyId())).isEqualTo(1);
        // the presented secret is never stored anywhere
        assertThat(count("SELECT COUNT(*) FROM security_events WHERE detail_json LIKE ?", "%" + p.secret() + "%")).isZero();
    }

    @org.springframework.beans.factory.annotation.Autowired
    AuthProperties authProps;

    @Test
    void secretIsStoredOnlyAsPepperedHmac() {
        String key = chatKey();
        ApiKeyFormat.Parsed p = ApiKeyFormat.parse(key);
        String stored = jdbc.queryForObject("SELECT secret_hash FROM api_keys WHERE key_id=?", String.class, p.keyId());
        assertThat(stored).hasSize(64).doesNotContain(p.secret());
        assertThat(stored).isEqualTo(new KeyHasher(authProps.pepper()).hash(p.secret()));
    }

    // ---- scopes ----

    @Test
    void routineKeyReadsButCannotWrite() throws Exception {
        String routine = routineKey();
        assertThat(get("/api/memos/due", routine).statusCode()).isEqualTo(200);
        assertThat(get("/api/ops/should-wake?lead_minutes=10", routine).statusCode()).isEqualTo(200);

        HttpResponse<String> r = post("/api/fridge", routine, "{\"name\":\"milk\"}");
        assertThat(r.statusCode()).isEqualTo(403);
        assertThat(r.body()).contains("\"error\":\"insufficient_scope\"").contains("\"required_scope\":\"fridge:write\"");
        assertThat(r.headers().firstValue("WWW-Authenticate").orElse(""))
                .contains("error=\"insufficient_scope\"").contains("scope=\"fridge:write\"");
        assertThat(get("/api/receipts", routine).statusCode()).isEqualTo(403);   // no finance at all
        assertThat(post("/api/memos", routine, "{\"title\":\"x\"}").statusCode()).isEqualTo(403); // read != write
        assertThat(count("SELECT COUNT(*) FROM security_events WHERE type='scope_denied' AND outcome='missing fridge:write'"))
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    void writeScopeImpliesRead() throws Exception {
        String key = newKey(ClientKind.AGENT, List.of(Scopes.FRIDGE_WRITE));
        assertThat(get("/api/fridge", key).statusCode()).isEqualTo(200);
        assertThat(post("/api/fridge", key, "{\"name\":\"tofu-" + uniq() + "\"}").statusCode()).isEqualTo(200);
        assertThat(get("/api/memos/due", key).statusCode()).isEqualTo(403);
    }

    @Test
    void chatKeyCanUseTheDomainsButNeverAdmin() throws Exception {
        String chat = chatKey();
        assertThat(post("/api/memos", chat, "{\"title\":\"buy milk " + uniq() + "\",\"kind\":\"reminder\"}").statusCode()).isEqualTo(200);
        assertThat(get("/api/receipts", chat).statusCode()).isEqualTo(200);
        assertThat(get("/api/holdings", chat).statusCode()).isEqualTo(200);
        assertThat(get("/api/ops/summary", chat).statusCode()).isEqualTo(200);

        HttpResponse<String> purge = post("/api/ops/purge", chat, "{\"older_than_days\":1}");
        assertThat(purge.statusCode()).isEqualTo(403);
        assertThat(purge.body()).contains("owner_only");
        assertThat(get("/actuator/metrics", chat).statusCode()).isEqualTo(403);
        assertThat(get("/actuator/db", chat).statusCode()).isEqualTo(403);
    }

    @Test
    void agentsCanNeverHoldOpsAdmin() {
        assertThatThrownBy(() -> keys.ensureClient("evil-" + uniq(), "x", ClientKind.AGENT, List.of(Scopes.OPS_ADMIN)))
                .hasMessageContaining("never granted to an agent");
        // even if the row were edited by hand, the provider strips it for agents
        String key = chatKey();
        String keyId = ApiKeyFormat.parse(key).keyId();
        jdbc.update("UPDATE api_keys SET scopes = scopes || ' ops:admin' WHERE key_id=?", keyId);
        jdbc.update("UPDATE api_clients SET max_scopes = max_scopes || ' ops:admin' WHERE id=(SELECT client_id FROM api_keys WHERE key_id=?)", keyId);
        try {
            assertThat(get("/actuator/metrics", key).statusCode()).isEqualTo(403);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void unmappedApiPathsAreDeniedEvenWithAValidKey() throws Exception {
        String chat = chatKey();
        HttpResponse<String> r = get("/api/admin/keys", chat);
        assertThat(r.statusCode()).isEqualTo(403);
        assertThat(r.body()).contains("no access rule");
    }

    // ---- revocation, expiry, rotation ----

    @Test
    void revokedKeyStopsOnTheNextRequest() throws Exception {
        String key = chatKey();
        assertThat(get("/api/memos/due", key).statusCode()).isEqualTo(200);
        assertThat(keys.revoke(ApiKeyFormat.parse(key).keyId(), "test")).isTrue();
        HttpResponse<String> r = get("/api/memos/due", key);
        assertThat(r.statusCode()).isEqualTo(401);
        assertThat(count("SELECT COUNT(*) FROM security_events WHERE type='auth_failed' AND outcome='revoked' AND credential_id=?",
                ApiKeyFormat.parse(key).keyId())).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM security_events WHERE type='key_revoked' AND credential_id=?",
                ApiKeyFormat.parse(key).keyId())).isEqualTo(1);
    }

    @Test
    void expiredKeyIs401() throws Exception {
        String key = chatKey();
        jdbc.update("UPDATE api_keys SET expires_at='2020-01-01T00:00:00Z' WHERE key_id=?", ApiKeyFormat.parse(key).keyId());
        assertThat(get("/api/memos/due", key).statusCode()).isEqualTo(401);
    }

    @Test
    void disabledClientIs401() throws Exception {
        String key = chatKey();
        jdbc.update("UPDATE api_clients SET disabled_at='2026-01-01T00:00:00Z' WHERE id=(SELECT client_id FROM api_keys WHERE key_id=?)",
                ApiKeyFormat.parse(key).keyId());
        assertThat(get("/api/memos/due", key).statusCode()).isEqualTo(401);
    }

    @Test
    void rotationAllowsTwoActiveKeysThenRevokesTheOldest() throws Exception {
        String client = "rot-" + uniq();
        keys.ensureClient(client, client, ClientKind.AGENT, Scopes.GROKBOT_ROUTINE);
        String k1 = keys.mint(client, null, 90, false).key();
        String k2 = keys.mint(client, null, 90, false).key();
        assertThat(get("/api/memos/due", k1).statusCode()).isEqualTo(200);
        assertThat(get("/api/memos/due", k2).statusCode()).isEqualTo(200);

        assertThatThrownBy(() -> keys.mint(client, null, 90, false)).hasMessageContaining("already has 2 active keys");

        String k3 = keys.mint(client, null, 90, true).key(); // --rotate: oldest goes
        assertThat(get("/api/memos/due", k1).statusCode()).isEqualTo(401);
        assertThat(get("/api/memos/due", k2).statusCode()).isEqualTo(200);
        assertThat(get("/api/memos/due", k3).statusCode()).isEqualTo(200);
    }

    @Test
    void mintedScopesMustStayWithinTheClientCeiling() {
        String client = "ceil-" + uniq();
        keys.ensureClient(client, client, ClientKind.AGENT, Scopes.GROKBOT_ROUTINE);
        assertThatThrownBy(() -> keys.mint(client, List.of(Scopes.FINANCE_WRITE), 90, false))
                .hasMessageContaining("exceed client max_scopes");
        assertThat(keys.mint(client, List.of(Scopes.MEMOS_READ), 0, false).scopes()).containsExactly(Scopes.MEMOS_READ);
    }

    @Test
    void lastUsedIsRecordedWithFirstUseEvent() throws Exception {
        String key = routineKey();
        String keyId = ApiKeyFormat.parse(key).keyId();
        get("/api/memos/due", key);
        get("/api/memos/due", key);
        assertThat(jdbc.queryForObject("SELECT last_used_at FROM api_keys WHERE key_id=?", String.class, keyId)).isNotNull();
        assertThat(count("SELECT COUNT(*) FROM security_events WHERE type='key_first_use' AND credential_id=?", keyId)).isEqualTo(1);
    }

    // ---- actor + audit ----

    @Test
    void writesAreAttributedToTheClientAndKey() throws Exception {
        String chat = chatKey();
        String keyId = ApiKeyFormat.parse(chat).keyId();
        String sym = "T" + uniq().toUpperCase().replaceAll("[^A-Z]", "X");
        HttpResponse<String> r = post("/api/holdings", chat,
                "{\"symbol\":\"" + sym + "\",\"market\":\"US\",\"qty\":1,\"avg_cost\":1.0,\"currency\":\"USD\"}",
                "X-Request-Id", "req-" + sym);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        Map<String, Object> ev = jdbc.queryForMap(
                "SELECT actor_id, client_id, credential_id, request_id FROM events WHERE domain='stocks' AND payload_json=?", sym);
        assertThat(((Number) ev.get("actor_id")).longValue()).isEqualTo(1L);
        assertThat(ev.get("credential_id")).isEqualTo(keyId);
        assertThat((String) ev.get("client_id")).startsWith("test-agent-");
        assertThat(ev.get("request_id")).isEqualTo("req-" + sym);
        Map<String, Object> http = jdbc.queryForMap(
                "SELECT client_id, credential_id, actor_id FROM http_requests WHERE correlation_id=?", "req-" + sym);
        assertThat(http.get("credential_id")).isEqualTo(keyId);
    }

    @Test
    void xLifeHandleCanNoLongerActAsOrCreateAPerson() throws Exception {
        long people = count("SELECT COUNT(*) FROM people");
        String chat = chatKey();
        String sym = "H" + uniq().toUpperCase().replaceAll("[^A-Z]", "X");
        HttpResponse<String> r = post("/api/holdings", chat,
                "{\"symbol\":\"" + sym + "\",\"market\":\"US\",\"qty\":1}", "X-Life-Handle", "someone-else-" + uniq());
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(count("SELECT COUNT(*) FROM people")).isEqualTo(people);
        assertThat(count("SELECT owner_id FROM holdings WHERE symbol=?", sym)).isEqualTo(1L);
        assertThat(count("SELECT COUNT(*) FROM security_events WHERE type='identity_header_ignored'")).isGreaterThanOrEqualTo(1);
    }

    @Test
    void aKeyOnlyReachesItsOwnPersonsRows() throws Exception {
        long other = jdbc.queryForObject(
                "INSERT INTO people (handle, display_name, role) VALUES (?, 'Other', 'member') RETURNING id",
                Long.class, "other-" + uniq());
        String otherClient = "other-" + uniq();
        jdbc.update("INSERT INTO api_clients (client_id, display_name, kind, person_id, max_scopes) VALUES (?,?,?,?,?)",
                otherClient, otherClient, "agent", other, Scopes.format(Scopes.GROKBOT_CHAT));
        String otherKey = keys.mint(otherClient, null, 90, false).key();

        String owner = chatKey();
        HttpResponse<String> memo = post("/api/memos", owner, "{\"title\":\"owner memo " + uniq() + "\",\"kind\":\"reminder\"}");
        long memoId = Long.parseLong(memo.body().replaceAll(".*\"id\":(\\d+).*", "$1"));
        HttpResponse<String> fridge = post("/api/fridge", owner, "{\"name\":\"owner-egg-" + uniq() + "\"}");
        long fridgeId = Long.parseLong(fridge.body().replaceAll(".*\"id\":(\\d+).*", "$1"));

        assertThat(send("PATCH", "/api/memos/" + memoId, otherKey, "{\"status\":\"done\"}").statusCode()).isEqualTo(404);
        assertThat(post("/api/memos/" + memoId + "/fired", otherKey, null).statusCode()).isEqualTo(404);
        assertThat(post("/api/fridge/" + fridgeId + "/resolve", otherKey, "{\"action\":\"eaten\"}").statusCode()).isEqualTo(404);
        assertThat(get("/api/fridge", otherKey).body()).doesNotContain("owner-egg");
        assertThat(jdbc.queryForObject("SELECT status FROM memos WHERE id=?", String.class, memoId)).isNotEqualTo("done");
        // and the owner still can
        assertThat(post("/api/memos/" + memoId + "/fired", owner, null).statusCode()).isEqualTo(200);
    }

    // ---- public paths, actuator ----

    @Test
    void healthIsPublicButDetailsNeedOpsAdmin() throws Exception {
        HttpResponse<String> anon = get("/actuator/health", null);
        assertThat(anon.statusCode()).isEqualTo(200);
        assertThat(anon.body()).contains("\"status\":\"UP\"").doesNotContain("components");
        assertThat(get("/actuator/health/liveness", null).statusCode()).isEqualTo(200);

        String admin = newKey(ClientKind.SERVICE, List.of(Scopes.OPS_ADMIN));
        assertThat(get("/actuator/health", admin).body()).contains("components").contains("\"life\"");
        assertThat(get("/actuator/db", admin).statusCode()).isEqualTo(200);
        assertThat(get("/actuator/metrics", admin).statusCode()).isEqualTo(200);
        assertThat(get("/actuator/info", admin).body()).contains("life-os-app");
    }

    @Test
    void actuatorWritesAreOffEvenForAdmin() throws Exception {
        String admin = newKey(ClientKind.SERVICE, List.of(Scopes.OPS_ADMIN));
        HttpResponse<String> r = post("/actuator/loggers/com.lifeos", admin, "{\"configuredLevel\":\"TRACE\"}");
        assertThat(r.statusCode()).isNotIn(200, 204);
    }

    @Test
    void docsAndWebhookPingStayPublic() throws Exception {
        assertThat(get("/v3/api-docs/api", null).statusCode()).isEqualTo(200);
        // OpenClaw off: the ping endpoint answers itself (it has its own hook token), not 401
        HttpResponse<String> ping = post("/api/ops/webhook/ping", null, "{}");
        assertThat(ping.statusCode()).isEqualTo(404);
        assertThat(ping.body()).contains("openclaw_disabled");
    }

    @Test
    void unknownNonApiPathsAreDenied() throws Exception {
        assertThat(get("/admin/secret", null).statusCode()).isIn(401, 403, 404);
        assertThat(get("/random", null).statusCode()).isIn(401, 403);
    }

    @Test
    void pathTricksNeverReachAProtectedHandler() throws Exception {
        for (String p : List.of("/actuator/health/../metrics", "/actuator/health/..;/metrics", "/actuator/health;x=1/../metrics",
                "/actuator/./metrics", "//api/ops/summary", "/api/ops/webhook/ping/../summary", "/API/ops/summary",
                "/api/ops/%2e%2e/ops/summary", "/actuator/health%2F..%2Fmetrics")) {
            String raw = rawGet(p);
            assertThat(raw).as(p).doesNotStartWith("HTTP/1.1 200").doesNotContain("jvm.memory").doesNotContain("\"ai_calls\"");
        }
    }

    private String rawGet(String path) throws Exception {
        try (Socket s = new Socket("localhost", port)) {
            s.getOutputStream().write(("GET " + path + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            return new String(s.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
