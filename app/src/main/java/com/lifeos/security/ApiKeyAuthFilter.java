package com.lifeos.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * API key auth: {@code Authorization: Bearer <LIFE_API_KEY>}.
 *
 * <p>Everything is protected except a short allowlist of public paths (health probes, static ops
 * page, OpenAPI docs, and the OpenClaw webhook ping, which checks its own OPENCLAW_HOOK_TOKEN).
 * X-Life-Handle stays an identity header only; this filter is what authenticates the caller.
 *
 * <p>Fail closed: with no key configured the app refuses to start, unless the {@code local}
 * profile is active (developer laptop / CI SQLite overlay), where auth is then off with a warning.
 * A configured key is always enforced, in every profile. The key is never logged.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@Slf4j
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    public static final int MIN_KEY_LENGTH = 32;

    /** Exact public paths, and public prefixes (matched as {@code prefix} or {@code prefix/...}). */
    private static final List<String> PUBLIC_EXACT = List.of("/favicon.ico", "/api/ops/webhook/ping");
    private static final List<String> PUBLIC_PREFIXES = List.of(
            "/actuator/health", "/ops", "/swagger-ui", "/v3/api-docs");

    private static final UrlPathHelper PATHS = new UrlPathHelper();

    private final byte[] keyDigest; // null = auth disabled (local profile without key)

    public ApiKeyAuthFilter(@Value("${life.api-key:}") String apiKey, Environment env) {
        String key = apiKey == null ? "" : apiKey.trim();
        boolean local = env.acceptsProfiles(Profiles.of("local"));
        if (key.isEmpty()) {
            if (!local) {
                throw new IllegalStateException(
                        "LIFE_API_KEY is not set. The API refuses to start without an API key outside the "
                                + "'local' profile. Generate one with `openssl rand -hex 32` and set LIFE_API_KEY "
                                + "(CD: GitHub repository secret LIFE_API_KEY).");
            }
            log.warn("API auth DISABLED: no LIFE_API_KEY and the 'local' profile is active. Never expose this instance.");
            this.keyDigest = null;
            return;
        }
        if (key.length() < MIN_KEY_LENGTH && !local) {
            throw new IllegalStateException("LIFE_API_KEY is too short (" + key.length() + " chars, need >= "
                    + MIN_KEY_LENGTH + "). Generate one with `openssl rand -hex 32`.");
        }
        this.keyDigest = sha256(key);
        log.info("API auth enabled: Authorization: Bearer <LIFE_API_KEY> required on /api/** and /actuator/** "
                + "(public: /actuator/health, /ops, /swagger-ui, /v3/api-docs, /api/ops/webhook/ping)");
    }

    public boolean enabled() {
        return keyDigest != null;
    }

    static boolean isPublic(String path) {
        if (PUBLIC_EXACT.contains(path)) return true;
        for (String p : PUBLIC_PREFIXES) {
            if (path.equals(p) || path.startsWith(p + "/")) return true;
        }
        return false;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (keyDigest == null) return true;
        // Public only if BOTH views of the path agree: the decoded request URI (';' params stripped,
        // what Spring MVC routes on) and the container-normalized servlet path ('..' resolved).
        // So /actuator/health/../db or /actuator/health;x/../db cannot reach a protected endpoint.
        String routed = PATHS.getPathWithinApplication(request);
        String normalized = request.getServletPath() + (request.getPathInfo() == null ? "" : request.getPathInfo());
        return isPublic(routed) && isPublic(normalized);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String presented = bearer(request.getHeader("Authorization"));
        if (presented == null) {
            reject(request, response, null, "Authorization: Bearer <LIFE_API_KEY> required");
            return;
        }
        if (!MessageDigest.isEqual(keyDigest, sha256(presented))) {
            reject(request, response, "invalid_token", "invalid API key");
            return;
        }
        chain.doFilter(request, response);
    }

    static String bearer(String header) {
        if (header == null) return null;
        String v = header.trim();
        if (v.length() < 7 || !v.regionMatches(true, 0, "Bearer ", 0, 7)) return null;
        String tok = v.substring(7).trim();
        return tok.isEmpty() ? null : tok;
    }

    private static void reject(HttpServletRequest req, HttpServletResponse res, String error, String message)
            throws IOException {
        log.debug("401 {} {} from {} ({})", req.getMethod(), req.getRequestURI(), req.getRemoteAddr(),
                error == null ? "no bearer" : error);
        res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        res.setHeader("WWW-Authenticate", error == null
                ? "Bearer realm=\"lifeos\""
                : "Bearer realm=\"lifeos\", error=\"" + error + "\"");
        res.setHeader("Cache-Control", "no-store");
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setCharacterEncoding(StandardCharsets.UTF_8.name());
        res.getWriter().write("{\"error\":\"unauthorized\",\"message\":\"" + message + "\"}");
    }

    /** Hash both sides first so isEqual compares fixed-length values (no length leak). */
    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
