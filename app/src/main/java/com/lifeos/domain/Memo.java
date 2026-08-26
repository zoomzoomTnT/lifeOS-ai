package com.lifeos.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "memos")
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class Memo extends AuditedEntity {

    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id", insertable = false, updatable = false)
    private Person owner;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "body")
    private String body;

    @Column(name = "kind", nullable = false)
    private MemoKind kind;

    @Column(name = "status", nullable = false)
    private MemoStatus status;

    @Column(name = "priority", nullable = false)
    private int priority;

    @Column(name = "due_at")
    private String dueAt;

    @Column(name = "timezone", nullable = false)
    private String timezone;

    @Column(name = "cron_expr")
    private String cronExpr;

    @Column(name = "cron_tz")
    private String cronTz;

    @Column(name = "source_domain")
    private String sourceDomain;

    @Column(name = "source_table")
    private String sourceTable;

    @Column(name = "source_id")
    private Long sourceId;

    @Column(name = "payload_json")
    private String payloadJson;

    @Column(name = "automation_id")
    private String automationId;

    @Column(name = "last_fired_at")
    private String lastFiredAt;
}
