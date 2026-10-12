package com.lifeos.security;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpResponse;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Human-in-the-loop over real HTTP: approval_required → (zoomzoom says yes in chat) → the admin client
 * approves → single-use retry of the exact request; body mismatch, other client, replay, expiry (5 min),
 * denial; agents can't approve; requester ≠ approver; tier 3 admin client only; the admin client
 * skips tier 2.
 */
class ApprovalFlowHttpTest extends AuthHttpTestBase {

    static final JsonMapper JSON = JsonMapper.builder().build();

    @org.springframework.beans.factory.annotation.Autowired
    com.lifeos.security.approval.RiskPolicy riskPolicy;
    @org.springframework.beans.factory.annotation.Autowired
    com.lifeos.security.approval.ApprovalService approvals;

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

    HttpResponse<String> adminDecides(String approvalId, String decision) throws Exception {
        return post("/api/admin/approvals/" + approvalId + "/" + decision, adminKey(), null);
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
    void bigConfirmNeedsTheAdminClientsApprovalOnceForThatExactRequest() throws Exception {
        String key = chatKey(), otherAgent = chatKey();
        long id = previewReceipt(key, 6_000_000, 2); // 60,000.00 >= 50,000.00
        String confirm = "/api/receipts/" + id + "/confirm";

        // 1. asking does nothing but create a pending approval (same one when asked again)
        HttpResponse<String> asked = post(confirm, key, "{\"also_fridge\":false}");
        assertThat(asked.statusCode()).isEqualTo(403);
        assertThat(json(asked).path("error").asString()).isEqualTo("approval_required");
        assertThat(json(asked).path("action").asString()).isEqualTo("receipt.confirm");
        String approvalId = json(asked).path("approval_id").asString();
        assertThat(json(asked).path("summary").asString()).contains("Confirm receipt #" + id).contains("60,000.00 JPY");
        assertThat(json(asked).has("approve_url")).isFalse();
        java.time.Instant expires = java.time.Instant.parse(json(asked).path("expires_at").asString());
        assertThat(expires).isBetween(java.time.Instant.now().plusSeconds(4 * 60), java.time.Instant.now().plusSeconds(5 * 60 + 1));
        assertThat(status(id)).isEqualTo("pending_confirm");
        assertThat(json(post(confirm, key, "{ \"also_fridge\" : false }")).path("approval_id").asString()).isEqualTo(approvalId);
        assertThat(count("SELECT COUNT(*) FROM approval_requests WHERE public_id = ? AND status='pending'", approvalId)).isEqualTo(1);

        // 2. presenting it before the owner approved fails
        HttpResponse<String> early = post(confirm, key, "{\"also_fridge\":false}", "Life-Approval", approvalId);
        assertThat(json(early).path("reason").asString()).isEqualTo("pending");

        // 3. agents can't approve (their own request or anyone's)
        HttpResponse<String> self = post("/api/admin/approvals/" + approvalId + "/approve", key, null);
        assertThat(self.statusCode()).isEqualTo(403);
        assertThat(json(self).path("error").asString()).isEqualTo("admin_only");
        assertThat(post("/api/admin/approvals/" + approvalId + "/approve", otherAgent, null).statusCode()).isEqualTo(403);

        // 4. the admin client re-reads the server's own description, then approves (after a yes in chat)
        HttpResponse<String> seen = get("/api/admin/approvals/" + approvalId, adminKey());
        assertThat(seen.statusCode()).isEqualTo(200);
        assertThat(json(seen).path("summary").asString()).contains("60,000.00 JPY").contains("Confirm receipt #" + id);
        assertThat(json(seen).path("status").asString()).isEqualTo("pending");
        HttpResponse<String> pending = get("/api/admin/approvals?status=pending", adminKey());
        assertThat(pending.body()).contains(approvalId);
        HttpResponse<String> decided = adminDecides(approvalId, "approve");
        assertThat(decided.statusCode()).as(decided.body()).isEqualTo(200);
        assertThat(json(decided).path("status").asString()).isEqualTo("approved");
        assertThat(json(decided).path("decided_via").asString()).isEqualTo("admin_key");
        assertThat(json(decided).path("decided_by_client").asString()).isEqualTo("lifeos-admin");
        assertThat(jdbc.queryForObject("SELECT decided_by_credential FROM approval_requests WHERE public_id = ?",
                String.class, approvalId)).isEqualTo(com.lifeos.testsupport.TestAdminKey.KEY_ID);
        assertThat(count("SELECT COUNT(*) FROM security_events WHERE type='approval_approved' AND client_id='lifeos-admin' "
                + "AND detail_json LIKE ? AND detail_json LIKE '%\"decided_by_client\":\"lifeos-admin\"%'",
                "%" + approvalId + "%")).isEqualTo(1);
        // deciding twice does nothing
        assertThat(adminDecides(approvalId, "deny").statusCode()).isEqualTo(409);

        // 5. it can't be used by another client or for a different body
        assertThat(json(post(confirm, otherAgent, "{\"also_fridge\":false}", "Life-Approval", approvalId))
                .path("reason").asString()).isEqualTo("other_client");
        assertThat(json(post(confirm, key, "{\"also_fridge\":true}", "Life-Approval", approvalId))
                .path("reason").asString()).isEqualTo("request_mismatch");
        assertThat(status(id)).isEqualTo("pending_confirm");

        // 6. the exact request (key order / whitespace don't matter) goes through once
        HttpResponse<String> ok = post(confirm, key, "{ \"also_fridge\": false }", "Life-Approval", approvalId);
        assertThat(ok.statusCode()).as(ok.body()).isEqualTo(200);
        assertThat(status(id)).isEqualTo("confirmed");
        Long approvalRow = jdbc.queryForObject("SELECT id FROM approval_requests WHERE public_id = ?", Long.class, approvalId);
        assertThat(count("SELECT COUNT(*) FROM events WHERE approval_id = ?", approvalRow)).isPositive();
        assertThat(jdbc.queryForObject("SELECT status FROM approval_requests WHERE id = ?", String.class, approvalRow))
                .isEqualTo("consumed");

        // 7. replay is refused
        HttpResponse<String> replay = post(confirm, key, "{\"also_fridge\":false}", "Life-Approval", approvalId);
        assertThat(replay.statusCode()).isEqualTo(403);
        assertThat(json(replay).path("reason").asString()).isEqualTo("already_used");
        assertThat(count("SELECT COUNT(*) FROM security_events WHERE type='approval_consumed' AND detail_json LIKE ?",
                "%" + approvalId + "%")).isEqualTo(1);
    }

    @Test
    void approvalsExpireAfterFiveMinutesAndDenialsStick() throws Exception {
        String key = chatKey();

        long a = previewReceipt(key, 7_000_000, 1);
        String idA = json(post("/api/receipts/" + a + "/confirm", key, "{}")).path("approval_id").asString();
        assertThat(adminDecides(idA, "approve").statusCode()).isEqualTo(200);
        jdbc.update("UPDATE approval_requests SET expires_at = '2020-01-01T00:00:00Z' WHERE public_id = ?", idA);
        assertThat(json(post("/api/receipts/" + a + "/confirm", key, "{}", "Life-Approval", idA)).path("reason").asString())
                .isEqualTo("expired");
        assertThat(status(a)).isEqualTo("pending_confirm");

        // an expired pending request can't be approved any more
        long b = previewReceipt(key, 8_000_000, 1);
        String idB = json(post("/api/receipts/" + b + "/confirm", key, "{}")).path("approval_id").asString();
        jdbc.update("UPDATE approval_requests SET expires_at = '2020-01-01T00:00:00Z' WHERE public_id = ?", idB);
        HttpResponse<String> late = adminDecides(idB, "approve");
        assertThat(late.statusCode()).isEqualTo(409);
        assertThat(json(get("/api/admin/approvals/" + idB, adminKey())).path("status").asString()).isEqualTo("expired");
        assertThat(get("/api/admin/approvals?status=expired", adminKey()).body()).contains(idB);

        // denied stays denied
        long c = previewReceipt(key, 9_000_000, 1);
        String idC = json(post("/api/receipts/" + c + "/confirm", key, "{}")).path("approval_id").asString();
        HttpResponse<String> denied = adminDecides(idC, "deny");
        assertThat(json(denied).path("status").asString()).isEqualTo("denied");
        assertThat(count("SELECT COUNT(*) FROM security_events WHERE type='approval_denied' AND client_id='lifeos-admin' "
                + "AND detail_json LIKE ?", "%" + idC + "%")).isEqualTo(1);
        assertThat(json(post("/api/receipts/" + c + "/confirm", key, "{}", "Life-Approval", idC)).path("reason").asString())
                .isEqualTo("denied");
        assertThat(status(c)).isEqualTo("pending_confirm");

        assertThat(adminDecides("apr_doesnotexist", "approve").statusCode()).isEqualTo(404);
        assertThat(get("/api/admin/approvals?status=bogus", adminKey()).statusCode()).isEqualTo(400);
    }

    @Test
    void theRequesterCanNeverBeTheApprover() {
        LifePrincipal admin = new LifePrincipal(1, "lifeos-admin", com.lifeos.testsupport.TestAdminKey.KEY_ID, ClientKind.ADMIN, java.util.Set.of());
        var risk = new com.lifeos.security.approval.RiskAssessment(2, "test.self", "Self test", java.util.Map.of());
        var a = approvals.request(admin, risk, "POST", "/api/x", "hash-" + uniq());
        var d = approvals.approve(a.publicId(), admin);
        assertThat(d.decision()).isEqualTo(com.lifeos.security.approval.ApprovalService.Decision.SELF_APPROVAL);
        assertThat(approvals.view(a.publicId()).orElseThrow().status()).isEqualTo("pending");
        LifePrincipal agent = new LifePrincipal(1, "x", "y", ClientKind.AGENT, java.util.Set.of());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> approvals.approve(a.publicId(), agent))
                .isInstanceOf(IllegalArgumentException.class);
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
    void tier3IsAdminClientOnly() throws Exception {
        jdbc.update("INSERT INTO http_requests (method, path, status, created_at) VALUES ('GET','/old',200,'2020-01-01T00:00:00Z')");
        String purge = "{\"older_than_days\":30}";

        for (String k : List.of(chatKey(), routineKey(), newKey(ClientKind.SERVICE, List.of(Scopes.OPS_WRITE)))) {
            HttpResponse<String> r = post("/api/ops/purge", k, purge);
            assertThat(r.statusCode()).isEqualTo(403);
            assertThat(json(r).path("error").asString()).isEqualTo("admin_only");
            // no approval id smuggling either
            assertThat(post("/api/ops/purge", k, purge, "Life-Approval", "apr_x").statusCode()).isEqualTo(403);
        }
        assertThat(count("SELECT COUNT(*) FROM http_requests WHERE path='/old'")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM approval_requests WHERE path='/api/ops/purge'")).isZero();

        HttpResponse<String> ok = post("/api/ops/purge", adminKey(), purge);
        assertThat(ok.statusCode()).as(ok.body()).isEqualTo(200);
        assertThat(count("SELECT COUNT(*) FROM http_requests WHERE path='/old'")).isZero();
    }

    @Test
    void adminClientSkipsTier2() throws Exception {
        HttpResponse<String> r = send("PUT", "/api/ops/prices", adminKey(),
                "{\"provider\":\"test\",\"model\":\"admin-" + uniq() + "\",\"input_usd_micros_per_mtok\":1,\"output_usd_micros_per_mtok\":2}");
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
    }

    @Test
    void theAdminClientHasNoDataWrites() throws Exception {
        HttpResponse<String> r = post("/api/memos", adminKey(), "{\"title\":\"x\"}");
        assertThat(r.statusCode()).isEqualTo(403);
        assertThat(json(r).path("required_scope").asString()).isEqualTo(Scopes.MEMOS_WRITE);
    }
}
