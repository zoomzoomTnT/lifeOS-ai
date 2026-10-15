package com.lifeos.security;

import org.springframework.security.core.AuthenticatedPrincipal;

import java.io.Serializable;
import java.util.Set;

/**
 * Who is calling, derived only from the credential (never from a request header).
 *
 * @param personId     on whose behalf (people.id); the actor for every domain write
 * @param clientId     api_clients.client_id, or 'local-dev'
 * @param credentialId api_keys.key_id, or 'local' (a JWT jti in Phase 3)
 * @param kind         agent / service / admin ...
 * @param scopes       granted scopes, already expanded (write implies read)
 */
public record LifePrincipal(long personId, String clientId, String credentialId, ClientKind kind,
                            Set<String> scopes) implements AuthenticatedPrincipal, Serializable {

    public static final String LOCAL_DEV_CLIENT = "local-dev";

    @Override
    public String getName() {
        return clientId;
    }

    public boolean has(String scope) {
        return scopes.contains(scope);
    }
}
