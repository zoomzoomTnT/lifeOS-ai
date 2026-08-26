package com.lifeos.service;

import com.lifeos.domain.FridgeItem;
import com.lifeos.domain.FridgeStatus;
import com.lifeos.web.dto.FridgeAddRequest;
import com.lifeos.web.dto.FridgeAddResponse;
import com.lifeos.web.dto.FridgeResolveRequest;
import com.lifeos.web.dto.FridgeResolveResponse;

import java.util.List;

public interface FridgeService {
    FridgeAddResponse add(FridgeAddRequest request, String handle);

    List<FridgeItem> list(FridgeStatus status, Integer expiringWithinHours, String handle);

    FridgeResolveResponse resolve(long id, FridgeResolveRequest request, String handle);
}
