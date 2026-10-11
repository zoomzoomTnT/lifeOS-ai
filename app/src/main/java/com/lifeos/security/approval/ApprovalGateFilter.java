package com.lifeos.security.approval;

import com.lifeos.security.ApiErrors;
import com.lifeos.security.AuditContext;
import com.lifeos.security.AuthProperties;
import com.lifeos.security.ClientKind;
import com.lifeos.security.CurrentActor;
import com.lifeos.security.LifePrincipal;
import com.lifeos.security.SecurityEventLog;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Human-in-the-loop gate on the API chain, after scope authorization (doc §6):
 * <ul>
 *   <li>tier 3 (purge, actuator writes): owner session with a passkey from the last 5 minutes only;
 *       any API key, agent or not, gets {@code 403 owner_only}.</li>
 *   <li>tier 2 (big receipt confirms, price changes, deletes): an API-key caller gets
 *       {@code 403 approval_required} + a link; after the owner approves with a passkey it repeats
 *       the identical request with {@code Life-Approval: <id>}, which is consumed (single use).</li>
 * </ul>
 * The server decides the tier from its own data; the agent's words play no part.
 */
public class ApprovalGateFilter extends OncePerRequestFilter {

    private final RiskPolicy policy;
    private final ApprovalService approvals;
    private final SecurityEventLog events;
    private final AuthProperties props;
    private final Clock clock;
    private final java.time.Duration passkeyMaxAge;

    public ApprovalGateFilter(RiskPolicy policy, ApprovalService approvals, SecurityEventLog events,
                              AuthProperties props, Clock clock, java.time.Duration passkeyMaxAge) {
        this.passkeyMaxAge = passkeyMaxAge;
        this.policy = policy;
        this.approvals = approvals;
        this.events = events;
        this.props = props;
        this.clock = clock;
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
        if (r.tier() >= RiskAssessment.ADMIN) {
            if (p.kind() != ClientKind.OWNER_SESSION) {
                events.record(SecurityEventLog.Event.of("owner_only_denied").principal(p).request(req).outcome(r.action()));
                Map<String, Object> b = ApiErrors.body("owner_only", r.headline()
                        + ": admin action (tier 3). Never allowed with an API key; the owner does it signed in with a passkey.");
                b.put("action", r.action());
                ApiErrors.write(res, 403, b);
                return;
            }
            if (!PasskeyFreshness.isFresh(SecurityContextHolder.getContext().getAuthentication(), clock, passkeyMaxAge)) {
                Map<String, Object> b = ApiErrors.body("passkey_reauth_required",
                        "confirm it's you: sign in with your passkey again (admin actions need one from the last 5 minutes)");
                b.put("login_url", props.baseUrl() + "/login");
                ApiErrors.write(res, 403, b);
                return;
            }
            chain.doFilter(wrapped, res);
            return;
        }
        if (!p.kind().needsApproval()) { // the owner, present in the browser
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
                    case PENDING -> "not approved yet: the owner has to open the link and approve it";
                    case DENIED -> "the owner denied this request";
                    case EXPIRED -> "approval expired (10 minutes); request a new one";
                    case ALREADY_USED -> "approval already used (single use); request a new one";
                });
                b.put("reason", v.rejection().name().toLowerCase());
                if (v.rejection() == ApprovalService.Rejection.PENDING) {
                    b.put("approve_url", props.baseUrl() + "/approve/" + v.approval().publicId());
                }
                ApiErrors.write(res, 403, b);
                return;
            }
            req.setAttribute(AuditContext.APPROVAL_ID_ATTR, v.approval().id());
            chain.doFilter(wrapped, res);
            return;
        }
        ApprovalService.Approval a = approvals.request(p, r, req.getMethod(), path, hash);
        Map<String, Object> b = new LinkedHashMap<>(ApiErrors.body("approval_required",
                r.headline() + ". Send the owner the approve_url; once approved, repeat this exact request with header "
                        + ApprovalService.HEADER + ": " + a.publicId()));
        b.put("approval_id", a.publicId());
        b.put("approve_url", props.baseUrl() + "/approve/" + a.publicId());
        b.put("expires_at", a.expiresAt().toString());
        b.put("action", r.action());
        b.put("tier", r.tier());
        ApiErrors.write(res, 403, b);
    }
}
