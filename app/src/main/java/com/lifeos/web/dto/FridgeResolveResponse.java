package com.lifeos.web.dto;

import com.lifeos.domain.FridgeResolveAction;

@JsonApi
public record FridgeResolveResponse(long id, FridgeResolveAction action) {}
