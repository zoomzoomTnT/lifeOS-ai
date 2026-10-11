package com.lifeos.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthUnitTest {

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void keyFormatRoundTripsAndRejectsJunk() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            ApiKeyFormat.Generated g = ApiKeyFormat.generate();
            assertThat(g.fullKey()).startsWith("lok_").hasSize(4 + 12 + 1 + 52).matches("lok_[a-z2-7]{12}_[a-z2-7]{52}");
            ApiKeyFormat.Parsed p = ApiKeyFormat.parse(g.fullKey());
            assertThat(p.keyId()).isEqualTo(g.keyId());
            assertThat(p.secret()).isEqualTo(g.secret());
            assertThat(seen.add(g.keyId())).isTrue();
            assertThat(g.toString()).doesNotContain(g.secret());
        }
        for (String bad : new String[]{null, "", "lok_", "lok_abc_def", "LOK_aaaaaaaaaaaa_" + "a".repeat(52),
                "lok_aaaaaaaaaaaa_" + "A".repeat(52), "lok_aaaaaaaaaaaa_" + "a".repeat(51), "lok_aaaaaaaaaaa1_" + "a".repeat(52),
                "Bearer lok_x", "lok_aaaaaaaaaaaa-" + "a".repeat(52)}) {
            assertThat(ApiKeyFormat.parse(bad)).as(String.valueOf(bad)).isNull();
        }
    }

    @Test
    void hasherNeedsALongPepperAndComparesExactly() {
        assertThatThrownBy(() -> new KeyHasher("short")).hasMessageContaining("at least 32");
        assertThatThrownBy(() -> new KeyHasher(null)).isInstanceOf(IllegalArgumentException.class);
        KeyHasher h = new KeyHasher("p".repeat(32));
        String hash = h.hash("secret");
        assertThat(hash).hasSize(64).isNotEqualTo(new KeyHasher("q".repeat(32)).hash("secret"));
        assertThat(h.matches("secret", hash)).isTrue();
        assertThat(h.matches("secreT", hash)).isFalse();
        assertThat(h.matches(null, hash)).isFalse();
        assertThat(h.matches("secret", null)).isFalse();
    }

    @Test
    void scopesParseExpandAndCheckCeilings() {
        assertThat(Scopes.parse("memos:read ops:read")).containsExactly("memos:read", "ops:read");
        assertThat(Scopes.parse(" ")).isEmpty();
        assertThatThrownBy(() -> Scopes.parse("memos:delete")).hasMessageContaining("unknown scope");
        assertThat(Scopes.expand(List.of("fridge:write"))).containsExactlyInAnyOrder("fridge:write", "fridge:read");
        assertThat(Scopes.expand(List.of("ops:admin"))).contains("ops:read", "ops:write");
        assertThat(Scopes.within(List.of("fridge:read"), List.of("fridge:write"))).isTrue();
        assertThat(Scopes.within(List.of("fridge:write"), List.of("fridge:read"))).isFalse();
        assertThat(Scopes.GROKBOT_CHAT).doesNotContain(Scopes.OPS_ADMIN);
        assertThat(Scopes.ALL_NON_ADMIN).doesNotContain(Scopes.OPS_ADMIN).hasSize(Scopes.ALL.size() - 1);
    }

    @Test
    void settingsCheckFailsClosed() {
        assertThat(AuthSettingsCheck.problem(null, false, false)).contains("LIFE_KEY_PEPPER is not set");
        assertThat(AuthSettingsCheck.problem("  ", false, false)).contains("LIFE_KEY_PEPPER is not set");
        assertThat(AuthSettingsCheck.problem("x".repeat(31), false, false)).contains("too short");
        assertThat(AuthSettingsCheck.problem("x".repeat(32), true, false)).contains("only allowed with the `local` profile");
        assertThat(AuthSettingsCheck.problem("x".repeat(32), false, false)).isNull();
        assertThat(AuthSettingsCheck.problem("x".repeat(32), true, true)).isNull();
        assertThatThrownBy(() -> AuthSettingsCheck.validate(null, false, false, "x.example.com"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void writeRateLimitPerClientPerMinute() throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-10-11T00:00:10Z"), ZoneOffset.UTC);
        WriteRateLimitFilter f = new WriteRateLimitFilter(3, clock);
        LifePrincipal p = new LifePrincipal(1, "grokbot-chat", "k", ClientKind.AGENT, Set.of());
        SecurityContextHolder.getContext().setAuthentication(new ApiKeyAuthenticationToken(p, List.of()));
        int[] passed = {0};
        FilterChain chain = (rq, rs) -> passed[0]++;
        int last = 0;
        for (int i = 0; i < 5; i++) {
            MockHttpServletResponse res = new MockHttpServletResponse();
            f.doFilter(new MockHttpServletRequest("POST", "/api/memos"), res, chain);
            last = res.getStatus();
        }
        assertThat(passed[0]).isEqualTo(3);
        assertThat(last).isEqualTo(429);
        // reads are never limited
        f.doFilter(new MockHttpServletRequest("GET", "/api/memos/due"), new MockHttpServletResponse(), chain);
        assertThat(passed[0]).isEqualTo(4);
        // owner sessions are not limited
        LifePrincipal owner = new LifePrincipal(1, "owner-web", "passkey", ClientKind.OWNER_SESSION, Set.of());
        SecurityContextHolder.getContext().setAuthentication(new ApiKeyAuthenticationToken(owner, List.of()));
        f.doFilter(new MockHttpServletRequest("POST", "/api/memos"), new MockHttpServletResponse(), chain);
        assertThat(passed[0]).isEqualTo(5);
    }

    @Test
    void everyApiRuleUsesAKnownScope() {
        for (ScopeRules.Rule r : ScopeRules.RULES) {
            if (!r.isPublic()) assertThat(Scopes.ALL).as(r.pattern()).contains(r.scope());
        }
    }

    @Test
    void requestFingerprintBindsMethodPathQueryClientAndCanonicalBody() {
        String h = com.lifeos.security.approval.RequestFingerprint.of("POST", "/api/x", "b=2&a=1", "c1",
                "{\"b\":{\"y\":1,\"x\":[1,2]},\"a\":true}".getBytes());
        assertThat(h).hasSize(64);
        assertThat(com.lifeos.security.approval.RequestFingerprint.of("POST", "/api/x", "a=1&b=2", "c1",
                " { \"a\" : true, \"b\" : { \"x\" : [1,2], \"y\" : 1 } } ".getBytes())).isEqualTo(h);
        assertThat(com.lifeos.security.approval.RequestFingerprint.of("POST", "/api/x", "a=1&b=2", "c2",
                "{\"a\":true,\"b\":{\"x\":[1,2],\"y\":1}}".getBytes())).isNotEqualTo(h);
        assertThat(com.lifeos.security.approval.RequestFingerprint.of("POST", "/api/x", "a=1&b=2", "c1",
                "{\"a\":true,\"b\":{\"x\":[2,1],\"y\":1}}".getBytes())).isNotEqualTo(h);
        assertThat(com.lifeos.security.approval.RequestFingerprint.of("PUT", "/api/x", "a=1&b=2", "c1",
                "{\"a\":true,\"b\":{\"x\":[1,2],\"y\":1}}".getBytes())).isNotEqualTo(h);
        assertThat(com.lifeos.security.approval.RequestFingerprint.of("POST", "/api/y", "a=1&b=2", "c1",
                "{\"a\":true,\"b\":{\"x\":[1,2],\"y\":1}}".getBytes())).isNotEqualTo(h);
        assertThat(com.lifeos.security.approval.RequestFingerprint.canonicalBody("not json".getBytes())).startsWith("raw:");
        assertThat(com.lifeos.security.approval.RequestFingerprint.canonicalBody(new byte[0])).isEmpty();
    }
}
