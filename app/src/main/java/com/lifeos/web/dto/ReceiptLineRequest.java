package com.lifeos.web.dto;

import com.lifeos.domain.FoodCategory;

@JsonApi
public record ReceiptLineRequest(
        String name,
        Double qty,
        Integer amountCents,
        Boolean isFood,
        FoodCategory category
) {}
