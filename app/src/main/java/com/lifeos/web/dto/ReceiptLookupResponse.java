package com.lifeos.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReceiptLookupResponse(boolean found, ReceiptResponse receipt) {}
