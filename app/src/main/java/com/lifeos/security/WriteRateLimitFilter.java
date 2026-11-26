package com.lifeos.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Tier-1 control: at most N write requests per minute per client (fixed window, in memory). */
public class WriteRateLimitFilter extends OncePerRequestFilter {

    private static final Set<String> SAFE = Set.of("GET", "HEAD", "OPTIONS");

    private final int perMinute;
    private final Clock clock;
    private final Map<String, AtomicInteger> counts = new ConcurrentHashMap<>();
    private volatile long minute = -1;

    public WriteRateLimitFilter(int perMinute, Clock clock) {
        this.perMinute = perMinute;
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (perMinute > 0 && !SAFE.contains(req.getMethod()) && auth != null
                && auth.getPrincipal() instanceof LifePrincipal p && p.kind() != ClientKind.LOCAL_DEV) {
            long now = clock.millis();
            long m = now / 60_000;
            if (m != minute) {
                counts.clear();
                minute = m;
            }
            if (counts.computeIfAbsent(p.clientId(), k -> new AtomicInteger()).incrementAndGet() > perMinute) {
                res.setHeader("Retry-After", String.valueOf(60 - (now / 1000) % 60));
                ApiErrors.write(res, 429, ApiErrors.body("rate_limited",
                        "more than " + perMinute + " write requests this minute for client " + p.clientId()));
                return;
            }
        }
        chain.doFilter(req, res);
    }
}
