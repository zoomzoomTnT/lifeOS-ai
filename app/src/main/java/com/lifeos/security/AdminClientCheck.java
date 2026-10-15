package com.lifeos.security;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * {@code @adminClient.check(authentication)} for @PreAuthorize: the caller is the admin client
 * (kind admin). Mirrors the URL rule for /api/admin/** and the tier-3 gate.
 */
@Component("adminClient")
public class AdminClientCheck {

    public boolean check(Authentication authentication) {
        return authentication != null && authentication.getPrincipal() instanceof LifePrincipal p
                && p.kind() == ClientKind.ADMIN;
    }
}
