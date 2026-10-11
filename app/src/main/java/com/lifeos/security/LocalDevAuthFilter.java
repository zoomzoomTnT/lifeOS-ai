package com.lifeos.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * `local` profile with life.auth.local-open=true only (AuthSettingsCheck enforces that): a request
 * with no Authorization header and no session acts as the owner, so curl and the ops page work
 * without keys. A request that does send a key is still verified normally.
 */
public class LocalDevAuthFilter extends OncePerRequestFilter {

    private final LongSupplier ownerId;

    public LocalDevAuthFilter(LongSupplier ownerId) {
        this.ownerId = ownerId;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        var ctx = SecurityContextHolder.getContext();
        if (ctx.getAuthentication() == null && BearerApiKeyConverter.bearer(req) == null) {
            Set<String> all = Set.copyOf(Scopes.ALL);
            var authorities = new ArrayList<>(ApiKeyAuthenticationProvider.authorities(all));
            authorities.add(new SimpleGrantedAuthority(OwnerAccess.ROLE_OWNER));
            LifePrincipal p = new LifePrincipal(ownerId.getAsLong(), LifePrincipal.LOCAL_DEV_CLIENT, "local",
                    ClientKind.LOCAL_DEV, all);
            var empty = SecurityContextHolder.createEmptyContext();
            empty.setAuthentication(new ApiKeyAuthenticationToken(p, authorities));
            SecurityContextHolder.setContext(empty);
        }
        chain.doFilter(req, res);
    }
}
