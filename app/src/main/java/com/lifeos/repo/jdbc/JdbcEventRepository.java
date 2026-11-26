package com.lifeos.repo.jdbc;

import com.lifeos.repo.EventRepository;
import com.lifeos.security.AuditContext;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class JdbcEventRepository implements EventRepository {

    private final JdbcTemplate jdbc;

    @Override
    public void insert(String domain, String action, long actorId, String entityTable, Long entityId, String payloadJson) {
        AuditContext a = AuditContext.current();
        jdbc.update("""
                INSERT INTO events (domain, action, actor_id, entity_table, entity_id, payload_json,
                  client_id, credential_id, request_id, approval_id)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """, domain, action, actorId, entityTable, entityId, payloadJson,
                a.clientId(), a.credentialId(), a.requestId(), a.approvalId());
    }
}
