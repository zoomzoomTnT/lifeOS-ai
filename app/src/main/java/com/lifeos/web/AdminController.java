package com.lifeos.web;

import com.lifeos.security.ApiKeyService;
import com.lifeos.security.ClientKind;
import com.lifeos.security.LifePrincipal;
import com.lifeos.security.Scopes;
import com.lifeos.security.approval.ApprovalService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Admin endpoints for the lifeOS Admin bot (the admin client only: kind admin + ops:admin). It acts
 * only after zoomzoom says yes in chat. Responses are {@code Cache-Control: no-store} and request bodies
 * are not excerpted into http_requests (HttpAccessFilter). See docs/auth.md.
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('SCOPE_ops:admin') and @adminClient.check(authentication)")
public class AdminController {

    static final Set<String> APPROVAL_STATUSES = Set.of("pending", "approved", "denied", "expired", "consumed");
    static final int APPROVAL_LIST_LIMIT = 100;

    private final ApiKeyService keys;
    private final ApprovalService approvals;

    public record CreateClientRequest(
            @NotBlank @Pattern(regexp = "[a-z0-9][a-z0-9-]{0,62}", message = "lowercase letters, digits and '-'")
            String clientId,
            String displayName,
            @NotBlank @Pattern(regexp = "agent|human_app|service", message = "agent, human_app or service") String kind,
            @NotEmpty List<String> maxScopes) {}

    public record MintRequest(List<String> scopes, @PositiveOrZero Integer ttlDays, Boolean rotate) {}

    public record RevokeRequest(String reason) {}

    @GetMapping("/credentials")
    public Map<String, Object> credentials() {
        return Map.of("clients", keys.listClients(), "keys", keys.listKeys());
    }

    @PostMapping("/clients")
    public ResponseEntity<Map<String, Object>> createClient(@Valid @RequestBody CreateClientRequest r) {
        var c = keys.createClient(r.clientId(), r.displayName() == null ? r.clientId() : r.displayName(),
                ClientKind.fromDb(r.kind()), Scopes.parse(String.join(" ", r.maxScopes())));
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("client_id", c.clientId(), "kind", c.kind(),
                "max_scopes", c.maxScopes()));
    }

    /** The secret is in this response only (shown once). */
    @PostMapping("/clients/{clientId}/keys")
    public ResponseEntity<Map<String, Object>> mint(@PathVariable String clientId, @Valid @RequestBody MintRequest r) {
        ApiKeyService.MintedKey k = keys.mint(clientId, r.scopes(),
                r.ttlDays() == null ? ApiKeyService.DEFAULT_TTL_DAYS : r.ttlDays(), Boolean.TRUE.equals(r.rotate()));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("client_id", k.clientId());
        body.put("key_id", k.keyId());
        body.put("key", k.key());
        body.put("scopes", Scopes.format(k.scopes()));
        body.put("expires_at", k.expiresAt());
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @PostMapping("/keys/{keyId}/revoke")
    public ResponseEntity<Map<String, Object>> revoke(@PathVariable String keyId, @RequestBody(required = false) RevokeRequest r) {
        if (!keys.revokeNonAdmin(keyId, r == null ? null : r.reason())) {
            throw new NotFoundException("no active key " + keyId);
        }
        return ResponseEntity.ok(Map.of("key_id", keyId, "revoked", true));
    }

    @GetMapping("/approvals")
    public List<Map<String, Object>> approvals(@RequestParam(defaultValue = "pending") String status) {
        if (!APPROVAL_STATUSES.contains(status)) throw new IllegalArgumentException("status must be one of " + APPROVAL_STATUSES);
        return approvals.list(status, APPROVAL_LIST_LIMIT).stream().map(AdminController::view).toList();
    }

    @GetMapping("/approvals/{id}")
    public Map<String, Object> approval(@PathVariable String id) {
        return approvals.view(id).map(AdminController::view).orElseThrow(() -> new NotFoundException("no approval " + id));
    }

    @PostMapping("/approvals/{id}/approve")
    public ResponseEntity<Map<String, Object>> approve(@PathVariable String id, @AuthenticationPrincipal LifePrincipal admin) {
        return decided(id, approvals.approve(id, admin));
    }

    @PostMapping("/approvals/{id}/deny")
    public ResponseEntity<Map<String, Object>> deny(@PathVariable String id, @AuthenticationPrincipal LifePrincipal admin) {
        return decided(id, approvals.deny(id, admin));
    }

    private static ResponseEntity<Map<String, Object>> decided(String id, ApprovalService.DecisionResult d) {
        return switch (d.decision()) {
            case DONE -> ResponseEntity.ok(view(d.approval()));
            case NOT_FOUND -> throw new NotFoundException("no approval " + id);
            case SELF_APPROVAL -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "self_approval",
                    "message", "the requester can never approve its own request", "details", Map.of()));
            case NOT_PENDING -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "not_pending",
                    "message", "approval is " + d.approval().status() + ", not pending", "details", Map.of()));
        };
    }

    static Map<String, Object> view(ApprovalService.Approval a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("approval_id", a.publicId());
        m.put("status", a.status());
        m.put("requested_by", a.clientId());
        m.put("action", a.action());
        m.put("tier", a.tier());
        m.put("method", a.method());
        m.put("path", a.path());
        m.put("summary", a.headline());
        m.put("details", a.summary());
        m.put("created_at", a.createdAt().toString());
        m.put("expires_at", a.expiresAt().toString());
        m.put("decided_at", a.decidedAt() == null ? null : a.decidedAt().toString());
        m.put("decided_via", a.decidedVia());
        m.put("decided_by_client", a.decidedByClient());
        return m;
    }
}
