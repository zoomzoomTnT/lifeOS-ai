package com.lifeos.web.dto;

import com.lifeos.domain.FridgeResolveAction;
import jakarta.validation.constraints.NotNull;

@JsonApi
public record FridgeResolveRequest(@NotNull FridgeResolveAction action) {}
