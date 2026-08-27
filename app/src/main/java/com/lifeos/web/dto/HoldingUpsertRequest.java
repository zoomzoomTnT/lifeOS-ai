package com.lifeos.web.dto;

import com.lifeos.domain.Market;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record HoldingUpsertRequest(
        @NotBlank String symbol,
        @NotNull Market market,
        String name,
        Double qty,
        Double avgCost,
        String currency,
        String notes
) {}
