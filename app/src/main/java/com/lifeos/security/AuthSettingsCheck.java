package com.lifeos.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/**
 * Fail closed before any bean exists: production refuses to start without LIFE_KEY_PEPPER, and the
 * unauthenticated local-dev mode can never be switched on outside the `local` profile.
 */
@Configuration
public class AuthSettingsCheck {

    private static final Logger log = LoggerFactory.getLogger(AuthSettingsCheck.class);

    @Bean
    static BeanFactoryPostProcessor authSettingsValidator(Environment env) {
        return bf -> validate(env.getProperty("life.auth.pepper"),
                Boolean.parseBoolean(env.getProperty("life.auth.local-open", "false")),
                env.acceptsProfiles(Profiles.of("local")), env.getProperty("life.auth.domain"));
    }

    static void validate(String pepper, boolean localOpen, boolean localProfile, String domain) {
        String problem = problem(pepper, localOpen, localProfile);
        if (problem == null) problem = AuthProperties.domainProblem(domain);
        if (problem != null) {
            log.error(problem);
            throw new IllegalStateException(problem);
        }
        if (localOpen) {
            log.warn("life.auth.local-open=true (local profile): requests WITHOUT credentials act as the owner. "
                    + "Never expose this instance.");
        }
        if (!localProfile && (domain == null || domain.isBlank())) {
            log.warn("LIFE_DOMAIN is not set: owner passkey login and approvals are unavailable (they need an HTTPS "
                    + "domain). API keys work; risky agent requests will wait for approvals nobody can give.");
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
}
