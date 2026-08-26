package com.lifeos.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.lifeos.domain.FoodCategory;
import com.lifeos.domain.FridgeLocation;
import com.lifeos.domain.FridgeStatus;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record FridgeItemResponse(
        Long id,
        Long ownerId,
        Long addedById,
        String name,
        String nameNorm,
        FoodCategory category,
        FridgeLocation location,
        FridgeStatus status,
        Double qty,
        String expiresAt,
        Long sourceReceiptId,
        Long sourceReceiptItemId
) {}
