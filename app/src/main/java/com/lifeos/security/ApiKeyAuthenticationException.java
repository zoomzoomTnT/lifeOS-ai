package com.lifeos.security;

import org.springframework.security.authentication.BadCredentialsException;

/** Why a presented key was refused (reason goes to security_events, never the key itself). */
public class ApiKeyAuthenticationException extends BadCredentialsException {
    private final String reason;
    private final String keyId;

    public ApiKeyAuthenticationException(String reason, String keyId) {
        super("invalid API key (" + reason + ")");
        this.reason = reason;
        this.keyId = keyId;
    }

    public String reason() {
        return reason;
    }

    public String keyId() {
        return keyId;
    }
}
