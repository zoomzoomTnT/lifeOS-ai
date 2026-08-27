package com.lifeos.repo.jpa;

import com.lifeos.domain.Holding;
import com.lifeos.domain.Market;
import com.lifeos.domain.StockEvent;
import com.lifeos.repo.HoldingRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
@Transactional
public class JpaHoldingRepository implements HoldingRepository {

    @PersistenceContext
    private EntityManager em;

    @Override
    public List<Holding> listByOwner(long ownerId) {
        return em.createQuery("""
                        select h from Holding h
                        where h.ownerId = :owner
                        order by h.symbol
                        """, Holding.class)
                .setParameter("owner", ownerId)
                .getResultList();
    }

    @Override
    public Optional<Holding> findById(long id) {
        return Optional.ofNullable(em.find(Holding.class, id));
    }

    @Override
    public Optional<Holding> findByOwnerSymbolMarket(long ownerId, String symbol, Market market) {
        List<Holding> rows = em.createQuery("""
                        select h from Holding h
                        where h.ownerId = :owner and h.symbol = :symbol and h.market = :market
                        """, Holding.class)
                .setParameter("owner", ownerId)
                .setParameter("symbol", symbol)
                .setParameter("market", market)
                .setMaxResults(1)
                .getResultList();
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public long upsert(Holding holding) {
        if (holding.getId() == null) {
            em.persist(holding);
        } else {
            em.merge(holding);
        }
        em.flush();
        return holding.getId();
    }

    @Override
    public long insertEvent(StockEvent event) {
        em.persist(event);
        em.flush();
        return event.getId();
    }

    @Override
    public List<StockEvent> listEvents(long holdingId) {
        return em.createQuery("""
                        select e from StockEvent e
                        where e.holdingId = :holding
                        order by e.id desc
                        """, StockEvent.class)
                .setParameter("holding", holdingId)
                .getResultList();
    }
}
