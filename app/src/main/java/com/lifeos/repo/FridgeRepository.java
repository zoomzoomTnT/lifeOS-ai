package com.lifeos.repo;

import com.lifeos.domain.FridgeItem;
import com.lifeos.domain.FridgeStatus;

import java.util.List;

public interface FridgeRepository {
    long insertInStock(FridgeItem item, Integer expiresInDays);

    List<FridgeItem> list(long ownerId, FridgeStatus status, Integer expiringWithinHours);

    /** owner_id of the item, empty if it does not exist. */
    java.util.Optional<Long> ownerOf(long id);

    void updateStatus(long id, FridgeStatus status);

    void bumpExpiryOneDay(long id);
}
