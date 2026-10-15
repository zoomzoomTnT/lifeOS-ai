package com.lifeos.service.impl;

import com.lifeos.domain.Holding;
import com.lifeos.domain.StockEvent;
import com.lifeos.mapper.HoldingMapper;
import com.lifeos.repo.EventRepository;
import com.lifeos.repo.HoldingRepository;
import com.lifeos.service.HoldingService;
import com.lifeos.web.NotFoundException;
import com.lifeos.web.dto.HoldingResponse;
import com.lifeos.web.dto.HoldingUpsertRequest;
import com.lifeos.web.dto.HoldingWriteResponse;
import com.lifeos.web.dto.StockEventRequest;
import com.lifeos.web.dto.StockEventResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class HoldingServiceImpl implements HoldingService {

    private final HoldingRepository holdings;
    private final EventRepository events;
    private final HoldingMapper holdingMapper;

    @Override
    public List<HoldingResponse> list(long personId) {
        return holdingMapper.toResponseList(holdings.listByOwner(personId));
    }

    @Override
    public HoldingResponse get(long id, long personId) {
        return holdingMapper.toResponse(requireOwned(id, personId), holdings.listEvents(id));
    }

    @Override
    @Transactional
    public HoldingWriteResponse upsert(HoldingUpsertRequest request, long personId) {
        long ownerId = personId;
        Holding incoming = holdingMapper.toNewHolding(request, ownerId);
        Optional<Holding> existing = holdings.findByOwnerSymbolMarket(ownerId, incoming.getSymbol(), incoming.getMarket());
        boolean created;
        long id;
        if (existing.isPresent()) {
            Holding row = existing.get();
            row.setName(incoming.getName() != null ? incoming.getName() : row.getName());
            row.setQty(incoming.getQty());
            row.setAvgCost(incoming.getAvgCost() != null ? incoming.getAvgCost() : row.getAvgCost());
            row.setCurrency(incoming.getCurrency());
            row.setNotes(incoming.getNotes() != null ? incoming.getNotes() : row.getNotes());
            id = holdings.upsert(row);
            created = false;
        } else {
            id = holdings.upsert(incoming);
            created = true;
        }
        events.insert("stocks", created ? "create" : "update", ownerId, "holdings", id, incoming.getSymbol());
        return holdingMapper.toWritten(id, incoming.getSymbol(), incoming.getMarket(), created);
    }

    @Override
    @Transactional
    public StockEventResponse addEvent(long holdingId, StockEventRequest request, long personId) {
        long ownerId = personId;
        requireOwned(holdingId, ownerId);
        StockEvent event = holdingMapper.toNewEvent(request, holdingId);
        holdings.insertEvent(event);
        events.insert("stocks", "event", ownerId, "stock_events", event.getId(), request.kind().db());
        return holdingMapper.toEventResponse(event);
    }

    private Holding requireOwned(long id, long ownerId) {
        Holding holding = holdings.findById(id)
                .orElseThrow(() -> new NotFoundException("holding not found: " + id));
        if (!ownerIdEquals(holding.getOwnerId(), ownerId)) {
            throw new NotFoundException("holding not found: " + id);
        }
        return holding;
    }

    private static boolean ownerIdEquals(Long ownerId, long expected) {
        return ownerId != null && ownerId == expected;
    }
}
