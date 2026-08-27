package com.lifeos.repo;

import com.lifeos.domain.Holding;
import com.lifeos.domain.Market;
import com.lifeos.domain.StockEvent;

import java.util.List;
import java.util.Optional;

public interface HoldingRepository {
    List<Holding> listByOwner(long ownerId);

    Optional<Holding> findById(long id);

    Optional<Holding> findByOwnerSymbolMarket(long ownerId, String symbol, Market market);

    long upsert(Holding holding);

    long insertEvent(StockEvent event);

    List<StockEvent> listEvents(long holdingId);
}
