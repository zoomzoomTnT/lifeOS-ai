package com.lifeos.web.dto;

@JsonApi
public record ReceiptLookupRequest(String barcode, String printedAt) {}
