package com.lifeos.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.lifeos.domain.MemoKind;
import com.lifeos.domain.MemoStatus;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record MemoResponse(
        Long id,
        Long ownerId,
        String title,
        String body,
        MemoKind kind,
        MemoStatus status,
        Integer priority,
        String dueAt,
        String timezone,
        String cronExpr,
        String cronTz,
        String sourceDomain,
        String sourceTable,
        Long sourceId,
        String payloadJson
) {}
