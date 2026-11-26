package com.lifeos.security.approval;

import com.lifeos.security.ApiErrors;
import com.lifeos.security.AuditContext;
import com.lifeos.security.ClientKind;
import com.lifeos.security.CurrentActor;
import com.lifeos.security.LifePrincipal;
import com.lifeos.security.SecurityEventLog;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Human-in-the-loop gate on the API chain, after scope authorization (docs/auth.md):
 * <ul>
 *   <li>tier 3 (purge, price changes, actuator writes): the admin client only; every other key gets
 *       {@code 403 admin_only}.</li>
 *   <li>tier 2 (big receipt confirms, deletes): an agent, app or service key gets
 *       {@code 403 approval_required} with the server-rendered summary. The bot asks zoomzoom in chat;
 *       on yes the admin client approves it, and the caller repeats the identical request with
 *       {@code Life-Approval: <id>} within 5 minutes, which consumes it (single use). The admin
 *       client itself passes directly.</li>
 * </ul>
 * The server decides the tier from its own data; the agent's words play no part.
 */
public class ApprovalGateFilter extends OncePerRequestFilter {

    private final RiskPolicy policy;
    private final ApprovalService approvals;
    private final SecurityEventLog events;
    private final RouteCheck routes;

    public ApprovalGateFilter(RiskPolicy policy, ApprovalService approvals, SecurityEventLog events, RouteCheck routes) {
        this.routes = routes;
        this.policy = policy;
        this.approvals = approvals;
        this.events = events;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        return !RiskPolicy.candidate(req.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        Optional<LifePrincipal> actor = CurrentActor.get();
        if (actor.isEmpty() || actor.get().kind() == ClientKind.LOCAL_DEV) {
            chain.doFilter(req, res);
            return;
        }
        LifePrincipal p = actor.get();
        CachedBodyRequest wrapped = CachedBodyRequest.wrap(req);
        if (wrapped == null) {
            ApiErrors.write(res, 413, ApiErrors.body("payload_too_large", "request body over 1 MiB"));
            return;
        }
        String path = req.getRequestURI().substring(req.getContextPath().length());
        Optional<RiskAssessment> risk;
        try {
            risk = policy.assess(req.getMethod(), path, wrapped.body);
        } catch (RiskPolicy.InvalidPathId e) {
            ApiErrors.write(res, 400, ApiErrors.body("bad_request", e.getMessage()));
            return;
        }
        if (risk.isEmpty()) {
            chain.doFilter(wrapped, res);
            return;
        }
        RiskAssessment r = risk.get();
        // Only gate requests a handler would actually serve (no approval row for a nonexistent action).
        if (r.tier() < RiskAssessment.ADMIN && routes != null) {
            int st = routes.status(req);
            if (st == 404) {
                ApiErrors.write(res, 404, ApiErrors.body("not_found", "no such endpoint: " + req.getMethod() + " " + path));
                return;
            }
            if (st == 405) {
                ApiErrors.write(res, 405, ApiErrors.body("method_not_allowed", req.getMethod() + " is not supported on " + path));
                return;
            }
        }
        if (r.tier() >= RiskAssessment.ADMIN) {
            if (p.kind() != ClientKind.ADMIN) {
                events.record(SecurityEventLog.Event.of("admin_only_denied").principal(p).request(req).outcome(r.action()));
                Map<String, Object> b = ApiErrors.body("admin_only", r.headline()
                        + ": admin action (tier 3), only the admin client may do it (after zoomzoom says yes in chat)");
                b.put("action", r.action());
                ApiErrors.write(res, 403, b);
                return;
            }
            chain.doFilter(wrapped, res);
            return;
        }
        if (!p.kind().needsApproval()) { // the admin client
            chain.doFilter(wrapped, res);
            return;
        }
        String hash = RequestFingerprint.of(req.getMethod(), path, req.getQueryString(), p.clientId(), wrapped.body);
        String presented = req.getHeader(ApprovalService.HEADER);
        if (presented != null && !presented.isBlank()) {
            ApprovalService.Verdict v = approvals.consume(presented.trim(), p, hash);
            if (!v.ok()) {
                Map<String, Object> b = ApiErrors.body("approval_invalid", switch (v.rejection()) {
                    case NOT_FOUND -> "no such approval";
                    case OTHER_CLIENT -> "this approval belongs to another client";
                    case REQUEST_MISMATCH -> "this approval was granted for a different request (method, path or body)";
                    case PENDING -> "not approved yet: ask zoomzoom in chat; the admin client approves it on yes";
                    case DENIED -> "this request was denied";
                    case EXPIRED -> "approval expired (5 minutes); request a new one";
                    case ALREADY_USED -> "approval already used (single use); request a new one";
                });
                b.put("reason", v.rejection().name().toLowerCase());
                ApiErrors.write(res, 403, b);
                return;
            }
            req.setAttribute(AuditContext.APPROVAL_ID_ATTR, v.approval().id());
            chain.doFilter(wrapped, res);
            return;
        }
        ApprovalService.Approval a = approvals.request(p, r, req.getMethod(), path, hash);
        Map<String, Object> b = new LinkedHashMap<>(ApiErrors.body("approval_required",
                r.headline() + ". Show zoomzoom the summary in chat and ask; once the admin client has approved it, "
                        + "repeat this exact request with header " + ApprovalService.HEADER + ": " + a.publicId()));
        b.put("approval_id", a.publicId());
        b.put("summary", r.headline());
        b.put("expires_at", a.expiresAt().toString());
        b.put("action", r.action());
        b.put("tier", r.tier());
        ApiErrors.write(res, 403, b);
    }
}
