package com.lifeos.ops;

import com.lifeos.security.PrincipalAttributeFilter;

import com.lifeos.security.AuditContext;
import com.lifeos.security.CurrentActor;
import com.lifeos.security.LifePrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * One http_requests row per request, including the ones Spring Security refuses (401/403/429/400):
 * runs before the security filter chain. The caller comes from {@link PrincipalAttributeFilter}'s
 * request attribute (the security context is already cleared when the chain returns).
 */
@Component
@org.springframework.core.annotation.Order(org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties.DEFAULT_FILTER_ORDER - 10)
@RequiredArgsConstructor
@Slf4j
public class HttpAccessFilter extends OncePerRequestFilter {

    private static final int BODY_CAP = 4096;

    private final OpsService opsService;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        String method = request.getMethod();
        if ("GET".equals(method) && (path.startsWith("/actuator") || path.startsWith("/ops")
                || path.startsWith("/swagger-ui") || path.startsWith("/v3/api-docs"))) {
            return true;
        }
        if ("GET".equals(method) && path.startsWith("/api/ops")) {
            return true;
        }
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String corr = request.getHeader("X-Request-Id");
        if (corr == null || corr.isBlank()) corr = UUID.randomUUID().toString();
        response.setHeader("X-Request-Id", corr);

        ContentCachingRequestWrapper req = request instanceof ContentCachingRequestWrapper r
                ? r : new ContentCachingRequestWrapper(request, BODY_CAP);
        ContentCachingResponseWrapper res = response instanceof ContentCachingResponseWrapper r
                ? r : new ContentCachingResponseWrapper(response);

        long t0 = System.currentTimeMillis();
        String error = null;
        try {
            req.setAttribute(AuditContext.REQUEST_ID_ATTR, corr);
            filterChain.doFilter(req, res);
        } catch (Exception e) {
            error = e.getClass().getSimpleName() + ": " + e.getMessage();
            throw e;
        } finally {
            long ms = System.currentTimeMillis() - t0;
            try {
                String excerpt = excerpt(req);
                Integer reqBytes = req.getContentLength() >= 0 ? req.getContentLength() : req.getContentAsByteArray().length;
                LifePrincipal actor = CurrentActor.get().orElseGet(() ->
                        req.getAttribute(PrincipalAttributeFilter.ATTR) instanceof LifePrincipal p ? p : null);
                opsService.recordHttp(
                        corr,
                        actor == null ? null : actor.personId(),
                        actor == null ? null : actor.clientId(),
                        actor == null ? null : actor.credentialId(),
                        req.getMethod(),
                        req.getRequestURI(),
                        req.getQueryString(),
                        res.getStatus(),
                        ms,
                        reqBytes,
                        res.getContentSize(),
                        excerpt,
                        error
                );
            } catch (Exception e) {
                log.warn("http access log failed: {}", e.getMessage());
            }
            res.copyBodyToResponse();
        }
    }

    private static String excerpt(ContentCachingRequestWrapper req) {
        String ct = req.getContentType();
        if (ct == null || !ct.contains("json")) return null;
        byte[] buf = req.getContentAsByteArray();
        if (buf.length == 0) return null;
        int n = Math.min(buf.length, BODY_CAP);
        String s = new String(buf, 0, n, StandardCharsets.UTF_8);
        return s.replaceAll("(?i)(\"(password|token|authorization|secret)\"\\s*:\\s*\")[^\"]*", "$1***");
    }
}
