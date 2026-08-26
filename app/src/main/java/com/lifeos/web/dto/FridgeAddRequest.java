package com.lifeos.web.dto;

import com.lifeos.domain.FoodCategory;
import com.lifeos.domain.FridgeLocation;
import jakarta.validation.constraints.NotBlank;

@JsonApi
public record FridgeAddRequest(
        @NotBlank String name,
        FoodCategory category,
        FridgeLocation location,
        Double qty,
        Integer expiresInDays
) {}
