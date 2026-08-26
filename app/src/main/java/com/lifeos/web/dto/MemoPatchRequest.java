package com.lifeos.web.dto;

import com.lifeos.domain.MemoStatus;

public record MemoPatchRequest(
        MemoStatus status,
        String dueAt,
        String automationId,
        String lastFiredAt
) {}
