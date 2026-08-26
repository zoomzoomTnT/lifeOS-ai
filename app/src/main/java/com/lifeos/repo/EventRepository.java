package com.lifeos.repo;

public interface EventRepository {
    void insert(String domain, String action, long actorId, String entityTable, Long entityId, String payloadJson);
}
