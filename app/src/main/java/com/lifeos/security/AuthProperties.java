package com.lifeos.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code life.auth.*}.
 *
 * @param pepper           LIFE_KEY_PEPPER: HMAC key for API key hashes (>= 32 chars, env only).
 *                         Changing it invalidates every API key except the admin key.
 * @param localOpen        `local` profile only: requests without credentials act as the owner.
 * @param writesPerMinute  per-client cap on write requests (tier 1 rate limit); 0 = off
 * @param adminKeyId       LIFE_ADMIN_KEY_ID: key id of the admin client's key (the {@code <keyId>} in
 *                         {@code lok_<keyId>_<secret>})
 * @param adminKeySha256   LIFE_ADMIN_KEY_SHA256: lowercase hex SHA-256 of that key's secret part
 */
@ConfigurationProperties("life.auth")
public record AuthProperties(String pepper, boolean localOpen, int writesPerMinute, String adminKeyId,
                             String adminKeySha256) {

    /** application-local.yml's fallback pepper. Not a secret. */
    public static final String LOCAL_DEV_PEPPER = "local-dev-pepper-not-a-secret-000000";

    public AuthProperties {
        if (writesPerMinute < 0) writesPerMinute = 0;
    }

    public boolean hasAdminKey() {
        return adminKeyId != null && !adminKeyId.isBlank();
    }

    /** Never print the pepper (the record default would, e.g. in a bind-failure message or a debug log). */
    @Override
    public String toString() {
        return "AuthProperties[pepper=" + (pepper == null || pepper.isEmpty() ? "(unset)" : "***") + ", localOpen=" + localOpen
                + ", writesPerMinute=" + writesPerMinute + ", adminKeyId=" + adminKeyId + "]";
    }
}
