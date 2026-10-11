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
    ApiKeyAuthenticationProvider provider;

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
}
