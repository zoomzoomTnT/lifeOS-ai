package com.lifeos.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.lifeos.domain.ReceiptStatus;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReceiptResponse(
        Long id,
        Long merchantId,
        Long payerId,
        String barcode,
        String printedAt,
        String fingerprint,
        String currency,
        Integer totalCents,
        Integer computedCents,
        ReceiptStatus status,
        String createdAt
) {}
