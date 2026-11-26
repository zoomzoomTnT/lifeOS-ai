package com.lifeos.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The auth tables through the production driver path (Hikari → libSQL driver → Hrana): INSERT ...
 * RETURNING, ISO timestamps, blobs and booleans. Run against FakeTurso and a real sqld.
 */
abstract class AbstractAuthStoresTest {

    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    ApiKeyService keys;
    @Autowired
    ApiKeyStore store;
    @Autowired
    KeyHasher hasher;
    @Autowired
    SecurityEventLog events;
    ApiKeyAuthenticationProvider provider;

    @org.junit.jupiter.api.BeforeEach
    void provider() {
        provider = new ApiKeyAuthenticationProvider(store, hasher, events, java.time.Clock.systemUTC());
    }

    static String uniq() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    @Test
    void mintAuthenticateRotateRevoke() {
        String client = "store-" + uniq();
        keys.createClient(client, "Store test", ClientKind.AGENT, Scopes.GROKBOT_CHAT);
        assertThatThrownBy(() -> keys.createClient(client, "again", ClientKind.AGENT, Scopes.GROKBOT_CHAT))
                .isInstanceOf(ApiKeyService.Conflict.class);
        ApiKeyService.MintedKey k1 = keys.mint(client, null, 90, false);
        ApiKeyService.MintedKey k2 = keys.mint(client, List.of(Scopes.MEMOS_READ), 0, false);
        assertThat(k1.expiresAt()).endsWith("Z");
        assertThat(k2.expiresAt()).isNull();

        Authentication a = provider.authenticate(ApiKeyAuthenticationToken.unauthenticated(k1.key()));
        LifePrincipal p = (LifePrincipal) a.getPrincipal();
        assertThat(p.clientId()).isEqualTo(client);
        assertThat(p.kind()).isEqualTo(ClientKind.AGENT);
        assertThat(p.scopes()).contains(Scopes.FINANCE_WRITE, Scopes.FINANCE_READ).doesNotContain(Scopes.OPS_ADMIN);
        assertThat(a.getAuthorities()).extracting(Object::toString).contains("SCOPE_finance:read");
        assertThat(jdbc.queryForObject("SELECT last_used_at FROM api_keys WHERE key_id=?", String.class, k1.keyId()))
                .isNotNull();

        LifePrincipal p2 = (LifePrincipal) provider.authenticate(ApiKeyAuthenticationToken.unauthenticated(k2.key())).getPrincipal();
        assertThat(p2.scopes()).containsExactly(Scopes.MEMOS_READ);

        ApiKeyService.MintedKey k3 = keys.mint(client, null, 90, true); // rotates k1 out
        assertThatThrownBy(() -> provider.authenticate(ApiKeyAuthenticationToken.unauthenticated(k1.key())))
                .isInstanceOf(ApiKeyAuthenticationException.class).hasMessageContaining("revoked");
        provider.authenticate(ApiKeyAuthenticationToken.unauthenticated(k3.key()));

        assertThat(keys.revokeNonAdmin(k3.keyId(), "test")).isTrue();
        assertThat(keys.revokeNonAdmin(k3.keyId(), "test")).isFalse();
        assertThatThrownBy(() -> provider.authenticate(ApiKeyAuthenticationToken.unauthenticated(k3.key())))
                .hasMessageContaining("revoked");

        jdbc.update("UPDATE api_keys SET expires_at='2020-01-01T00:00:00Z' WHERE key_id=?", k2.keyId());
        assertThatThrownBy(() -> provider.authenticate(ApiKeyAuthenticationToken.unauthenticated(k2.key())))
                .hasMessageContaining("expired");

        assertThat(count("SELECT COUNT(*) FROM security_events WHERE type='key_created' AND client_id=?", client)).isEqualTo(3);
        assertThat(count("SELECT COUNT(*) FROM security_events WHERE type='key_revoked' AND credential_id=?", k1.keyId())).isEqualTo(1);
        assertThat(keys.listKeys()).anySatisfy(row -> assertThat(row.get("key_id")).isEqualTo(k3.keyId()));
    }

