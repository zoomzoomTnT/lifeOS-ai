package com.lifeos.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;

/**
 * X-Life-Handle no longer means anything: the person comes from the credential. The header is
 * ignored; when a caller still sends it, that is recorded (throttled) so old clients, or a
 * prompt-injected agent trying to act as someone else, show up in security_events.
 */
public class IdentityHeaderFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Life-Handle";
    private final SecurityEventLog events;

    public IdentityHeaderFilter(SecurityEventLog events) {
        this.events = events;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String handle = req.getHeader(HEADER);
        if (handle != null && !handle.isBlank()) {
            events.record(SecurityEventLog.Event.of("identity_header_ignored")
                    .principal(CurrentActor.get().orElse(null)).request(req)
                    .detail(Map.of("handle", handle.length() > 64 ? handle.substring(0, 64) : handle)));
        }
        chain.doFilter(req, res);
    }
}
