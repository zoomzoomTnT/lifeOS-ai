package com.lifeos.domain;

public record Receipt(
        Long id,
        Long merchantId,
        Long payerId,
        String barcode,
        String printedAt,
        String fingerprint,
        String currency,
        Integer totalCents,
        Integer computedCents,
        ReceiptStatus status,
        String createdAt
) {}
