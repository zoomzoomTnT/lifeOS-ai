package com.lifeos.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Key management: create clients, mint, rotate and revoke keys. Called from /api/admin/** (admin
 * client only) and, for the admin key itself, from {@link AdminKeyBootstrap} at startup. Secrets are
 * returned to the caller exactly once and never logged or stored.
 * <p>Hard rules: ops:admin only for a client of kind admin (and there is at most one); that client
 * never holds data-write scopes; its key comes only from the deploy secret, never from {@link #mint}.
 */
public class ApiKeyService {

    /** Old + new during a rotation. A third key requires revoking one first (or rotate). */
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

    /** Thrown when the request conflicts with existing state (duplicate client, too many keys). */
    public static class Conflict extends IllegalStateException {
        public Conflict(String message) {
            super(message);
        }
    }

    /** A new non-admin client (the admin client exists only through {@link AdminKeyBootstrap}). */
    public ApiKeyStore.ClientRecord createClient(String clientId, String displayName, ClientKind kind,
                                                 Collection<String> maxScopes) {
        if (kind == ClientKind.ADMIN) {
            throw new IllegalArgumentException("the admin client comes only from the deploy secret (LIFE_ADMIN_KEY_*)");
        }
        validateClientScopes(kind, maxScopes);
        if (store.findClient(clientId).isPresent()) throw new Conflict("client already exists: " + clientId);
        return insertClient(clientId, displayName, kind, maxScopes, null);
    }

    private ApiKeyStore.ClientRecord insertClient(String clientId, String displayName, ClientKind kind,
                                                  Collection<String> maxScopes, String via) {
        long person = store.ownerPersonId();
        store.insertClient(clientId, displayName, kind.db(), person, Scopes.format(maxScopes));
        Map<String, Object> detail = new java.util.LinkedHashMap<>(Map.of("kind", kind.db(), "max_scopes", Scopes.format(maxScopes)));
        if (via != null) detail.put("via", via);
        events.record(SecurityEventLog.Event.of("client_created").client(clientId).person(person).detail(detail));
        return store.findClient(clientId).orElseThrow();
    }

    static void validateClientScopes(ClientKind kind, Collection<String> scopes) {
        if (scopes.isEmpty()) throw new IllegalArgumentException("a client needs at least one scope");
        if (kind != ClientKind.ADMIN && scopes.contains(Scopes.OPS_ADMIN)) {
            throw new IllegalArgumentException("ops:admin is only for the admin client, never for kind " + kind.db());
        }
        if (kind == ClientKind.ADMIN && scopes.stream().anyMatch(Scopes.DATA_WRITES::contains)) {
            throw new IllegalArgumentException("the admin client never holds data-write scopes");
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
        if (ClientKind.fromDb(client.kind()) == ClientKind.ADMIN) {
            throw new IllegalArgumentException("the admin key comes only from the deploy secret (LIFE_ADMIN_KEY_*); "
                    + "it is never minted or rotated over the API");
        }
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
                throw new Conflict(clientId + " already has " + active.size()
                        + " active keys (" + String.join(", ", active) + "); revoke one or rotate");
            }
            for (int i = 0; i <= active.size() - MAX_ACTIVE_KEYS_PER_CLIENT; i++) revoke(active.get(i), "rotated", null);
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

    /** Revokes a non-admin key (the admin key is replaced only through the deploy secret). */
    public boolean revokeNonAdmin(String keyId, String reason) {
        Optional<ApiKeyStore.KeyRecord> k = store.findByKeyId(keyId);
        if (k.isPresent() && ClientKind.fromDb(k.get().clientKind()) == ClientKind.ADMIN) {
            throw new IllegalArgumentException("the admin key is replaced only through the deploy secret (LIFE_ADMIN_KEY_*)");
        }
        return revoke(keyId, reason, null);
    }

    boolean revoke(String keyId, String reason, String via) {
        boolean done = store.revoke(keyId, now()) == 1;
        if (done) {
            Map<String, Object> detail = new java.util.LinkedHashMap<>(Map.of("reason", reason == null ? "manual" : reason));
            if (via != null) detail.put("via", via);
            events.record(SecurityEventLog.Event.of("key_revoked").credential(keyId).detail(detail));
        }
        return done;
    }

    /**
     * Makes {@code keyId} with {@code sha256Hex} = SHA-256(secret) the admin client's only active key
     * (creating client {@value AdminKeyBootstrap#ADMIN_CLIENT_ID} if needed) and revokes every other
     * admin key. Same values again = no change.
     *
     * @return true if anything changed
     */
    public boolean installAdminKey(String keyId, String sha256Hex) {
        String via = "deploy_secret";
        ApiKeyStore.ClientRecord admin = store.findAdminClient().orElse(null);
        if (admin == null) {
            admin = insertClient(AdminKeyBootstrap.ADMIN_CLIENT_ID, "lifeOS Admin bot", ClientKind.ADMIN,
                    Scopes.ADMIN_CLIENT, via);
        } else if (!admin.clientId().equals(AdminKeyBootstrap.ADMIN_CLIENT_ID)) {
            throw new IllegalStateException("the admin client is '" + admin.clientId() + "', expected '"
                    + AdminKeyBootstrap.ADMIN_CLIENT_ID + "'");
        }
        boolean changed = false;
        Optional<ApiKeyStore.KeyRecord> existing = store.findByKeyId(keyId);
        if (existing.isPresent()) {
            ApiKeyStore.KeyRecord k = existing.get();
            if (!k.clientId().equals(admin.clientId()) || k.revokedAt() != null || !KeyHasher.equalHex(k.secretHash(), sha256Hex)) {
                throw new IllegalStateException("LIFE_ADMIN_KEY_ID " + keyId + " is already used by another or an "
                        + "older key: generate a new admin key (new key id) and update both secrets");
            }
        } else {
            store.insertKey(keyId, admin.id(), sha256Hex, Scopes.format(Scopes.parse(admin.maxScopes())), null);
            events.record(SecurityEventLog.Event.of("key_created").client(admin.clientId()).credential(keyId)
                    .person(admin.personId()).detail(Map.of("scopes", admin.maxScopes(), "via", via)));
            changed = true;
        }
        for (String other : store.activeKeyIds(admin.id(), now())) {
            if (!other.equals(keyId)) changed |= revoke(other, "replaced", via);
        }
        return changed;
    }

    public List<Map<String, Object>> listKeys() {
        return store.listKeys();
    }

    public List<Map<String, Object>> listClients() {
        return store.listClients();
    }
}
