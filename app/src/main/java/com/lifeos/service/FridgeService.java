package com.lifeos.service;

import com.lifeos.domain.FridgeItem;
import com.lifeos.domain.FridgeStatus;
import com.lifeos.web.dto.FridgeAddRequest;
import com.lifeos.web.dto.FridgeResolveRequest;

import java.util.List;
import java.util.Map;

public interface FridgeService {
    Map<String, Object> add(FridgeAddRequest request, String handle);

    List<FridgeItem> list(FridgeStatus status, Integer expiringWithinHours, String handle);

    Map<String, Object> resolve(long id, FridgeResolveRequest request, String handle);
}
