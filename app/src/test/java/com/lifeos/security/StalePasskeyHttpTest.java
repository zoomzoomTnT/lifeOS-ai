package com.lifeos.security;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/** Approvals and tier-3 calls need a recent passkey assertion (max age shortened to 2 s here). */
@TestPropertySource(properties = "life.auth.passkey-max-age=PT2S")
class StalePasskeyHttpTest extends OwnerHttpTestBase {

    @Test
    void staleSessionMustConfirmWithPasskeyAgain() throws Exception {
        String key = chatKey();
        HttpResponse<String> preview = post("/api/receipts/preview", key, "{\"merchant_name\":\"Stale " + uniq()
                + "\",\"barcode\":\"" + uniq() + "\",\"printed_at\":\"2026-10-11 11:00:00\",\"currency\":\"JPY\","
                + "\"total_cents\":6000000,\"items\":[{\"name\":\"tv\",\"qty\":1,\"amount_cents\":6000000}]}");
        long id = ApprovalFlowHttpTest.json(preview).path("receipt_id").asLong();
        HttpResponse<String> asked = post("/api/receipts/" + id + "/confirm", key, "{}");
        String path = ApprovalFlowHttpTest.json(asked).path("approve_url").asString().substring("http://localhost:8787".length());

        OwnerBrowser owner = signedInOwner();
        Thread.sleep(2_500);
        HttpResponse<String> page = owner.get(path);
        assertThat(page.body()).contains("Confirm with passkey").doesNotContain("Approve (single use)");
        assertThat(owner.postForm(path, "decision=approve&_csrf=" + owner.csrf).statusCode()).isEqualTo(303);
        assertThat(count("SELECT COUNT(*) FROM approval_requests WHERE status='approved' AND path=?",
                "/api/receipts/" + id + "/confirm")).isZero();
        HttpResponse<String> purge = owner.send("POST", "/api/ops/purge", "{\"older_than_days\":365}", true);
        assertThat(purge.statusCode()).isEqualTo(403);
        assertThat(purge.body()).contains("passkey_reauth_required");
    }
}
