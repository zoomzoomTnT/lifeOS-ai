package com.lifeos.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Copies the authenticated caller into a request attribute, before authorization runs. The access
 * log (HttpAccessFilter) sits outside Spring Security, where the security context is already
 * cleared, and still attributes refused requests (403 scope_denied, 429, approval_required).
 */
public class PrincipalAttributeFilter extends OncePerRequestFilter {

    public static final String ATTR = "lifeos.principal";

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        CurrentActor.get().ifPresent(p -> req.setAttribute(ATTR, p));
        chain.doFilter(req, res);
    }
}
