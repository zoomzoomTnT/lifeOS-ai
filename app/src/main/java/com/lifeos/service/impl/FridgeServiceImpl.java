package com.lifeos.service.impl;

import com.lifeos.domain.FridgeItem;
import com.lifeos.domain.FridgeResolveAction;
import com.lifeos.domain.FridgeStatus;
import com.lifeos.mapper.FridgeMapper;
import com.lifeos.repo.FridgeRepository;
import com.lifeos.service.FridgeService;
import com.lifeos.web.dto.FridgeAddRequest;
import com.lifeos.web.dto.FridgeItemResponse;
import com.lifeos.web.dto.FridgeResolveRequest;
import com.lifeos.web.dto.FridgeWriteResponse;
import com.lifeos.web.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class FridgeServiceImpl implements FridgeService {

    private final FridgeRepository fridge;
    private final FridgeMapper fridgeMapper;

    @Override
    @Transactional
    public FridgeWriteResponse add(FridgeAddRequest request, long personId) {
        FridgeItem item = fridgeMapper.toNewItem(request, personId);
        long id = fridge.insertInStock(item, request.expiresInDays());
        return fridgeMapper.toCreated(id);
    }

    @Override
    public List<FridgeItemResponse> list(FridgeStatus status, Integer expiringWithinHours, long personId) {
        return fridgeMapper.toResponseList(fridge.list(personId, status, expiringWithinHours));
    }

    @Override
    @Transactional
    public FridgeWriteResponse resolve(long id, FridgeResolveRequest request, long personId) {
        if (fridge.ownerOf(id).filter(o -> o == personId).isEmpty()) {
            throw new NotFoundException("fridge item not found: " + id); // also when it is someone else's
        }
        FridgeResolveAction action = request.action();
        switch (action) {
            case EATEN -> fridge.updateStatus(id, FridgeStatus.EATEN);
            case DISCARDED -> fridge.updateStatus(id, FridgeStatus.DISCARDED);
            case KEEP_ONE_MORE_DAY -> fridge.bumpExpiryOneDay(id);
        }
        return fridgeMapper.toResolved(id, action);
    }
}
