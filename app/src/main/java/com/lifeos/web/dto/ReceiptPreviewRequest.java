package com.lifeos.web.dto;

import com.lifeos.domain.FoodCategory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record ReceiptPreviewRequest(
        String merchantName,
        String barcode,
        String printedAt,
        String currency,
        Integer totalCents,
        Integer taxCents,
        Integer discountCents,
        @Valid List<Line> items,
        Object rawOcrJson,
        String imagePath,
        String payerHandle
) {
    public record Line(
            @NotBlank String name,
            Double qty,
            Integer amountCents,
            Boolean isFood,
            FoodCategory category
    ) {}
}
