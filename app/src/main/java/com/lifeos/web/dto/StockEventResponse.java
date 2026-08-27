package com.lifeos.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.lifeos.domain.StockEventKind;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record StockEventResponse(
        Long id,
        Long holdingId,
        StockEventKind kind,
        String eventDate,
        String notes,
        Long memoId,
        String createdAt
) {}
