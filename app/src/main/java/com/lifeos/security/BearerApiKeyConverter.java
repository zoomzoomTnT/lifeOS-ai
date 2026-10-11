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

    @Override
    public Authentication convert(HttpServletRequest request) {
        String token = bearer(request);
        return token == null ? null : ApiKeyAuthenticationToken.unauthenticated(token);
    }

    public static String bearer(HttpServletRequest request) {
        String h = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (h == null || h.length() < 7 || !h.regionMatches(true, 0, "Bearer ", 0, 7)) return null;
        String token = h.substring(7).trim();
        return token.isEmpty() ? null : token;
    }
}
