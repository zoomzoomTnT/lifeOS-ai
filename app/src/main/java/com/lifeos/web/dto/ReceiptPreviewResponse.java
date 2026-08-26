package com.lifeos.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.lifeos.domain.FoodCategory;
import com.lifeos.domain.ReceiptStatus;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
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
        List<FoodHint> foodItems
) {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FoodHint(String name, String nameNorm, FoodCategory category) {}
}
