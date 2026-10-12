package com.lifeos.security;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Verifies {@code lok_<keyId>_<secret>} against api_keys: the stored hash matches (constant time), not
 * revoked, not expired, client enabled. Normal keys store HMAC-SHA256(pepper, secret); the admin
 * client's key stores the unpeppered SHA-256 it was configured with (LIFE_ADMIN_KEY_SHA256). Effective
 * scopes = key scopes ∩ client ceiling, never ops:admin outside the admin client, then expanded
 * (write ⇒ read). Every check hits the database, so revocation is immediate.
 */
public class ApiKeyAuthenticationProvider implements AuthenticationProvider {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthenticationProvider.class);

    public static final String ROLE_API_CLIENT = "ROLE_API_CLIENT";
    /** Only the admin client's key carries it; /api/admin/** requires it next to ops:admin. */
    public static final String ROLE_ADMIN_CLIENT = "ROLE_ADMIN_CLIENT";
    /** last_used_at is refreshed at most this often per key (fewer writes), or on a new IP. */
    static final long TOUCH_EVERY_SECONDS = 60;

    private final ApiKeyStore store;
    private final KeyHasher hasher;
    private final SecurityEventLog events;
    private final Clock clock;

    public ApiKeyAuthenticationProvider(ApiKeyStore store, KeyHasher hasher, SecurityEventLog events, Clock clock) {
        this.store = store;
        this.hasher = hasher;
        this.events = events;
        this.clock = clock;
    }

    @Override
    public Authentication authenticate(Authentication authentication) {
        String raw = (String) authentication.getCredentials();
        ApiKeyFormat.Parsed parsed = ApiKeyFormat.parse(raw);
        if (parsed == null) throw new ApiKeyAuthenticationException("malformed", null);

        ApiKeyStore.KeyRecord key = store.findByKeyId(parsed.keyId()).orElse(null);
        if (key == null) {
            hasher.hash(parsed.secret()); // same work as a real check
            throw new ApiKeyAuthenticationException("unknown_key", parsed.keyId());
        }
        ClientKind kind = ClientKind.fromDb(key.clientKind());
        boolean secretOk = kind == ClientKind.ADMIN
                ? KeyHasher.equalHex(KeyHasher.sha256Hex(parsed.secret()), key.secretHash())
                : hasher.matches(parsed.secret(), key.secretHash());
        if (!secretOk) {
            throw new ApiKeyAuthenticationException("bad_secret", key.keyId());
        }
        Instant now = Instant.now(clock);
        if (key.revokedAt() != null) throw new ApiKeyAuthenticationException("revoked", key.keyId());
        if (key.expiresAt() != null && !Instant.parse(key.expiresAt()).isAfter(now)) {
            throw new ApiKeyAuthenticationException("expired", key.keyId());
        }
        if (key.clientDisabledAt() != null) throw new ApiKeyAuthenticationException("client_disabled", key.keyId());

        Set<String> ceiling = Scopes.expand(Scopes.parse(key.maxScopes()));
        Set<String> scopes = new TreeSet<>(Scopes.parse(key.scopes()));
        scopes.retainAll(ceiling);
        if (kind != ClientKind.ADMIN) scopes.remove(Scopes.OPS_ADMIN);
        Set<String> effective = Set.copyOf(Scopes.expand(scopes));

        touch(key, now);

        LifePrincipal principal = new LifePrincipal(key.personId(), key.clientId(), key.keyId(), kind, effective);
        List<GrantedAuthority> authorities = authorities(effective);
        if (kind == ClientKind.ADMIN) authorities.add(new SimpleGrantedAuthority(ROLE_ADMIN_CLIENT));
        return new ApiKeyAuthenticationToken(principal, authorities);
    }

    static List<GrantedAuthority> authorities(Set<String> scopes) {
        List<GrantedAuthority> out = new ArrayList<>();
        for (String s : new TreeSet<>(scopes)) out.add(new SimpleGrantedAuthority(Scopes.authority(s)));
        out.add(new SimpleGrantedAuthority(ROLE_API_CLIENT));
        return out;
    }

    private void touch(ApiKeyStore.KeyRecord key, Instant now) {
        String ip = currentIp();
        boolean newIp = ip != null && !Objects.equals(ip, key.lastUsedIp());
        boolean stale = key.lastUsedAt() == null
                || Instant.parse(key.lastUsedAt()).plusSeconds(TOUCH_EVERY_SECONDS).isBefore(now);
        if (!newIp && !stale) return;
        try {
            store.touch(key.id(), now.truncatedTo(ChronoUnit.SECONDS).toString(), ip);
        } catch (DataAccessException e) {
            // bookkeeping only: the request is still authenticated; the security event below is still written
            log.warn("api key {}: last_used not updated: {}", key.keyId(), e.getMessage());
        }
        if (key.lastUsedAt() == null || newIp) {
            events.record(SecurityEventLog.Event.of(key.lastUsedAt() == null ? "key_first_use" : "key_new_ip")
                    .client(key.clientId()).credential(key.keyId()).person(key.personId()).request(currentRequest())
                    .detail(key.lastUsedIp() == null ? Map.of() : Map.of("previous_ip", key.lastUsedIp())));
        }
    }

    private static HttpServletRequest currentRequest() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a ? a.getRequest() : null;
    }

    private static String currentIp() {
        HttpServletRequest r = currentRequest();
        return r == null ? null : r.getRemoteAddr();
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return ApiKeyAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
