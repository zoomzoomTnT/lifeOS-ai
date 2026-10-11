package com.lifeos.security;

import com.lifeos.security.approval.RiskPolicy;
import com.lifeos.web.OpsController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Path tricks against the tier-2/3 gate (QA, PR #29): encoded, '+', '//', dot segments, ';' params,
 * trailing slash... must never reach a risky handler without the approval / owner check. Requests
 * go over a raw socket so the client library cannot normalize the path first.
 */
class PathTricksHttpTest extends OwnerHttpTestBase {

    static final JsonMapper JSON = JsonMapper.builder().build();

    record Raw(int status, String body) {}

    Raw raw(String method, String rawPath, String key, String json) throws Exception {
        byte[] body = json == null ? new byte[0] : json.getBytes(StandardCharsets.UTF_8);
        try (Socket s = new Socket("localhost", port)) {
            s.setSoTimeout(15_000);
            OutputStream o = s.getOutputStream();
            String head = method + " " + rawPath + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n"
                    + (key == null ? "" : "Authorization: Bearer " + key + "\r\n")
                    + "Content-Type: application/json\r\nContent-Length: " + body.length + "\r\n\r\n";
            o.write(head.getBytes(StandardCharsets.ISO_8859_1));
            o.write(body);
            o.flush();
            InputStream in = s.getInputStream();
            ByteArrayOutputStream all = new ByteArrayOutputStream();
            in.transferTo(all);
            String resp = all.toString(StandardCharsets.UTF_8);
            int status = Integer.parseInt(resp.substring(9, 12));
            int split = resp.indexOf("\r\n\r\n");
            return new Raw(status, split < 0 ? "" : resp.substring(split + 4));
        }
    }

    long bigReceipt(String key) throws Exception {
        String u = uniq();
        var r = post("/api/receipts/preview", key, "{\"merchant_name\":\"Store " + u + "\",\"barcode\":\"" + u
                + "\",\"printed_at\":\"2026-10-11 10:00:00\",\"currency\":\"JPY\",\"total_cents\":6000000,"
                + "\"items\":[{\"name\":\"tv\",\"qty\":1,\"amount_cents\":6000000}]}");
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return JSON.readTree(r.body()).path("receipt_id").asLong();
    }

    String status(long id) {
        return jdbc.queryForObject("SELECT status FROM receipts WHERE id = ?", String.class, id);
    }

    String svcAdminKey() {
        return newKey(ClientKind.SERVICE, List.of(Scopes.OPS_ADMIN, Scopes.FINANCE_WRITE));
    }

    /** Variants of /api/receipts/{id}/confirm for a big receipt: none may confirm it. */
    @Test
    void receiptConfirmVariantsNeverConfirmWithoutApproval() throws Exception {
        String key = chatKey();
        long id = bigReceipt(key);
        String d = String.valueOf(id);
        String enc = d.chars().mapToObj(c -> String.format("%%%02X", c)).reduce("", String::concat); // every digit %3X
        List<String> variants = List.of(
                "/api/receipts/" + enc + "/confirm",
                "/api/receipts/+" + d + "/confirm",
                "/api/receipts/0" + d + "/confirm",
                "/api/receipts/" + d + "/confirm/",
                "/api/receipts/" + d + "/c%6Fnfirm",
                "/api/receipts/" + d + ";x=1/confirm",
                "/api/receipts/" + d + "/confirm;jsessionid=1",
                "/api/receipts//" + d + "/confirm",
                "/api/receipts/./" + d + "/confirm",
                "/api/receipts/9/../" + d + "/confirm",
                "/api/%72eceipts/" + d + "/confirm",
                "/api/receipts/" + d + "%2Fconfirm",
                "/api/receipts/%2B" + d + "/confirm");
        for (String v : variants) {
            Raw r = raw("POST", v, key, "{}");
            assertThat(r.status()).as(v + " -> " + r.body()).isIn(400, 403);
            assertThat(status(id)).as(v).isEqualTo("pending_confirm");
        }
        // the plain path still asks for approval
        Raw plain = raw("POST", "/api/receipts/" + d + "/confirm", key, "{}");
        assertThat(plain.status()).isEqualTo(403);
        assertThat(plain.body()).contains("approval_required");
        assertThat(raw("POST", "/api/receipts/" + enc + "/confirm", key, "{}").body()).contains("non_canonical_path");
        assertThat(raw("POST", "/api/receipts/+" + d + "/confirm", key, "{}").status()).isEqualTo(400);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/ops/pric%65s", "/api/ops/%70rices", "/api/ops/prices/", "/api/ops//prices",
            "/api/ops/./prices", "/api/ops/x/../prices", "/api/ops/prices;a=b", "/api/o%70s/prices"})
    void priceChangeVariantsAreRefused(String path) throws Exception {
        String body = "{\"provider\":\"tricks\",\"model\":\"m-" + uniq() + "\",\"input_usd_micros_per_mtok\":1,\"output_usd_micros_per_mtok\":1}";
        Raw r = raw("PUT", path, chatKey(), body);
        assertThat(r.status()).as(path + " -> " + r.body()).isIn(400, 403);
        assertThat(count("SELECT COUNT(*) FROM model_prices WHERE provider = 'tricks'")).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/ops/%70urge", "/api/ops/purg%65", "/api/ops/purge/", "/api/ops//purge",
            "/api/ops/./purge", "/api/ops/a/../purge", "/api/ops/purge;x", "/api/%6Fps/purge", "/api/ops/PURGE"})
    void purgeVariantsWithAnOpsAdminKeyAreRefused(String path) throws Exception {
        jdbc.update("INSERT INTO http_requests (method, path, status, created_at) VALUES ('GET','/old',200,'2000-01-01T00:00:00Z')");
        Raw r = raw("POST", path, svcAdminKey(), "{\"older_than_days\":1}");
        assertThat(r.status()).as(path + " -> " + r.body()).isIn(400, 403, 404);
        assertThat(count("SELECT COUNT(*) FROM http_requests WHERE path = '/old'")).isPositive();
    }

    @Test
    void hugeOrNonNumericIdsAre400Not500() throws Exception {
        String key = chatKey();
        assertThat(raw("POST", "/api/receipts/99999999999999999999/confirm", key, "{}").status()).isEqualTo(400);
        assertThat(raw("GET", "/api/holdings/99999999999999999999", key, null).status()).isEqualTo(400);
        assertThat(raw("POST", "/api/memos/abc/fired", key, "{}").status()).isEqualTo(400);
    }

    @Autowired
    OpsController ops;

    /** Method security alone (no filter in the way): an ops:admin API key cannot run purge. */
    @Test
    void purgeHandlerItselfNeedsTheFreshOwnerSession() {
        var p = new LifePrincipal(1, "svc-x", "kid", ClientKind.SERVICE, Set.of(Scopes.OPS_ADMIN));
        var ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(new ApiKeyAuthenticationToken(p, ApiKeyAuthenticationProvider.authorities(Set.of(Scopes.OPS_ADMIN))));
        SecurityContextHolder.setContext(ctx);
        try {
            assertThatThrownBy(() -> ops.purge(Map.of("older_than_days", 1))).isInstanceOf(AccessDeniedException.class);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Autowired
    RiskPolicy policy;

    /** Second line of defence: the risk tier is decided on the routed (decoded) path. */
    @Test
    void riskPolicyMatchesLikeTheRouter() {
        assertThat(policy.assess("POST", "/api/ops/%70urge", "{}".getBytes())).get()
                .extracting(r -> r.action()).isEqualTo("ops.purge");
        assertThat(policy.assess("PUT", "/api/ops/pric%65s", "{}".getBytes())).get()
                .extracting(r -> r.action()).isEqualTo("ops.prices.update");
        assertThat(policy.assess("POST", "/api/ops/purge;x=1", "{}".getBytes())).isPresent();
        assertThatThrownBy(() -> policy.assess("POST", "/api/receipts/+3/confirm", "{}".getBytes()))
                .isInstanceOf(RiskPolicy.InvalidPathId.class);
        assertThatThrownBy(() -> policy.assess("POST", "/api/receipts/99999999999999999999/confirm", "{}".getBytes()))
                .isInstanceOf(RiskPolicy.InvalidPathId.class);
        assertThat(policy.assess("POST", "/api/receipts/%31%32%33456789/confirm", "{}".getBytes())).isEmpty(); // no such receipt
    }

    @Test
    void canonicalPathRule() {
        assertThat(CanonicalPathFilter.problem("/api/receipts/12/confirm")).isNull();
        assertThat(CanonicalPathFilter.problem("/actuator/metrics/jvm.memory.used")).isNull();
        for (String bad : List.of("/api/a%41", "/api/+1", "/api//a", "/api/./a", "/api/../a", "/api/a;b", "/api/a/",
                "/api/a\\b", "/api/caf\u00e9", "/api/a b", "", "/")) {
            assertThat(CanonicalPathFilter.problem(bad)).as(bad).isNotNull();
        }
    }

    @Test
    void refusedPathsAreAudited() throws Exception {
        raw("POST", "/api/ops/%70urge", chatKey(), "{}");
        assertThat(count("SELECT COUNT(*) FROM security_events WHERE type = 'non_canonical_path'")).isPositive();
    }

    /** DELETE etc. on routes no handler serves: 404/405 as without the gate, and no approval row. */
    @Test
    void unknownRoutesAreNotGatedAndCreateNoApproval() throws Exception {
        String key = chatKey();
        long before = count("SELECT COUNT(*) FROM approval_requests");
        Raw none = raw("DELETE", "/api/fridge/1", key, null);
        assertThat(none.status()).as(none.body()).isEqualTo(404);
        assertThat(none.body()).contains("not_found").doesNotContain("approval");
        Raw other = raw("DELETE", "/api/memos/1", key, null); // PATCH /api/memos/{id} exists
        assertThat(other.status()).as(other.body()).isEqualTo(405);
        Raw rec = raw("DELETE", "/api/receipts/1", key, null);
        assertThat(rec.status()).as(rec.body()).isIn(403, 404, 405); // finance:write rule: still never approval_required
        assertThat(rec.body()).doesNotContain("approval_required");
        assertThat(count("SELECT COUNT(*) FROM approval_requests")).isEqualTo(before);
        // real gated routes still gate
        long id = bigReceipt(key);
        assertThat(raw("POST", "/api/receipts/" + id + "/confirm", key, "{}").body()).contains("approval_required");
    }
}
