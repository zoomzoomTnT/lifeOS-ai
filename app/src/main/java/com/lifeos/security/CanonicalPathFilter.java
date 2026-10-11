package com.lifeos.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * First filter of the API chain: the path must already be in canonical form, so every later
 * decision (scope rules, risk tier, approval fingerprint, controller routing) sees one and the same
 * path. Anything a decoder or normalizer would change is refused with 400 {@code non_canonical_path}
 * instead of being "helpfully" interpreted: percent-encoding ({@code /api/ops/pric%65s}), '+',
 * empty segments ({@code //}), dot segments, matrix parameters ({@code ;x}), backslashes, a trailing
 * slash, non-ASCII. Every lifeOS API path is plain ASCII words and numeric ids, so no client needs any
 * of these. (Spring's StrictHttpFirewall already rejects some of them, before this filter.)
 */
public class CanonicalPathFilter extends OncePerRequestFilter {

    /** Segments of letters, digits, '_', '-', '.' (actuator metric names); no empty segment. */
    static final Pattern CANONICAL = Pattern.compile("^(/[A-Za-z0-9_.\\-]+)+$");

    private final SecurityEventLog events;

    public CanonicalPathFilter(SecurityEventLog events) {
        this.events = events;
    }

    /** Null if canonical, else why not. */
    public static String problem(String rawPath) {
        if (rawPath == null || rawPath.isEmpty()) return "empty path";
        if (!CANONICAL.matcher(rawPath).matches()) {
            if (rawPath.indexOf('%') >= 0) return "percent-encoding";
            if (rawPath.indexOf(';') >= 0) return "matrix parameter";
            if (rawPath.contains("//")) return "empty segment";
            if (rawPath.endsWith("/")) return "trailing slash";
            return "character outside [A-Za-z0-9_.-/]";
        }
        for (String seg : rawPath.split("/")) {
            if (seg.equals(".") || seg.equals("..")) return "dot segment";
        }
        return null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String raw = req.getRequestURI().substring(req.getContextPath().length());
        String why = problem(raw);
        if (why != null) {
            events.record(SecurityEventLog.Event.of("non_canonical_path").request(req).outcome(why));
            Map<String, Object> b = ApiErrors.body("non_canonical_path",
                    "request path must be canonical (" + why + "): send it without encoding, '+', '//', '.', ';' or a trailing '/'");
            ApiErrors.write(res, 400, b);
            return;
        }
        chain.doFilter(req, res);
    }
}
