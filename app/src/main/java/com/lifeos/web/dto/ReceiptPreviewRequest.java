package com.lifeos.web.dto;

import jakarta.validation.Valid;

import java.util.List;

@JsonApi
public record ReceiptPreviewRequest(
        String merchantName,
        String barcode,
        String printedAt,
        String currency,
        Integer totalCents,
        Integer taxCents,
        Integer discountCents,
        @Valid List<ReceiptLineRequest> items,
        Object rawOcrJson,
        String imagePath,
        String payerHandle
) {}
