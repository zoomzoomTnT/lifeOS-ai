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
        keys.ensureClient(client, "Store test", ClientKind.AGENT, Scopes.GROKBOT_CHAT);
        keys.ensureClient(client, "again (no-op)", ClientKind.AGENT, Scopes.GROKBOT_CHAT);
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

        assertThat(keys.revoke(k3.keyId(), "test")).isTrue();
        assertThat(keys.revoke(k3.keyId(), "test")).isFalse();
        assertThatThrownBy(() -> provider.authenticate(ApiKeyAuthenticationToken.unauthenticated(k3.key())))
                .hasMessageContaining("revoked");

        jdbc.update("UPDATE api_keys SET expires_at='2020-01-01T00:00:00Z' WHERE key_id=?", k2.keyId());
        assertThatThrownBy(() -> provider.authenticate(ApiKeyAuthenticationToken.unauthenticated(k2.key())))
                .hasMessageContaining("expired");

        assertThat(count("SELECT COUNT(*) FROM security_events WHERE type='key_created' AND client_id=?", client)).isEqualTo(3);
        assertThat(count("SELECT COUNT(*) FROM security_events WHERE type='key_revoked' AND credential_id=?", k1.keyId())).isEqualTo(1);
        assertThat(keys.listKeys()).anySatisfy(row -> assertThat(row.get("key_id")).isEqualTo(k3.keyId()));
    }

    @Autowired
    org.springframework.security.web.webauthn.management.PublicKeyCredentialUserEntityRepository userEntities;
    @Autowired
    org.springframework.security.web.webauthn.management.UserCredentialRepository credentials;
    @Autowired
    org.springframework.security.authentication.ott.OneTimeTokenService ott;

    /** Spring Security's stock WebAuthn JDBC repositories: blobs, booleans, instants, transports. */
    @Test
    void webauthnRepositoriesRoundTrip() {
        var userId = org.springframework.security.web.webauthn.api.Bytes.random();
        String name = "owner-" + uniq();
        userEntities.save(org.springframework.security.web.webauthn.api.ImmutablePublicKeyCredentialUserEntity.builder()
                .name(name).id(userId).displayName("Owner").build());
        var found = userEntities.findByUsername(name);
        assertThat(found).isNotNull();
        assertThat(found.getId()).isEqualTo(userId);
        assertThat(userEntities.findById(userId).getName()).isEqualTo(name);

        byte[] cose = new byte[77];
        new java.util.Random(7).nextBytes(cose);
        cose[0] = 0; // includes a NUL byte: must survive as a real blob
        var credId = org.springframework.security.web.webauthn.api.Bytes.random();
        java.time.Instant created = java.time.Instant.parse("2026-10-11T05:00:00.123Z");
        var rec = org.springframework.security.web.webauthn.api.ImmutableCredentialRecord.builder()
                .credentialType(org.springframework.security.web.webauthn.api.PublicKeyCredentialType.PUBLIC_KEY)
                .credentialId(credId).userEntityUserId(userId)
                .publicKey(new org.springframework.security.web.webauthn.api.ImmutablePublicKeyCose(cose))
                .signatureCount(3).uvInitialized(true).backupEligible(true).backupState(false)
                .transports(java.util.Set.of(org.springframework.security.web.webauthn.api.AuthenticatorTransport.INTERNAL,
                        org.springframework.security.web.webauthn.api.AuthenticatorTransport.HYBRID))
                .attestationObject(new org.springframework.security.web.webauthn.api.Bytes(new byte[]{1, 0, 2}))
                .attestationClientDataJSON(new org.springframework.security.web.webauthn.api.Bytes("{}".getBytes()))
                .created(created).lastUsed(created).label("laptop").build();
        credentials.save(rec);
        var back = credentials.findByCredentialId(credId);
        assertThat(back).isNotNull();
        assertThat(back.getPublicKey().getBytes()).isEqualTo(cose);
        assertThat(back.getSignatureCount()).isEqualTo(3);
        assertThat(back.isUvInitialized()).isTrue();
        assertThat(back.isBackupEligible()).isTrue();
        assertThat(back.isBackupState()).isFalse();
        assertThat(back.getTransports()).hasSize(2);
        assertThat(back.getCreated()).isEqualTo(created);
        assertThat(back.getLabel()).isEqualTo("laptop");
        assertThat(credentials.findByUserId(userId)).hasSize(1);

        // sign-in updates the counter and last_used (UPDATE path)
        java.time.Instant later = created.plusSeconds(60);
        credentials.save(org.springframework.security.web.webauthn.api.ImmutableCredentialRecord.fromCredentialRecord(back)
                .signatureCount(4).lastUsed(later).build());
        assertThat(credentials.findByCredentialId(credId).getSignatureCount()).isEqualTo(4);
        assertThat(credentials.findByCredentialId(credId).getLastUsed()).isEqualTo(later);
        assertThat(count("SELECT COUNT(*) FROM security_events WHERE type='passkey_registered' AND detail_json LIKE ?",
                "%" + credId.toBase64UrlString() + "%")).isEqualTo(1); // only the insert is audited

        credentials.delete(credId);
        assertThat(credentials.findByCredentialId(credId)).isNull();
        userEntities.delete(userId);
        assertThat(userEntities.findByUsername(name)).isNull();
    }

    /** Spring's JdbcOneTimeTokenService and the CLI's own insert agree on the format; single use. */
    @Test
    void oneTimeTokensAreSingleUseAndExpire() {
        var t = ott.generate(new org.springframework.security.authentication.ott.GenerateOneTimeTokenRequest("u-" + uniq()));
        var consumed = ott.consume(new org.springframework.security.authentication.ott.OneTimeTokenAuthenticationToken(t.getTokenValue()));
        assertThat(consumed).isNotNull();
        assertThat(consumed.getUsername()).isEqualTo(t.getUsername());
        assertThat(ott.consume(new org.springframework.security.authentication.ott.OneTimeTokenAuthenticationToken(t.getTokenValue()))).isNull();

        // the CLI path (lifeos-admin owner-login) binds a java.sql.Timestamp
        String cli = java.util.UUID.randomUUID().toString();
        jdbc.update("INSERT INTO one_time_tokens (token_value, username, expires_at) VALUES (?,?,?)", cli, "owner",
                java.sql.Timestamp.from(java.time.Instant.now().plusSeconds(300)));
        assertThat(ott.consume(new org.springframework.security.authentication.ott.OneTimeTokenAuthenticationToken(cli))).isNotNull();

        String old = java.util.UUID.randomUUID().toString();
        jdbc.update("INSERT INTO one_time_tokens (token_value, username, expires_at) VALUES (?,?,?)", old, "owner",
                java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(1)));
        assertThat(ott.consume(new org.springframework.security.authentication.ott.OneTimeTokenAuthenticationToken(old))).isNull();
        assertThat(count("SELECT COUNT(*) FROM one_time_tokens WHERE token_value IN (?,?)", cli, old)).isZero();
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
        assertThat(a.expiresAt()).isEqualTo(a.createdAt().plus(com.lifeos.security.approval.ApprovalService.TTL));
        assertThat(approvals.request(p, risk, "POST", "/api/receipts/1/confirm", "h1").id()).isEqualTo(a.id());
        assertThat(approvals.consume(a.publicId(), p, "h1").rejection())
                .isEqualTo(com.lifeos.security.approval.ApprovalService.Rejection.PENDING);
        assertThat(approvals.decide(a.publicId(), true, 1).orElseThrow().status()).isEqualTo("approved");
        assertThat(approvals.consume(a.publicId(), p, "h2").rejection())
                .isEqualTo(com.lifeos.security.approval.ApprovalService.Rejection.REQUEST_MISMATCH);
        assertThat(approvals.consume(a.publicId(), p, "h1").ok()).isTrue();
        assertThat(approvals.consume(a.publicId(), p, "h1").rejection())
                .isEqualTo(com.lifeos.security.approval.ApprovalService.Rejection.ALREADY_USED);
        assertThat(approvals.view(a.publicId()).orElseThrow().summary()).containsEntry("total", "60,000.00 JPY");
    }
}
