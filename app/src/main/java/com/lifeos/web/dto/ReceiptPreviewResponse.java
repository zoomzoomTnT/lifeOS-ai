package com.lifeos.web.dto;

import com.lifeos.domain.ReceiptStatus;

import java.util.List;

@JsonApi
public record ReceiptPreviewResponse(
        String action,
        Long receiptId,
        Long existingReceiptId,
        ReceiptStatus status,
        String message,
        String fingerprint,
        Boolean sumOk,
        Integer computedCents,
        Integer totalCents,
        Long merchantId,
        List<ReceiptFoodHint> foodItems
) {}
