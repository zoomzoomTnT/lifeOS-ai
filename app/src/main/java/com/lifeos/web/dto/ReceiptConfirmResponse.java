package com.lifeos.web.dto;

import com.lifeos.domain.ReceiptStatus;

import java.util.List;

@JsonApi
public record ReceiptConfirmResponse(
        ReceiptStatus status,
        Long receiptId,
        List<Long> fridgeItemIds,
        String error
) {}
