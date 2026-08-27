package com.lifeos.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.lifeos.domain.Market;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record HoldingResponse(
        Long id,
        Long ownerId,
        String symbol,
        Market market,
        String name,
        Double qty,
        Double avgCost,
        String currency,
        String notes,
        String createdAt,
        String updatedAt,
        List<StockEventResponse> events
) {}
