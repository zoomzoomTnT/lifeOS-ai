package com.lifeos.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Owner-only key management: create clients, mint, rotate and revoke keys. Called by the admin CLI
 * ({@code lifeos-admin}), never by an HTTP endpoint an agent could reach. Secrets are returned to
 * the caller exactly once and never logged or stored.
 */
public class ApiKeyService {

    /** Old + new during a rotation. A third key requires revoking one first (or --rotate). */
    public static final int MAX_ACTIVE_KEYS_PER_CLIENT = 2;
    public static final int DEFAULT_TTL_DAYS = 90;

    private final ApiKeyStore store;
    private final KeyHasher hasher;
    private final SecurityEventLog events;
    private final Clock clock;

    public ApiKeyService(ApiKeyStore store, KeyHasher hasher, SecurityEventLog events, Clock clock) {
        this.store = store;
        this.hasher = hasher;
        this.events = events;
        this.clock = clock;
    }

    public record MintedKey(String clientId, String keyId, String key, Set<String> scopes, String expiresAt) {
        @Override
        public String toString() {
            return "MintedKey[client=" + clientId + ", keyId=" + keyId + ", key=***]";
        }
    }

    String now() {
        return Instant.now(clock).truncatedTo(ChronoUnit.SECONDS).toString();
    }

    /** Creates the client if missing; an existing client keeps its settings. */
    public ApiKeyStore.ClientRecord ensureClient(String clientId, String displayName, ClientKind kind,
                                                 Collection<String> maxScopes) {
        validateClientScopes(kind, maxScopes);
        return store.findClient(clientId).orElseGet(() -> {
            long person = store.ownerPersonId();
            store.insertClient(clientId, displayName, kind.db(), person, Scopes.format(maxScopes));
            events.record(SecurityEventLog.Event.of("client_created").client(clientId).person(person)
                    .detail(Map.of("kind", kind.db(), "max_scopes", Scopes.format(maxScopes))));
            return store.findClient(clientId).orElseThrow();
        });
    }

    static void validateClientScopes(ClientKind kind, Collection<String> scopes) {
        if (scopes.isEmpty()) throw new IllegalArgumentException("a client needs at least one scope");
        if (kind == ClientKind.AGENT && scopes.contains(Scopes.OPS_ADMIN)) {
            throw new IllegalArgumentException("ops:admin is never granted to an agent client");
        }
    }

    /**
     * Mints a key for an existing client.
     *
     * @param scopes  null = the client's max_scopes
     * @param ttlDays 0 = no expiry
     * @param rotate  if the client already has {@value #MAX_ACTIVE_KEYS_PER_CLIENT} active keys,
     *                revoke the oldest instead of failing
     */
    public MintedKey mint(String clientId, Collection<String> scopes, int ttlDays, boolean rotate) {
        ApiKeyStore.ClientRecord client = store.findClient(clientId)
                .orElseThrow(() -> new IllegalArgumentException("no such client: " + clientId));
        if (client.disabledAt() != null) throw new IllegalArgumentException("client is disabled: " + clientId);
        Set<String> ceiling = Scopes.parse(client.maxScopes());
        Set<String> granted = scopes == null ? ceiling : Scopes.parse(String.join(" ", scopes));
        if (!Scopes.within(granted, ceiling)) {
            throw new IllegalArgumentException("scopes " + Scopes.format(granted) + " exceed client max_scopes "
                    + client.maxScopes());
        }
        validateClientScopes(ClientKind.fromDb(client.kind()), granted);

        String now = now();
        List<String> active = store.activeKeyIds(client.id(), now);
        if (active.size() >= MAX_ACTIVE_KEYS_PER_CLIENT) {
            if (!rotate) {
                throw new IllegalStateException(clientId + " already has " + active.size()
                        + " active keys (" + String.join(", ", active) + "); revoke one or use --rotate");
            }
            for (int i = 0; i <= active.size() - MAX_ACTIVE_KEYS_PER_CLIENT; i++) revoke(active.get(i), "rotated");
        }
        ApiKeyFormat.Generated g = ApiKeyFormat.generate();
        String expires = ttlDays > 0
                ? Instant.now(clock).plus(Duration.ofDays(ttlDays)).truncatedTo(ChronoUnit.SECONDS).toString() : null;
        store.insertKey(g.keyId(), client.id(), hasher.hash(g.secret()), Scopes.format(granted), expires);
        events.record(SecurityEventLog.Event.of("key_created").client(clientId).credential(g.keyId())
                .person(client.personId()).detail(Map.of("scopes", Scopes.format(granted),
                        "expires_at", String.valueOf(expires))));
        return new MintedKey(clientId, g.keyId(), g.fullKey(), granted, expires);
    }

    public boolean revoke(String keyId, String reason) {
        boolean done = store.revoke(keyId, now()) == 1;
        if (done) {
            events.record(SecurityEventLog.Event.of("key_revoked").credential(keyId)
                    .detail(Map.of("reason", reason == null ? "manual" : reason)));
        }
        return done;
    }

    public List<Map<String, Object>> listKeys() {
        return store.listKeys();
    }

    public List<Map<String, Object>> listClients() {
        return store.listClients();
    }
}
