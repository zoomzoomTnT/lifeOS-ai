package com.lifeos.security.approval;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.FactorGrantedAuthority;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/** Tier 3 and approval decisions need a passkey assertion from the last few minutes. */
public final class PasskeyFreshness {

    public static final Duration MAX_AGE = Duration.ofMinutes(5);

    private PasskeyFreshness() {
    }

    public static Instant passkeyAt(Authentication a) {
        if (a == null) return null;
        Instant latest = null;
        for (var g : a.getAuthorities()) {
            if (g instanceof FactorGrantedAuthority f && FactorGrantedAuthority.WEBAUTHN_AUTHORITY.equals(f.getAuthority())
                    && f.getIssuedAt() != null && (latest == null || f.getIssuedAt().isAfter(latest))) {
                latest = f.getIssuedAt();
            }
        }
        return latest;
    }

    public static boolean isFresh(Authentication a, Clock clock, Duration maxAge) {
        Instant at = passkeyAt(a);
        return at != null && !at.plus(maxAge).isBefore(clock.instant());
    }
}
