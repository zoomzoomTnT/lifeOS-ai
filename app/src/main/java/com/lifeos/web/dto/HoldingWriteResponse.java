package com.lifeos.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.lifeos.domain.Market;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record HoldingWriteResponse(
        Long id,
        String symbol,
        Market market,
        boolean created
) {}
