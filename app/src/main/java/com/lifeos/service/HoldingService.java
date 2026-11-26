package com.lifeos.service;

import com.lifeos.web.dto.HoldingResponse;
import com.lifeos.web.dto.HoldingUpsertRequest;
import com.lifeos.web.dto.HoldingWriteResponse;
import com.lifeos.web.dto.StockEventRequest;
import com.lifeos.web.dto.StockEventResponse;

import java.util.List;

public interface HoldingService {
    List<HoldingResponse> list(long personId);

    HoldingResponse get(long id, long personId);

    HoldingWriteResponse upsert(HoldingUpsertRequest request, long personId);

    StockEventResponse addEvent(long holdingId, StockEventRequest request, long personId);
}
