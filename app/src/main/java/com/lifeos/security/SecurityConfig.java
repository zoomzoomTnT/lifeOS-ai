package com.lifeos.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.AuthenticationFilter;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

import org.springframework.security.web.authentication.session.NullAuthenticatedSessionStrategy;

import java.time.Clock;
import java.util.Set;

/**
 * Spring Security filter chains (docs/auth.md):
 * <ol>
 *   <li>{@code apiChain}: /api/**, /actuator/** — stateless, Bearer API keys, path → scope rules
 *       ({@link ScopeRules}), 401/403 JSON with RFC 6750 challenges. Phase 3 adds
 *       {@code oauth2ResourceServer(jwt)} here; Phase 4 adds /mcp/** to the matcher.</li>
 *   <li>{@code defaultChain}: docs and static pages public, everything else denied.</li>
 * </ol>
 * Method security (@PreAuthorize on controllers) repeats the scope checks.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties(AuthProperties.class)
public class SecurityConfig {

    @Bean
    Clock authClock() {
        return Clock.systemUTC();
    }

    @Bean
    KeyHasher keyHasher(AuthProperties props) {
        return new KeyHasher(props.pepper());
    }

    @Bean
    ApiKeyStore apiKeyStore(JdbcTemplate jdbc) {
        return new ApiKeyStore(jdbc);
    }

    @Bean
    SecurityEventLog securityEventLog(JdbcTemplate jdbc, Clock authClock) {
        return new SecurityEventLog(jdbc, authClock);
    }

    @Bean
    ApiKeyService apiKeyService(ApiKeyStore store, KeyHasher hasher, SecurityEventLog events, Clock authClock) {
        return new ApiKeyService(store, hasher, events, authClock);
    }

    @Bean
    @Order(1)
    SecurityFilterChain apiChain(HttpSecurity http, SecurityEventLog events, KeyHasher hasher,
                                 AuthProperties props, ApiKeyStore store, Clock authClock) throws Exception {
        // Not a bean: an AuthenticationProvider bean would become the global AuthenticationManager and
        // leak into the owner chain. Phase 3 adds a JwtAuthenticationProvider next to it here.
        ApiKeyAuthenticationProvider provider = new ApiKeyAuthenticationProvider(store, hasher, events, authClock);
        ApiAuthenticationEntryPoint entryPoint = new ApiAuthenticationEntryPoint(events);
        // Not a @Bean on purpose: Boot would also register it as a global servlet filter.
        AuthenticationFilter apiKeyFilter = new AuthenticationFilter(new ProviderManager(provider),
                new BearerApiKeyConverter());
        apiKeyFilter.setSuccessHandler((req, res, auth) -> { }); // continue the chain
        apiKeyFilter.setFailureHandler(entryPoint);
        apiKeyFilter.setSecurityContextRepository(new RequestAttributeSecurityContextRepository());

        http.securityMatcher("/api/**", "/actuator/**")
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Bearer requests carry no ambient credential, so CSRF only matters for the owner's
                // session cookie (OwnerSessionBridgeFilter): unsafe methods on that path need the token.
                .csrf(c -> c.requireCsrfProtectionMatcher(req -> OwnerSessionBridgeFilter.usesSession(req)
                                && !Set.of("GET", "HEAD", "OPTIONS", "TRACE").contains(req.getMethod()))
                        // every request "authenticates" here; that must not rotate the owner's session token
                        .sessionAuthenticationStrategy(new NullAuthenticatedSessionStrategy()))
                .httpBasic(b -> b.disable())
                .formLogin(f -> f.disable())
                .logout(l -> l.disable())
                .requestCache(r -> r.disable())
                .exceptionHandling(e -> e.authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(new ApiAccessDeniedHandler(events)))
                .addFilterBefore(apiKeyFilter, AnonymousAuthenticationFilter.class)
                // same slot, kept in insertion order: API key → owner session → (local dev)
                .addFilterBefore(new OwnerSessionBridgeFilter(store::ownerPersonId), AnonymousAuthenticationFilter.class)
                .addFilterAfter(new WriteRateLimitFilter(props.writesPerMinute(), authClock), AuthorizationFilter.class)
                .addFilterAfter(new IdentityHeaderFilter(events), WriteRateLimitFilter.class)
                .authorizeHttpRequests(a -> {
                    for (ScopeRules.Rule r : ScopeRules.RULES) {
                        if (r.isPublic()) a.requestMatchers(r.matcher()).permitAll();
                        else a.requestMatchers(r.matcher()).hasAuthority(Scopes.authority(r.scope()));
                    }
                    a.anyRequest().denyAll();
                });
        if (props.localOpen()) {
            http.addFilterBefore(new LocalDevAuthFilter(store::ownerPersonId), AnonymousAuthenticationFilter.class);
        }
        return http.build();
    }

    @Bean
    @Order(100)
    SecurityFilterChain defaultChain(HttpSecurity http) throws Exception {
        http.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(c -> c.disable())
                .httpBasic(b -> b.disable())
                .formLogin(f -> f.disable())
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**",
                                "/favicon.ico", "/error").permitAll()
                        .anyRequest().denyAll());
        return http.build();
    }
}
