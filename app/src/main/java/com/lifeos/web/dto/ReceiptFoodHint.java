package com.lifeos.web.dto;

import com.lifeos.domain.FoodCategory;

@JsonApi
public record ReceiptFoodHint(String name, String nameNorm, FoodCategory category) {}
