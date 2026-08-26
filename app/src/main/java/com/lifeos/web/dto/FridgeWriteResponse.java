package com.lifeos.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.lifeos.domain.FridgeResolveAction;
import com.lifeos.domain.FridgeStatus;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record FridgeWriteResponse(
        long id,
        FridgeStatus status,
        FridgeResolveAction action
) {}
