package com.lifeos.security;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.security.web.session.DisableEncodeUrlFilter;

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

import org.springframework.security.authorization.AuthorityAuthorizationManager;
import org.springframework.security.authorization.AuthorizationManagers;

import com.lifeos.security.approval.ApprovalGateFilter;
import com.lifeos.security.approval.ApprovalService;
import com.lifeos.security.approval.RiskPolicy;

import java.time.Clock;

/**
 * Spring Security filter chains (docs/auth.md):
 * <ol>
 *   <li>{@code apiChain}: /api/**, /actuator/** — stateless, Bearer API keys only, path → scope rules
 *       ({@link ScopeRules}), 401/403 JSON with RFC 6750 challenges. Phase 3 adds
 *       {@code oauth2ResourceServer(jwt)} here; Phase 4 adds /mcp/** to the matcher.</li>
 *   <li>{@code defaultChain}: API docs public, everything else denied. No login, no session.</li>
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
    MeterBinder securityEventFailures(SecurityEventLog events) {
        return r -> Gauge.builder("lifeos.security_events.write_failures", events, SecurityEventLog::failures)
                .description("security_events rows that could not be written since start").register(r);
    }

    @Bean
    ApiKeyService apiKeyService(ApiKeyStore store, KeyHasher hasher, SecurityEventLog events, Clock authClock) {
        return new ApiKeyService(store, hasher, events, authClock);
    }

    @Bean
    AdminKeyBootstrap adminKeyBootstrap(ApiKeyService keys, AuthProperties props) {
        return new AdminKeyBootstrap(keys, props);
    }

    @Bean
    ApprovalService approvalService(JdbcTemplate jdbc, SecurityEventLog events, Clock authClock) {
        return new ApprovalService(jdbc, events, authClock);
    }

    @Bean
    @Order(1)
    SecurityFilterChain apiChain(HttpSecurity http, SecurityEventLog events, KeyHasher hasher,
                                 AuthProperties props, ApiKeyStore store, Clock authClock,
                                 RiskPolicy riskPolicy, ApprovalService approvals,
                                 @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping")
                                 org.springframework.beans.factory.ObjectProvider<org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping> mvcMappings) throws Exception {
        // Not a bean: an AuthenticationProvider bean would become the global AuthenticationManager and
        // leak into the default chain. Phase 3 adds a JwtAuthenticationProvider next to it here.
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
                // Bearer only: no cookie, no session, no ambient credential, so nothing for CSRF to protect
                .csrf(c -> c.disable())
                .httpBasic(b -> b.disable())
                .formLogin(f -> f.disable())
                .logout(l -> l.disable())
                .requestCache(r -> r.disable())
                .exceptionHandling(e -> e.authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(new ApiAccessDeniedHandler(events)))
                // before anything else decides on the path (rules, risk tier, fingerprint, routing)
                .addFilterBefore(new CanonicalPathFilter(events), DisableEncodeUrlFilter.class)
                .addFilterBefore(apiKeyFilter, AnonymousAuthenticationFilter.class)
                .addFilterAfter(new PrincipalAttributeFilter(), AnonymousAuthenticationFilter.class)
                .addFilterAfter(new WriteRateLimitFilter(props.writesPerMinute(), authClock), AuthorizationFilter.class)
                .addFilterAfter(new IdentityHeaderFilter(events), WriteRateLimitFilter.class)
                // human-in-the-loop, after scopes: tier 2 → approval_required, tier 3 → admin client only
                .addFilterAfter(new ApprovalGateFilter(riskPolicy, approvals, events,
                        new com.lifeos.security.approval.RouteCheck(mvcMappings)),
                        IdentityHeaderFilter.class)
                .authorizeHttpRequests(a -> {
                    for (ScopeRules.Rule r : ScopeRules.RULES) {
                        if (r.isPublic()) a.requestMatchers(r.matcher()).permitAll();
                        else if (r.adminClientOnly()) a.requestMatchers(r.matcher()).access(AuthorizationManagers.allOf(
                                AuthorityAuthorizationManager.hasAuthority(Scopes.authority(r.scope())),
                                AuthorityAuthorizationManager.hasAuthority(ApiKeyAuthenticationProvider.ROLE_ADMIN_CLIENT)));
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
                .logout(l -> l.disable())
                .requestCache(r -> r.disable())
                .authorizeHttpRequests(a -> a
                        // the error page only as an error dispatch, never as a URL of its own
                        .dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ERROR).permitAll()
                        .requestMatchers("/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**",
                                "/favicon.ico").permitAll()
                        .anyRequest().denyAll());
        return http.build();
    }
}
