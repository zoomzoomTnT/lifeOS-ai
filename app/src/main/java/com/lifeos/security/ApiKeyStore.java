package com.lifeos.security;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** SQL for api_clients / api_keys. */
public class ApiKeyStore {

    private final JdbcTemplate jdbc;

    public ApiKeyStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record KeyRecord(long id, String keyId, String secretHash, String scopes, String expiresAt,
                            String revokedAt, String lastUsedAt, String lastUsedIp,
                            String clientId, String clientKind, long personId, String maxScopes,
                            String clientDisabledAt) {
        @Override
        public String toString() {
            return "KeyRecord[keyId=" + keyId + ", client=" + clientId + "]";
        }
    }

    public record ClientRecord(long id, String clientId, String displayName, String kind, long personId,
                               String maxScopes, String disabledAt) {}

    public Optional<KeyRecord> findByKeyId(String keyId) {
        List<KeyRecord> rows = jdbc.query("""
                SELECT k.id, k.key_id, k.secret_hash, k.scopes, k.expires_at, k.revoked_at, k.last_used_at,
                       k.last_used_ip, c.client_id, c.kind, c.person_id, c.max_scopes, c.disabled_at
                  FROM api_keys k JOIN api_clients c ON c.id = k.api_client_id
                 WHERE k.key_id = ?""", (rs, i) -> new KeyRecord(rs.getLong(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
                rs.getString(9), rs.getString(10), rs.getLong(11), rs.getString(12), rs.getString(13)), keyId);
        return rows.stream().findFirst();
    }

    public void touch(long keyRowId, String now, String ip) {
        jdbc.update("UPDATE api_keys SET last_used_at = ?, last_used_ip = ? WHERE id = ?", now, ip, keyRowId);
    }

    public Optional<ClientRecord> findClient(String clientId) {
        return jdbc.query("""
                SELECT id, client_id, display_name, kind, person_id, max_scopes, disabled_at
                  FROM api_clients WHERE client_id = ?""", (rs, i) -> new ClientRecord(rs.getLong(1),
                rs.getString(2), rs.getString(3), rs.getString(4), rs.getLong(5), rs.getString(6),
                rs.getString(7)), clientId).stream().findFirst();
    }

    /** The single client of kind admin, if it exists yet. */
    public Optional<ClientRecord> findAdminClient() {
        return jdbc.query("""
                SELECT id, client_id, display_name, kind, person_id, max_scopes, disabled_at
                  FROM api_clients WHERE kind = 'admin'""", (rs, i) -> new ClientRecord(rs.getLong(1),
                rs.getString(2), rs.getString(3), rs.getString(4), rs.getLong(5), rs.getString(6),
                rs.getString(7))).stream().findFirst();
    }

    public long insertClient(String clientId, String displayName, String kind, long personId, String maxScopes) {
        Long id = jdbc.queryForObject("""
                INSERT INTO api_clients (client_id, display_name, kind, person_id, max_scopes)
                VALUES (?,?,?,?,?) RETURNING id""", Long.class, clientId, displayName, kind, personId, maxScopes);
        return id;
    }

    public void insertKey(String keyId, long clientRowId, String secretHash, String scopes, String expiresAt) {
        jdbc.update("""
                INSERT INTO api_keys (key_id, api_client_id, secret_hash, scopes, expires_at)
                VALUES (?,?,?,?,?)""", keyId, clientRowId, secretHash, scopes, expiresAt);
    }

    /** Active = not revoked and not expired at {@code now}, oldest first. */
    public List<String> activeKeyIds(long clientRowId, String now) {
        return jdbc.queryForList("""
                SELECT key_id FROM api_keys
                 WHERE api_client_id = ? AND revoked_at IS NULL AND (expires_at IS NULL OR expires_at > ?)
                 ORDER BY created_at, id""", String.class, clientRowId, now);
    }

    public int revoke(String keyId, String now) {
        return jdbc.update("UPDATE api_keys SET revoked_at = ? WHERE key_id = ? AND revoked_at IS NULL", now, keyId);
    }

    public List<Map<String, Object>> listKeys() {
        return jdbc.queryForList("""
                SELECT c.client_id, c.kind, k.key_id, k.scopes, k.created_at, k.expires_at, k.last_used_at,
                       k.last_used_ip, k.revoked_at
                  FROM api_keys k JOIN api_clients c ON c.id = k.api_client_id
                 ORDER BY c.client_id, k.created_at, k.id""");
    }

    public List<Map<String, Object>> listClients() {
        return jdbc.queryForList("""
                SELECT client_id, display_name, kind, person_id, max_scopes, created_at, disabled_at
                  FROM api_clients ORDER BY client_id""");
    }

    /** The owner person (people.role = 'owner'; id 1 in every lifeOS database). */
    public long ownerPersonId() {
        List<Long> ids = jdbc.queryForList("SELECT id FROM people WHERE role = 'owner' ORDER BY id LIMIT 1", Long.class);
        if (ids.isEmpty()) throw new IllegalStateException("no owner row in people (role = 'owner')");
        return ids.getFirst();
    }
}
