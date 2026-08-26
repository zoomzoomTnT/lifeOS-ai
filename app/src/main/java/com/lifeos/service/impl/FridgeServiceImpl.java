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
import com.lifeos.web.dto.FridgeAddResponse;
import com.lifeos.web.dto.FridgeResolveRequest;
import com.lifeos.web.dto.FridgeResolveResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class FridgeServiceImpl implements FridgeService {

    private final FridgeRepository fridge;
    private final PersonService people;

    @Override
    @Transactional
    public FridgeAddResponse add(FridgeAddRequest request, String handle) {
        long personId = people.resolveId(handle);
        FridgeLocation location = request.location() == null ? FridgeLocation.FRIDGE : request.location();
        double qty = request.qty() == null ? 1d : request.qty();
        FridgeItem item = new FridgeItem(
                null, personId, personId, request.name(), Names.norm(request.name()),
                request.category(), location, FridgeStatus.IN_STOCK, qty,
                null, null, null
        );
        long id = fridge.insertInStock(item, request.expiresInDays());
        return new FridgeAddResponse(id, FridgeStatus.IN_STOCK);
    }

    @Override
    public List<FridgeItem> list(FridgeStatus status, Integer expiringWithinHours, String handle) {
        return fridge.list(people.resolveId(handle), status, expiringWithinHours);
    }

    @Override
    @Transactional
    public FridgeResolveResponse resolve(long id, FridgeResolveRequest request, String handle) {
        FridgeResolveAction action = request.action();
        switch (action) {
            case EATEN -> fridge.updateStatus(id, FridgeStatus.EATEN);
            case DISCARDED -> fridge.updateStatus(id, FridgeStatus.DISCARDED);
            case KEEP_ONE_MORE_DAY -> fridge.bumpExpiryOneDay(id);
        }
        return new FridgeResolveResponse(id, action);
    }
}
