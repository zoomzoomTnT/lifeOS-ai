package com.lifeos.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.authentication.ott.JdbcOneTimeTokenService;
import org.springframework.security.authentication.ott.OneTimeTokenAuthentication;
import org.springframework.security.authentication.ott.OneTimeTokenService;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.config.annotation.web.configurers.DefaultLoginPageConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.ui.DefaultLoginPageGeneratingFilter;
import org.springframework.security.web.authentication.ui.DefaultResourcesFilter;
import org.springframework.security.web.webauthn.authentication.WebAuthnAuthentication;
import org.springframework.security.web.webauthn.management.JdbcPublicKeyCredentialUserEntityRepository;
import org.springframework.security.web.webauthn.management.PublicKeyCredentialUserEntityRepository;
import org.springframework.security.web.webauthn.management.UserCredentialRepository;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The owner's browser side (Phase 2): passkey login (Spring Security WebAuthn), one-time-token
 * login ONLY to register the first passkey (tokens come from {@code lifeos-admin owner-login}; the
 * web endpoint that would generate one is disabled), the approval pages and the ops page.
 * Session-based with CSRF, SameSite=Strict cookie, separate from the stateless API chain.
 * Phase 3 puts the authorization server's /oauth2/authorize consent behind this same login.
 */
@Configuration
public class OwnerWebConfig {

    private static final Logger log = LoggerFactory.getLogger(OwnerWebConfig.class);

    /** The one human account. No password exists: passkeys (or a CLI-minted one-time token). */
    @Bean
    UserDetailsService ownerUserDetailsService() {
        var owner = User.withUsername(OwnerAccess.OWNER_USERNAME)
                .password("{noop}" + UUID.randomUUID()) // never usable: there is no password login
                .authorities(OwnerAccess.ROLE_OWNER)
                .build();
        return username -> {
            if (!OwnerAccess.OWNER_USERNAME.equals(username)) throw new UsernameNotFoundException("unknown user");
            return User.withUserDetails(owner).build();
        };
    }

    @Bean
    PublicKeyCredentialUserEntityRepository publicKeyCredentialUserEntityRepository(JdbcTemplate jdbc) {
        return new JdbcPublicKeyCredentialUserEntityRepository(jdbc);
    }

    @Bean
    UserCredentialRepository userCredentialRepository(JdbcTemplate jdbc, SecurityEventLog events) {
        return new AuditedUserCredentialRepository(
                new org.springframework.security.web.webauthn.management.JdbcUserCredentialRepository(jdbc), events);
    }

    @Bean
    OneTimeTokenService oneTimeTokenService(JdbcTemplate jdbc) {
        return new JdbcOneTimeTokenService(jdbc);
    }

    static String rpId(AuthProperties props) {
        return props.hasDomain() ? props.domain().trim() : "localhost";
    }

    static Set<String> origins(AuthProperties props) {
        Set<String> o = new LinkedHashSet<>();
        o.add(props.baseUrl());
        if (!props.hasDomain()) o.add("http://localhost:8787");
        return o;
    }

    @Bean
    @Order(2)
    SecurityFilterChain ownerWebChain(HttpSecurity http, AuthProperties props, OneTimeTokenService ott)
            throws Exception {
        http.securityMatcher("/login", "/login/**", "/logout", "/webauthn/**", "/default-ui.css",
                        "/approve/**", "/admin/**", "/ops", "/ops/**")
                .webAuthn(w -> w.rpName("lifeOS").rpId(rpId(props)).allowedOrigins(origins(props)))
                .oneTimeTokenLogin(o -> o
                        .tokenService(ott)
                        // Tokens are minted only by `lifeos-admin owner-login` on the server. Returning
                        // null here turns the web "send me a token" endpoint into a no-op (then denied).
                        .generateRequestResolver(request -> null)
                        .tokenGenerationSuccessHandler((req, res, token) -> res.sendError(404))
                        .authenticationSuccessHandler((req, res, auth) -> res.sendRedirect("/webauthn/register")))
                .logout(l -> l.logoutSuccessUrl("/login?logout"))
                .formLogin(f -> f.disable())
                .httpBasic(b -> b.disable())
                // passkey success → owner home (or the page that sent us to /login, e.g. an approval)
                .exceptionHandling(e -> e.authenticationEntryPoint(new LoginUrlAuthenticationEntryPoint("/login")))
                .authorizeHttpRequests(a -> {
                    a.requestMatchers("/login", "/login/**", "/default-ui.css", "/webauthn/authenticate/**").permitAll();
                    // An OTT session may only manage passkeys; everything else needs a passkey sign-in.
                    a.requestMatchers("/webauthn/register", "/webauthn/register/**").hasAuthority(OwnerAccess.ROLE_OWNER);
                    if (props.localOpen()) a.requestMatchers("/ops", "/ops/**").permitAll();
                    a.anyRequest().hasAllAuthorities(OwnerAccess.ROLE_OWNER, FactorGrantedAuthority.WEBAUTHN_AUTHORITY);
                });
        // Our own /login (OwnerPages) replaces the generated one, which would offer a web OTT request form.
        http.removeConfigurer(DefaultLoginPageConfigurer.class);
        http.addFilterBefore(DefaultResourcesFilter.css(), DefaultLoginPageGeneratingFilter.class);
        http.addFilterBefore(DefaultResourcesFilter.webauthn(), DefaultLoginPageGeneratingFilter.class);
        if (!props.hasDomain()) {
            log.info("Passkeys: RP ID 'localhost' (no LIFE_DOMAIN); they only work on http://localhost");
        }
        return http.build();
    }

    /** Owner sign-ins (passkey / one-time token) and failures go to security_events. */
    @Bean
    OwnerLoginAudit ownerLoginAudit(SecurityEventLog events) {
        return new OwnerLoginAudit(events);
    }

    public static class OwnerLoginAudit {
        private final SecurityEventLog events;

        OwnerLoginAudit(SecurityEventLog events) {
            this.events = events;
        }

        @EventListener
        public void success(AuthenticationSuccessEvent e) {
            String via = e.getAuthentication() instanceof WebAuthnAuthentication ? "passkey"
                    : e.getAuthentication() instanceof OneTimeTokenAuthentication ? "ott" : null;
            if (via != null) {
                events.record(SecurityEventLog.Event.of("owner_login").credential(via).outcome("success")
                        .client(LifePrincipal.OWNER_WEB_CLIENT));
            }
        }

        @EventListener
        public void failure(AbstractAuthenticationFailureEvent e) {
            String type = e.getAuthentication() == null ? "?" : e.getAuthentication().getClass().getSimpleName();
            if (type.contains("WebAuthn") || type.contains("OneTimeToken")) {
                events.record(SecurityEventLog.Event.of("auth_failed").client(LifePrincipal.OWNER_WEB_CLIENT)
                        .outcome(e.getException().getClass().getSimpleName()).detail(Map.of("via", type)));
            }
        }
    }
}
