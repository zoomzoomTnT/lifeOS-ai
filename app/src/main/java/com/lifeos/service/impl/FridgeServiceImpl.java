package com.lifeos.service.impl;

import com.lifeos.domain.FridgeItem;
import com.lifeos.domain.FridgeLocation;
import com.lifeos.domain.FridgeResolveAction;
import com.lifeos.domain.FridgeStatus;
import com.lifeos.domain.Names;
import com.lifeos.repo.FridgeRepository;
import com.lifeos.service.FridgeService;
import com.lifeos.service.PersonService;
import com.lifeos.web.dto.FridgeAddRequest;
import com.lifeos.web.dto.FridgeResolveRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class FridgeServiceImpl implements FridgeService {

    private final FridgeRepository fridge;
    private final PersonService people;

    @Override
    @Transactional
    public Map<String, Object> add(FridgeAddRequest request, String handle) {
        long personId = people.resolveId(handle);
        FridgeLocation location = request.location() == null ? FridgeLocation.FRIDGE : request.location();
        FridgeItem item = new FridgeItem(
                null, personId, personId, request.name(), Names.norm(request.name()),
                request.category(), location, FridgeStatus.IN_STOCK,
                request.qty() == null ? 1d : request.qty(),
                null, null, null
        );
        long id = fridge.insertInStock(item, request.expiresInDays());
        return Map.of("id", id, "status", FridgeStatus.IN_STOCK.db());
    }

    @Override
    public List<FridgeItem> list(FridgeStatus status, Integer expiringWithinHours, String handle) {
        return fridge.list(people.resolveId(handle), status, expiringWithinHours);
    }

    @Override
    @Transactional
    public Map<String, Object> resolve(long id, FridgeResolveRequest request, String handle) {
        FridgeResolveAction action = request.action();
        switch (action) {
            case EATEN -> fridge.updateStatus(id, FridgeStatus.EATEN);
            case DISCARDED -> fridge.updateStatus(id, FridgeStatus.DISCARDED);
            case KEEP_ONE_MORE_DAY -> fridge.bumpExpiryOneDay(id);
        }
        return Map.of("id", id, "action", action.db());
    }
}
