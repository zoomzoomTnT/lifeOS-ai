package com.lifeos.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

import java.util.List;
import java.util.Optional;

import static com.lifeos.security.Scopes.*;

/**
 * Path → scope for the API chain, first match wins (scope table in docs/auth.md). Anything under
 * /api/** or /actuator/** without a rule is denied, so a new endpoint is closed until it gets one.
 * Controllers repeat the scope with @PreAuthorize (defence in depth; a test checks they agree).
 * A null scope means public (the controller does its own check, e.g. the OpenClaw hook token).
 */
public final class ScopeRules {
    private ScopeRules() {}

    public record Rule(HttpMethod method, String pattern, String scope, RequestMatcher matcher) {
        static Rule of(HttpMethod method, String pattern, String scope) {
            var b = PathPatternRequestMatcher.withDefaults();
            return new Rule(method, pattern, scope, method == null ? b.matcher(pattern) : b.matcher(method, pattern));
        }

        public boolean isPublic() {
            return scope == null;
        }
    }

    private static final HttpMethod GET = HttpMethod.GET, POST = HttpMethod.POST, PUT = HttpMethod.PUT;

    public static final List<Rule> RULES = List.of(
            // public
            Rule.of(GET, "/actuator/health", null),
            Rule.of(GET, "/actuator/health/**", null),
            Rule.of(POST, "/api/ops/webhook/ping", null),          // OPENCLAW_HOOK_TOKEN, checked in the controller
            // actuator: owner / ops:admin only
            Rule.of(null, "/actuator/**", OPS_ADMIN),
            // finance
            Rule.of(GET, "/api/receipts", FINANCE_READ),
            Rule.of(GET, "/api/receipts/**", FINANCE_READ),
            Rule.of(POST, "/api/receipts/lookup", FINANCE_READ),   // read-only dedupe check
            Rule.of(POST, "/api/receipts/**", FINANCE_WRITE),
            // fridge
            Rule.of(GET, "/api/fridge", FRIDGE_READ),
            Rule.of(GET, "/api/fridge/**", FRIDGE_READ),
            Rule.of(null, "/api/fridge", FRIDGE_WRITE),
            Rule.of(null, "/api/fridge/**", FRIDGE_WRITE),
            // memos
            Rule.of(GET, "/api/memos/**", MEMOS_READ),
            Rule.of(null, "/api/memos", MEMOS_WRITE),
            Rule.of(null, "/api/memos/**", MEMOS_WRITE),
            // stocks
            Rule.of(GET, "/api/holdings", STOCKS_READ),
            Rule.of(GET, "/api/holdings/**", STOCKS_READ),
            Rule.of(null, "/api/holdings", STOCKS_WRITE),
            Rule.of(null, "/api/holdings/**", STOCKS_WRITE),
            // ops
            Rule.of(POST, "/api/ops/purge", OPS_ADMIN),            // tier 3: never an agent
            Rule.of(GET, "/api/ops/**", OPS_READ),
            Rule.of(POST, "/api/ops/ai", OPS_WRITE),
            Rule.of(POST, "/api/ops/logs/ingest", OPS_WRITE),
            Rule.of(POST, "/api/ops/proactive/run", OPS_WRITE),
            Rule.of(PUT, "/api/ops/prices", OPS_WRITE)             // tier 2: approval required for agents
    );

    /** The rule that decides this request, if any (no rule = denied). */
    public static Optional<Rule> match(HttpServletRequest request) {
        for (Rule r : RULES) if (r.matcher().matches(request)) return Optional.of(r);
        return Optional.empty();
    }
}
