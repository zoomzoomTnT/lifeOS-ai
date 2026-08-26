package com.lifeos.service.impl;

import com.lifeos.domain.FridgeItem;
import com.lifeos.domain.FridgeResolveAction;
import com.lifeos.domain.FridgeStatus;
import com.lifeos.mapper.FridgeMapper;
import com.lifeos.repo.FridgeRepository;
import com.lifeos.service.FridgeService;
import com.lifeos.service.PersonService;
import com.lifeos.web.dto.FridgeAddRequest;
import com.lifeos.web.dto.FridgeItemResponse;
import com.lifeos.web.dto.FridgeResolveRequest;
import com.lifeos.web.dto.FridgeWriteResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class FridgeServiceImpl implements FridgeService {

    private final FridgeRepository fridge;
    private final PersonService people;
    private final FridgeMapper fridgeMapper;

    @Override
    @Transactional
    public FridgeWriteResponse add(FridgeAddRequest request, String handle) {
        long personId = people.resolveId(handle);
        FridgeItem item = fridgeMapper.toNewItem(request, personId);
        long id = fridge.insertInStock(item, request.expiresInDays());
        return fridgeMapper.toCreated(id);
    }

    @Override
    public List<FridgeItemResponse> list(FridgeStatus status, Integer expiringWithinHours, String handle) {
        return fridgeMapper.toResponseList(fridge.list(people.resolveId(handle), status, expiringWithinHours));
    }

    @Override
    @Transactional
    public FridgeWriteResponse resolve(long id, FridgeResolveRequest request, String handle) {
        FridgeResolveAction action = request.action();
        switch (action) {
            case EATEN -> fridge.updateStatus(id, FridgeStatus.EATEN);
            case DISCARDED -> fridge.updateStatus(id, FridgeStatus.DISCARDED);
            case KEEP_ONE_MORE_DAY -> fridge.bumpExpiryOneDay(id);
        }
        return fridgeMapper.toResolved(id, action);
    }
}
