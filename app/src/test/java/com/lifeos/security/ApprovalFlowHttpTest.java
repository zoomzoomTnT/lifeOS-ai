package com.lifeos.security;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Human-in-the-loop (doc §5 Phase 2 acceptance + §6 tiers) over real HTTP with a real passkey
 * session: approval_required → owner approves with a passkey → single-use retry; body mismatch,
 * other client, replay, expiry, denial; tier 3 owner-only; owner sessions skip approvals.
 */
class ApprovalFlowHttpTest extends OwnerHttpTestBase {

    static final JsonMapper JSON = JsonMapper.builder().build();

    @org.springframework.beans.factory.annotation.Autowired
    com.lifeos.security.approval.RiskPolicy riskPolicy;

    static JsonNode json(HttpResponse<String> r) {
        return JSON.readTree(r.body());
    }

    long previewReceipt(String key, long totalCents, int lines) throws Exception {
        String u = uniq();
        StringBuilder items = new StringBuilder();
        long each = totalCents / lines;
        for (int i = 0; i < lines; i++) {
            long amt = i == lines - 1 ? totalCents - each * (lines - 1) : each;
            items.append(i == 0 ? "" : ",").append("{\"name\":\"item ").append(i).append("\",\"qty\":1,\"amount_cents\":").append(amt).append("}");
        }
        HttpResponse<String> r = post("/api/receipts/preview", key, "{\"merchant_name\":\"Store " + u
                + "\",\"barcode\":\"" + u + "\",\"printed_at\":\"2026-10-11 10:00:00\",\"currency\":\"JPY\",\"total_cents\":"
                + totalCents + ",\"items\":[" + items + "]}");
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return json(r).path("receipt_id").asLong();
    }

    String status(long receiptId) {
        return jdbc.queryForObject("SELECT status FROM receipts WHERE id = ?", String.class, receiptId);
    }

    String approvalPath(HttpResponse<String> r) {
        String url = json(r).path("approve_url").asString();
        assertThat(url).startsWith("http://localhost:8787/approve/apr_");
        return url.substring("http://localhost:8787".length());
    }

