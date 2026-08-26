package com.lifeos.service.impl;

import com.lifeos.domain.FoodCategory;
import com.lifeos.domain.FridgeItem;
import com.lifeos.domain.FridgeLocation;
import com.lifeos.domain.FridgeResolveAction;
import com.lifeos.domain.FridgeStatus;
import com.lifeos.domain.Names;
import com.lifeos.repo.FridgeRepository;
import com.lifeos.service.FridgeService;
import com.lifeos.service.PersonService;
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
    public Map<String, Object> add(Map<String, Object> body, String handle) {
        long personId = people.resolveId(handle);
        String name = Bodies.str(body, "name");
        FridgeLocation location = body.get("location") == null
                ? FridgeLocation.FRIDGE : FridgeLocation.from(Bodies.str(body, "location"));
        FoodCategory category = body.get("category") == null ? null : FoodCategory.from(Bodies.str(body, "category"));
        FridgeItem item = new FridgeItem(
                null, personId, personId, name, Names.norm(name),
                category, location, FridgeStatus.IN_STOCK,
                Bodies.doubleVal(body.get("qty"), 1d),
                null, null, null
        );
        long id = fridge.insertInStock(item, Bodies.intOrNull(body.get("expires_in_days")));
        return Map.of("id", id, "status", FridgeStatus.IN_STOCK.db());
    }

    @Override
    public List<FridgeItem> list(FridgeStatus status, Integer expiringWithinHours, String handle) {
        return fridge.list(people.resolveId(handle), status, expiringWithinHours);
    }

    @Override
    @Transactional
    public Map<String, Object> resolve(long id, Map<String, Object> body, String handle) {
        FridgeResolveAction action = FridgeResolveAction.from(Bodies.str(body, "action"));
        if (action == null) throw new IllegalArgumentException("unknown_action");
        switch (action) {
            case EATEN -> fridge.updateStatus(id, FridgeStatus.EATEN);
            case DISCARDED -> fridge.updateStatus(id, FridgeStatus.DISCARDED);
            case KEEP_ONE_MORE_DAY -> fridge.bumpExpiryOneDay(id);
        }
        return Map.of("id", id, "action", action.db());
    }
}
