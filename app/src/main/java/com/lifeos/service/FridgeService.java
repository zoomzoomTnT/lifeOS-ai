package com.lifeos.service;

import com.lifeos.domain.FridgeStatus;
import com.lifeos.web.dto.FridgeAddRequest;
import com.lifeos.web.dto.FridgeItemResponse;
import com.lifeos.web.dto.FridgeResolveRequest;
import com.lifeos.web.dto.FridgeWriteResponse;

import java.util.List;

public interface FridgeService {
    FridgeWriteResponse add(FridgeAddRequest request, String handle);

    List<FridgeItemResponse> list(FridgeStatus status, Integer expiringWithinHours, String handle);

    FridgeWriteResponse resolve(long id, FridgeResolveRequest request, String handle);
}
