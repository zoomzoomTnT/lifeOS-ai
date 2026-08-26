package com.lifeos.web.dto;

import com.lifeos.domain.MemoKind;
import jakarta.validation.constraints.NotBlank;

@JsonApi
public record MemoCreateRequest(
        @NotBlank String title,
        String body,
        MemoKind kind,
        Integer priority,
        String dueAt,
        String timezone,
        String cronExpr,
        String cronTz,
        String sourceDomain,
        String sourceTable,
        Long sourceId,
        Object payloadJson
) {}
