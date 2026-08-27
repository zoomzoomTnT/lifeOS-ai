package com.lifeos.web.dto;

import com.lifeos.domain.StockEventKind;
import jakarta.validation.constraints.NotNull;

public record StockEventRequest(
        @NotNull StockEventKind kind,
        String eventDate,
        String notes,
        Long memoId
) {}
