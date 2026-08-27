package com.lifeos.service;

import com.lifeos.web.dto.HoldingResponse;
import com.lifeos.web.dto.HoldingUpsertRequest;
import com.lifeos.web.dto.HoldingWriteResponse;
import com.lifeos.web.dto.StockEventRequest;
import com.lifeos.web.dto.StockEventResponse;

import java.util.List;

public interface HoldingService {
    List<HoldingResponse> list(String handle);

    HoldingResponse get(long id, String handle);

    HoldingWriteResponse upsert(HoldingUpsertRequest request, String handle);

    StockEventResponse addEvent(long holdingId, StockEventRequest request, String handle);
}
