package com.lifeos.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;
import java.util.Map;

/**
 * 403 {@code insufficient_scope} naming the missing scope (RFC 6750 §3.1, the MCP step-up shape), or
 * {@code admin_only} when only the admin client can ever get it (ops:admin, /api/admin/**).
 */
public class ApiAccessDeniedHandler implements AccessDeniedHandler {

    private final SecurityEventLog events;

    public ApiAccessDeniedHandler(SecurityEventLog events) {
        this.events = events;
    }

    @Override
    public void handle(HttpServletRequest req, HttpServletResponse res, AccessDeniedException e) throws IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        LifePrincipal p = auth != null && auth.getPrincipal() instanceof LifePrincipal lp ? lp : null;
        String scope = ScopeRules.match(req).map(ScopeRules.Rule::scope).orElse(null);
        events.record(SecurityEventLog.Event.of("scope_denied").principal(p).request(req)
                .outcome(scope == null ? "no_rule" : "missing " + scope));
        Map<String, Object> body;
        if (scope == null) {
            body = ApiErrors.body("forbidden", "no access rule for this endpoint");
        } else if (Scopes.OPS_ADMIN.equals(scope) && (p == null || p.kind() != ClientKind.ADMIN)) {
            body = ApiErrors.body("admin_only", "only the admin client (lifeOS Admin bot) may call this; "
                    + "other credentials never get " + Scopes.OPS_ADMIN);
            body.put("required_scope", scope);
        } else {
            body = ApiErrors.body("insufficient_scope", "this credential lacks scope " + scope);
            body.put("required_scope", scope);
        }
        res.setHeader("WWW-Authenticate", ApiErrors.challenge("insufficient_scope", null, scope));
        ApiErrors.write(res, 403, body);
    }
}
