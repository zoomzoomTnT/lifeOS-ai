package com.lifeos.web.dto;

import com.lifeos.domain.Receipt;

@JsonApi
public record ReceiptLookupResponse(boolean found, Receipt receipt) {}
