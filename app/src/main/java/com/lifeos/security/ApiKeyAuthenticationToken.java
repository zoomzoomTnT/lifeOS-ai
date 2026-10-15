package com.lifeos.security;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;
import java.util.List;

/** Unauthenticated: carries the raw Bearer value. Authenticated: a {@link LifePrincipal}. */
public class ApiKeyAuthenticationToken extends AbstractAuthenticationToken {

    private final LifePrincipal principal;
    private transient String rawKey;

    private ApiKeyAuthenticationToken(String rawKey) {
        super(List.of());
        this.principal = null;
        this.rawKey = rawKey;
        setAuthenticated(false);
    }

    public ApiKeyAuthenticationToken(LifePrincipal principal, Collection<? extends GrantedAuthority> authorities) {
        super(authorities);
        this.principal = principal;
        super.setAuthenticated(true);
    }

    public static ApiKeyAuthenticationToken unauthenticated(String rawKey) {
        return new ApiKeyAuthenticationToken(rawKey);
    }

    @Override
    public Object getCredentials() {
        return rawKey;
    }

    @Override
    public LifePrincipal getPrincipal() {
        return principal;
    }

    @Override
    public void eraseCredentials() {
        rawKey = null;
        super.eraseCredentials();
    }

    @Override
    public String toString() {
        return "ApiKeyAuthenticationToken[" + (principal == null ? "unauthenticated" : principal.clientId()
                + "/" + principal.credentialId()) + "]";
    }
}
