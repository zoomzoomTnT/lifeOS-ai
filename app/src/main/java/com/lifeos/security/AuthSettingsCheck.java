package com.lifeos.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/**
 * Fail closed before any bean exists: production refuses to start without LIFE_KEY_PEPPER or without a
 * well-formed admin key (LIFE_ADMIN_KEY_ID + LIFE_ADMIN_KEY_SHA256), and the unauthenticated local-dev
 * mode can never be switched on outside the `local` profile.
 */
@Configuration
public class AuthSettingsCheck {

    private static final Logger log = LoggerFactory.getLogger(AuthSettingsCheck.class);

    @Bean
    static BeanFactoryPostProcessor authSettingsValidator(Environment env) {
        return bf -> validate(env.getProperty("life.auth.pepper"),
                Boolean.parseBoolean(env.getProperty("life.auth.local-open", "false")),
                env.acceptsProfiles(Profiles.of("local")),
                env.getProperty("life.auth.admin-key-id"), env.getProperty("life.auth.admin-key-sha256"));
    }

    static void validate(String pepper, boolean localOpen, boolean localProfile, String adminKeyId, String adminKeySha256) {
        String problem = problem(pepper, localOpen, localProfile);
        if (problem == null) problem = adminKeyProblem(adminKeyId, adminKeySha256, localProfile);
        if (problem != null) {
            log.error(problem);
            throw new IllegalStateException(problem);
        }
        if (localOpen) {
            log.warn("life.auth.local-open=true (local profile): requests WITHOUT credentials act as the owner. "
                    + "Never expose this instance.");
        }
    }

    static String problem(String pepper, boolean localOpen, boolean localProfile) {
        if (localOpen && !localProfile) {
            return "life.auth.local-open=true is only allowed with the `local` profile (it disables authentication).";
        }
        if (pepper == null || pepper.isBlank()) {
            return "LIFE_KEY_PEPPER is not set: refusing to start without API authentication. Generate one with "
                    + "`openssl rand -hex 32` and add it as the GitHub secret LIFE_KEY_PEPPER (CD writes it to the "
                    + "server .env). Local development: run with SPRING_PROFILES_ACTIVE=local.";
        }
        if (pepper.length() < KeyHasher.MIN_PEPPER_LENGTH) {
            return "LIFE_KEY_PEPPER is too short (" + pepper.length() + " chars, need >= " + KeyHasher.MIN_PEPPER_LENGTH
                    + "). Generate one with `openssl rand -hex 32`.";
        }
        return null;
    }

    /** Both admin values or neither (neither only under `local`); never prints the values. */
    static String adminKeyProblem(String keyId, String sha256, boolean localProfile) {
        boolean hasId = keyId != null && !keyId.isBlank();
        boolean hasHash = sha256 != null && !sha256.isBlank();
        if (!hasId && !hasHash) {
            return localProfile ? null : "LIFE_ADMIN_KEY_ID and LIFE_ADMIN_KEY_SHA256 are not set: refusing to start "
                    + "without the admin key. See docs/auth.md (\"Admin key\") and add both as GitHub secrets.";
        }
        if (!hasId || !hasHash) {
            return "Set both LIFE_ADMIN_KEY_ID and LIFE_ADMIN_KEY_SHA256, or neither (local only).";
        }
        if (!ApiKeyFormat.isKeyId(keyId)) {
            return "LIFE_ADMIN_KEY_ID must be the 12-character key id of a lok_ key (lowercase base32).";
        }
        if (!sha256.matches("[0-9a-f]{64}")) {
            return "LIFE_ADMIN_KEY_SHA256 must be 64 lowercase hex characters: the SHA-256 of the key's secret part.";
        }
        return null;
    }
}
