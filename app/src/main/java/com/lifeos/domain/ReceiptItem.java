package com.lifeos.domain;

public record ReceiptItem(
        Long id,
        long receiptId,
        String name,
        String nameNorm,
        double qty,
        int amountCents,
        boolean food,
        FoodCategory category
) {}
