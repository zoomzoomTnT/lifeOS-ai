package com.lifeos.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code life.auth.*}.
 *
 * @param pepper           LIFE_KEY_PEPPER: HMAC key for API key hashes (>= 32 chars, env only).
 *                         Changing it invalidates every API key.
 * @param localOpen        `local` profile only: requests without credentials act as the owner.
 * @param writesPerMinute  per-client cap on write requests (tier 1 rate limit); 0 = off
 * @param domain           LIFE_DOMAIN: public HTTPS host; the passkey RP ID
 * @param publicUrl        LIFE_PUBLIC_URL: base for approval links (default https://{domain})
 */
@ConfigurationProperties("life.auth")
public record AuthProperties(String pepper, boolean localOpen, int writesPerMinute, String domain,
                             String publicUrl) {

    public AuthProperties {
        if (writesPerMinute < 0) writesPerMinute = 0;
    }

    public boolean hasDomain() {
        return domain != null && !domain.isBlank();
    }

    /** Base URL for links we hand out (approval pages, one-time login). No trailing slash. */
    public String baseUrl() {
        if (publicUrl != null && !publicUrl.isBlank()) return publicUrl.replaceAll("/+$", "");
        if (hasDomain()) return "https://" + domain.trim();
        return "http://localhost:8787";
    }
}