    /** The owner opens the link and presses a button, as in the browser. */
    HttpResponse<String> ownerDecides(OwnerBrowser owner, String path, String decision) throws Exception {
        HttpResponse<String> page = owner.get(path);
        assertThat(page.statusCode()).isEqualTo(200);
        Matcher m = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"").matcher(page.body());
        assertThat(m.find()).as(page.body()).isTrue();
        return owner.postForm(path, "decision=" + decision + "&_csrf=" + m.group(1));
    }

    @Test
    void smallConfirmNeedsNoApproval() throws Exception {
        String key = chatKey();
        long id = previewReceipt(key, 1_200, 2);
        HttpResponse<String> r = post("/api/receipts/" + id + "/confirm", key, "{}");
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        assertThat(status(id)).isEqualTo("confirmed");
    }

    @Test
    void bigConfirmNeedsTheOwnersPasskeyApprovalOnceForThatExactRequest() throws Exception {
        String key = chatKey(), otherAgent = chatKey();
        long id = previewReceipt(key, 6_000_000, 2); // 60,000.00 >= 50,000.00
        String confirm = "/api/receipts/" + id + "/confirm";

        // 1. asking does nothing but create a pending approval (same one when asked again)
        HttpResponse<String> asked = post(confirm, key, "{\"also_fridge\":false}");
        assertThat(asked.statusCode()).isEqualTo(403);
        assertThat(json(asked).path("error").asString()).isEqualTo("approval_required");
        assertThat(json(asked).path("action").asString()).isEqualTo("receipt.confirm");
        String approvalId = json(asked).path("approval_id").asString();
        assertThat(json(asked).path("expires_at").asString()).endsWith("Z");
        assertThat(status(id)).isEqualTo("pending_confirm");
        assertThat(json(post(confirm, key, "{ \"also_fridge\" : false }")).path("approval_id").asString()).isEqualTo(approvalId);
        assertThat(count("SELECT COUNT(*) FROM approval_requests WHERE public_id = ? AND status='pending'", approvalId)).isEqualTo(1);

        // 2. presenting it before the owner approved fails
        HttpResponse<String> early = post(confirm, key, "{\"also_fridge\":false}", "Life-Approval", approvalId);
        assertThat(json(early).path("reason").asString()).isEqualTo("pending");

        // 3. the owner sees the server's own description and approves with a passkey
        OwnerBrowser owner = signedInOwner();
        HttpResponse<String> page = owner.get(approvalPath(asked));
        assertThat(page.body()).contains("60,000.00 JPY").contains("Confirm receipt #" + id).contains("Approve");
        HttpResponse<String> decided = ownerDecides(owner, approvalPath(asked), "approve");
        assertThat(decided.body()).contains("Approved.");
        assertThat(count("SELECT COUNT(*) FROM security_events WHERE type='approval_approved' AND detail_json LIKE ?",
                "%" + approvalId + "%")).isEqualTo(1);

        // 4. it can't be used by another client or for a different body
        assertThat(json(post(confirm, otherAgent, "{\"also_fridge\":false}", "Life-Approval", approvalId))
                .path("reason").asString()).isEqualTo("other_client");
        assertThat(json(post(confirm, key, "{\"also_fridge\":true}", "Life-Approval", approvalId))
                .path("reason").asString()).isEqualTo("request_mismatch");
        assertThat(status(id)).isEqualTo("pending_confirm");

        // 5. the exact request (key order / whitespace don't matter) goes through once
        HttpResponse<String> ok = post(confirm, key, "{ \"also_fridge\": false }", "Life-Approval", approvalId);
        assertThat(ok.statusCode()).as(ok.body()).isEqualTo(200);
        assertThat(status(id)).isEqualTo("confirmed");
        Long approvalRow = jdbc.queryForObject("SELECT id FROM approval_requests WHERE public_id = ?", Long.class, approvalId);
        assertThat(count("SELECT COUNT(*) FROM events WHERE approval_id = ?", approvalRow)).isPositive();
        assertThat(jdbc.queryForObject("SELECT status FROM approval_requests WHERE id = ?", String.class, approvalRow))
                .isEqualTo("consumed");

        // 6. replay is refused
        HttpResponse<String> replay = post(confirm, key, "{\"also_fridge\":false}", "Life-Approval", approvalId);
        assertThat(replay.statusCode()).isEqualTo(403);
        assertThat(json(replay).path("reason").asString()).isEqualTo("already_used");
        assertThat(count("SELECT COUNT(*) FROM security_events WHERE type='approval_consumed' AND detail_json LIKE ?",
                "%" + approvalId + "%")).isEqualTo(1);
    }

    @Test
    void approvalsExpireAndDenialsStick() throws Exception {
        String key = chatKey();
        OwnerBrowser owner = signedInOwner();

        long a = previewReceipt(key, 7_000_000, 1);
        HttpResponse<String> askA = post("/api/receipts/" + a + "/confirm", key, "{}");
        String idA = json(askA).path("approval_id").asString();
        ownerDecides(owner, approvalPath(askA), "approve");
        jdbc.update("UPDATE approval_requests SET expires_at = '2020-01-01T00:00:00Z' WHERE public_id = ?", idA);
        assertThat(json(post("/api/receipts/" + a + "/confirm", key, "{}", "Life-Approval", idA)).path("reason").asString())
                .isEqualTo("expired");
        assertThat(status(a)).isEqualTo("pending_confirm");

        // an expired pending request can't be approved any more
        long b = previewReceipt(key, 8_000_000, 1);
        HttpResponse<String> askB = post("/api/receipts/" + b + "/confirm", key, "{}");
        String idB = json(askB).path("approval_id").asString();
        jdbc.update("UPDATE approval_requests SET expires_at = '2020-01-01T00:00:00Z' WHERE public_id = ?", idB);
        HttpResponse<String> late = owner.get(approvalPath(askB));
        assertThat(late.body()).contains("expired").doesNotContain("Approve (single use)");

        // denied stays denied
        long c = previewReceipt(key, 9_000_000, 1);
        HttpResponse<String> askC = post("/api/receipts/" + c + "/confirm", key, "{}");
        String idC = json(askC).path("approval_id").asString();
        assertThat(ownerDecides(owner, approvalPath(askC), "deny").body()).contains("Denied.");
        assertThat(json(post("/api/receipts/" + c + "/confirm", key, "{}", "Life-Approval", idC)).path("reason").asString())
                .isEqualTo("denied");
        assertThat(status(c)).isEqualTo("pending_confirm");
    }

    @Test
    void bulkRowsAndPriceChangesAndDeletesAreTier2() throws Exception {
        String key = chatKey();
        long bulk = previewReceipt(key, 2_100, 21); // small total, but > 20 rows
        HttpResponse<String> r = post("/api/receipts/" + bulk + "/confirm", key, "{}");
        assertThat(json(r).path("error").asString()).isEqualTo("approval_required");

        HttpResponse<String> price = send("PUT", "/api/ops/prices", key,
                "{\"provider\":\"test\",\"model\":\"m-" + uniq() + "\",\"input_usd_micros_per_mtok\":1,\"output_usd_micros_per_mtok\":2}");
        assertThat(price.statusCode()).isEqualTo(403);
        assertThat(json(price).path("action").asString()).isEqualTo("ops.prices.update");

        // DELETE stays tier 2 in the policy, but there is no DELETE handler yet: 404, no approval row
        assertThat(riskPolicy.assess("DELETE", "/api/fridge/1", null)).get().extracting(a -> a.action()).isEqualTo("delete");
        HttpResponse<String> del = send("DELETE", "/api/fridge/1", key, null);
        assertThat(del.statusCode()).isEqualTo(404);
        assertThat(count("SELECT COUNT(*) FROM approval_requests WHERE method='DELETE'")).isZero();
    }

    @Test
    void approvalPagesNeedTheOwnersPasskey() throws Exception {
        String key = chatKey();
        long id = previewReceipt(key, 6_500_000, 1);
        String path = approvalPath(post("/api/receipts/" + id + "/confirm", key, "{}"));
        // no session: off to the passkey login; the agent's key is useless here
        OwnerBrowser stranger = new OwnerBrowser(port);
        assertThat(stranger.get(path).statusCode()).isEqualTo(302);
        HttpResponse<String> withKey = send("GET", path, key, null);
        assertThat(withKey.statusCode()).isEqualTo(302);
        // a forged POST without CSRF does nothing
        OwnerBrowser owner = signedInOwner();
        assertThat(owner.postForm(path, "decision=approve").statusCode()).isEqualTo(403);
        assertThat(count("SELECT COUNT(*) FROM approval_requests WHERE status='approved' AND path = ?",
                "/api/receipts/" + id + "/confirm")).isZero();
        assertThat(owner.get("/approve/apr_doesnotexist").statusCode()).isEqualTo(404);
    }

    @Test
    void tier3IsOwnerPasskeyOnly() throws Exception {
        jdbc.update("INSERT INTO http_requests (method, path, status, created_at) VALUES ('GET','/old',200,'2020-01-01T00:00:00Z')");
        String purge = "{\"older_than_days\":30}";

        // Grok Bot never has ops:admin
        assertThat(post("/api/ops/purge", chatKey(), purge).statusCode()).isEqualTo(403);
        // even a service key that holds ops:admin can't: owner only
        String svc = newKey(ClientKind.SERVICE, List.of(Scopes.OPS_ADMIN));
        HttpResponse<String> s = post("/api/ops/purge", svc, purge);
        assertThat(s.statusCode()).isEqualTo(403);
        assertThat(json(s).path("error").asString()).isEqualTo("owner_only");
        assertThat(count("SELECT COUNT(*) FROM http_requests WHERE path='/old'")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM security_events WHERE type='owner_only_denied'")).isPositive();
        // the agent can't smuggle an approval id in either
        assertThat(post("/api/ops/purge", svc, purge, "Life-Approval", "apr_x").statusCode()).isEqualTo(403);

        // the owner, freshly signed in with a passkey, can
        OwnerBrowser owner = signedInOwner();
        HttpResponse<String> ok = owner.send("POST", "/api/ops/purge", purge, true);
        assertThat(ok.statusCode()).as(ok.body()).isEqualTo(200);
        assertThat(count("SELECT COUNT(*) FROM http_requests WHERE path='/old'")).isZero();
    }

    @Test
    void ownerSessionSkipsTier2() throws Exception {
        OwnerBrowser owner = signedInOwner();
        HttpResponse<String> r = owner.send("PUT", "/api/ops/prices",
                "{\"provider\":\"test\",\"model\":\"owner-" + uniq() + "\",\"input_usd_micros_per_mtok\":1,\"output_usd_micros_per_mtok\":2}", true);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
    }
}
