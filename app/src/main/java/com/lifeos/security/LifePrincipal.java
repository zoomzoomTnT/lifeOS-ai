package com.lifeos.security;

import org.springframework.security.core.AuthenticatedPrincipal;

import java.io.Serializable;
import java.util.Set;

/**
 * Who is calling, derived only from the credential (never from a request header).
 *
 * @param personId     on whose behalf (people.id); the actor for every domain write
 * @param clientId     api_clients.client_id, or 'owner-web' / 'local-dev' for sessions
 * @param credentialId api_keys.key_id, 'passkey', 'ott' or 'local' (a JWT jti in Phase 3)
 * @param kind         agent / service / owner session ...
 * @param scopes       granted scopes, already expanded (write implies read)
 */
public record LifePrincipal(long personId, String clientId, String credentialId, ClientKind kind,
                            Set<String> scopes) implements AuthenticatedPrincipal, Serializable {

    public static final String OWNER_WEB_CLIENT = "owner-web";
    public static final String LOCAL_DEV_CLIENT = "local-dev";

    @Override
    public String getName() {
        return clientId;
    }

    public boolean has(String scope) {
        return scopes.contains(scope);
    }

    public boolean isOwnerSession() {
        return kind == ClientKind.OWNER_SESSION || kind == ClientKind.LOCAL_DEV;
    }
}
