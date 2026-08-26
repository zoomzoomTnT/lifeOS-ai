package com.lifeos.web.dto;

public record ReceiptLookupRequest(
        String barcode,
        String printedAt,
        String merchantName,
        Integer totalCents
) {}
