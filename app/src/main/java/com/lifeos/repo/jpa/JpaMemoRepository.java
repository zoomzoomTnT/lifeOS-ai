package com.lifeos.repo.jpa;

import com.lifeos.domain.Memo;
import com.lifeos.domain.MemoStatus;
import com.lifeos.domain.Utc;
import com.lifeos.repo.MemoRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
@Transactional
public class JpaMemoRepository implements MemoRepository {

    @PersistenceContext
    private EntityManager em;

    @Override
    public long insert(Memo memo) {
        em.persist(memo);
        em.flush();
        return memo.getId();
    }

    @Override
    public List<Memo> due(long ownerId, int withinHours) {
        return em.createQuery("""
                        select m from Memo m
                        where m.ownerId = :owner
                          and m.status in :statuses
                          and m.dueAt is not null
                          and m.dueAt <= :cutoff
                        order by m.priority, m.dueAt
                        """, Memo.class)
                .setParameter("owner", ownerId)
                .setParameter("statuses", List.of(MemoStatus.OPEN, MemoStatus.SNOOZED))
                .setParameter("cutoff", Utc.plusHours(withinHours))
                .setMaxResults(20)
                .getResultList();
    }

    @Override
    public List<Memo> dueForWake(long ownerId, int leadMinutes, boolean nightPriorityOnly) {
        var q = em.createQuery("""
                        select m from Memo m
                        where m.ownerId = :owner
                          and m.status in :statuses
                          and m.dueAt is not null
                          and m.dueAt <= :cutoff
                          and (m.lastFiredAt is null or m.lastFiredAt <= :refire)
                          and (:night = false or m.priority = 1)
                        order by m.priority, m.dueAt
                        """, Memo.class)
                .setParameter("owner", ownerId)
                .setParameter("statuses", List.of(MemoStatus.OPEN, MemoStatus.SNOOZED))
                .setParameter("cutoff", Utc.plusMinutes(leadMinutes))
                .setParameter("refire", Utc.minusHours(6))
                .setParameter("night", nightPriorityOnly)
                .setMaxResults(10);
        return q.getResultList();
    }

    @Override
    public void updateStatus(long id, MemoStatus status) {
        Memo memo = em.find(Memo.class, id);
        if (memo != null) {
            memo.setStatus(status);
        }
    }

    @Override
    public void updateDueAt(long id, String dueAt) {
        Memo memo = em.find(Memo.class, id);
        if (memo != null) {
            memo.setDueAt(dueAt);
        }
    }

    @Override
    public void updateAutomationId(long id, String automationId) {
        Memo memo = em.find(Memo.class, id);
        if (memo != null) {
            memo.setAutomationId(automationId);
        }
    }

    @Override
    public void markFired(long id) {
        Memo memo = em.find(Memo.class, id);
        if (memo != null) {
            memo.setLastFiredAt(Utc.now());
        }
    }
}
