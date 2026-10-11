package com.lifeos.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Lets the owner's browser (signed in with a passkey on the owner chain) call /api/** with its
 * session cookie, e.g. the /ops dashboard. Only when no Authorization header is sent and only for a
 * passkey session (an OTT-only session that hasn't registered/used a passkey gets nothing). Never
 * creates a session. Unsafe methods through this path need the CSRF token (see SecurityConfig).
 */
public class OwnerSessionBridgeFilter extends OncePerRequestFilter {

    private final HttpSessionSecurityContextRepository sessions = new HttpSessionSecurityContextRepository();
    private final LongSupplier ownerId;

    public OwnerSessionBridgeFilter(LongSupplier ownerId) {
        this.ownerId = ownerId;
        sessions.setAllowSessionCreation(false);
    }

    /** CSRF applies to a request that rides on the session cookie rather than a bearer credential. */
    public static boolean usesSession(HttpServletRequest req) {
        return req.getHeader("Authorization") == null && req.getRequestedSessionId() != null;
    }

    static boolean isPasskeyOwner(Authentication a) {
        if (a == null || !a.isAuthenticated()) return false;
        boolean owner = false, passkey = false;
        for (GrantedAuthority g : a.getAuthorities()) {
            owner |= OwnerAccess.ROLE_OWNER.equals(g.getAuthority());
            passkey |= FactorGrantedAuthority.WEBAUTHN_AUTHORITY.equals(g.getAuthority());
        }
        return owner && passkey;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() == null && usesSession(req)
                && req.getSession(false) != null) {
            Authentication web = sessions.loadDeferredContext(req).get().getAuthentication();
            if (isPasskeyOwner(web)) {
                Set<String> all = Set.copyOf(Scopes.ALL);
                var authorities = new ArrayList<GrantedAuthority>(ApiKeyAuthenticationProvider.authorities(all));
                authorities.add(new SimpleGrantedAuthority(OwnerAccess.ROLE_OWNER));
                // keep the passkey factor (with its issuedAt) so "fresh passkey" checks work here too
                web.getAuthorities().stream().filter(FactorGrantedAuthority.class::isInstance).forEach(authorities::add);
                var p = new LifePrincipal(ownerId.getAsLong(), LifePrincipal.OWNER_WEB_CLIENT, "passkey",
                        ClientKind.OWNER_SESSION, all);
                var ctx = SecurityContextHolder.createEmptyContext();
                ctx.setAuthentication(new ApiKeyAuthenticationToken(p, authorities));
                SecurityContextHolder.setContext(ctx);
            }
        }
        chain.doFilter(req, res);
    }
}
