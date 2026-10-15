package com.lifeos.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

/** The caller of the current request, if there is one (scheduled jobs have none). */
public final class CurrentActor {
    private CurrentActor() {}

    public static Optional<LifePrincipal> get() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a != null && a.isAuthenticated() && a.getPrincipal() instanceof LifePrincipal p
                ? Optional.of(p) : Optional.empty();
    }

    public static LifePrincipal require() {
        return get().orElseThrow(() -> new IllegalStateException("no authenticated lifeOS caller"));
    }
}
