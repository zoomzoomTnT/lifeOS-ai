package com.lifeos.config;

import com.lifeos.db.libsql.LibSqlHttpDriver;
import com.lifeos.db.libsql.LibSqlUrl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Fails fast, before any bean (DataSource, Hikari pool) is created, when the database settings
 * cannot work — instead of an obscure Hikari/driver error later:
 * <ul>
 *   <li>no {@code spring.datasource.url} (TURSO_DATABASE_URL unset and not the {@code local} profile);</li>
 *   <li>a remote (https) libSQL URL without TURSO_AUTH_TOKEN.</li>
 * </ul>
 */
@Configuration
public class DatabaseSettingsCheck {

    private static final Logger log = LoggerFactory.getLogger(DatabaseSettingsCheck.class);

    @Bean
    static BeanFactoryPostProcessor databaseSettingsValidator(Environment env) {
        return beanFactory -> validate(env.getProperty("spring.datasource.url"),
                env.getProperty("spring.datasource.password"));
    }

    static void validate(String url, String token) {
        String problem = problem(url, token);
        if (problem != null) {
            log.error(problem);
            throw new IllegalStateException(problem);
        }
    }

    /** @return a human-readable configuration problem, or null if the settings are usable. */
    static String problem(String url, String token) {
        if (url == null || url.isBlank()) {
            return "No database configured: TURSO_DATABASE_URL is not set. Production: set TURSO_DATABASE_URL "
                    + "(e.g. jdbc:libsql:https://<db>-<org>.turso.io) and TURSO_AUTH_TOKEN. "
                    + "Local development: run with SPRING_PROFILES_ACTIVE=local (SQLite file).";
        }
        if (LibSqlUrl.accepts(url)) {
            LibSqlUrl u;
            try {
                u = LibSqlUrl.parse(url);
            } catch (IllegalArgumentException e) {
                return "Invalid TURSO_DATABASE_URL: " + e.getMessage();
            }
            boolean hasToken = (token != null && !token.isBlank())
                    || (u.authTokenFromUrl() != null && !u.authTokenFromUrl().isBlank());
            if (u.requiresAuthToken() && !hasToken) {
                return LibSqlHttpDriver.missingTokenMessage(u);
            }
        }
        return null;
    }
}
