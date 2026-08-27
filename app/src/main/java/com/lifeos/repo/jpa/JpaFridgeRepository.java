package com.lifeos.repo.jpa;

import com.lifeos.domain.FridgeItem;
import com.lifeos.domain.FridgeLocation;
import com.lifeos.domain.FridgeStatus;
import com.lifeos.domain.Utc;
import com.lifeos.repo.FridgeRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Repository
@Transactional
public class JpaFridgeRepository implements FridgeRepository {

    @PersistenceContext
    private EntityManager em;

    @Override
    public long insertInStock(FridgeItem item, Integer expiresInDays) {
        if (item.getStatus() == null) {
            item.setStatus(FridgeStatus.IN_STOCK);
        }
        if (item.getLocation() == null) {
            item.setLocation(FridgeLocation.FRIDGE);
        }
        if (item.getPurchasedAt() == null) {
            item.setPurchasedAt(Utc.now());
        }
        if (expiresInDays != null) {
            item.setExpiresAt(Utc.plusDays(expiresInDays));
        }
        em.persist(item);
        em.flush();
        return item.getId();
    }

    @Override
    public List<FridgeItem> list(long ownerId, FridgeStatus status, Integer expiringWithinHours) {
        if (expiringWithinHours != null) {
            return em.createQuery("""
                            select f from FridgeItem f
                            where f.ownerId = :owner and f.status = :status
                              and f.expiresAt is not null and f.expiresAt <= :cutoff
                            order by f.expiresAt
                            """, FridgeItem.class)
                    .setParameter("owner", ownerId)
                    .setParameter("status", FridgeStatus.IN_STOCK)
                    .setParameter("cutoff", Utc.plusHours(expiringWithinHours))
                    .getResultList();
        }
        if (status != null) {
            return em.createQuery("""
                            select f from FridgeItem f
                            where f.ownerId = :owner and f.status = :status
                            order by f.id desc
                            """, FridgeItem.class)
                    .setParameter("owner", ownerId)
                    .setParameter("status", status)
                    .getResultList();
        }
        return em.createQuery("""
                        select f from FridgeItem f
                        where f.ownerId = :owner
                        order by f.id desc
                        """, FridgeItem.class)
                .setParameter("owner", ownerId)
                .setMaxResults(50)
                .getResultList();
    }

    @Override
    public void updateStatus(long id, FridgeStatus status) {
        FridgeItem item = em.find(FridgeItem.class, id);
        if (item != null) {
            item.setStatus(status);
        }
    }

    @Override
    public void bumpExpiryOneDay(long id) {
        FridgeItem item = em.find(FridgeItem.class, id);
        if (item != null && item.getExpiresAt() != null) {
            item.setExpiresAt(Instant.parse(item.getExpiresAt()).plus(1, ChronoUnit.DAYS)
                    .truncatedTo(ChronoUnit.SECONDS).toString());
        }
    }
}
