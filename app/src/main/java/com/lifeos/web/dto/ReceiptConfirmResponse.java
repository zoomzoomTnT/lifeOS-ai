package com.lifeos.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.lifeos.domain.ReceiptStatus;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReceiptConfirmResponse(
        ReceiptStatus status,
        Long receiptId,
        List<Long> fridgeItemIds,
        String error
) {}
