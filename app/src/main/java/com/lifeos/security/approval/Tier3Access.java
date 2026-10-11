package com.lifeos.security.approval;

import com.lifeos.security.ClientKind;
import com.lifeos.security.LifePrincipal;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;

/**
 * Method-security half of tier 3 ({@code @PreAuthorize("... and @tier3.freshOwner(authentication)")}):
 * the owner's own browser session with a passkey from the last few minutes. No API key qualifies,
 * not even an {@code ops:admin} service key. Repeats ApprovalGateFilter's check at the handler, so a
 * path trick that slipped past the filter still cannot reach the action.
 */
@Component("tier3")
public class Tier3Access {

    private final Clock clock;
    private final Duration maxAge;

    public Tier3Access(@Qualifier("authClock") Clock clock,
                       @Value("${life.auth.passkey-max-age:PT5M}") Duration maxAge) {
        this.clock = clock;
        this.maxAge = maxAge;
    }

    public boolean freshOwner(Authentication a) {
        return a != null && a.getPrincipal() instanceof LifePrincipal p && p.kind() == ClientKind.OWNER_SESSION
                && PasskeyFreshness.isFresh(a, clock, maxAge);
    }
}
