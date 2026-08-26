package com.lifeos.web.dto;

import com.lifeos.domain.FridgeResolveAction;
import jakarta.validation.constraints.NotNull;

public record FridgeResolveRequest(@NotNull FridgeResolveAction action) {}
