package com.lifeos.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpResponse;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * /api/admin/** (docs/auth.md "Admin API"): only the admin client's key; mint/rotate/revoke work end to
 * end; secrets are shown once, never stored in logs or http_requests, and never cached.
 */
@ExtendWith(OutputCaptureExtension.class)
class AdminApiHttpTest extends AuthHttpTestBase {

    static final JsonMapper JSON = JsonMapper.builder().build();

    static JsonNode json(HttpResponse<String> r) {
        return JSON.readTree(r.body());
    }

    HttpResponse<String> createClient(String id, String kind, String scopes) throws Exception {
        return post("/api/admin/clients", adminKey(), "{\"client_id\":\"" + id + "\",\"kind\":\"" + kind
                + "\",\"max_scopes\":[" + String.join(",", List.of(scopes.split(" ")).stream().map(s -> "\"" + s + "\"").toList()) + "]}");
    }

    @Test
    void adminCreatesMintsRotatesListsAndRevokes(CapturedOutput out) throws Exception {
        String client = "bot-" + uniq();
        HttpResponse<String> created = createClient(client, "agent", "memos:write ops:read");
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        assertThat(createClient(client, "agent", "memos:write").statusCode()).isEqualTo(409);

        HttpResponse<String> minted = post("/api/admin/clients/" + client + "/keys", adminKey(), "{}");
        assertThat(minted.statusCode()).as(minted.body()).isEqualTo(201);
        assertThat(minted.headers().firstValue("Cache-Control")).contains("no-store");
        String key1 = json(minted).path("key").asString(), id1 = json(minted).path("key_id").asString();
        assertThat(json(minted).path("expires_at").asString()).endsWith("Z");
        // the minted key works, within its scopes
        assertThat(post("/api/memos", key1, "{\"title\":\"from minted " + uniq() + "\",\"kind\":\"reminder\"}").statusCode()).isEqualTo(200);
        assertThat(post("/api/fridge", key1, "{\"name\":\"x\"}").statusCode()).isEqualTo(403);

        String key2 = json(post("/api/admin/clients/" + client + "/keys", adminKey(), "{\"scopes\":[\"memos:read\"],\"ttl_days\":0}")).path("key").asString();
        assertThat(post("/api/admin/clients/" + client + "/keys", adminKey(), "{}").statusCode()).isEqualTo(409); // max two
        HttpResponse<String> rotated = post("/api/admin/clients/" + client + "/keys", adminKey(), "{\"rotate\":true}");
        assertThat(rotated.statusCode()).isEqualTo(201);
        String key3 = json(rotated).path("key").asString();
        assertThat(get("/api/memos/due", key1).statusCode()).isEqualTo(401); // oldest rotated out
        assertThat(get("/api/memos/due", key2).statusCode()).isEqualTo(200);
        assertThat(get("/api/memos/due", key3).statusCode()).isEqualTo(200);

        HttpResponse<String> list = get("/api/admin/credentials", adminKey());
        assertThat(list.statusCode()).isEqualTo(200);
        assertThat(list.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(list.body()).contains(client).contains(id1).contains("lifeos-admin")
                .doesNotContain(ApiKeyFormat.parse(key2).secret()).doesNotContain("secret_hash");

        String id3 = json(rotated).path("key_id").asString();
        HttpResponse<String> revoked = post("/api/admin/keys/" + id3 + "/revoke", adminKey(), "{\"reason\":\"test\"}");
        assertThat(revoked.statusCode()).as(revoked.body()).isEqualTo(200);
        assertThat(get("/api/memos/due", key3).statusCode()).isEqualTo(401);
        assertThat(post("/api/admin/keys/" + id3 + "/revoke", adminKey(), "{}").statusCode()).isEqualTo(404);

        // secrets never land in logs or http_requests
        for (String k : List.of(key1, key2, key3)) {
            String secret = ApiKeyFormat.parse(k).secret();
            assertThat(out.getAll()).doesNotContain(secret);
            assertThat(count("SELECT COUNT(*) FROM http_requests WHERE COALESCE(body_excerpt,'') || COALESCE(query,'') LIKE ?",
                    "%" + secret + "%")).isZero();
        }
        assertThat(count("SELECT COUNT(*) FROM http_requests WHERE path LIKE '/api/admin/%' AND body_excerpt IS NOT NULL")).isZero();
        assertThat(count("SELECT COUNT(*) FROM http_requests WHERE path = ? AND status = 201",
                "/api/admin/clients/" + client + "/keys")).isEqualTo(3);
    }

    @Test
    void theAdminKeyItselfIsDeployControlled() throws Exception {
        assertThat(post("/api/admin/clients/lifeos-admin/keys", adminKey(), "{}").statusCode()).isEqualTo(400);
        assertThat(post("/api/admin/keys/" + com.lifeos.testsupport.TestAdminKey.KEY_ID + "/revoke", adminKey(), "{}").statusCode())
                .isEqualTo(400);
        assertThat(createClient("admin2-" + uniq(), "admin", "ops:admin").statusCode()).isEqualTo(400);
        assertThat(get("/api/admin/credentials", adminKey()).statusCode()).isEqualTo(200);
    }

    @Test
    void opsAdminIsNeverGrantedToAnotherClient() throws Exception {
        for (String kind : List.of("agent", "service", "human_app")) {
            HttpResponse<String> r = createClient("evil-" + uniq(), kind, "ops:admin");
            assertThat(r.statusCode()).as(kind).isEqualTo(400);
            assertThat(r.body()).contains("only for the admin client");
        }
        String client = "bot-" + uniq();
        assertThat(createClient(client, "agent", "memos:write").statusCode()).isEqualTo(201);
        HttpResponse<String> mint = post("/api/admin/clients/" + client + "/keys", adminKey(), "{\"scopes\":[\"ops:admin\"]}");
        assertThat(mint.statusCode()).isEqualTo(400);
        assertThat(count("SELECT COUNT(*) FROM api_keys k JOIN api_clients c ON c.id = k.api_client_id WHERE c.client_id = ?", client))
                .isZero();
    }

    @Test
    void everyOtherCallerIsRefused() throws Exception {
        String svc = newKey(ClientKind.SERVICE, List.of(Scopes.OPS_WRITE));
        for (String k : List.of(chatKey(), routineKey(), svc)) {
            for (String[] call : List.of(new String[]{"GET", "/api/admin/credentials"},
                    new String[]{"POST", "/api/admin/clients"}, new String[]{"POST", "/api/admin/clients/x/keys"},
                    new String[]{"POST", "/api/admin/keys/abcdefghijkl/revoke"}, new String[]{"GET", "/api/admin/approvals"},
                    new String[]{"POST", "/api/admin/approvals/apr_x/approve"})) {
                HttpResponse<String> r = send(call[0], call[1], k, call[0].equals("POST") ? "{}" : null);
                assertThat(r.statusCode()).as(call[0] + " " + call[1]).isEqualTo(403);
                assertThat(json(r).path("error").asString()).isEqualTo("admin_only");
            }
        }
        HttpResponse<String> anon = post("/api/admin/clients", null, "{\"client_id\":\"x\"}");
        assertThat(anon.statusCode()).isEqualTo(401);
        assertThat(get("/api/admin/credentials", ApiKeyFormat.generate().fullKey()).statusCode()).isEqualTo(401);
    }
}
