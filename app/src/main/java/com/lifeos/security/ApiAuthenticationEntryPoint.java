package com.lifeos.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;

import java.io.IOException;
import java.util.Map;

/**
 * 401 for the API chain: no credential (entry point) or a refused one (failure handler, which also
 * records auth_failed with the reason and key_id; the key itself never leaves the request).
 */
public class ApiAuthenticationEntryPoint implements AuthenticationEntryPoint, AuthenticationFailureHandler {

    private final SecurityEventLog events;

    public ApiAuthenticationEntryPoint(SecurityEventLog events) {
        this.events = events;
    }

    @Override
    public void commence(HttpServletRequest req, HttpServletResponse res, AuthenticationException e) throws IOException {
        res.setHeader("WWW-Authenticate", ApiErrors.challenge(null, null, null));
        ApiErrors.write(res, 401, ApiErrors.body("unauthorized",
                "Authorization: Bearer <lifeOS API key> required (see docs/auth.md)"));
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest req, HttpServletResponse res, AuthenticationException e)
            throws IOException {
        String reason = e instanceof ApiKeyAuthenticationException k ? k.reason() : "invalid";
        String keyId = e instanceof ApiKeyAuthenticationException k ? k.keyId() : null;
        events.record(SecurityEventLog.Event.of("auth_failed").credential(keyId).request(req).outcome(reason));
        res.setHeader("WWW-Authenticate", ApiErrors.challenge("invalid_token", "invalid, expired or revoked API key", null));
        Map<String, Object> body = ApiErrors.body("invalid_token", "API key is invalid, expired or revoked");
        ApiErrors.write(res, 401, body);
    }
}
