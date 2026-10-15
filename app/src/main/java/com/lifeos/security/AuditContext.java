package com.lifeos.security;

import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * Which client / credential / request / approval is behind the write being recorded. Read from the
 * security context and request attributes, so repositories need no extra parameters. Scheduled
 * jobs (no request) are recorded as client 'system'.
 */
public record AuditContext(String clientId, String credentialId, String requestId, Long approvalId) {

    public static final String REQUEST_ID_ATTR = "lifeos.requestId";
    public static final String APPROVAL_ID_ATTR = "lifeos.approvalId";
    public static final String SYSTEM_CLIENT = "system";

    public static AuditContext current() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        String requestId = attrs == null ? null
                : (String) attrs.getAttribute(REQUEST_ID_ATTR, RequestAttributes.SCOPE_REQUEST);
        Long approvalId = attrs == null ? null
                : (Long) attrs.getAttribute(APPROVAL_ID_ATTR, RequestAttributes.SCOPE_REQUEST);
        return CurrentActor.get()
                .map(p -> new AuditContext(p.clientId(), p.credentialId(), requestId, approvalId))
                .orElseGet(() -> new AuditContext(attrs == null ? SYSTEM_CLIENT : null, null, requestId, approvalId));
    }
}
