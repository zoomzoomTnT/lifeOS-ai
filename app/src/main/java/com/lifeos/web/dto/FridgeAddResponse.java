package com.lifeos.web.dto;

import com.lifeos.domain.FridgeStatus;

@JsonApi
public record FridgeAddResponse(long id, FridgeStatus status) {}
