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

    /** application-local.yml's fallback pepper (also used by lifeos-admin under `local`). Not a secret. */
    public static final String LOCAL_DEV_PEPPER = "local-dev-pepper-not-a-secret-000000";

    public AuthProperties {
        if (writesPerMinute < 0) writesPerMinute = 0;
    }

    /** Never print the pepper (the record default would, e.g. in a bind-failure message or a debug log). */
    @Override
    public String toString() {
        return "AuthProperties[pepper=" + (pepper == null || pepper.isEmpty() ? "(unset)" : "***") + ", localOpen=" + localOpen
                + ", writesPerMinute=" + writesPerMinute + ", domain=" + domain + ", publicUrl=" + publicUrl + "]";
    }

    /** LIFE_DOMAIN as a host name: trimmed, lower case, no trailing dot. */
    public String host() {
        if (!hasDomain()) return null;
        String h = domain.trim().toLowerCase(java.util.Locale.ROOT);
        return h.endsWith(".") ? h.substring(0, h.length() - 1) : h;
    }

    /** Null if LIFE_DOMAIN is empty or a bare host name, else what is wrong with it. */
    public static String domainProblem(String domain) {
        if (domain == null || domain.isBlank()) return null;
        String h = domain.trim();
        if (!h.matches("(?i)[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)*\\.?")) {
            return "LIFE_DOMAIN must be a bare host name like life.example.com or 203-0-113-5.sslip.io "
                    + "(no https://, port, path or trailing slash), got '" + h + "'";
        }
        return null;
    }

    public boolean hasDomain() {
        return domain != null && !domain.isBlank();
    }

    /** Base URL for links we hand out (approval pages, one-time login). No trailing slash. */
    public String baseUrl() {
        if (publicUrl != null && !publicUrl.isBlank()) return publicUrl.replaceAll("/+$", "");
        if (hasDomain()) return "https://" + host();
        return "http://localhost:8787";
    }
}
