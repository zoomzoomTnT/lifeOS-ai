package com.lifeos.domain;

public record FridgeItem(
        Long id,
        long ownerId,
        long addedById,
        String name,
        String nameNorm,
        FoodCategory category,
        FridgeLocation location,
        FridgeStatus status,
        double qty,
        String expiresAt,
        Long sourceReceiptId,
        Long sourceReceiptItemId
) {}
