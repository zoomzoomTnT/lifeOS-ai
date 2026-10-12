package com.lifeos.security;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.convention.TestBean;

import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/** Approvals and tier-3 calls need a recent passkey assertion (default max age 5 min; the auth clock is moved on 6 min). */
class StalePasskeyHttpTest extends OwnerHttpTestBase {

    /** System time plus an offset the test moves forward. */
    static final class ShiftedClock extends Clock {
        volatile Duration offset = Duration.ZERO;

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.now().plus(offset); }
    }

    static final ShiftedClock CLOCK = new ShiftedClock();

    @TestBean(name = "authClock")
    Clock authClock;

    static Clock authClock() {
        return CLOCK;
    }

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
        CLOCK.offset = Duration.ofMinutes(6);
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