    /** The admin key from the (test) deploy secrets: SHA-256 branch, admin authority, at most one admin client. */
    @Test
    void adminKeyThroughTheDriver() {
        Authentication a = provider.authenticate(ApiKeyAuthenticationToken.unauthenticated(com.lifeos.testsupport.TestAdminKey.FULL));
        LifePrincipal p = (LifePrincipal) a.getPrincipal();
        assertThat(p.clientId()).isEqualTo(AdminKeyBootstrap.ADMIN_CLIENT_ID);
        assertThat(p.kind()).isEqualTo(ClientKind.ADMIN);
        assertThat(p.scopes()).contains(Scopes.OPS_ADMIN).doesNotContainAnyElementsOf(Scopes.DATA_WRITES);
        assertThat(a.getAuthorities()).extracting(Object::toString).contains(ApiKeyAuthenticationProvider.ROLE_ADMIN_CLIENT);
        assertThat(keys.installAdminKey(com.lifeos.testsupport.TestAdminKey.KEY_ID,
                KeyHasher.sha256Hex(com.lifeos.testsupport.TestAdminKey.SECRET))).isFalse(); // same values: no-op
        assertThatThrownBy(() -> jdbc.update("INSERT INTO api_clients (client_id, display_name, kind, person_id, max_scopes) "
                + "VALUES (?, 'x', 'admin', 1, 'ops:admin')", "second-admin-" + uniq()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Autowired
    com.lifeos.security.approval.ApprovalService approvals;

    /** approval_requests through the production driver: request → approve → consume once. */
    @Test
    void approvalLifecycle() {
        var p = new LifePrincipal(1, "ap-" + uniq(), "kid", ClientKind.AGENT, java.util.Set.of());
        var risk = new com.lifeos.security.approval.RiskAssessment(2, "receipt.confirm", "Confirm receipt #1",
                java.util.Map.of("total", "60,000.00 JPY"));
        var a = approvals.request(p, risk, "POST", "/api/receipts/1/confirm", "h1");
        assertThat(a.status()).isEqualTo("pending");
        assertThat(a.publicId()).startsWith("apr_").hasSize(36);
        assertThat(a.expiresAt()).isEqualTo(a.createdAt().plus(java.time.Duration.ofMinutes(5)));
        assertThat(approvals.request(p, risk, "POST", "/api/receipts/1/confirm", "h1").id()).isEqualTo(a.id());
        assertThat(approvals.consume(a.publicId(), p, "h1").rejection())
                .isEqualTo(com.lifeos.security.approval.ApprovalService.Rejection.PENDING);
        var admin = new LifePrincipal(1, AdminKeyBootstrap.ADMIN_CLIENT_ID, com.lifeos.testsupport.TestAdminKey.KEY_ID,
                ClientKind.ADMIN, java.util.Set.of());
        var decided = approvals.approve(a.publicId(), admin);
        assertThat(decided.approval().status()).isEqualTo("approved");
        assertThat(decided.approval().decidedVia()).isEqualTo("admin_key");
        assertThat(decided.approval().decidedByClient()).isEqualTo(AdminKeyBootstrap.ADMIN_CLIENT_ID);
        assertThat(decided.approval().decidedByCredential()).isEqualTo(com.lifeos.testsupport.TestAdminKey.KEY_ID);
        assertThat(approvals.list("approved", 100)).extracting(x -> x.publicId()).contains(a.publicId());
        assertThat(approvals.consume(a.publicId(), p, "h2").rejection())
                .isEqualTo(com.lifeos.security.approval.ApprovalService.Rejection.REQUEST_MISMATCH);
        assertThat(approvals.consume(a.publicId(), p, "h1").ok()).isTrue();
        assertThat(approvals.consume(a.publicId(), p, "h1").rejection())
                .isEqualTo(com.lifeos.security.approval.ApprovalService.Rejection.ALREADY_USED);
        assertThat(approvals.view(a.publicId()).orElseThrow().summary()).containsEntry("total", "60,000.00 JPY");
    }
}
