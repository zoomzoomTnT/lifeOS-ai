package com.lifeos.repo.jdbc;

import com.lifeos.repo.EventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class JdbcEventRepository implements EventRepository {

    private final JdbcTemplate jdbc;

    @Override
    public void insert(String domain, String action, long actorId, String entityTable, Long entityId, String payloadJson) {
        jdbc.update("""
                INSERT INTO events (domain, action, actor_id, entity_table, entity_id, payload_json)
                VALUES (?,?,?,?,?,?)
                """, domain, action, actorId, entityTable, entityId, payloadJson);
    }
}
