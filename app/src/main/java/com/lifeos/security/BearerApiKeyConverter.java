package com.lifeos.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationConverter;

/**
 * {@code Authorization: Bearer lok_...} → unauthenticated {@link ApiKeyAuthenticationToken}.
 * No header (or another scheme) → null: the request stays anonymous and is refused by the path
 * rules with 401. Phase 3 adds a JWT resource server next to this; a Bearer value that is not a
 * {@code lok_} key would then go there instead.
 */
public class BearerApiKeyConverter implements AuthenticationConverter {

    static final String KEY_PREFIX = "lok_";

    @Override
    public Authentication convert(HttpServletRequest request) {
        String token = bearer(request);
        if (token == null) return null;
        // A public endpoint with its own token check (POST /api/ops/webhook/ping takes
        // "Authorization: Bearer <OPENCLAW_HOOK_TOKEN>") gets its Bearer value passed through
        // untouched instead of a 401 invalid_token. A lok_ key there is still verified as usual.
        if (!token.startsWith(KEY_PREFIX) && ScopeRules.match(request).map(ScopeRules.Rule::isPublic).orElse(false)) {
            return null;
        }
        return ApiKeyAuthenticationToken.unauthenticated(token);
    }

    public static String bearer(HttpServletRequest request) {
        String h = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (h == null || h.length() < 7 || !h.regionMatches(true, 0, "Bearer ", 0, 7)) return null;
        String token = h.substring(7).trim();
        return token.isEmpty() ? null : token;
    }
}
