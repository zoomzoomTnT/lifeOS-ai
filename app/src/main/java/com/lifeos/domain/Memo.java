package com.lifeos.domain;

public record Memo(
        Long id,
        long ownerId,
        String title,
        String body,
        MemoKind kind,
        MemoStatus status,
        int priority,
        String dueAt,
        String timezone,
        String cronExpr,
        String cronTz,
        String sourceDomain,
        String sourceTable,
        Long sourceId,
        String payloadJson
) {}
